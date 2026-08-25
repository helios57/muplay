package app.muplay.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `Song` → `MediaItem` mapping, field by field.
 *
 * **Every field is observed at two different values**, in one assertion per field, by mapping two
 * deliberately dissimilar songs and asserting the resulting *pair*. That shape is not stylistic:
 * a mapped field replaced by a hardcoded constant is the defect this project found four times in
 * a row on Plan 2 Task 3, and it survives any test that looks at one input.
 *
 * Instrumented rather than JVM because `MediaItem` is built on `android.net.Uri`, which throws
 * off-device, and Robolectric is banned. The rigour moves with the test; it does not get dropped.
 */
@RunWith(AndroidJUnit4::class)
class MediaItemsTest {

  private val first = Song(
    id = "song-1",
    libraryId = 1,
    title = "Track 1",
    albumId = "album-1",
    albumName = "Test Album",
    artistId = "artist-1",
    artistName = "Test Artist",
    trackNumber = 1,
    discNumber = 1,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = "art-1",
  )

  private val second = Song(
    id = "chapter-14",
    libraryId = 2,
    title = "Chapter 14",
    albumId = "album-2",
    albumName = "Test Book",
    artistId = "artist-2",
    artistName = "Test Author",
    trackNumber = 14,
    discNumber = 2,
    durationSeconds = 900,
    suffix = "m4b",
    coverArtId = "art-2",
  )

  private val firstItem =
    MediaItems.of(first, "https://host/rest/stream?id=song-1&s=aaa", "https://host/art-1", StreamFormat.Raw)
  private val secondItem =
    MediaItems.of(second, "https://host/rest/stream?id=chapter-14&s=bbb", "https://host/art-2", StreamFormat.Raw)

  private fun <T> pair(select: (MediaItem) -> T): List<T> = listOf(select(firstItem), select(secondItem))

  @Test
  fun theMediaIdIsTheSongId() {
    // The single most important field in the app: `media_progress` is keyed on it, so a constant
    // here would make every book share one position row.
    assertThat(pair { it.mediaId }).containsExactly("song-1", "chapter-14")
  }

  @Test
  fun theUriIsTheStreamUrlItWasGiven() {
    assertThat(pair { it.localConfiguration?.uri?.toString() })
      .containsExactly("https://host/rest/stream?id=song-1&s=aaa", "https://host/rest/stream?id=chapter-14&s=bbb")
  }

  /**
   * The cache key is the **track id**, never the URI — spec section 4, and the defect Tempo ships.
   * This client's stream URLs carry a fresh auth salt per call (`StreamUrlTest` pins that), so a
   * URL-derived key produces a cache with a 0% hit rate. `TrackIdCacheKeyFactory` refuses a
   * `DataSpec` with no key at all; this is where the key is actually put on.
   */
  @Test
  fun theCustomCacheKeyIsTheSongIdAndNotTheUri() {
    assertThat(pair { it.localConfiguration?.customCacheKey }).containsExactly("song-1", "chapter-14")
  }

  @Test
  fun theTitleIsTheSongTitle() {
    assertThat(pair { it.mediaMetadata.title?.toString() }).containsExactly("Track 1", "Chapter 14")
  }

  @Test
  fun theArtistIsTheSongArtist() {
    assertThat(pair { it.mediaMetadata.artist?.toString() }).containsExactly("Test Artist", "Test Author")
  }

  @Test
  fun theAlbumTitleIsTheSongAlbum() {
    assertThat(pair { it.mediaMetadata.albumTitle?.toString() }).containsExactly("Test Album", "Test Book")
  }

  @Test
  fun theTrackNumberIsTheSongTrackNumber() {
    assertThat(pair { it.mediaMetadata.trackNumber }).containsExactly(1, 14)
  }

  @Test
  fun theDiscNumberIsTheSongDiscNumber() {
    assertThat(pair { it.mediaMetadata.discNumber }).containsExactly(1, 2)
  }

  /**
   * The mirror's duration, in milliseconds, on the metadata.
   *
   * Not decoration. For an Ogg/Opus source `StreamFormat.forSuffix` forces a **live** transcode,
   * a live transcode carries no `Content-Length`, and ExoPlayer therefore reports
   * `C.TIME_UNSET` -- at which point `LegacyConversions` falls back to exactly this field for the
   * platform session. Unset, that fallback is null and every such track is unknown-length on the
   * lock screen, in Auto and on Wear.
   *
   * Two observations at two values, like every other mapped field here: 5 s and 900 s. A
   * `setDurationMs(0L)`, a `setDurationMs(song.durationSeconds.toLong())` (seconds, not
   * milliseconds) and a deleted call all fail this.
   */
  @Test
  fun theDurationIsTheSongDurationInMilliseconds() {
    assertThat(pair { it.mediaMetadata.durationMs }).containsExactly(5_000L, 900_000L)
  }

  @Test
  fun theArtworkUriIsTheOneItWasGiven() {
    assertThat(pair { it.mediaMetadata.artworkUri?.toString() })
      .containsExactly("https://host/art-1", "https://host/art-2")
  }

  @Test
  fun aSongWithNoArtworkGetsNoArtworkUriRatherThanAPlaceholder() {
    val item = MediaItems.of(
      first.copy(coverArtId = null),
      "https://host/stream",
      artworkUri = null,
      format = StreamFormat.Raw,
    )

    assertThat(item.mediaMetadata.artworkUri).isNull()
    // ...and the rest of the mapping is unaffected, so "no artwork" is not silently "no metadata".
    assertThat(item.mediaMetadata.title?.toString()).isEqualTo("Track 1")
  }

  @Test
  fun absentTrackAndDiscNumbersStayAbsent() {
    // Navidrome omits these for a single-file audiobook. Mapping a missing number to 0 would put
    // "0" on a lock screen and sort a book above every real track.
    val item =
      MediaItems.of(first.copy(trackNumber = null, discNumber = null), "https://host/s", null, StreamFormat.Raw)

    assertThat(item.mediaMetadata.trackNumber).isNull()
    assertThat(item.mediaMetadata.discNumber).isNull()
  }

  @Test
  fun everyItemIsPlayableAndNotBrowsable() {
    // Fixed values rather than mapped ones, so the two-observation rule does not apply -- but they
    // are load-bearing: Android Auto (Plan 5) renders a browse tree from exactly these flags, and
    // an item marked browsable shows up as a folder that opens onto nothing.
    assertThat(pair { it.mediaMetadata.isPlayable }).containsExactly(true, true)
    assertThat(pair { it.mediaMetadata.isBrowsable }).containsExactly(false, false)
    assertThat(pair { it.mediaMetadata.mediaType })
      .containsExactly(MediaMetadata.MEDIA_TYPE_MUSIC, MediaMetadata.MEDIA_TYPE_MUSIC)
  }

  /**
   * Navidrome hardcodes `child.Type = "music"` for every media file — the seeded `Test Book.m4b`
   * comes back as `"type": "music"` — so `MEDIA_TYPE_MUSIC` above is not this app agreeing that a
   * book is music; it is the only value the protocol supports, and the library id is what actually
   * distinguishes them. Recorded here so nobody later "fixes" it by inferring a book from a
   * suffix.
   */
  @Test
  fun theMediaTypeIsNotAnAudiobookInferenceAndTheSuffixDoesNotChangeIt() {
    assertThat(MediaItems.of(second, "https://host/s", null, StreamFormat.Raw).mediaMetadata.mediaType)
      .isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
  }

  /**
   * The **served** MIME type, which is not the source file's suffix.
   *
   * `MediaItem.localConfiguration.mimeType` is a real Media3 field the local extractor reads as a
   * hint, and Plan 6 makes it the single value three separate parties read: the proxy serves it as
   * `Content-Type`, the proxy path ends in the matching extension, and `res/@protocolInfo` in the
   * DIDL document declares it. See `ServedMedia` and `MimeAgreement` in `:core:cast`.
   *
   * Two observations of the raw branch, so it cannot be a constant, and one of the transcode
   * branch, where the suffix must NOT win: without that last line an Opus track is announced to
   * Sonos as `audio/ogg` while MP3 bytes are served -- spec section 12's "Sonos rejects a served
   * format" risk in its most confusing form.
   */
  @Test
  fun theMimeTypeIsTheServedFormatAndNotTheSourceSuffix() {
    assertThat(mimeOf(first.copy(suffix = "mp3"), StreamFormat.Raw)).isEqualTo("audio/mpeg")
    assertThat(mimeOf(first.copy(suffix = "flac"), StreamFormat.Raw)).isEqualTo("audio/flac")
    assertThat(mimeOf(first.copy(suffix = "opus"), StreamFormat.Mp3(192))).isEqualTo("audio/mpeg")
  }

  /**
   * ...and the value really did come from this call rather than from the queue deciding twice.
   * `QueueRepository` computes one `StreamFormat`, builds the URL with it and passes the same value
   * here, so `format=mp3` on the wire and `audio/mpeg` on the item are one decision. The pairing
   * that would go unnoticed is a `.opus` source streamed raw, which this rules out from the other
   * side: the same song answers differently depending only on the format it is given.
   */
  @Test
  fun theSameSongAnswersDifferentlyForTheFormatItsUrlWasBuiltWith() {
    val song = first.copy(suffix = "flac")

    assertThat(mimeOf(song, StreamFormat.Raw)).isEqualTo("audio/flac")
    assertThat(mimeOf(song, StreamFormat.Mp3(192))).isEqualTo("audio/mpeg")
  }

  private fun mimeOf(song: Song, format: StreamFormat): String? =
    MediaItems.of(song, "https://host/s", null, format).localConfiguration?.mimeType
}
