package app.muplay.media

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.model.AlbumWithSongs
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.SearchResults
import app.muplay.model.ServerInfo
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.network.SubsonicSource
import java.io.File
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
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

    override fun streamUrl(songId: String, format: StreamFormat): String {
      streamCalls += songId to format
      return "https://host/rest/stream?id=$songId&format=${format.wireValue}"
    }

    override fun coverArtUrl(coverArtId: String, sizePx: Int?): String {
      coverArtCalls += coverArtId to sizePx
      return "https://host/rest/getCoverArt?id=$coverArtId&size=$sizePx"
    }

    // Everything else on the port is out of this test's scope. `error(...)` rather than a benign
    // default: a call that should never happen must fail loudly rather than return something
    // plausible that the test would then be quietly asserting about.
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

  private fun repository(source: SubsonicSource): QueueRepository {
    val (provider, file) = fixedSubsonicSourceProvider(
      ApplicationProvider.getApplicationContext(),
      source,
    )
    storeFile = file
    return QueueRepository(provider)
  }

  @After
  fun tearDown() {
    if (::storeFile.isInitialized) storeFile.delete()
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
   * Asserted once, on both sides of the same call: what was requested (`coverArtCalls`) and what
   * came back on the item.
   */
  @Test
  fun aSongWithCoverArtGetsAnArtworkUriAtTheSizeThisAppAsksFor() = runTest {
    val source = RecordingSource()

    val items = repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", "art-1"))))

    assertThat(source.coverArtCalls).containsExactly("art-1" to 512)
    assertThat(items.single().mediaMetadata.artworkUri.toString())
      .isEqualTo("https://host/rest/getCoverArt?id=art-1&size=512")
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
   * held **one song**, so `artworkUri = queue.songs.first().coverArtId?.let { … }` -- and hoisting
   * the `coverArtUrl` call out of `map` entirely -- were green across the whole suite. That is the
   * same shape as the stream URL's own probe, left unwritten for the other URL.
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
      "https://host/rest/getCoverArt?id=art-b&size=512",
      "https://host/rest/getCoverArt?id=art-a&size=512",
    )
    // The requests, not just the results: this is the observation that a single hoisted
    // `coverArtUrl` call fails on, and it fails on the *count* rather than on a value.
    assertThat(source.coverArtCalls).containsExactly("art-b" to 512, "art-a" to 512)
  }
}
