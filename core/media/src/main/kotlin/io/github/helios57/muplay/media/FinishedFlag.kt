package io.github.helios57.muplay.media

/**
 * Whether a `media_progress` row is finished after one more write -- [ProgressWriter]'s whole
 * decision about the flag, with no Android, no Room and no clock in it.
 *
 * It used to be `finished || existing.isFinished`: set at `STATE_ENDED` and **never cleared**. That
 * made a finished book a one-way door. Pressing Resume on it started playback again (the resume
 * policy answers zero for a finished item), the ticker wrote positions from the start of the file,
 * and every one of those writes carried the stale flag forward. The listener heard the book a second
 * time through and it was still "Finished" -- never back in Continue listening, and the next Resume
 * started at zero again whatever they had heard.
 *
 * The rule now: a write that **moves the position back by more than [REWIND_TOLERANCE_MS]** is the
 * listener listening again, and clears the flag. Anything else preserves it, which is what the
 * flag was always for:
 *
 *  * the ticker keeps firing after `STATE_ENDED`, with the position parked at the end -- not a
 *    rewind, so the flag survives. A comparison that cleared on any `<` would un-finish the book at
 *    the first tick after it finished, because a player's final `currentPosition` is not guaranteed
 *    to equal the one written at the ending;
 *  * a stray write with the same position (pause, focus loss) does not un-finish either.
 *
 * Multi-file books are handled one layer up and need nothing here: the flag lives on the last
 * file's row, and hearing any earlier file afterwards makes that row older than the one just
 * written, which is what `BookFinish` reads.
 */
internal object FinishedFlag {

  /**
   * Slack for the end of a file. A position that jitters back by a second as a player settles at the
   * end is not a listener starting over; thirty seconds back into a chapter they just finished is
   * ambiguous and is treated as listening again, because a book finished by accident is more
   * harmful than a book un-finished by a long seek.
   */
  const val REWIND_TOLERANCE_MS = 5_000L

  fun next(
    wasFinished: Boolean,
    storedPositionMs: Long,
    newPositionMs: Long,
    reachedTheEnd: Boolean,
  ): Boolean {
    if (reachedTheEnd) return true
    if (!wasFinished) return false
    return newPositionMs >= storedPositionMs - REWIND_TOLERANCE_MS
  }
}
