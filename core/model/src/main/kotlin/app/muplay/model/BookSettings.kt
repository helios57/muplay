package app.muplay.model

/**
 * How one **book** plays. Not how one file plays.
 *
 * Spec section 3 puts `speed` and `skipSilence` on the per-item progress row. For a book that is a
 * single M4B file those are the same grain and it works; for a book that is thirty MP3s they are
 * not, and per-item storage means the speed a listener chose in chapter 3 does not survive the
 * transition to chapter 4. This type, and the `book_settings` table behind it, are at the grain
 * the setting actually has. Spec section 3 and section 5 are corrected accordingly (Task 10).
 *
 * `gainDb` is deliberately absent: ReplayGain is a property of the individual file, measured by
 * whatever tagged it, so `media_progress.gainDb` is at the right grain already. Applying it means
 * a gain stage in the audio pipeline, which is Plan 3 Task 11's and has landed there -- this plan
 * neither writes nor reads the column.
 */
data class BookSettings(
  val bookId: String,
  val speed: Float,
  val skipSilence: Boolean,
) {
  companion object {
    const val DEFAULT_SPEED = 1.0f

    /**
     * Below 0.5x speech is unintelligible and above 3.0x it is a sound effect. The bounds are also
     * what stops a corrupted row from handing `ExoPlayer` a speed of 0 (silence that looks like a
     * hang) or 100 (a burst and an immediate `STATE_ENDED`).
     */
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f

    /** What one press of the faster/slower control moves. */
    const val SPEED_STEP = 0.1f

    fun default(bookId: String): BookSettings =
      BookSettings(bookId = bookId, speed = DEFAULT_SPEED, skipSilence = false)

    /**
     * `isNaN` first, and it is not defensive noise: `Float.NaN.coerceIn(0.5f, 3.0f)` returns
     * `NaN` unchanged, and `ExoPlayer.setPlaybackSpeed(NaN)` throws `IllegalArgumentException`
     * from a listener callback -- which surfaces as playback dying with no message a user could
     * act on. A `NaN` reaches here from a corrupted `REAL` column or from arithmetic on one.
     */
    fun clampSpeed(speed: Float): Float = when {
      speed.isNaN() -> DEFAULT_SPEED
      else -> speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }
  }
}
