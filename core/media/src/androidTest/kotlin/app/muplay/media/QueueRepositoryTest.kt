package app.muplay.media

import androidx.media3.common.MediaMetadata
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.LibraryRepository
import app.muplay.database.MuPlayDatabase
import app.muplay.database.entity.LibraryEntity
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The join between a queue of mirrored songs and the URLs Media3 needs.
 *
 * The `SubsonicSource` here is a **hand-written fake**, not a mock: it records the arguments it was
 * called with and answers deterministically. That is what lets this test assert the *format
 * decision* — the one place "never Opus" is actually made — without a server that has an Opus file
 * in it. There is no Opus in the CI corpus and there is not going to be one.
 */
@RunWith(AndroidJUnit4::class)
class QueueRepositoryTest {

  private class RecordingSource : SubsonicSource {
    val streamCalls = mutableListOf<Pair<String, StreamFormat>>()
    val coverArtCalls = mutableListOf<Pair<String, Int?>>()

    /**
     * The `timeOffsetSeconds` each `streamUrl` call carried. Building a queue must never ask for
     * one -- an offset is a *seek*, decided later by `TranscodeSeek` on the item that is already
     * playing -- so every entry here is expected to be null.
     */
    val streamOffsets = mutableListOf<Int?>()

    override fun streamUrl(songId: String, format: StreamFormat, timeOffsetSeconds: Int?): String {
      streamCalls += songId to format
      streamOffsets += timeOffsetSeconds
      return "https://host/rest/stream?id=$songId&format=${format.wireValue}"
    }

    override fun coverArtUrl(coverArtId: String, sizePx: Int?): String {
      coverArtCalls += coverArtId to sizePx
      return "https://host/rest/getCoverArt?id=$coverArtId&size=$sizePx"
    }

    // Everything else on the port is out of this test's scope. `error(...)` rather than a benign
    // default: a call that should never happen must fail loudly rather than return something
    // plausible that the test would then be quietly asserting about.
    override suspend fun capabilities(): ServerCapabilities = error("not used by QueueRepositoryTest")
    override suspend fun ping(): ServerInfo = error("not used by QueueRepositoryTest")
    override suspend fun getMusicFolders(): List<MusicLibrary> = error("not used by QueueRepositoryTest")
    override suspend fun getScanStatus(): ScanStatus = error("not used by QueueRepositoryTest")
    override suspend fun getAlbumList2(musicFolderId: Int, type: AlbumListType, size: Int, offset: Int): List<Album> = error("not used by QueueRepositoryTest")
    override suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs = error("not used by QueueRepositoryTest")
    override suspend fun search3(query: String, musicFolderId: Int, artistCount: Int, albumCount: Int, songCount: Int): SearchResults = error("not used by QueueRepositoryTest")
    override suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song> = error("not used by QueueRepositoryTest")
  }

  // [trackNumber] is a parameter rather than the constant it started as, for the same reason
  // `PlaybackQueueTest`'s is: with one shared track number across a fixture,
  // `queue.songs.sortedBy { it.trackNumber }` is a stable no-op and an order-sensitive assertion
  // cannot see it.
  private fun song(id: String, suffix: String?, coverArtId: String?, trackNumber: Int? = 1) = Song(
    id = id,
    libraryId = 1,
    title = "Title $id",
    albumId = "album",
    albumName = "Album",
    artistId = "artist",
    artistName = "Artist",
    trackNumber = trackNumber,
    discNumber = null,
    durationSeconds = 5,
    suffix = suffix,
    coverArtId = coverArtId,
  )

  private lateinit var storeFile: File
  private lateinit var db: MuPlayDatabase

  /**
   * A **real** `LibraryRepository` over a real in-memory Room database, seeded the way the setup
   * flow seeds it — library 1 tagged `MUSIC`, library 2 tagged `AUDIOBOOKS`.
   *
   * Real rather than faked, and that is not ceremony: `idsWithRole` is a SQL `WHERE role = :role`,
   * and the defect this fixture has to be able to catch is a repository that asks for the wrong
   * role (or does not ask at all). A hand-written `LibraryDao` would answer whatever this file told
   * it to, which is the same shape as asserting that a query was configured.
   */
  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    runBlocking {
      db.libraryDao().mergeFromServer(
        listOf(
          LibraryEntity(musicFolderId = 1, name = "Music", role = LibraryRole.UNASSIGNED),
          LibraryEntity(musicFolderId = 2, name = "Audiobooks", role = LibraryRole.UNASSIGNED),
        ),
      )
      // Through `setRole`, not through the entity above: `mergeFromServer` deliberately never
      // writes the role column (a re-sync must not reset the user's tag), so seeding it in the
      // entity would leave both libraries UNASSIGNED and this whole fixture testing nothing.
      db.libraryDao().setRole(1, LibraryRole.MUSIC)
      db.libraryDao().setRole(2, LibraryRole.AUDIOBOOKS)
    }
  }

  private fun repository(source: SubsonicSource): QueueRepository {
    val (provider, file) = fixedSubsonicSourceProvider(
      ApplicationProvider.getApplicationContext(),
      source,
    )
    storeFile = file
    return QueueRepository(provider, LibraryRepository(db.libraryDao(), provider))
  }

  @After
  fun tearDown() {
    if (::storeFile.isInitialized) storeFile.delete()
    db.close()
  }

  /**
   * **The fixture is non-monotone in every key this list could be sorted by**, deliberately.
   *
   * `containsExactly` is order-sensitive, which catches a reversal and nothing else: over an
   * ascending `a, b, c` fixture whose songs share a track number, `queue.songs.sortedBy { it.id }`,
   * `sortedBy { it.title }` and `sortedBy { it.trackNumber }` are all the identity, so a
   * repository that re-sorted the queue on its way to Media3 passed this file. `c(3), a(1), b(2)`
   * discriminates all three at once -- and the one that matters is track number: *"sort by track
   * number for album playback"* is a change someone makes on purpose, and here it would silently
   * undo library-scoped shuffle.
   */
  @Test
  fun everySongInTheQueueBecomesOneMediaItemInTheSameOrder() = runTest {
    val source = RecordingSource()
    val queue = PlaybackQueue.of(
      listOf(
        song("c", "mp3", null, trackNumber = 3),
        song("a", "flac", null, trackNumber = 1),
        song("b", "mp3", null, trackNumber = 2),
      ),
    )

    val items = repository(source).mediaItems(queue)

    assertThat(items.map { it.mediaId }).containsExactly("c", "a", "b")
  }

  /**
   * **`mediaItems` returns the whole queue, whatever `startIndex` names.**
   *
   * The contract is deliberate and it is the one place a reader could reasonably expect this class
   * to do something and it does not: `startIndex` is an argument to
   * `Player.setMediaItems(items, startIndex, positionMs)`, so consuming it here would hand Media3
   * a truncated queue *and* an index into it, and playback would begin at the wrong track with
   * everything before it gone.
   *
   * Until this test existed the contract was held by prose alone -- every other fixture in the
   * file takes the default `startIndex = 0`, so `queue.songs.drop(queue.startIndex)`, which is the
   * exact misreading, passed all of them. `startIndex = 2` over a three-song queue makes that
   * mutation return one item instead of three.
   */
  @Test
  fun mediaItemsIsTheWholeQueueWhateverTheStartIndexNames() = runTest {
    val source = RecordingSource()
    val queue = PlaybackQueue.of(
      listOf(song("c", "mp3", null), song("a", "mp3", null), song("b", "mp3", null)),
      startIndex = 2,
    )

    val items = repository(source).mediaItems(queue)

    assertThat(items.map { it.mediaId }).containsExactly("c", "a", "b")
  }

  @Test
  fun anMp3SourceIsStreamedRaw() = runTest {
    val source = RecordingSource()

    repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", null))))

    assertThat(source.streamCalls).containsExactly("a" to StreamFormat.Raw)
  }

  /**
   * **"Never Opus", at the one place the decision is actually made.** Spec section 4 states the
   * rule; `StreamFormat.forSuffix` implements it; this is the assertion that the repository
   * actually consults it rather than defaulting every song to raw.
   */
  @Test
  fun anOpusSourceIsTranscodedRatherThanStreamedRaw() = runTest {
    val source = RecordingSource()

    repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "opus", null))))

    assertThat(source.streamCalls)
      .containsExactly("a" to StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS))
  }

  @Test
  fun aMixedQueueGetsAPerSongFormatDecision() = runTest {
    // The discriminating shape: one queue, two answers. A repository that decided the format once
    // for the whole queue passes both single-song tests above and fails this one.
    val source = RecordingSource()

    repository(source).mediaItems(
      PlaybackQueue.of(listOf(song("c", "mp3", null), song("a", "opus", null), song("b", "flac", null))),
    )

    // Ids in `c, a, b` order for the reason the order test above records: an ascending fixture
    // makes a sort indistinguishable from the identity, and this assertion pairs each id with the
    // format chosen for it, so it is an order assertion as much as a format one.
    assertThat(source.streamCalls).containsExactly(
      "c" to StreamFormat.Raw,
      "a" to StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS),
      "b" to StreamFormat.Raw,
    )
  }

  /**
   * **512, written out, and not `QueueRepository.ARTWORK_SIZE_PX`.**
   *
   * Interpolating the constant on the expected side compares it to itself: `const val
   * ARTWORK_SIZE_PX = 1` -- a notification cover one pixel across -- was green across the entire
   * suite while this test claimed to pin the size. That is the self-referential assertion shape
   * this task's own audit went looking for, and it was standing here the whole time. A literal is
   * the only thing that can disagree with the constant.
   *
   * Asserted on both sides: that nothing was requested of the server at all (`coverArtCalls`), and
   * what came back on the item.
   */
  @Test
  fun aSongWithCoverArtGetsAnArtworkIdRatherThanAnAuthenticatedUrl() = runTest {
    // **This assertion inverted, and the inversion is the fix.** It used to require the finished
    // `coverArtUrl` string on the item, and that string carries the Subsonic `u`, `t` and `s` -- a
    // non-expiring password equivalent -- which `MediaSessionLegacyStub` then mirrors onto the
    // platform media session as `ART_URI`, where any notification-listener app reads it. The item
    // carries the id now; `ArtworkUrls` is where the credential goes back on, in-process, for the
    // three consumers that fetch bytes. See `ArtworkUri`.
    val source = RecordingSource()

    val items = repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", "art-1"))))

    // Nothing asks the server to build a cover URL at queue time any more, which is the mechanical
    // form of "the credential is not on the item".
    assertThat(source.coverArtCalls).isEmpty()
    assertThat(items.single().mediaMetadata.artworkUri.toString()).isEqualTo("muplay-art:art-1")
  }

  @Test
  fun aSongWithNoCoverArtAsksForNoneAndGetsNone() = runTest {
    val source = RecordingSource()

    val items = repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", null))))

    assertThat(source.coverArtCalls).isEmpty()
    assertThat(items.single().mediaMetadata.artworkUri).isNull()
  }

  /**
   * **Both URLs this class builds, each landing on the item it was built for.**
   *
   * The stream half has always been here. The artwork half was not, and its absence was invisible
   * rather than benign: exactly one test in this file used a non-null `coverArtId` and its queue
   * held **one song**, so `artworkId = queue.songs.first().coverArtId` would have been green across
   * the whole suite. That is the same shape as the stream URL's own probe, left unwritten for the
   * other URL.
   *
   * `MediaItemsTest` does prove the pure mapper carries two distinct artwork strings, and that is
   * not the same claim: it is the layer where the value is *placed*, not the layer where it is
   * *chosen per song*. Verified-at-one-layer, applied-at-another is this project's recorded defect
   * class, so the choice is asserted where it is made.
   *
   * The trigger is not exotic. Any queue spanning two albums -- shuffle, the Continue shelf, a
   * search result -- would have shown the first song's cover on every item.
   *
   * `b` before `a`, so the fixture is non-monotone by id and by title and a sort cannot pass as
   * the identity here either.
   */
  @Test
  fun eachItemCarriesTheStreamAndArtworkUrlBuiltForItsOwnSong() = runTest {
    // Two songs, four URLs, asserted as ordered pairs: a repository that built one URL of either
    // kind and reused it for every item would pass every test above.
    val source = RecordingSource()

    val items = repository(source)
      .mediaItems(PlaybackQueue.of(listOf(song("b", "mp3", "art-b"), song("a", "mp3", "art-a"))))

    assertThat(items.map { it.localConfiguration?.uri?.toString() }).containsExactly(
      "https://host/rest/stream?id=b&format=raw",
      "https://host/rest/stream?id=a&format=raw",
    )
    assertThat(items.map { it.mediaMetadata.artworkUri?.toString() }).containsExactly(
      "muplay-art:art-b",
      "muplay-art:art-a",
    )
    // The count assertion this used to make -- that `coverArtUrl` was called once per song -- went
    // with the call itself. What replaces it is the two *different* artwork URIs above, in queue
    // order: a repository that took `queue.songs.first().coverArtId` for every item still fails
    // here, which is the defect that assertion existed to catch.
    assertThat(source.coverArtCalls).isEmpty()
  }

  /**
   * **One queue, two answers**, from the user's own `LibraryRole` assignment.
   *
   * The shape is the same one every other per-song decision in this file is asserted with, and for
   * the same reason: a repository that read the role once for the whole queue — from the first
   * song, or from a constant — passes any single-song test and fails this. It is also the only
   * assertion in the project that the *join* happens at all: `MediaItemsTest` proves the mapper
   * places whichever answer it is handed, which is a different claim from the repository choosing
   * the right one per song.
   */
  @Test
  fun aSongFromAnAudiobookLibraryIsMarkedAsAnAudiobookChapter() = runTest {
    val source = RecordingSource()

    // libraryId 1 is Music, libraryId 2 is Audiobooks -- seeded in @Before through the real DAO.
    val items = repository(source).mediaItems(
      PlaybackQueue.of(
        listOf(
          song("a", "mp3", null).copy(libraryId = 1),
          song("b", "mp3", null).copy(libraryId = 2),
        ),
      ),
    )

    assertThat(items.map { it.mediaMetadata.mediaType }).containsExactly(
      MediaMetadata.MEDIA_TYPE_MUSIC,
      MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
    )
  }

  /**
   * A library the user tagged **Music** is music, and so is one they have not tagged at all.
   *
   * The control for the test above. `idsWithRole(MUSIC)` and `idsWithRole(UNASSIGNED)` are one
   * character apart from `idsWithRole(AUDIOBOOKS)` at the call site, and every one of them returns
   * a non-empty list over this fixture — so a repository asking for the wrong role still marks
   * *something* as a book, and only an assertion that names which songs are not books can see it.
   * Library 3 exists nowhere in the mirror, which is the state a song is in between a server-side
   * library being added and the next sync.
   */
  @Test
  fun aSongFromAMusicLibraryOrFromNoKnownLibraryAtAllIsNotAnAudiobook() = runTest {
    val source = RecordingSource()

    val items = repository(source).mediaItems(
      PlaybackQueue.of(
        listOf(
          song("a", "mp3", null).copy(libraryId = 1),
          song("b", "mp3", null).copy(libraryId = 3),
          song("c", "mp3", null).copy(libraryId = 2),
        ),
      ),
    )

    assertThat(items.map { it.mediaMetadata.mediaType }).containsExactly(
      MediaMetadata.MEDIA_TYPE_MUSIC,
      MediaMetadata.MEDIA_TYPE_MUSIC,
      MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
    )
  }
}
