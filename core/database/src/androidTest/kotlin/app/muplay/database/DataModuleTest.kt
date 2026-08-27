package app.muplay.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.di.DataModule
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.BookSettingsEntity
import app.muplay.database.entity.ChapterEntity
import app.muplay.database.entity.LibraryEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.RememberedRenderer
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

    // Exercised, not merely constructed: DataStore creates its file lazily on first access, so
    // constructing one proves nothing about where it would write.
    CredentialStore(credentialDataStore).save(
      app.muplay.model.SubsonicCredentials("https://music.example", "alice", "sesame"),
    )

    assertThat(expected).describedAs("the file the shipped app reads credentials from").exists()
    expected.delete()
  }

  /**
   * Plan 6 Task 2. The cast store gets a DataStore of its own, and the point of it is the word
   * *own*: `:core:database` already binds an unqualified `DataStore<Preferences>` holding the
   * Navidrome password, and `CredentialStore.clear()` is `dataStore.edit { it.clear() }` -- it
   * empties the whole **file**, not its own keys. A `RendererStore` sharing that file would lose
   * every remembered speaker on sign-out, and the symptom (an empty "not answering" list) reads
   * as a discovery bug and would be chased in `RendererDirectory`.
   *
   * Asserted by searching the credentials file for the speaker's own UDN rather than by deleting
   * files first: if the two providers named one path, that file *is* this file and the marker is
   * in it. A `exists()` check on two names could not tell the difference.
   */
  @Test
  fun theProvidedCastDataStoreWritesToItsOwnFileAndNotIntoTheCredentialsOne() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val castFile = File(context.filesDir, "cast.preferences_pb")
    val credentialsFile = File(context.filesDir, "credentials.preferences_pb")

    RendererStore(castDataStore).remember(
      listOf(RememberedRenderer(MARKER_UDN, "Kitchen", "http://10.0.0.1:1400/d.xml")),
    )

    assertThat(castFile).describedAs("the file the shipped app remembers speakers in").exists()
    assertThat(String(castFile.readBytes(), Charsets.ISO_8859_1)).contains(MARKER_UDN)
    val credentials =
      if (credentialsFile.exists()) String(credentialsFile.readBytes(), Charsets.ISO_8859_1) else ""
    assertThat(credentials)
      .describedAs("a remembered speaker must not be in the file sign-out empties")
      .doesNotContain(MARKER_UDN)
  }

  /**
   * The same decision from the user's side, through both **production** providers at once: sign
   * in, remember a speaker, sign out, and the speaker is still there.
   *
   * This lives here rather than in `RendererStoreTest` for a mechanical reason worth stating:
   * DataStore throws `IllegalStateException: There are multiple DataStores active for the same
   * file` if a second instance is created for a path already in use, so exactly one place in this
   * module's instrumented suite may build the shipped DataStores. That place is this class, and
   * the two `by lazy` holders below are why.
   */
  @Test
  fun signingOutDoesNotForgetTheSpeakers() = runTest {
    val credentialStore = CredentialStore(credentialDataStore)
    val rendererStore = RendererStore(castDataStore)
    credentialStore.save(app.muplay.model.SubsonicCredentials("http://nav.example", "u", "p"))
    rendererStore.remember(
      listOf(RememberedRenderer("uuid:kitchen", "Kitchen", "http://10.0.0.1:1400/d.xml")),
    )

    credentialStore.clear()

    // Two stores sharing one file passes every other test in this class and fails only this one.
    assertThat(rendererStore.load().map { it.udn }).containsExactly("uuid:kitchen")
    assertThat(credentialStore.load()).isNull()
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

  /**
   * F-2 in task-6-review.md: `theProvidedSyncEngineIsWiredToTheRealCollaborators` above saves no
   * credentials on purpose and returns `Failed` before a single page is even requested, so it
   * cannot observe `albumPageSize` at all -- and `SyncEngineTest`'s own constructor call was the
   * *only* place in the whole repository that ever did, at exactly one value (1), never the
   * production one `DataModule.provideSyncEngine` actually wires (`SubsonicClient.
   * MAX_ALBUM_LIST_PAGE`, 500). This signs in for real and reads the size that reached the fake.
   */
  @Test
  fun theProvidedSyncEngineRequestsTheProductionPageSize() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val file = File(context.filesDir, "data-module-test-sync-engine-pagesize.preferences_pb")
    file.delete()
    val credentialStore = CredentialStore(PreferenceDataStoreFactory.create { file })
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))
    val source = FakeSubsonicSource().apply {
      musicFolders = listOf(MusicLibrary(1, "Music", LibraryRole.UNASSIGNED))
      albumsByLibrary = mapOf(1 to emptyList())
    }
    val sourceProvider = SubsonicSourceProvider(credentialStore, SubsonicSourceFactory { source })
    val libraryRepository = LibraryRepository(DataModule.provideLibraryDao(database), sourceProvider)

    val engine = DataModule.provideSyncEngine(
      libraryRepository = libraryRepository,
      browseDao = DataModule.provideBrowseDao(database),
      watermarkDao = DataModule.provideSyncWatermarkDao(database),
      sourceProvider = sourceProvider,
    )
    engine.syncIfStale()

    assertThat(source.callLog)
      .contains("getAlbumList2(1, offset=0, size=${SubsonicClient.MAX_ALBUM_LIST_PAGE})")
    credentialStore.clear()
    file.delete()
  }

  /**
   * Plan 4 Task 2 added `provideBookSettingsDao` and `provideChapterDao`, and the four tests above
   * are this class's own record of what happens when a new provider arrives with nothing calling
   * it: `provideLibraryDao`, `provideBrowseDao`, `provideSyncWatermarkDao` and `provideSyncEngine`
   * each measured 0/1 LINE until somebody noticed. These two close the same gap on arrival rather
   * than on review.
   *
   * Exercised through the **shipped, on-disk** database, which is also the only place in this
   * module's suite where the real `Room.databaseBuilder` -- migrations and all -- has to produce
   * a working `book_settings` and `chapters`.
   */
  @Test
  fun theProvidedBookSettingsDaoWorks() = runTest {
    val dao = DataModule.provideBookSettingsDao(database)

    dao.upsert(BookSettingsEntity("book-1", speed = 1.4f, skipSilence = true))

    assertThat(dao.find("book-1")!!.speed).isEqualTo(1.4f)
    assertThat(dao.find("book-1")!!.skipSilence).isTrue
  }

  @Test
  fun theProvidedChapterDaoWorks() = runTest {
    val dao = DataModule.provideChapterDao(database)

    dao.store(
      "m-1",
      listOf(
        ChapterEntity("m-1", 1, 7_000, 12_000, "Tail"),
        ChapterEntity("m-1", 0, 0, 7_000, "Head"),
      ),
      scannedAtEpochMs = 5L,
    )

    assertThat(dao.find("m-1").map { it.title }).containsExactly("Head", "Tail")
    assertThat(dao.findScan("m-1")!!.chapterCount).isEqualTo(2)
  }

  /**
   * Plan 4 Task 4 added `provideAudiobookDao`, and the same rule as the two tests above applies: a
   * provider nothing calls measures 0/1 LINE.
   *
   * It also proves the one thing an in-memory `AudiobookRepositoryTest` cannot -- that the
   * role-scoped `IN (SELECT ... FROM libraries WHERE role = :role)` sub-select runs against the
   * **shipped** on-disk database, whose `libraries.role` column arrived through `MIGRATION_6_7`'s
   * ancestry rather than through `createAllTables`. Two libraries and two albums, so "scoped to the
   * audiobook library" and "every album there is" are different answers.
   */
  @Test
  fun theProvidedAudiobookDaoWorks() = runTest {
    val dao = DataModule.provideAudiobookDao(database)
    val libraryDao = DataModule.provideLibraryDao(database)
    val browseDao = DataModule.provideBrowseDao(database)
    libraryDao.mergeFromServer(
      listOf(
        LibraryEntity(41, "Music", LibraryRole.UNASSIGNED),
        LibraryEntity(42, "Audiobooks", LibraryRole.UNASSIGNED),
      ),
    )
    libraryDao.setRole(41, LibraryRole.MUSIC)
    libraryDao.setRole(42, LibraryRole.AUDIOBOOKS)
    browseDao.replaceLibraryContents(
      libraryId = 41,
      artists = emptyList(),
      albums = listOf(album("dm-record", 41, "A Record")),
      songs = listOf(song("dm-track", "dm-record", 41, "A Track")),
    )
    browseDao.replaceLibraryContents(
      libraryId = 42,
      artists = emptyList(),
      albums = listOf(album("dm-book", 42, "A Book")),
      songs = listOf(song("dm-part", "dm-book", 42, "A Part")),
    )

    assertThat(dao.observeBookAlbums(LibraryRole.AUDIOBOOKS).first().map { it.id })
      .containsExactly("dm-book")
    assertThat(dao.observeItems(LibraryRole.AUDIOBOOKS).first().map { it.mediaId })
      .containsExactly("dm-part")
    assertThat(dao.observeSongsInRole(LibraryRole.AUDIOBOOKS).first().map { it.id })
      .containsExactly("dm-part")
    assertThat(dao.files("dm-book", LibraryRole.AUDIOBOOKS).map { it.id }).containsExactly("dm-part")
    assertThat(dao.findBookAlbum("dm-book", LibraryRole.AUDIOBOOKS)?.name).isEqualTo("A Book")
    // The music album, through the same call. Without the role guard this answers a book.
    assertThat(dao.findBookAlbum("dm-record", LibraryRole.AUDIOBOOKS)).isNull()
  }

  private fun album(id: String, libraryId: Int, name: String) = AlbumEntity(
    id = id, libraryId = libraryId, artistId = null, name = name, artistName = "Anne Author",
    coverArtId = null, songCount = 1, durationSeconds = 4, sortName = name.lowercase(),
  )

  private fun song(id: String, albumId: String, libraryId: Int, title: String) = SongEntity(
    id = id, libraryId = libraryId, albumId = albumId, artistId = null, title = title,
    albumName = albumId, artistName = "Anne Author", trackNumber = 1, discNumber = 1,
    durationSeconds = 4, suffix = "mp3", coverArtId = null, sortTitle = title.lowercase(),
  )

  private companion object {
    /**
     * The two DataStores the shipped app uses, built **once** for this whole class.
     *
     * DataStore refuses a second instance over the same file
     * (`IllegalStateException: There are multiple DataStores active for the same file`), and more
     * than one test here now needs each of them. A `by lazy` in the companion is one instance per
     * class load; a `val` on the test class would be one per test method, which is exactly the
     * failure.
     */
    private val credentialDataStore by lazy {
      DataModule.provideCredentialDataStore(ApplicationProvider.getApplicationContext())
    }

    private val castDataStore by lazy {
      DataModule.provideCastDataStore(ApplicationProvider.getApplicationContext())
    }

    /** Distinctive enough that finding it in a file is evidence rather than a coincidence. */
    private const val MARKER_UDN = "uuid:RINCON-cast-store-marker"

  }
}
