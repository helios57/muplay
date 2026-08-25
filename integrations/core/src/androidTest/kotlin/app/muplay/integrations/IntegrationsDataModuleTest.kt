package app.muplay.integrations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.CredentialStore
import app.muplay.database.di.DataModule
import app.muplay.integrations.db.MediaRequestEntity
import app.muplay.integrations.di.IntegrationsDataModule
import app.muplay.model.SubsonicCredentials
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the **production** wiring, not a test-only rebuild of it.
 *
 * [IntegrationCredentialStoreTest] builds its DataStore over a file of its own, which is the right
 * tool for asserting the store's behaviour but means it never runs a line of [IntegrationsDataModule]
 * — so the code that actually decides *where* a user's API key is written would be measured at 0
 * lines and gated by nothing. That is the shape of gap `DataModuleTest` in `:core:database` exists
 * to close, and this is the same argument one module over.
 *
 * [IntegrationsDataModule] is a Kotlin `object` whose provider takes a plain `Context`, so calling
 * it directly needs no Hilt machinery at all — no `@HiltAndroidTest`, no test application, no
 * generated component, and **no `@EntryPoint`**. That last one is the point: the `@EntryPoint`s in
 * `:core:database` are moving to `src/debug/` because they are public API of a release-graph module
 * serving nothing that ships, and the way to avoid adding another is to make the store's
 * collaborator an ordinary constructor parameter, which it is.
 */
@RunWith(AndroidJUnit4::class)
class IntegrationsDataModuleTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val integrationsFile = File(context.filesDir, "integrations.preferences_pb")

  private fun url(raw: String) =
    (IntegrationBaseUrl.parse(raw, CleartextPolicy.Forbidden) as BaseUrlResult.Valid).url

  private val lidarr =
    IntegrationCredentials.Lidarr(baseUrl = url(MARKER_URL), apiKey = API_KEY)

  @After
  fun tearDown() = runTest {
    IntegrationCredentialStore(integrationDataStore).clear(IntegrationService.LIDARR)
    integrationsFile.delete()
    // The requests database is the *shipped* file, so anything written here has to be taken back
    // out again -- an emulator's app data survives between runs, and a test that leaves a row
    // behind is a test whose second run measures something different from its first.
    requestsDatabase.requestDao().delete(MARKER_REQUEST_ID)
  }

  /**
   * Exercised, not merely constructed: DataStore creates its file lazily on first access, so
   * constructing one proves nothing about where it would write.
   */
  @Test
  fun theProvidedDataStoreWritesWhereTheAppExpects() = runTest {
    IntegrationCredentialStore(integrationDataStore).save(lidarr)

    assertThat(integrationsFile)
      .describedAs("the file the shipped app reads integration credentials from")
      .exists()
    val bytes = integrationsFile.readBytes().toString(Charsets.ISO_8859_1)
    // The control: this file really is the one that was just written, so the negative below is a
    // negative result rather than a search of an empty file.
    assertThat(bytes).contains(MARKER_URL)
    // ...and the seal survives the production path, not only the test-built one.
    assertThat(bytes).doesNotContain(API_KEY)
  }

  /**
   * The severability property, through **both shipped providers at once**: sign in to Navidrome,
   * configure Lidarr, sign out, and Lidarr is still configured.
   *
   * `CredentialStore.clear()` is `dataStore.edit { it.clear() }` — it empties the whole **file**,
   * not its own keys. So an integrations store that named `credentials.preferences_pb` would be
   * silently forgotten by a Navidrome sign-out, and the symptom (a Lidarr connection that has to be
   * set up again) reads as an integrations bug rather than as a shared-file bug. Two providers
   * naming one path passes every other test in this module and fails only this one.
   */
  @Test
  fun signingOutOfNavidromeDoesNotForgetAConfiguredIntegration() = runTest {
    val credentialStore = CredentialStore(credentialDataStore)
    val integrationStore = IntegrationCredentialStore(integrationDataStore)
    credentialStore.save(SubsonicCredentials("https://music.example", "alice", "sesame"))
    integrationStore.save(lidarr)

    credentialStore.clear()

    assertThat(integrationStore.load(IntegrationService.LIDARR))
      .describedAs("a configured integration must survive a Navidrome sign-out")
      .isNotNull()
    // The other direction in the same call, so a `clear()` that did nothing at all would fail here.
    assertThat(credentialStore.load()).isNull()
  }

  /**
   * The shipped requests database, opened by the shipped provider, and proven to be **its own
   * file** rather than a table inside `muplay.db`.
   *
   * That is this plan's severability contract at the filesystem level: `git rm -r integrations`
   * plus one file deletion has to take the whole feature's storage with it. A provider that named
   * `MuPlayDatabase.DATABASE_NAME` would pass every other assertion in this module -- Room would
   * happily create `media_requests` inside the library's own database -- and fail only here.
   */
  @Test
  fun theProvidedRequestsDatabaseWritesToItsOwnFile() = runTest {
    // Written through `provideMediaRequestDao`, not through `requestsDatabase.requestDao()`, so
    // both shipped providers are on the path this assertion measures.
    IntegrationsDataModule.provideMediaRequestDao(requestsDatabase).upsert(
      MediaRequestEntity(
        id = MARKER_REQUEST_ID, service = IntegrationService.LIDARR.name,
        externalId = "marker", title = MARKER_TITLE, subtitle = "s", remoteId = null,
        status = "REQUESTED", statusDetail = null, requestedAtEpochMs = 1L, updatedAtEpochMs = 1L,
      ),
    )

    val requestsFile = context.getDatabasePath("muplay-integration-requests.db")
    val libraryFile = context.getDatabasePath("muplay.db")

    // The control first: the write really did land on disk, so the negative below is a negative
    // result rather than a search of a file that was never created.
    // A control, not a claim: the row really is readable back through the database the DAO came
    // from. No production mutation available to this module reddens it (there is no second
    // database in `provideMediaRequestDao`'s scope to return a DAO from), and it is recorded as a
    // control in task-3-report.md rather than left looking like a discriminating assertion.
    assertThat(requestsDatabase.requestDao().find(MARKER_REQUEST_ID)?.title).isEqualTo(MARKER_TITLE)

    assertThat(requestsFile).describedAs("the integrations' own database file").exists()
    assertThat(onDisk(requestsFile)).contains(MARKER_TITLE)
    assertThat(requestsFile.path).isNotEqualTo(libraryFile.path)
    // ...and the library's database, if this device has one at all, did not receive the row.
    assertThat(onDisk(libraryFile))
      .describedAs("Plan 7's storage must not land inside MuPlayDatabase")
      .doesNotContain(MARKER_TITLE)
  }

  /**
   * A database file's bytes **and its write-ahead log's**.
   *
   * Room enables WAL, so a just-written row lives in `<name>-wal` until a checkpoint and the main
   * file alone reads as an empty schema. Measured: the first version of the assertion above read
   * only the main file and failed on a database that had genuinely received the row.
   */
  private fun onDisk(database: File): String =
    listOf(database, File(database.path + "-wal"))
      .filter { it.exists() }
      .joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }

  /**
   * The shipped clock reads the wall clock in UTC.
   *
   * Two observations, because "a Clock was returned" is satisfied by `Clock.fixed(EPOCH, UTC)` --
   * which would stamp every request in a user's history with 1970 and make "requested on" useless.
   * The window is generous on purpose: this asserts the clock is *live*, not that it is precise.
   */
  @Test
  fun theProvidedClockReadsTheWallClockInUtc() {
    val clock = IntegrationsDataModule.provideClock()

    assertThat(clock.zone).isEqualTo(ZoneOffset.UTC)
    assertThat(clock.millis()).isCloseTo(System.currentTimeMillis(), within(60_000L))
  }

  private companion object {
    /**
     * The two shipped DataStores, built **once** for this whole class.
     *
     * DataStore refuses a second instance over the same file (`IllegalStateException: There are
     * multiple DataStores active for the same file`) and throws when the store is first *used*
     * rather than when it is constructed, so a second instance looks fine until an assertion runs.
     * A `by lazy` in the companion is one instance per class load; a `val` on the test class would
     * be one per test method, which is exactly the failure.
     */
    private val integrationDataStore by lazy {
      IntegrationsDataModule.provideIntegrationDataStore(ApplicationProvider.getApplicationContext())
    }

    private val credentialDataStore by lazy {
      DataModule.provideCredentialDataStore(ApplicationProvider.getApplicationContext())
    }

    /**
     * One instance of the shipped requests database for the whole class, for a reason one framework
     * over from the DataStore note above: two `RoomDatabase` instances over one file are two
     * connection pools with two write locks, and the symptom is an exception at *use*.
     * Deliberately never closed -- a `@After` close would leave the `by lazy` holding a closed
     * handle for the next test method.
     */
    private val requestsDatabase by lazy {
      IntegrationsDataModule.provideRequestsDatabase(ApplicationProvider.getApplicationContext())
    }

    /** Distinctive enough that finding it in a file is evidence rather than a coincidence. */
    private const val MARKER_URL = "https://lidarr-datamodule-marker.example.com/"
    private const val API_KEY = "0123456789abcdef0123456789abcdef"

    /** Distinctive for the same reason [MARKER_URL] is: found in a file, it is evidence. */
    private const val MARKER_REQUEST_ID = "LIDARR:datamodule-marker"
    private const val MARKER_TITLE = "RequestsDatabaseMarkerTitle"
  }
}
