package app.muplay.cast.session

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **The seek bar between two polls.**
 *
 * `AVTransport` is polled once a second and there is no cheaper source of a position -- the
 * `LastChange` event excludes `RelTime` and `AbsTime` outright. Everything a listener sees between
 * two polls is this function, and every one of its rules exists because the obvious implementation
 * gets that case wrong:
 *
 * - a constant makes the bar tick once a second and makes a progress writer record a stale position;
 * - an unconditional extrapolation invents playback on a speaker that never started, and runs past
 *   the end of the track when a poll stalls.
 */
class CastPlaybackTest {

  @Test
  fun `the position advances in real time between polls`() {
    val playback = playing(positionMs = 30_000L, measuredAt = 1_000L)

    assertThat(playback.positionAtMs(1_000L)).isEqualTo(30_000L)
    assertThat(playback.positionAtMs(1_250L)).isEqualTo(30_250L)
    assertThat(playback.positionAtMs(1_999L)).isEqualTo(30_999L)
  }

  @Test
  fun `a poll resets it to what the renderer said, forwards or backwards`() {
    // The next poll is the truth. A renderer that reports 29 s after we extrapolated to 31 s is
    // not wrong -- it is the device, and a supplier that only ever moved forwards would drift
    // permanently apart from it.
    val afterSeekBack = playing(positionMs = 5_000L, measuredAt = 10_000L)

    assertThat(afterSeekBack.positionAtMs(10_000L)).isEqualTo(5_000L)
  }

  @Test
  fun `it does not advance unless the renderer said it was playing`() {
    // The optimistic window after `play()`, and every pause. Two snapshots identical but for this
    // one flag, so nothing else can be what stopped the clock.
    val advancing = playing(positionMs = 30_000L, measuredAt = 1_000L)
    val still = advancing.copy(positionAdvancing = false)

    assertThat(advancing.positionAtMs(4_000L)).isEqualTo(33_000L)
    assertThat(still.positionAtMs(4_000L)).isEqualTo(30_000L)
  }

  @Test
  fun `it never runs backwards when the clock does`() {
    // `nowMs` is a seam and a caller can read a snapshot taken microseconds later on another
    // thread. A negative elapsed time must not rewind a listener's position.
    val playback = playing(positionMs = 30_000L, measuredAt = 5_000L)

    assertThat(playback.positionAtMs(4_000L)).isEqualTo(30_000L)
  }

  @Test
  fun `it never runs past a known duration`() {
    // A stalled poll must not report 4:31 of a 3:00 track. That number reaches `ProgressWriter`,
    // which writes it as a resume position -- past the end of the chapter, which is where a
    // listener would be sent next time they opened the book.
    val playback = playing(positionMs = 170_000L, measuredAt = 0L).copy(durationMs = 180_000L)

    assertThat(playback.positionAtMs(9_000L)).isEqualTo(179_000L)
    assertThat(playback.positionAtMs(60_000L)).isEqualTo(180_000L)
  }

  @Test
  fun `an unknown duration does not clamp to zero`() {
    // The other side of the clamp. `durationMs` is 0 until the item is loaded, and a clamp that
    // did not check would pin every position at 0 -- a seek bar that never moves, on every track,
    // which is the exact failure the clamp exists to prevent one variant of.
    val playback = playing(positionMs = 1_000L, measuredAt = 0L).copy(durationMs = 0L)

    assertThat(playback.positionAtMs(9_000L)).isEqualTo(10_000L)
  }

  private fun playing(positionMs: Long, measuredAt: Long): CastPlayback = CastPlayback.IDLE.copy(
    playbackState = CastPlaybackState.READY,
    playWhenReady = true,
    positionAdvancing = true,
    queueSize = 1,
    currentMediaId = "track-1",
    positionMs = positionMs,
    positionMeasuredAtMs = measuredAt,
    durationMs = 300_000L,
  )
}
