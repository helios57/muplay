package app.muplay.media

/**
 * How loud the player should be, given how long the sleep timer has left.
 *
 * Audio cutting to silence mid-word wakes people up, which is the opposite of what a sleep timer is
 * for. The last stretch ramps down instead.
 *
 * Pure arithmetic, deliberately: this is the one half of the sleep timer that needs no player, no
 * clock and no Android, so it is gated on the fast tier where an assertion can be exact.
 * [SleepTimerController] owns *when* to ask; this owns only the answer.
 */
object SleepTimerFade {

  const val DEFAULT_FADE_MS = 20_000L

  fun volumeFor(remainingMs: Long, fadeMs: Long = DEFAULT_FADE_MS): Float = when {
    // A caller can turn the fade off, and `x / 0f` is Infinity or NaN -- both of which reach
    // `player.volume`, which throws for one and does something unpredictable for the other.
    fadeMs <= 0L -> if (remainingMs <= 0L) 0f else 1f
    remainingMs >= fadeMs -> 1f
    // The tick lands past zero routinely; a negative volume throws.
    remainingMs <= 0L -> 0f
    else -> remainingMs.toFloat() / fadeMs
  }
}
