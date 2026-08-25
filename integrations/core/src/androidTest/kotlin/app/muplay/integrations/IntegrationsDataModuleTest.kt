package app.muplay.integrations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.CredentialStore
import app.muplay.database.di.DataModule
import app.muplay.integrations.di.IntegrationsDataModule
import app.muplay.model.SubsonicCredentials
import java.io.File
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
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

    /** Distinctive enough that finding it in a file is evidence rather than a coincidence. */
    private const val MARKER_URL = "https://lidarr-datamodule-marker.example.com/"
    private const val API_KEY = "0123456789abcdef0123456789abcdef"
  }
}
