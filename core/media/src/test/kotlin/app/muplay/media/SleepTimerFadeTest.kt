package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * The fade ramp, exactly, with no player and no clock anywhere near it.
 *
 * This is the arithmetic half of the sleep timer. The device tier proves the ramp reaches real
 * samples (`SleepTimerFadeAudioTest`); this proves the ramp is the shape it claims to be, at a
 * precision a real decoder can never offer.
 */
class SleepTimerFadeTest {

  private val tolerance = Offset.offset(0.001f)

  @Test
  fun `outside the fade window the volume is untouched`() {
    // Two observations well outside, plus the one exactly on the boundary: `>= fadeMs` and
    // `> fadeMs` differ only at 20 000, and that is a real off-by-one -- it decides whether the
    // ramp's first step is 1.0 or 0.99995.
    assertThat(SleepTimerFade.volumeFor(remainingMs = 120_000L, fadeMs = 20_000L)).isEqualTo(1.0f)
    assertThat(SleepTimerFade.volumeFor(remainingMs = 20_001L, fadeMs = 20_000L)).isEqualTo(1.0f)
    assertThat(SleepTimerFade.volumeFor(remainingMs = 20_000L, fadeMs = 20_000L)).isEqualTo(1.0f)
  }

  @Test
  fun `inside the fade window the volume falls linearly`() {
    // Five distinct values from one fade length. A constant satisfies at most one of them, and a
    // step function satisfies at most two.
    val volumes = listOf(20_000L, 15_000L, 10_000L, 5_000L, 0L)
      .map { SleepTimerFade.volumeFor(it, fadeMs = 20_000L) }

    assertThat(volumes[0]).isCloseTo(1.0f, tolerance)
    assertThat(volumes[1]).isCloseTo(0.75f, tolerance)
    assertThat(volumes[2]).isCloseTo(0.5f, tolerance)
    assertThat(volumes[3]).isCloseTo(0.25f, tolerance)
    assertThat(volumes[4]).isCloseTo(0.0f, tolerance)
  }

  @Test
  fun `the fade length is a parameter and it moves the answer`() {
    // Same remaining time, two fade lengths, two volumes. Without this, `fadeMs` could be ignored
    // entirely -- replaced by the constant `DEFAULT_FADE_MS` -- and every assertion above would
    // still pass, because every one of them passes 20 000.
    assertThat(SleepTimerFade.volumeFor(remainingMs = 5_000L, fadeMs = 20_000L))
      .isCloseTo(0.25f, tolerance)
    assertThat(SleepTimerFade.volumeFor(remainingMs = 5_000L, fadeMs = 10_000L))
      .isCloseTo(0.5f, tolerance)
  }

  @Test
  fun `the default fade length is the one the timer ships with`() {
    // The default is a value the controller's no-argument Hilt constructor depends on, so it is
    // observed here rather than only where it is declared.
    assertThat(SleepTimerFade.volumeFor(remainingMs = SleepTimerFade.DEFAULT_FADE_MS / 4))
      .isCloseTo(0.25f, tolerance)
  }

  @Test
  fun `a negative remaining time is silence, not a negative volume`() {
    // The timer ticks every 250 ms, so the last observation before expiry is routinely past zero.
    // `player.volume` rejects negatives, and it does so by throwing.
    assertThat(SleepTimerFade.volumeFor(remainingMs = -3_000L, fadeMs = 20_000L)).isZero
  }

  @Test
  fun `a fade length of zero does not divide by zero`() {
    // Reachable from a caller that turns the fade off. `x / 0f` is `Infinity` or `NaN`, and both
    // reach `player.volume`, which throws for one and behaves unpredictably for the other. Both
    // arms of the guard, so "returns 1 always" and "returns 0 always" are each refused.
    assertThat(SleepTimerFade.volumeFor(remainingMs = 5_000L, fadeMs = 0L)).isEqualTo(1.0f)
    assertThat(SleepTimerFade.volumeFor(remainingMs = 0L, fadeMs = 0L)).isEqualTo(0.0f)
    // Negative, because a caller subtracting two clock readings can produce one.
    assertThat(SleepTimerFade.volumeFor(remainingMs = 5_000L, fadeMs = -1L)).isEqualTo(1.0f)
  }

  @Test
  fun `every value the ramp can produce is a volume the player will accept`() {
    // `Player.setVolume` requires 0..1 inclusive and throws otherwise, so the range is a contract
    // rather than a nicety. Swept across the whole ramp and well past both ends of it.
    val sampled = (-40_000L..40_000L step 137L).map { SleepTimerFade.volumeFor(it, fadeMs = 20_000L) }

    // The exact size first: `allMatch` over an empty list is vacuously true, and a sweep that
    // produced nothing would satisfy the predicate below without measuring anything.
    assertThat(sampled).hasSize(584)
    assertThat(sampled).allMatch { it in 0.0f..1.0f }
    // ...and the sweep really did reach both ends, so "always 1" and "always 0" are both refused.
    assertThat(sampled).contains(1.0f, 0.0f)
  }
}
