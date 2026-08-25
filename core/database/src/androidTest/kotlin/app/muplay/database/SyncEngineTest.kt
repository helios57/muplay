package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.Album
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private lateinit var source: FakeSubsonicSource
  private lateinit var engine: SyncEngine

  private fun album(id: String, name: String, libraryId: Int) = Album(
    id = id,
    libraryId = libraryId,
    name = name,
    artistId = "artist-$libraryId",
    artistName = "Artist $libraryId",
    coverArtId = "al-$id",
    songCount = 1,
    durationSeconds = 5,
  )

  private fun song(id: String, title: String, libraryId: Int, albumId: String) = Song(
    id = id,
    libraryId = libraryId,
    title = title,
    albumId = albumId,
    albumName = "Album $albumId",
    artistId = "artist-$libraryId",
    artistName = "Artist $libraryId",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = "al-${albumId}_0",
  )

  @Before
  fun setUp() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    file = File(context.filesDir, "sync-engine-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    credentialStore = CredentialStore(dataStore)
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))

    source = FakeSubsonicSource().apply {
      musicFolders = listOf(
        MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
        MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
      )
      albumsByLibrary = mapOf(
        1 to listOf(album("album-1", "Test Album", 1), album("album-2", "Second Album", 1)),
        2 to listOf(album("book-1", "Test Book", 2)),
      )
      songsByAlbum = mapOf(
        "album-1" to listOf(song("song-1", "Track 1", 1, "album-1")),
        "album-2" to listOf(song("song-2", "Track 2", 1, "album-2")),
        "book-1" to listOf(song("chapter-1", "Chapter 1", 2, "book-1")),
      )
      scanStatus = ScanStatus(isScanning = false, scannedCount = 3, lastScan = "s1")
    }

    val sourceProvider = SubsonicSourceProvider(credentialStore, SubsonicSourceFactory { source })
    engine = SyncEngine(
      libraryRepository = LibraryRepository(db.libraryDao(), sourceProvider),
      browseDao = db.browseDao(),
      watermarkDao = db.syncWatermarkDao(),
      sourceProvider = sourceProvider,
      // One album per page, so the paging loop is exercised by two albums instead of 501.
      albumPageSize = 1,
    )
  }

  @After
  fun tearDown() = runTest {
    credentialStore.clear()
    file.delete()
    db.close()
  }

  @Test
  fun theFirstSyncMirrorsEveryLibrary() = runTest {
    val state = engine.syncIfStale()

    assertThat(state).isInstanceOf(SyncState.Synced::class.java)
    assertThat(db.browseDao().observeAlbums(1).first().map { it.name })
      .containsExactly("Second Album", "Test Album")
    assertThat(db.browseDao().observeAlbums(2).first().map { it.name })
      .containsExactly("Test Book")
    assertThat(db.browseDao().observeSongs("book-1").first().map { it.title })
      .containsExactly("Chapter 1")
    // Artists are derived from albums -- this plan never calls getArtists or getIndexes.
    assertThat(db.browseDao().observeArtists(1).first().map { it.name }).containsExactly("Artist 1")
    assertThat(source.callLog).noneMatch { it.startsWith("getArtists") }
  }

  @Test
  fun everyMirroredRowCarriesTheLibraryItWasFetchedFor() = runTest {
    engine.syncIfStale()

    // The stamp is the only link between a track and the user's Music/Audiobooks decision, and
    // the reconcile is where it could be lost by merging two libraries' pages.
    assertThat(db.browseDao().observeAlbums(1).first()).allMatch { it.libraryId == 1 }
    assertThat(db.browseDao().observeAlbums(2).first()).allMatch { it.libraryId == 2 }
    assertThat(db.browseDao().songIdsInLibrary(1, listOf("chapter-1"))).isEmpty()
    assertThat(db.browseDao().songIdsInLibrary(2, listOf("chapter-1"))).containsExactly("chapter-1")
  }

  @Test
  fun anUnchangedWatermarkSkipsTheReconcileEntirely() = runTest {
    engine.syncIfStale()
    source.callLog.clear()

    val state = engine.syncIfStale()

    assertThat(state).isEqualTo(SyncState.UpToDate)
    assertThat(source.callLog).noneMatch { it.startsWith("getAlbumList2") }
  }

  @Test
  fun aMovedWatermarkReconcilesAgain() = runTest {
    engine.syncIfStale()
    source.scanStatus = source.scanStatus.copy(lastScan = "s2")
    source.callLog.clear()

    assertThat(engine.syncIfStale()).isInstanceOf(SyncState.Synced::class.java)
    assertThat(source.callLog).anyMatch { it.startsWith("getAlbumList2") }
  }

  /** The case a delta protocol cannot express: Subsonic never reports a deletion. */
  @Test
  fun anAlbumDeletedOnTheServerVanishesFromTheMirror() = runTest {
    engine.syncIfStale()
    source.albumsByLibrary = source.albumsByLibrary + (1 to listOf(album("album-1", "Test Album", 1)))
    source.scanStatus = source.scanStatus.copy(lastScan = "s2")

    engine.syncIfStale()

    assertThat(db.browseDao().observeAlbums(1).first().map { it.id }).containsExactly("album-1")
    assertThat(db.browseDao().observeSongs("album-2").first()).isEmpty()
  }

  /**
   * The most important test in this file. If the watermark advances before the reconcile
   * commits, a failed sync is never retried and the mirror stays permanently stale — silently,
   * and with nothing that would ever trigger a repair.
   *
   * A real Navidrome cannot be asked to fail on the fourth of seven calls, which is exactly why
   * `FakeSubsonicSource` exists.
   */
  @Test
  fun aFailureMidReconcileDoesNotAdvanceTheWatermark() = runTest {
    // getScanStatus, getMusicFolders, then the paging and album calls -- fail once inside them.
    source.failAfterCalls = 4

    val state = engine.syncIfStale()

    assertThat(state).isInstanceOf(SyncState.Failed::class.java)
    assertThat(db.syncWatermarkDao().read()).isNull()

    // ...and the retry, with the failure removed, genuinely reconciles rather than reporting
    // "up to date" off a watermark the failed attempt should never have written.
    source.failAfterCalls = null
    assertThat(engine.syncIfStale()).isInstanceOf(SyncState.Synced::class.java)
    assertThat(db.browseDao().observeAlbums(1).first()).hasSize(2)
    assertThat(db.syncWatermarkDao().read()).isEqualTo("s1")
  }

  @Test
  fun aScanInProgressReconcilesNothingAndStoresNothing() = runTest {
    source.scanStatus = ScanStatus(isScanning = true, scannedCount = 1, lastScan = "s2")

    val state = engine.syncIfStale()

    assertThat(state).isEqualTo(SyncState.ScanInProgress)
    assertThat(db.browseDao().observeAlbums(1).first()).isEmpty()
    assertThat(db.syncWatermarkDao().read()).isNull()
    assertThat(source.callLog).noneMatch { it.startsWith("getAlbumList2") }
  }

  @Test
  fun aNewLibraryOnTheServerIsPickedUpByTheNextSync() = runTest {
    engine.syncIfStale()

    source.musicFolders = source.musicFolders + MusicLibrary(3, "Podcasts", LibraryRole.UNASSIGNED)
    source.albumsByLibrary = source.albumsByLibrary + (3 to listOf(album("pod-1", "A Podcast", 3)))
    source.songsByAlbum = source.songsByAlbum + ("pod-1" to listOf(song("ep-1", "Episode 1", 3, "pod-1")))
    source.scanStatus = source.scanStatus.copy(lastScan = "s2")

    engine.syncIfStale()

    assertThat(db.browseDao().observeAlbums(3).first().map { it.name }).containsExactly("A Podcast")
    // ...and it arrives untagged, because nothing may guess a role from a name.
    assertThat(db.libraryDao().find(3)!!.role).isEqualTo(LibraryRole.UNASSIGNED)
  }

  @Test
  fun theEngineReportsWhatItChangedPerLibrary() = runTest {
    val state = engine.syncIfStale() as SyncState.Synced

    assertThat(state.libraries.keys).containsExactlyInAnyOrder(1, 2)
    assertThat(state.libraries.getValue(1).albumsBefore).isEqualTo(0)
    assertThat(state.libraries.getValue(1).albumsAfter).isEqualTo(2)
    assertThat(state.libraries.getValue(2).songsAfter).isEqualTo(1)
  }

  @Test
  fun syncingWithNoCredentialsFailsRatherThanThrowing() = runTest {
    credentialStore.clear()

    // The engine is called from a ViewModel's coroutine, so an escaping exception would surface
    // as a crash rather than as a state the UI can render.
    val state = engine.syncIfStale()

    assertThat(state).isInstanceOf(SyncState.Failed::class.java)
    assertThat((state as SyncState.Failed).cause).isInstanceOf(NotConfiguredException::class.java)
  }

  /**
   * The named decision `SyncEngine.reconcileLibrary`'s own kdoc documents: `replaceLibraryContents`
   * cannot tell "the library truly emptied out" from "the caller passed empty lists by mistake",
   * and defers that call to the sync engine. This pins the choice made here -- trust the fetch --
   * as a deliberate, tested outcome rather than an accident nobody would notice regress.
   */
  @Test
  fun aLibraryThatBecomesEmptyOnTheServerIsWipedLocally() = runTest {
    engine.syncIfStale()
    assertThat(db.browseDao().observeAlbums(1).first()).hasSize(2)

    source.albumsByLibrary = source.albumsByLibrary + (1 to emptyList())
    source.scanStatus = source.scanStatus.copy(lastScan = "s2")

    val state = engine.syncIfStale()

    // Not a failure: a library that has genuinely gone to zero albums is exactly what a full
    // reconcile exists to notice, since Subsonic never reports that deletion any other way.
    assertThat(state).isInstanceOf(SyncState.Synced::class.java)
    assertThat(db.browseDao().observeAlbums(1).first()).isEmpty()
    assertThat(db.browseDao().observeSongs("album-1").first()).isEmpty()
    assertThat(db.browseDao().observeSongs("album-2").first()).isEmpty()
    // The other library is untouched by library 1 going to zero.
    assertThat(db.browseDao().observeAlbums(2).first()).hasSize(1)
  }

  /**
   * Album and song ids are assumed unique *per library* by Navidrome's own design --
   * `AlbumEntity`'s own doc cites the Go structs that back this, verified only by reading
   * Navidrome's source, "not re-verified against the pinned container, whose two fixture
   * libraries share no artist/album/song content to observe this against directly." That gap in
   * the live corpus is inherited, cross-task work (`task-6-report.md` flags it loudly) -- what
   * this test closes is the one piece reachable without the shared container: characterizing
   * `SyncEngine`'s own behaviour, via `FakeSubsonicSource`, if that assumption is ever wrong.
   *
   * `AlbumEntity`/`SongEntity` use a bare `id` primary key (unlike `ArtistEntity`, whose id *is*
   * global and which was given a composite `(id, libraryId)` key for exactly this reason -- see
   * its own doc for the live bug a bare key produced there). If two libraries' albums ever did
   * share an id, `INSERT ... REPLACE` moves that one row to whichever library reconciles last,
   * because the delete in `replaceLibraryContents` is scoped by `libraryId` but the insert's
   * conflict resolution is keyed on `id` alone, globally. This is **not** a defect introduced by
   * `SyncEngine`: it correctly fetches and hands off exactly what each library-scoped
   * `getAlbumList2` call returned, one library at a time, and the collision plays out entirely
   * inside `BrowseDao`'s insert. It is recorded here, characterized rather than fixed, because
   * fixing it means reshaping `AlbumEntity`'s primary key -- Task 5's design, out of this task's
   * file list, and a change wide enough to need its own review.
   */
  @Test
  fun collidingAlbumIdsAcrossLibrariesAreNotIsolatedByThisReconcile() = runTest {
    // No songs on "collide": a song can only ever belong to one library, so a fixture cannot
    // give the same album id a song in both libraries without itself tripping
    // `replaceLibraryContents`'s own libraryId guard -- this test isolates the album-id collision
    // specifically, not that separate (and separately guarded) one.
    source.albumsByLibrary = mapOf(
      1 to listOf(album("collide", "Music Copy", 1)),
      2 to listOf(album("collide", "Book Copy", 2)),
    )
    source.songsByAlbum = emptyMap()

    engine.syncIfStale()

    // Library 1 reconciles first (ascending musicFolderId) and library 2's insert of the same
    // album id then replaces library 1's row outright -- library 1 silently loses the album its
    // own getAlbumList2 call actually returned.
    assertThat(db.browseDao().observeAlbums(1).first()).isEmpty()
    assertThat(db.browseDao().observeAlbums(2).first().map { it.name }).containsExactly("Book Copy")
  }
}
