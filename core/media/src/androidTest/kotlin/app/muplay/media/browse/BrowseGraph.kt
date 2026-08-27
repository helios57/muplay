package app.muplay.media.browse

import android.content.Context
import androidx.room.Room
import app.muplay.database.AudiobookRepository
import app.muplay.database.BrowseRepository
import app.muplay.database.BrowseTreeRepository
import app.muplay.database.LibraryRepository
import app.muplay.database.MuPlayDatabase
import app.muplay.database.ShuffleRepository
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.ArtistEntity
import app.muplay.database.entity.LibraryEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import app.muplay.media.AudiobookSnapshot
import app.muplay.media.QueueRepository
import app.muplay.media.ResumptionQueue
import app.muplay.media.fixedSubsonicSourceProvider
import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.model.AlbumWithSongs
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.SearchResults
import app.muplay.model.ServerCapabilities
import app.muplay.model.ServerInfo
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.network.SubsonicSource
import java.io.File
import java.time.Clock
import kotlinx.coroutines.runBlocking

/**
 * The whole browse stack, over a **real** in-memory Room database, assembled the way Hilt
 * assembles it.
 *
 * Real repositories and a real DAO, not fakes, because every scoping decision this task makes is a
 * SQL `WHERE libraryId = :id` -- `BrowseRepository` offers no unscoped query at all, so "music
 * content is scoped to Music libraries" is a claim about *which libraries get asked*, and a
 * hand-written DAO would answer whatever this file told it to. That is the same construction, for
 * the same stated reason, that `QueueRepositoryTest` already uses in this source set.
 *
 * **What is substituted is exactly one thing: `SubsonicSourceFactory`**, already a `fun interface`
 * in production code. The mirror is seeded directly rather than synced from the container, because
 * the browse tree reads the mirror and never the server -- `SyncEngine` is Plan 2's and filling it
 * is not what this suite is about. The one server-shaped call the tree makes is `coverArtUrl`, and
 * [RecordingArtSource] answers it deterministically so an artwork assertion can name a value.
 *
 * **No credential and no stream URL is asserted, printed or fixtured anywhere below.** The art URLs
 * this fake produces carry no auth parameters at all, which is the point: a browse assertion must
 * never be the reason a real one gets written down.
 */
class BrowseGraph private constructor(
  val database: MuPlayDatabase,
  private val storeFile: File,
  val libraryRepository: LibraryRepository,
  val browseRepository: BrowseRepository,
  val treeRepository: BrowseTreeRepository,
  val queueRepository: QueueRepository,
  /**
   * The **one** `AudiobookRepository` in this graph, shared by the browse tree and by the
   * resumption path, exactly as Hilt shares the `@Singleton`.
   *
   * Hoisted out of the `BrowseTreeRepository` call it used to be constructed inside: Plan 4 Task 6
   * needs the same object to answer "which book do I carry on with", and two repositories over one
   * database is two derivations of the shelf again -- the duplication Task 4 removed.
   */
  val audiobookRepository: AudiobookRepository,
  /** Records every cover-art resolution the tree asked for -- see `artworkIsResolvedPerPage`. */
  val artSource: RecordingArtSource,
) {

  /**
   * The snapshot the resume policy reads, over **this** graph's database.
   *
   * Not started: `AudiobookSnapshotTest`'s coldest case is a snapshot nobody started, and a graph
   * that started one for every caller would make that case unreachable. Call `start` or `refresh`.
   */
  val audiobookSnapshot: AudiobookSnapshot =
    AudiobookSnapshot(audiobookRepository, database.mediaProgressDao(), database.bookSettingsDao())

  val resumptionQueue: ResumptionQueue = ResumptionQueue(audiobookRepository)

  /** Closes the database and deletes the credential store's backing file. */
  fun close() {
    audiobookSnapshot.stop()
    database.close()
    storeFile.delete()
  }

  fun callback(resolver: SurfaceResolver): MuPlayLibraryCallback =
    MuPlayLibraryCallback(
      treeRepository,
      resolver,
      queueRepository,
      resumptionQueue,
      audiobookSnapshot,
    )

  /** The production resolver, reading a real `ControllerInfo`. */
  fun callback(context: Context): MuPlayLibraryCallback = callback(DefaultSurfaceResolver(context))

  companion object {

    const val MUSIC_LIBRARY_ID: Int = 1
    const val AUDIOBOOK_LIBRARY_ID: Int = 2

    /**
     * Builds the stack and seeds the mirror.
     *
     * @param withProgress whether to write [PROGRESS_ROWS]. Withheld by the tests that need every
     * book unheard, so that "a book carries no percentage" and "a book carries this percentage" are
     * two observations of one branch rather than one observation repeated.
     * @param withAudiobooks whether library 2 is tagged `AUDIOBOOKS` at all. Withheld by the test
     * that needs a music-only install: "the root offers a Books tab" is not observed until a tree
     * that should not offer one has been asked.
     * @param withMusic the same, from the other side: an audiobook-only install offers no Albums,
     * Artists or Libraries tab.
     */
    fun create(
      context: Context,
      withProgress: Boolean = true,
      withAudiobooks: Boolean = true,
      withMusic: Boolean = true,
    ): BrowseGraph {
      val database = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
      val artSource = RecordingArtSource()
      val (provider, storeFile) = fixedSubsonicSourceProvider(context, artSource)

      runBlocking {
        database.libraryDao().mergeFromServer(
          listOf(
            LibraryEntity(MUSIC_LIBRARY_ID, "Music", LibraryRole.UNASSIGNED),
            LibraryEntity(AUDIOBOOK_LIBRARY_ID, "Audiobooks", LibraryRole.UNASSIGNED),
          ),
        )
        // Through `setRole`: `mergeFromServer` deliberately never writes the role column, so
        // seeding it in the entity above would leave both libraries UNASSIGNED and this whole
        // fixture would be testing an empty tree.
        if (withMusic) database.libraryDao().setRole(MUSIC_LIBRARY_ID, LibraryRole.MUSIC)
        if (withAudiobooks) database.libraryDao().setRole(AUDIOBOOK_LIBRARY_ID, LibraryRole.AUDIOBOOKS)

        database.browseDao().replaceLibraryContents(
          MUSIC_LIBRARY_ID,
          MUSIC_ARTISTS,
          MUSIC_ALBUMS,
          MUSIC_SONGS,
        )
        database.browseDao().replaceLibraryContents(
          AUDIOBOOK_LIBRARY_ID,
          BOOK_ARTISTS,
          BOOK_ALBUMS,
          BOOK_SONGS,
        )
        if (withProgress) PROGRESS_ROWS.forEach { database.mediaProgressDao().upsert(it) }
      }

      val libraryRepository = LibraryRepository(database.libraryDao(), provider)
      // Plan 4 Task 4 replaced `MirrorBookshelf` with the real `AudiobookRepository` behind the
      // same `Bookshelf` interface -- one derivation of "where is the listener in this book", not
      // two. A real clock: nothing this suite asserts reads one, and `markFinished` is the only
      // method that does.
      val audiobookRepository = AudiobookRepository(
        database.audiobookDao(),
        database.mediaProgressDao(),
        database.bookSettingsDao(),
        Clock.systemUTC(),
      )
      return BrowseGraph(
        database = database,
        storeFile = storeFile,
        libraryRepository = libraryRepository,
        browseRepository = BrowseRepository(database.browseDao(), provider),
        treeRepository = BrowseTreeRepository(
          LibraryRepository(database.libraryDao(), provider),
          BrowseRepository(database.browseDao(), provider),
          audiobookRepository,
          ShuffleRepository(database.browseDao(), provider),
        ),
        // The **real** `QueueRepository`, over the same fake source: Plan 5 Task 5 makes the
        // browse callback build playable items, and a hand-written stand-in here would prove the
        // callback calls something rather than that a tapped row becomes the queue the app plays.
        queueRepository = QueueRepository(provider, libraryRepository),
        audiobookRepository = audiobookRepository,
        artSource = artSource,
      )
    }

    // ---- the music library ------------------------------------------------------------------
    //
    // `sortName` is set explicitly rather than through `MirrorMapper`, so the order these tests
    // assert is a property of the seed and not of a mapper this task does not own.

    private val MUSIC_ARTISTS = listOf(
      ArtistEntity("ar-bowie", MUSIC_LIBRARY_ID, "David Bowie", "cov-bowie", 1, "david bowie"),
      ArtistEntity("ar-beatles", MUSIC_LIBRARY_ID, "The Beatles", "cov-beatles", 2, "the beatles"),
    )

    private val MUSIC_ALBUMS = listOf(
      album("al-abbey", MUSIC_LIBRARY_ID, "Abbey Road", "ar-beatles", "The Beatles", 3, 600),
      album("al-hunky", MUSIC_LIBRARY_ID, "Hunky Dory", "ar-bowie", "David Bowie", 1, 200),
      album("al-revolver", MUSIC_LIBRARY_ID, "Revolver", "ar-beatles", "The Beatles", 2, 400),
    )

    /**
     * Deliberately **not** in alphabetical order of title inside `al-abbey`.
     *
     * `observeSongs` orders by disc, then track, then title. With titles that happened to sort the
     * same way, an implementation that dropped the ordering entirely would still pass -- so
     * "Something" is track 2 and "Oh! Darling" is track 3, and the two orders differ.
     */
    private val MUSIC_SONGS = listOf(
      song("tr-a1", MUSIC_LIBRARY_ID, "al-abbey", "Come Together", 1, 200),
      song("tr-a2", MUSIC_LIBRARY_ID, "al-abbey", "Something", 2, 200),
      song("tr-a3", MUSIC_LIBRARY_ID, "al-abbey", "Oh! Darling", 3, 200),
      song("tr-r1", MUSIC_LIBRARY_ID, "al-revolver", "Taxman", 1, 200),
      song("tr-r2", MUSIC_LIBRARY_ID, "al-revolver", "Eleanor Rigby", 2, 200),
      song("tr-h1", MUSIC_LIBRARY_ID, "al-hunky", "Changes", 1, 200),
    )

    // ---- the audiobook library ---------------------------------------------------------------
    //
    // Eight books, six of them started, so the Continue shelf is longer than a watch's limit of
    // five and shorter than a car's of eight -- which is what makes the per-surface limit
    // observable over real IPC rather than only in Task 2's unit tests.

    private val BOOK_ARTISTS = listOf(
      // An artist row in the audiobook library, so "the Artists tab is scoped to Music" is an
      // assertion about a row that exists and is excluded, not about one that was never there.
      ArtistEntity("ar-narrator", AUDIOBOOK_LIBRARY_ID, "Ann Author", null, 1, "ann author"),
    )

    private val BOOK_ALBUMS = listOf(
      album("bk-alpha", AUDIOBOOK_LIBRARY_ID, "Alpha Book", "ar-narrator", "Eve Reader", 2, 200),
      album("bk-beta", AUDIOBOOK_LIBRARY_ID, "Beta Book", "ar-narrator", "Fay Speaker", 2, 200),
      // A book row the mirror holds no files for, and no author for either. Both are real states --
      // an album whose songs a sync has not reached yet, and a rip with no album artist tag -- and
      // both are branches in `BookSummaries` that every other book here takes the other way.
      album("bk-empty", AUDIOBOOK_LIBRARY_ID, "Empty Book", "ar-narrator", null, 0, 0),
      album("bk-gamma", AUDIOBOOK_LIBRARY_ID, "Gamma Book", "ar-narrator", "Gil Voice", 2, 200),
      album("bk-multi", AUDIOBOOK_LIBRARY_ID, "Multi Part Book", "ar-narrator", "Dee Narrator", 4, 400),
      album("bk-nine", AUDIOBOOK_LIBRARY_ID, "Ninth Book", "ar-narrator", "Hal Teller", 2, 200),
      album("bk-second", AUDIOBOOK_LIBRARY_ID, "Second Book", "ar-narrator", "Cy Chapter", 2, 200),
      album("bk-tail", AUDIOBOOK_LIBRARY_ID, "Tail Book", "ar-narrator", "Ann Author", 1, 100),
      album("bk-test", AUDIOBOOK_LIBRARY_ID, "Test Book", "ar-narrator", "Bea Bookwright", 3, 300),
    )

    /**
     * Every book's parts, 100 s each **except `bk-test`'s**, which are 100 s, 200 s and 300 s.
     *
     * The uneven one is not decoration. `BookSummaries` expresses a file position over the whole
     * book by adding the durations of the files *before* it, and with equal parts a rule that added
     * the wrong ones -- or added them in the wrong order -- reaches the same number. `bk-test` is
     * the book whose stored row sits on part two, so the offset it contributes (100 s) differs from
     * every other slice of that list.
     */
    private val BOOK_SONGS = BOOK_ALBUMS.flatMap { book ->
      (1..book.songCount).map { part ->
        song(
          id = "${book.id}-p$part",
          libraryId = AUDIOBOOK_LIBRARY_ID,
          albumId = book.id,
          title = "${book.name} Part $part",
          trackNumber = part,
          durationSeconds = if (book.id == "bk-test") part * 100 else 100,
        )
      }
    }

    /**
     * One row per started book, on a **different file** for two of them, at six distinct
     * `lastPlayedAtEpochMs` and six distinct positions.
     *
     * Distinct on purpose: a Continue shelf sorted by a constant, and a percentage that is the same
     * number for every book, both pass an assertion that only checks the set of ids.
     */
    private val PROGRESS_ROWS = listOf(
      progress("bk-second-p1", positionMs = 50_000, lastPlayedAtEpochMs = 7_000),
      // `bk-test`'s **older** row, on part one. It exists so that "the listener is where their most
      // recently written row says" is observed against a book with two rows on two files: with one
      // row per book, "the first row" and "the most recent row" are the same object and a rule that
      // took the wrong one is invisible. 90 s into part one would be a book position of 90 s; the
      // right answer is part two's, 120 s.
      progress("bk-test-p1", positionMs = 90_000, lastPlayedAtEpochMs = 5_500),
      // The second file of a three-file book whose parts are 100 s, 200 s and 300 s: 100 s of part
      // one, then 20 s into part two, so the book position is 120 s of 600 s and the fraction is
      // 0.2. A rule that read the row's own position and ignored the files before it would report
      // 20 s and 0.033; one that added the files *after* it would report 620 s.
      progress("bk-test-p2", positionMs = 20_000, lastPlayedAtEpochMs = 6_000),
      // `bk-alpha` carries two rows written in the **same millisecond**, which a batch write really
      // does produce. The tie has to resolve to the *later file*, deterministically, or the same
      // shelf orders itself differently between two identical requests: 110 s, not 20 s.
      progress("bk-alpha-p1", positionMs = 20_000, lastPlayedAtEpochMs = 5_000),
      progress("bk-alpha-p2", positionMs = 10_000, lastPlayedAtEpochMs = 5_000),
      progress("bk-beta-p1", positionMs = 40_000, lastPlayedAtEpochMs = 4_000),
      // Finished on part one of two. The book is **not** finished: a rule reading `isFinished`
      // alone would take a part-heard book off the Continue shelf the first time a chapter ran out.
      progress("bk-gamma-p1", positionMs = 60_000, lastPlayedAtEpochMs = 3_000, isFinished = true),
      // Position zero *in the third file*: started, at exactly half of a four-part book. The one
      // case where "the row says 0" and "the listener has not started" are different answers.
      progress("bk-multi-p3", positionMs = 0, lastPlayedAtEpochMs = 2_000),
      // An **older** row on a **later** file. It is the only input in this fixture for which the
      // "is this row more recent than the one I have?" test answers *no*, and without it a rule
      // that simply took the last row it saw would be indistinguishable from the right one.
      progress("bk-multi-p4", positionMs = 5_000, lastPlayedAtEpochMs = 1_500),
      // The only *actually* finished book, and it is finished on its last (here, only) file --
      // which is what makes it different from `bk-gamma` above.
      progress("bk-tail-p1", positionMs = 100_000, lastPlayedAtEpochMs = 1_000, isFinished = true),
    )

    /**
     * Every audiobook file id this fixture seeds, derived from [BOOK_SONGS] rather than written out.
     *
     * `AudiobookSnapshotTest` asserts the snapshot's key set **exactly**, and a hand-written copy
     * of these eighteen ids is a second corpus that drifts the first time a book is added here --
     * which is the class of defect `CLAUDE.md` records against `GaplessTest`'s hardcoded `3`.
     */
    val BOOK_SONG_IDS: List<String> get() = BOOK_SONGS.map { it.id }

    /** The same, for the music library -- the ids that must never be in the snapshot. */
    val MUSIC_SONG_IDS: List<String> get() = MUSIC_SONGS.map { it.id }

    private fun album(
      id: String,
      libraryId: Int,
      name: String,
      artistId: String,
      artistName: String?,
      songCount: Int,
      durationSeconds: Int,
    ) = AlbumEntity(
      id = id,
      libraryId = libraryId,
      artistId = artistId,
      name = name,
      artistName = artistName,
      coverArtId = "cov-$id",
      songCount = songCount,
      durationSeconds = durationSeconds,
      sortName = name.lowercase(),
    )

    private fun song(
      id: String,
      libraryId: Int,
      albumId: String,
      title: String,
      trackNumber: Int,
      durationSeconds: Int,
    ) = SongEntity(
      id = id,
      libraryId = libraryId,
      albumId = albumId,
      artistId = null,
      title = title,
      albumName = albumId,
      artistName = "Artist of $albumId",
      trackNumber = trackNumber,
      discNumber = 1,
      durationSeconds = durationSeconds,
      suffix = "mp3",
      coverArtId = "cov-$id",
      sortTitle = title.lowercase(),
    )

    private fun progress(
      mediaId: String,
      positionMs: Long,
      lastPlayedAtEpochMs: Long,
      isFinished: Boolean = false,
    ) = MediaProgressEntity(
      mediaId = mediaId,
      positionMs = positionMs,
      isFinished = isFinished,
      lastPlayedAtEpochMs = lastPlayedAtEpochMs,
      speed = 1.0f,
      skipSilence = false,
      gainDb = 0.0f,
    )
  }
}

/**
 * A `SubsonicSource` that answers cover-art URLs and refuses everything else.
 *
 * Hand-written, not a mock -- this project bans mock frameworks. `error(...)` rather than a benign
 * default on every other member, so a browse call that reached the network would fail loudly rather
 * than quietly return something plausible the test would then be asserting about.
 *
 * The URL it returns carries **no authentication parameters**, unlike the real one. That is not a
 * simplification for convenience: a test fixture is a file, and a file is a place a real Subsonic
 * token would outlive the session it was minted for.
 */
class RecordingArtSource : SubsonicSource {

  val coverArtCalls: MutableList<Pair<String, Int?>> = mutableListOf()

  /** Every stream URL the queue asked this source to build, in order. */
  val streamCalls: MutableList<Pair<String, StreamFormat>> = mutableListOf()

  /**
   * What `getRandomSongs` answers, keyed by the library id it was asked for.
   *
   * Keyed rather than a single list, because the assertion a shuffle row has to support is that the
   * **library id from the tapped id** reaches the repository: a source that answered the same songs
   * for every library would satisfy a test that only checked what came back. An unlisted library
   * answers an empty list, which is the other observation.
   */
  val randomSongsByLibrary: MutableMap<Int, List<Song>> = mutableMapOf()

  /** Every (libraryId, size) pair `getRandomSongs` was called with, in order. */
  val randomSongsCalls: MutableList<Pair<Int, Int>> = mutableListOf()

  /**
   * Art ids this source refuses to build a URL for.
   *
   * A real `SubsonicSourceProvider.current()` throws `NotConfiguredException` when no server has
   * been set up, and the browse tree has to answer a car with the rows it already has rather than
   * with an error. This is the only way to reach that arm from a test.
   */
  val failingArtIds: MutableSet<String> = mutableSetOf()

  override fun coverArtUrl(coverArtId: String, sizePx: Int?): String {
    coverArtCalls += coverArtId to sizePx
    if (coverArtId in failingArtIds) error("no server is configured")
    return "http://art.invalid/$coverArtId/$sizePx"
  }

  /**
   * A deterministic, **credential-free** stand-in for the real stream URL.
   *
   * It used to `error(...)` with *"the browse tree must never build a stream url; it builds
   * identities"*, and that was true of Plan 5 Task 4. Task 5 makes the same callback answer
   * `onAddMediaItems`, which is the browse tree handing a queue to a player -- so building one is
   * now the subject rather than a violation. What has not changed is the reason the old line
   * existed: the value below carries **no `u`, `s` or `t` parameter of any kind**, so no assertion
   * in this suite can become the place a real Subsonic token is written down. The real URL is built
   * by the real `SubsonicClient` and is asserted nowhere, here or anywhere else.
   *
   * `timeOffsetSeconds` is recorded on the URL rather than ignored (Plan 3 Task 12 added it to the
   * port): the browse tree never asks for one, and a stand-in that silently dropped the argument
   * would make "it never asks" unobservable.
   */
  override fun streamUrl(songId: String, format: StreamFormat, timeOffsetSeconds: Int?): String {
    streamCalls += songId to format
    return "http://stream.invalid/$songId" + (timeOffsetSeconds?.let { "?timeOffset=$it" } ?: "")
  }

  override suspend fun capabilities(): ServerCapabilities = error("not used by the browse suite")

  override suspend fun ping(): ServerInfo = error("not used by the browse suite")
  override suspend fun getMusicFolders(): List<MusicLibrary> = error("not used by the browse suite")
  override suspend fun getScanStatus(): ScanStatus = error("not used by the browse suite")
  override suspend fun getAlbumList2(
    musicFolderId: Int,
    type: AlbumListType,
    size: Int,
    offset: Int,
  ): List<Album> = error("not used by the browse suite")
  override suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs =
    error("not used by the browse suite")
  override suspend fun search3(
    query: String,
    musicFolderId: Int,
    artistCount: Int,
    albumCount: Int,
    songCount: Int,
  ): SearchResults = error("not used by the browse suite")
  override suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song> {
    randomSongsCalls += musicFolderId to size
    return randomSongsByLibrary[musicFolderId].orEmpty()
  }
}
