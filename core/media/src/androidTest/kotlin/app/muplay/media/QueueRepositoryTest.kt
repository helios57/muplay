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

  private fun song(id: String, suffix: String?, coverArtId: String?) = Song(
    id = id,
    libraryId = 1,
    title = "Title $id",
    albumId = "album",
    albumName = "Album",
    artistId = "artist",
    artistName = "Artist",
    trackNumber = 1,
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

  @Test
  fun everySongInTheQueueBecomesOneMediaItemInTheSameOrder() = runTest {
    val source = RecordingSource()
    val queue = PlaybackQueue.of(
      listOf(song("a", "mp3", null), song("b", "flac", null), song("c", "mp3", null)),
    )

    val items = repository(source).mediaItems(queue)

    assertThat(items.map { it.mediaId }).containsExactly("a", "b", "c")
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
      PlaybackQueue.of(listOf(song("a", "mp3", null), song("b", "opus", null), song("c", "flac", null))),
    )

    assertThat(source.streamCalls).containsExactly(
      "a" to StreamFormat.Raw,
      "b" to StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS),
      "c" to StreamFormat.Raw,
    )
  }

  @Test
  fun aSongWithCoverArtGetsAnArtworkUriAtTheSizeThisAppAsksFor() = runTest {
    val source = RecordingSource()

    val items = repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", "art-1"))))

    assertThat(source.coverArtCalls).containsExactly("art-1" to QueueRepository.ARTWORK_SIZE_PX)
    assertThat(items.single().mediaMetadata.artworkUri.toString())
      .isEqualTo("https://host/rest/getCoverArt?id=art-1&size=${QueueRepository.ARTWORK_SIZE_PX}")
  }

  @Test
  fun aSongWithNoCoverArtAsksForNoneAndGetsNone() = runTest {
    val source = RecordingSource()

    val items = repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", null))))

    assertThat(source.coverArtCalls).isEmpty()
    assertThat(items.single().mediaMetadata.artworkUri).isNull()
  }

  @Test
  fun theStreamUrlLandsOnTheItemItWasBuiltFor() = runTest {
    // Two songs, two URLs, asserted as a pair: a repository that built one URL and reused it for
    // every item would pass every test above.
    val source = RecordingSource()

    val items = repository(source)
      .mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", null), song("b", "mp3", null))))

    assertThat(items.map { it.localConfiguration?.uri?.toString() }).containsExactly(
      "https://host/rest/stream?id=a&format=raw",
      "https://host/rest/stream?id=b&format=raw",
    )
  }
}
