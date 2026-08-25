package app.muplay.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.Song
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
    MediaItems.of(first, "https://host/rest/stream?id=song-1&s=aaa", "https://host/art-1", isAudiobook = false)
  private val secondItem =
    MediaItems.of(second, "https://host/rest/stream?id=chapter-14&s=bbb", "https://host/art-2", isAudiobook = false)

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
      isAudiobook = false,
    )

    assertThat(item.mediaMetadata.artworkUri).isNull()
    // ...and the rest of the mapping is unaffected, so "no artwork" is not silently "no metadata".
    assertThat(item.mediaMetadata.title?.toString()).isEqualTo("Track 1")
  }

  @Test
  fun absentTrackAndDiscNumbersStayAbsent() {
    // Navidrome omits these for a single-file audiobook. Mapping a missing number to 0 would put
    // "0" on a lock screen and sort a book above every real track.
    val item = MediaItems.of(
      first.copy(trackNumber = null, discNumber = null),
      "https://host/s",
      null,
      isAudiobook = false,
    )

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
    // `mediaType` used to be asserted here too, at one value, because it was a constant. It is not
    // one any more, and both fixtures above are built `isAudiobook = false` -- so an assertion here
    // would observe the music arm twice and prove nothing about the switch. The two tests below
    // observe it at both values instead.
  }

  /**
   * The one fact the protocol cannot supply, and the one this app is allowed to decide.
   *
   * Navidrome hardcodes `child.Type = "music"` for every media file — the seeded `Test Book.m4b`
   * comes back as `"type": "music"` — so the user's own `LibraryRole` assignment, joined to
   * `Song.libraryId` by [QueueRepository], is the only mechanism there is. Two observations, so a
   * constant satisfies neither: this field was a hardcoded `MEDIA_TYPE_MUSIC` until this task, and
   * it is what `PlaybackAudioAttributes` reads to decide speech versus music.
   */
  @Test
  fun theMediaTypeFollowsTheUsersOwnLibraryRoleAndNothingElse() {
    assertThat(
      MediaItems.of(first, "https://host/s", null, isAudiobook = false).mediaMetadata.mediaType,
    ).isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
    assertThat(
      MediaItems.of(second, "https://host/s", null, isAudiobook = true).mediaMetadata.mediaType,
    ).isEqualTo(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
  }

  /**
   * The suffix is not the signal, in **both** directions.
   *
   * `second` has suffix `m4b` — the shape that tempts an inference — and is still music unless the
   * user's `LibraryRole` says otherwise; `first` has suffix `mp3` and is a book when the user said
   * its library is one. Both are real: an audiobook library holds plain mp3 chapters, and a music
   * library holds m4b DJ sets. A suffix inference passes the test above and fails this one.
   */
  @Test
  fun theFileSuffixNeverDecidesWhetherSomethingIsAnAudiobook() {
    assertThat(second.suffix).isEqualTo("m4b")
    assertThat(first.suffix).isEqualTo("mp3")
    assertThat(
      MediaItems.of(second, "https://host/s", null, isAudiobook = false).mediaMetadata.mediaType,
    ).isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
    assertThat(
      MediaItems.of(first, "https://host/s", null, isAudiobook = true).mediaMetadata.mediaType,
    ).isEqualTo(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
  }
}
