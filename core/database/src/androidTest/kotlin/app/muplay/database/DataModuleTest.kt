package app.muplay.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.di.DataModule
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.LibraryEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.model.LibraryRole
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the **production** wiring, not a test-only rebuild of it.
 *
 * [MediaProgressDaoTest] builds its database with `Room.inMemoryDatabaseBuilder`, which is the
 * right tool for asserting schema behaviour but means it never runs a line of [DataModule] — so
 * the code that actually opens the shipped, on-disk database was measured at 0/3 lines and had
 * nothing gating it. That is the shape of gap this project has found eleven times: a component
 * that is obviously fine, exercised by nothing.
 *
 * [DataModule] is a Kotlin `object` whose providers take plain parameters, so calling them
 * directly needs no Hilt machinery at all — no `@HiltAndroidTest`, no test application, no
 * generated component. What this proves is worth having on its own terms: the real
 * `Room.databaseBuilder` path opens successfully against the real file name, and the DAO handed
 * out by the real provider actually works.
 */
@RunWith(AndroidJUnit4::class)
class DataModuleTest {

  private val database = DataModule.provideDatabase(ApplicationProvider.getApplicationContext())

  @After
  fun tearDown() {
    database.close()
    // Deleting the file keeps this test independent of the order it runs in, and of anything a
    // previous run left behind: `provideDatabase` opens a persistent, on-disk database by design.
    ApplicationProvider.getApplicationContext<android.content.Context>()
      .getDatabasePath(MuPlayDatabase.DATABASE_NAME)
      .delete()
  }

  @Test
  fun theProvidedDatabaseOpensAndItsDaoWorks() = runTest {
    val dao = DataModule.provideMediaProgressDao(database)

    dao.upsert(MediaProgressEntity("book-1", 42L, false, 1_000L, 1.0f, false, 0f))

    assertThat(dao.find("book-1")!!.positionMs).isEqualTo(42L)
  }

  @Test
  fun theProvidedDatabaseIsTheOneTheAppShips() {
    // The provider is what decides where a user's progress actually lives; a silent rename here
    // would orphan every listener's saved position on upgrade without failing anything.
    assertThat(database.openHelper.databaseName).isEqualTo(MuPlayDatabase.DATABASE_NAME)
    assertThat(MuPlayDatabase.DATABASE_NAME).isEqualTo("muplay.db")
  }
  @Test
  fun theProvidedCredentialDataStoreWritesWhereTheAppExpects() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val expected = java.io.File(context.filesDir, "credentials.preferences_pb")
    expected.delete()

    val dataStore = DataModule.provideCredentialDataStore(context)
    // Exercised, not merely constructed: DataStore creates its file lazily on first access, so
    // constructing one proves nothing about where it would write.
    CredentialStore(dataStore).save(
      app.muplay.model.SubsonicCredentials("https://music.example", "alice", "sesame"),
    )

    assertThat(expected).describedAs("the file the shipped app reads credentials from").exists()
    expected.delete()
  }

  /**
   * Task 4 added `provideLibraryDao` and `provideSubsonicSourceFactory` to this module and
   * neither was called by anything: `LibraryRepositoryTest` builds its own `LibraryRepository`
   * and `SubsonicSourceProvider` by hand, which never touches this `object`. That left both
   * providers measuring 0/1 LINE -- the exact "obviously fine, exercised by nothing" shape this
   * class's own doc comment already names -- and dropped `DataModule`'s class-level LINE ratio
   * below the floor gating it. This test and the one below close that.
   */
  @Test
  fun theProvidedLibraryDaoWorks() = runTest {
    val dao = DataModule.provideLibraryDao(database)

    dao.mergeFromServer(listOf(LibraryEntity(1, "Music", LibraryRole.UNASSIGNED)))

    assertThat(dao.find(1)!!.name).isEqualTo("Music")
  }

  /**
   * Task 5 added `provideBrowseDao` and nothing called it either -- `BrowseDaoTest` and
   * `BrowseRepositoryTest` both build the DAO from an in-memory Room database of their own, the
   * same gap `provideLibraryDao` had above. Measured 9/10 LINE (0.90, exactly on the floor) before
   * this test, with `provideBrowseDao` itself the one uncovered line.
   */
  @Test
  fun theProvidedBrowseDaoWorks() = runTest {
    val dao = DataModule.provideBrowseDao(database)

    dao.replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(AlbumEntity("al1", 1, null, "Test Album", null, null, 1, 5, "test album")),
      songs = emptyList(),
    )

    assertThat(dao.observeAlbums(1).first().map { it.name }).containsExactly("Test Album")
  }

  @Test
  fun theProvidedSubsonicSourceFactoryBuildsARealSubsonicClient() {
    val factory = DataModule.provideSubsonicSourceFactory()

    val source = factory.create(SubsonicCredentials("https://music.example", "alice", "sesame"))

    // Not just "non-null": this is the one place that decides the production `SubsonicSource` is
    // a real `SubsonicClient` (with a real Retrofit stack) rather than, say, a leftover test
    // double -- `DefaultSubsonicSourceFactory` is a one-line object, and a test that only checked
    // "an instance came back" would pass just as well if that line built the wrong type.
    assertThat(source).isInstanceOf(SubsonicClient::class.java)
  }

  /**
   * Task 6 added `provideSyncWatermarkDao` and `provideSyncEngine`; neither was called by
   * anything else, the same "obviously fine, exercised by nothing" gap the two tests above close
   * for `provideLibraryDao`/`provideBrowseDao`.
   */
  @Test
  fun theProvidedSyncWatermarkDaoWorks() = runTest {
    val dao = DataModule.provideSyncWatermarkDao(database)

    dao.store("s1")

    assertThat(dao.read()).isEqualTo("s1")
  }

  @Test
  fun theProvidedSyncEngineIsWiredToTheRealCollaborators() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val file = File(context.filesDir, "data-module-test-sync-engine.preferences_pb")
    file.delete()
    val credentialStore = CredentialStore(PreferenceDataStoreFactory.create { file })
    val sourceProvider = SubsonicSourceProvider(credentialStore, SubsonicSourceFactory { FakeSubsonicSource() })
    val libraryRepository = LibraryRepository(DataModule.provideLibraryDao(database), sourceProvider)

    val engine = DataModule.provideSyncEngine(
      libraryRepository = libraryRepository,
      browseDao = DataModule.provideBrowseDao(database),
      watermarkDao = DataModule.provideSyncWatermarkDao(database),
      sourceProvider = sourceProvider,
    )

    // No credentials saved -> Failed, but that is still real evidence: the engine wired through
    // the real production provider actually runs and reaches a real SubsonicSourceProvider,
    // rather than merely type-checking the constructor call.
    assertThat(engine.syncIfStale()).isInstanceOf(SyncState.Failed::class.java)
    credentialStore.clear()
    file.delete()
  }
}
