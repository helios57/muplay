package app.muplay.media

/**
 * How far back a book goes when you come back to it.
 *
 * Spec section 5 asks for *"smart rewind on resume, scaled to how long the book was paused"* and
 * says nothing more, which admits a constant, a linear ramp and doing nothing at all. The table
 * below is the decision; Task 10 writes it into the spec, because a behaviour every resume in the
 * application passes through cannot live only in a source file.
 *
 * | Away for | Rewind | Why |
 * |---|---|---|
 * | under 15 s | 0 s | You paused to say something. You know where you are. |
 * | 15 s - 1 min | 2 s | Enough to lose the last clause. |
 * | 1 min - 1 h | 5 s | You did something else. A sentence, roughly. |
 * | 1 h - 1 day | 10 s | Later the same day. Enough to re-enter a paragraph. |
 * | over 1 day | 20 s | Another day. Enough to remember who is talking. |
 *
 * Bands are **half-open on the low side**: a band's threshold is the first value of the *next*
 * band, so [AWAY_NONE_MS] itself already rewinds [REWIND_SHORT_MS]. Every threshold is asserted on
 * both sides, one millisecond apart, in `SmartRewindTest` -- which is what makes that suite an
 * assertion about the comparison operator rather than about one number.
 *
 * The top band is deliberately **bounded**. An unbounded scale rewinds a listener into the
 * previous chapter after a holiday, which loses more than it recovers.
 *
 * A **negative** `awayMs` -- a device whose clock moved backwards between the write and the read,
 * so `clock.millis() - lastPlayedAtEpochMs` came out below zero (an NTP correction, a manual
 * change, a timezone-confused restore) -- lands in the first band and rewinds nothing. That is a
 * decision, and it is asserted at `-1` and at `Long.MIN_VALUE`.
 *
 * There is deliberately **no** separate `awayMs < 0L` arm in front of the table. One was written
 * first, and then measured: removing it changes the answer at **no input at all**, because every
 * negative is already below [AWAY_NONE_MS] -- `Long.MIN_VALUE` included. A `when` arm that no
 * input can tell apart from its own absence is the guard-that-cannot-fail this project keeps
 * finding, one layer below the assertions it usually finds it in, so the decision is recorded here
 * in prose -- where prose belongs -- rather than as a branch a coverage counter would score 2/2
 * and no mutation could ever redden.
 *
 * (The idea is Voice's, and Voice is GPL: none of it was read. The table above is derived from
 * spec section 5's sentence plus the reasoning in the last column.)
 */
object SmartRewind {

  const val AWAY_NONE_MS = 15_000L
  const val AWAY_SHORT_MS = 60_000L
  const val AWAY_MEDIUM_MS = 3_600_000L
  const val AWAY_LONG_MS = 86_400_000L

  const val REWIND_NONE_MS = 0L
  const val REWIND_SHORT_MS = 2_000L
  const val REWIND_MEDIUM_MS = 5_000L
  const val REWIND_LONG_MS = 10_000L
  const val REWIND_MAX_MS = 20_000L

  fun rewindMs(awayMs: Long): Long = when {
    awayMs < AWAY_NONE_MS -> REWIND_NONE_MS
    awayMs < AWAY_SHORT_MS -> REWIND_SHORT_MS
    awayMs < AWAY_MEDIUM_MS -> REWIND_MEDIUM_MS
    awayMs < AWAY_LONG_MS -> REWIND_LONG_MS
    else -> REWIND_MAX_MS
  }

  /**
   * Where playback actually starts: [storedPositionMs] less whatever [rewindMs] says this absence
   * is worth, and **never negative** -- a negative reaches `seekTo`, and ExoPlayer's behaviour for
   * one is not something a listener should discover.
   *
   * Compare-then-subtract rather than the shorter `(storedPositionMs - rewindMs(awayMs))
   * .coerceAtLeast(0L)`, which reads better and is wrong at one input: `Long.MIN_VALUE - 20_000`
   * **wraps to a huge positive**, which `coerceAtLeast` then passes straight through to `seekTo`.
   * The form below cannot overflow, because the subtraction only runs once the result is already
   * known to be positive. `ProgressWriter` coerces at write time so a negative row should not
   * exist, but this function is the last thing between a hand-edited database and the player.
   */
  fun resumePositionMs(storedPositionMs: Long, awayMs: Long): Long {
    val rewind = rewindMs(awayMs)
    return if (storedPositionMs <= rewind) 0L else storedPositionMs - rewind
  }
}
