package app.muplay.integrations.requests

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.BrowseRepository
import app.muplay.database.CredentialStore
import app.muplay.database.LibraryRepository
import app.muplay.database.MuPlayDatabase
import app.muplay.database.NotConfiguredException
import app.muplay.database.SubsonicSourceProvider
import app.muplay.database.SyncEngine
import app.muplay.database.SyncState
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.LibraryEntity
import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentialStore
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.requests.di.RequestsModule
import app.muplay.model.LibraryRole
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The four one-line bindings between this module's ports and the classes behind them.
 *
 * None of them has any logic, and that is exactly why they are here. Measured before this class
 * existed: every provider in `RequestsModule` and every SAM lambda it builds sat at **LINE 0/1 or
 * 0/4** — exercised by nothing at all — while all sixty JVM tests in this module passed. Both
 * sibling integration modules found the identical shape in their own wiring
 * (`LidarrWiringTest`/`BinderyWiringTest`, whose own headers record their equivalents at 0/1), and
 * this is the layer at which `AlbumSearch` could be bound to a search of the wrong library, or
 * `MirrorSync` to something that is not the sync engine, without one assertion in
 * `RequestArrivalDetectorTest` or `RequestsRepositoryTest` moving.
 *
 * **On the device rather than the JVM, and that is forced rather than chosen.** Every one of the
 * four providers takes a concrete class that transitively reaches the Android Keystore:
 * `SyncEngine`, `BrowseRepository` and `LibraryRepository` each hold a `SubsonicSourceProvider`,
 * whose collaborator is `CredentialStore`, and `IntegrationCredentialStore` is the Keystore
 * directly. There is no mock framework in this build to stand in for any of them, and standing one
 * in would prove nothing about the wiring anyway.
 *
 * Every method name here is camelCase and every body is a `runTest` — backticked names do not
 * survive D8 on `minSdk 26`, and neither do the lambda classes a backticked `runTest` generates.
 */
@RunWith(AndroidJUnit4::class)
class RequestsWiringTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var database: MuPlayDatabase
  private lateinit var navidromeFile: File
  private lateinit var navidromeStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private lateinit var integrationsFile: File
  private lateinit var integrationsStore: DataStore<Preferences>
  private lateinit var sourceProvider: SubsonicSourceProvider

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    // Both DataStores are this test's own files, never the shipped ones. `DataModuleTest` is the
    // only class allowed to open those, and it fails at *use* rather than at construction, so a
    // second opener here would surface as an unrelated-looking failure much later.
    navidromeFile = File(context.filesDir, "requests-wiring-navidrome-${System.nanoTime()}.preferences_pb")
    navidromeStore = PreferenceDataStoreFactory.create { navidromeFile }
    credentialStore = CredentialStore(navidromeStore)
    integrationsFile = File(context.filesDir, "requests-wiring-integrations-${System.nanoTime()}.preferences_pb")
    integrationsStore = PreferenceDataStoreFactory.create { integrationsFile }
    // Nothing is ever signed in here, so `current()` throws `NotConfiguredException` before this
    // factory is reached. `error(...)` rather than a stub source: if the factory is ever called,
    // the test that called it should say so loudly rather than quietly pass.
    sourceProvider = SubsonicSourceProvider(
      credentialStore = credentialStore,
      factory = SubsonicSourceFactory { error("no Navidrome is configured in RequestsWiringTest") },
    )
  }

  @After
  fun tearDown() = runTest {
    // Guarded: a failure in `setUp` before the last assignment would otherwise replace the real
    // failure with an `UninitializedPropertyAccessException` naming this method.
    if (::integrationsStore.isInitialized) {
      IntegrationCredentialStore(integrationsStore).clear(IntegrationService.LIDARR)
    }
    if (::integrationsFile.isInitialized) integrationsFile.delete()
    if (::credentialStore.isInitialized) credentialStore.clear()
    if (::navidromeFile.isInitialized) navidromeFile.delete()
    if (::database.isInitialized) database.close()
  }

  private fun library(id: Int, name: String, role: LibraryRole) =
    LibraryEntity(musicFolderId = id, name = name, role = role)

  private fun album(id: String, libraryId: Int, name: String) = AlbumEntity(
    id = id,
    libraryId = libraryId,
    artistId = null,
    name = name,
    artistName = "Miles Davis",
    coverArtId = null,
    songCount = 1,
    durationSeconds = 1,
    sortName = name.lowercase(),
  )

  private suspend fun seedLibraries(vararg rows: LibraryEntity) =
    database.libraryDao().mergeFromServer(rows.toList())

  private suspend fun seedAlbums(libraryId: Int, vararg albums: AlbumEntity) =
    database.browseDao().replaceLibraryContents(
      libraryId = libraryId,
      artists = emptyList(),
      albums = albums.toList(),
      songs = emptyList(),
    )

  private fun browseRepository() =
    BrowseRepository(browseDao = database.browseDao(), sourceProvider = sourceProvider)

  private fun libraryRepository() =
    LibraryRepository(libraryDao = database.libraryDao(), sourceProvider = sourceProvider)

  private fun syncEngine() = SyncEngine(
    libraryRepository = libraryRepository(),
    browseDao = database.browseDao(),
    watermarkDao = database.syncWatermarkDao(),
    sourceProvider = sourceProvider,
    albumPageSize = 500,
  )

  /**
   * The port really is the sync engine, and not something that answers the same question.
   *
   * Observed through the one answer only the real `SyncEngine` gives with nothing signed in: it
   * catches everything but cancellation, so `current()`'s `NotConfiguredException` comes back
   * *inside* a `SyncState.Failed` rather than being thrown. A binding to a constant, or to
   * anything that swallowed the cause, cannot produce that pair.
   */
  @Test
  fun theMirrorSyncPortReachesTheSyncEngineAndReturnsItsAnswer() = runTest {
    val port = RequestsModule.provideMirrorSync(syncEngine())

    val state = port.syncIfStale()

    assertThat(state).isInstanceOf(SyncState.Failed::class.java)
    assertThat((state as SyncState.Failed).cause).isInstanceOf(NotConfiguredException::class.java)
  }

  /**
   * `libraryId` reaches `BrowseRepository.search`, proven by two libraries holding an album of the
   * same name: a binding that dropped the id, or hardcoded one, answers both calls identically.
   */
  @Test
  fun theAlbumSearchPortSearchesTheLibraryItIsGiven() = runTest {
    seedLibraries(library(1, "Music", LibraryRole.MUSIC), library(2, "Books", LibraryRole.AUDIOBOOKS))
    seedAlbums(1, album("al-music", 1, "Kind of Blue"))
    seedAlbums(2, album("al-books", 2, "Kind of Blue"))
    val port = RequestsModule.provideAlbumSearch(browseRepository())

    assertThat(port.search(1, "Kind of Blue", 50).albums.map { it.id }).containsExactly("al-music")
    assertThat(port.search(2, "Kind of Blue", 50).albums.map { it.id }).containsExactly("al-books")
  }

  /**
   * `query` and `limit` reach it too, and in the right positions — the two `Int`s in
   * `search(libraryId, query, limit)` are exactly the pair a transposed binding would swap, and a
   * transposition survives the test above.
   */
  @Test
  fun theAlbumSearchPortPassesItsQueryAndItsLimitThrough() = runTest {
    seedLibraries(library(1, "Music", LibraryRole.MUSIC))
    seedAlbums(
      1,
      album("al-1", 1, "Blue One"),
      album("al-2", 1, "Blue Two"),
      album("al-3", 1, "Bitches Brew"),
    )
    val port = RequestsModule.provideAlbumSearch(browseRepository())

    // The query discriminates...
    assertThat(port.search(1, "Bitches", 50).albums.map { it.id }).containsExactly("al-3")
    // ...and the limit really is the limit, not the library id and not a constant.
    assertThat(port.search(1, "Blue", 50).albums).hasSize(2)
    assertThat(port.search(1, "Blue", 1).albums).hasSize(1)
  }

  /**
   * The role reaches `LibraryRepository.idsWithRole`.
   *
   * Two roles and two libraries, so a binding that asked for a fixed role — the exact defect the
   * `Bindery request looked for in the music library` case in `RequestArrivalDetectorTest` guards
   * one layer up — gives the same answer twice here.
   */
  @Test
  fun theLibraryRolesPortAsksForTheRoleItIsGiven() = runTest {
    seedLibraries(
      library(1, "Music", LibraryRole.MUSIC),
      library(2, "Books", LibraryRole.AUDIOBOOKS),
      library(3, "Untagged", LibraryRole.UNASSIGNED),
    )
    val port = RequestsModule.provideLibraryRoles(libraryRepository())

    assertThat(port.idsWithRole(LibraryRole.MUSIC)).containsExactly(1)
    assertThat(port.idsWithRole(LibraryRole.AUDIOBOOKS)).containsExactly(2)
    assertThat(port.idsWithRole(LibraryRole.UNASSIGNED)).containsExactly(3)
  }

  /**
   * The configured-services port really reads `IntegrationCredentialStore`, sealed key and all.
   *
   * The negative half matters as much as the positive one: what comes back through this port is
   * what `RequestsRepository` builds each service's client from, so a port wired to a store that
   * is not the one the setup screen writes to would leave a user who has configured Lidarr with a
   * Requests screen that says nothing is configured.
   */
  @Test
  fun theConfiguredServicesPortReadsTheCredentialStoreItIsGiven() = runTest {
    val store = IntegrationCredentialStore(integrationsStore)
    val port = RequestsModule.provideConfiguredServices(store)

    // The control: empty before anything is saved, so the positive below is a change rather than a
    // constant.
    assertThat(port.configured().first()).isEmpty()

    val parsed = IntegrationBaseUrl.parse("https://lidarr.example:8686", CleartextPolicy.Forbidden)
    val credentials = IntegrationCredentials.Lidarr(
      baseUrl = (parsed as BaseUrlResult.Valid).url,
      apiKey = TEST_API_KEY,
    )
    store.save(credentials)

    val configured = port.configured().first()
    assertThat(configured.keys).containsExactly(IntegrationService.LIDARR)
    val read = configured.getValue(IntegrationService.LIDARR)
    // The declared type is the sealed supertype, so this also observes that a credential filed
    // under LIDARR reads back *as* a Lidarr one -- the fact `RequestsRepository.refresh` refuses
    // to assume.
    assertThat(read).isInstanceOf(IntegrationCredentials.Lidarr::class.java)
    assertThat(read.baseUrl.value).isEqualTo("https://lidarr.example:8686/")
    assertThat((read as IntegrationCredentials.Lidarr).apiKey).isEqualTo(TEST_API_KEY)
    // ...and the key still is not in anything the credential prints.
    assertThat(read.toString()).doesNotContain(TEST_API_KEY)
  }

  private companion object {
    /**
     * A literal placeholder, never a real key. Nothing in this repository — fixture, test or
     * committed file — carries a credential that opens anything.
     */
    const val TEST_API_KEY = "requests-wiring-test-key"
  }
}
