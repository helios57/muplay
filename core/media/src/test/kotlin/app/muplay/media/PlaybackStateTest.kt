package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A plain JVM test, because [PlaybackState] is a plain Kotlin value with no Android or Media3 type
 * in it -- the same split `StreamRetryPolicy` gets, and for the same reason: the part of the media
 * layer that can be gated by the fast tier should be.
 *
 * What is worth asserting about a `data class` is only the part a person wrote, and here that is
 * [PlaybackState.NOTHING_PLAYING]. It is not decoration: four other tasks render from this value
 * before anything is loaded, and each field is a separate claim about what the UI should show then.
 * A `hasNext = true` renders an enabled "next" button with no queue behind it; a non-null `title`
 * names a track that is not loaded; a non-zero `durationMs` gives a seek bar a length it invented.
 *
 * The mapping *into* a non-empty state lives in `PlaybackConnection.publish`, takes a
 * `androidx.media3.common.Player`, and cannot be reached from here at all -- this project has no
 * Robolectric. It is gated on the device by
 * `MuPlaybackServiceTest.everyPlaybackStateFieldReachesTheUiSideOfTheConnection`, which observes
 * every field of it at a value no other field could have supplied.
 */
class PlaybackStateTest {

  @Test
  fun `nothing playing is neither playing nor buffering`() {
    assertThat(PlaybackState.NOTHING_PLAYING.isPlaying).isFalse()
    // Not the same claim: "buffering" is what a UI renders as a spinner, and a state that is
    // neither playing nor buffering is the only one that renders as idle.
    assertThat(PlaybackState.NOTHING_PLAYING.isBuffering).isFalse()
  }

  @Test
  fun `nothing playing names no track`() {
    assertThat(
      listOf(
        PlaybackState.NOTHING_PLAYING.mediaId,
        PlaybackState.NOTHING_PLAYING.title,
        PlaybackState.NOTHING_PLAYING.artist,
        PlaybackState.NOTHING_PLAYING.albumTitle,
        PlaybackState.NOTHING_PLAYING.artworkUri,
      ),
      // `containsOnlyNulls` on the list rather than five separate `isNull()` calls, so a failure
      // names which of the five is populated instead of stopping at the first.
    ).containsOnlyNulls()
  }

  @Test
  fun `nothing playing is at zero of zero`() {
    assertThat(PlaybackState.NOTHING_PLAYING.positionMs).isZero()
    assertThat(PlaybackState.NOTHING_PLAYING.durationMs).isZero()
  }

  @Test
  fun `nothing playing can step neither forward nor back`() {
    assertThat(PlaybackState.NOTHING_PLAYING.hasNext).isFalse()
    assertThat(PlaybackState.NOTHING_PLAYING.hasPrevious).isFalse()
  }
}
