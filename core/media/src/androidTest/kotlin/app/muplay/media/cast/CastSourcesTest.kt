package app.muplay.media.cast

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.cast.didl.ServedMedia
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The read of a `MediaItem` the cast layer makes, at the values `HandoverTest` never produces.
 *
 * On the device tier because every input here is an Android type: a `MediaItem` is built on
 * `android.net.Uri` and this project has no Robolectric. Deliberately separate from `HandoverTest`
 * -- that suite drives the *happy* item, the one `MediaItems.of` builds, and these are the arms a
 * real queue reaches for reasons a handover cannot arrange: an item resolved from a browse tree
 * with no URL, a track whose length the server did not report, a `mimeType` this app did not set.
 */
@RunWith(AndroidJUnit4::class)
class CastSourcesTest {

  /** What the artwork resolver answers when a case is not about artwork. */
  private val NO_ARTWORK: String? = null

  @Test
  fun aMediaItemWithNoPlayableUrlIsSkippedRatherThanCastAsNothing() {
    // What a `MediaController` sends when it asks the session to play something from the browse
    // tree: a media id and no `localConfiguration` at all. Casting it would publish a proxy token
    // for the empty string and hand a renderer a URL that 404s, which presents as a speaker that
    // connects and plays silence.
    val noUrl = MediaItem.Builder().setMediaId("browse-only").build()

    assertThat(CastSources.of(noUrl)).isNull()
    // ...and the rest of the queue still casts. A queue with one unplayable item in it must cast
    // the others, not fail.
    val cast = runBlocking { CastSources.of(listOf(noUrl, item("track-1"))) { NO_ARTWORK } }
    assertThat(cast.map { it.mediaId }).containsExactly("track-1")
  }

  @Test
  fun aBookIsToldApartFromASongByTheOneFieldThatCanAnswerIt() {
    // `MediaMetadata.mediaType` and nothing else: Navidrome hardcodes `child.Type = "music"` for
    // every file, and a suffix cannot tell a book from a DJ set. Two observations at two values,
    // because a constant `false` passes half of this.
    val book = item("book-1", mediaType = MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
    val song = item("song-1", mediaType = MediaMetadata.MEDIA_TYPE_MUSIC)

    assertThat(CastSources.of(book)!!.isAudiobook).isTrue()
    assertThat(CastSources.of(song)!!.isAudiobook).isFalse()
  }

  @Test
  fun aTrackWhoseLengthTheServerDidNotReportKeepsTheUnknownSentinel() {
    // Normalising "unknown" to zero is `CastItems.of`'s job, and it is the only place that should
    // do it -- a second normalisation here would make an unknown length indistinguishable from a
    // zero-length one at the boundary where they still differ.
    val unknown = MediaItem.Builder()
      .setMediaId("x")
      .setUri("https://host/x")
      .setMimeType("audio/mpeg")
      .setMediaMetadata(MediaMetadata.Builder().build())
      .build()

    assertThat(CastSources.of(unknown)!!.durationMs).isEqualTo(C.TIME_UNSET)
  }

  @Test
  fun theServedFormatIsReadBackOffTheItemAndFallsBackOnlyForAnItemThisAppDidNotBuild() {
    // Two arms, and they are different failures. A known MIME must round-trip to the extension the
    // proxy path will carry, because that is the URL leg of the three-way agreement a Sonos reads.
    assertThat(CastSources.of(item("a", mimeType = "audio/mp4"))!!.served)
      .isEqualTo(ServedMedia("audio/mp4", "m4a"))
    // An item with no `mimeType` at all is one this app did not build; MP3 is the guess most likely
    // to play and a renderer that disagrees sniffs the bytes.
    val noMime = MediaItem.Builder().setMediaId("b").setUri("https://host/b").build()
    assertThat(CastSources.of(noMime)!!.served)
      .isEqualTo(ServedMedia(ServedMedia.FALLBACK_MIME, ServedMedia.FALLBACK_EXTENSION))
  }

  @Test
  fun theTitleAndTheArtistTravelWithTheItem() {
    // A renderer displays these, and an absent `<dc:title>` is a 402 on a strict one -- so the
    // empty-string fallback is asserted rather than assumed, alongside a real value so that a
    // constant empty string cannot pass.
    val titled = item("t", title = "Chapter Four", artist = "A Narrator")
    val untitled = MediaItem.Builder().setMediaId("u").setUri("https://host/u").build()

    assertThat(CastSources.of(titled)!!.title).isEqualTo("Chapter Four")
    assertThat(CastSources.of(titled)!!.artist).isEqualTo("A Narrator")
    // The artwork a renderer's display shows, and its absence, at two values -- the null arm is
    // what a book with no cover art in the mirror produces.
    // The artwork URL is the **caller's**, not the item's: an item carries `muplay-art:<id>`, which
    // names nothing a renderer could fetch, and the resolved URL never leaves this phone -- the
    // renderer is handed a proxy token minted from it. See `ArtworkUri` and `CastSource.artworkUri`.
    assertThat(CastSources.of(titled)!!.artworkUri).isNull()
    assertThat(CastSources.of(withArtwork(), "https://host/art.jpg")!!.artworkUri)
      .isEqualTo("https://host/art.jpg")
    // ...and what the item itself carries is never copied across, whatever it is.
    assertThat(CastSources.of(withArtwork())!!.artworkUri).isNull()
    assertThat(CastSources.of(untitled)!!.title).isEmpty()
    assertThat(CastSources.of(untitled)!!.artist).isNull()
  }

  @Test
  fun theSourceDoesNotPrintTheCredentialBearingUpstreamUrl() {
    // The URL this carries is a Subsonic `/rest/stream` URL with the user's `u`, `t` and `s` in the
    // query string. It has to be *in* the object -- the proxy fetches it -- and it must never reach
    // a log line. Asserted here as well as in `:core:cast` because this is the object that puts it
    // there.
    val source = CastSources.of(item("s"))!!

    assertThat(source.upstreamUrl).isEqualTo("https://host/s")
    assertThat(source.toString()).doesNotContain("https://host/s")
  }

  private fun withArtwork(): MediaItem = MediaItem.Builder()
    .setMediaId("art")
    .setUri("https://host/art")
    .setMimeType("audio/mpeg")
    .setMediaMetadata(
      MediaMetadata.Builder().setArtworkUri("https://host/art.jpg".toUri()).build(),
    )
    .build()

  private fun item(
    mediaId: String,
    title: String? = "A Title",
    artist: String? = "An Artist",
    mimeType: String = "audio/mpeg",
    mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
  ): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri("https://host/$mediaId")
    .setMimeType(mimeType)
    .setMediaMetadata(
      MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle("An Album")
        .setDurationMs(180_000L)
        .setMediaType(mediaType)
        .build(),
    )
    .build()
}
