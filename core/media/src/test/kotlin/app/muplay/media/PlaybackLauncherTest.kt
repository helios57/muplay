package app.muplay.media

import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A plain JVM test over [launchQueue], the one decision [PlaybackLauncher] makes before it touches
 * a `MediaController`.
 *
 * It is a top-level function rather than a private method for exactly this reason: everything else
 * in `PlaybackLauncher.play` is a `MediaController` handshake that needs a bound media session and
 * therefore a device, but *which* item a caller asked to start from is the value a user notices
 * immediately when it is wrong — tapping track 7 and hearing track 1 — and it costs a device run
 * to notice on the instrumented tier. Same argument `ResumePolicy` and `StreamRetryPolicy` already
 * make in this module.
 */
class PlaybackLauncherTest {

  private fun song(id: String) = Song(
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
    suffix = "mp3",
    coverArtId = null,
  )

  private val threeSongs = listOf(song("a"), song("b"), song("c"))

  @Test
  fun `an empty request produces no queue at all`() {
    // Rather than a PlaybackQueue.of that would throw: "play this album" against an album whose
    // songs have not arrived from the mirror yet is an ordinary race, not a programming error.
    assertThat(launchQueue(emptyList(), startIndex = 0)).isNull()
  }

  @Test
  fun `the queue holds the songs it was given, in the order it was given them`() {
    val queue = launchQueue(threeSongs, startIndex = 0)

    assertThat(queue?.songs?.map { it.id }).containsExactly("a", "b", "c")
  }

  @Test
  fun `the start index is the one the caller asked for`() {
    // Two disjoint observations. A `startIndex` hardcoded to 0 -- the obvious accident, and the
    // one that makes every track on an album screen play the first track -- passes the first of
    // these and fails the second.
    assertThat(launchQueue(threeSongs, startIndex = 0)?.startIndex).isZero
    assertThat(launchQueue(threeSongs, startIndex = 2)?.startIndex).isEqualTo(2)
  }

  @Test
  fun `a start index past the end of the queue is clamped to the last song`() {
    // `PlaybackQueue`'s own `require` would throw here. A shuffle list that shrank between the tap
    // and the launch is a real race against a live mirror, and losing playback to an
    // IllegalArgumentException is a worse answer than starting from the nearest real song.
    val queue = launchQueue(threeSongs, startIndex = 9)

    assertThat(queue?.startIndex).isEqualTo(2)
    assertThat(queue?.songAt(2)?.id).isEqualTo("c")
  }

  @Test
  fun `a negative start index is clamped to the first song`() {
    val queue = launchQueue(threeSongs, startIndex = -4)

    assertThat(queue?.startIndex).isZero
    assertThat(queue?.songAt(0)?.id).isEqualTo("a")
  }

  /**
   * The clamp must not collapse to a constant at either end. With only the two tests above,
   * `startIndex.coerceIn(0, 0)` and `startIndex.coerceIn(songs.indices.last, songs.indices.last)`
   * each survive one of them; a single-song queue is the shape where both ends coincide and every
   * "play just this track" call makes it, so it is not a contrived fixture.
   */
  @Test
  fun `a single-song queue clamps every index onto its one song`() {
    val one = listOf(song("only"))

    assertThat(launchQueue(one, startIndex = 0)?.startIndex).isZero
    assertThat(launchQueue(one, startIndex = 5)?.startIndex).isZero
    assertThat(launchQueue(one, startIndex = -5)?.startIndex).isZero
  }
}
