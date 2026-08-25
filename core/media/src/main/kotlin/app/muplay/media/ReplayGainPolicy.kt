package app.muplay.media

import app.muplay.model.ReplayGain
import kotlin.math.pow

/**
 * Turns what a file's tags say into the number the gain stage multiplies samples by.
 *
 * Pure, and deliberately in a file with no Android import: this is where the defects that matter
 * live -- a sign error, a missing clamp -- and Tier 1 can see all of them.
 *
 * **Track gain is preferred and album gain is only a fallback.** There is no album-versus-track
 * *mode* for the user to choose, and that is a stated decision rather than an oversight: the queue
 * this feature exists to fix is a library-scoped shuffle, which has no album to be consistent
 * within. A file that carries only an album gain still gets it, because that beats nothing.
 */
object ReplayGainPolicy {

  /** The multiplicative identity. An untagged library is bit-identical to having no gain stage. */
  const val UNCHANGED: Float = 1.0f

  /** A tag below this is a corrupt tag, not a quiet file. */
  const val MIN_GAIN_DB: Float = -24.0f

  /** A tag above this is a corrupt tag, not a quiet file -- and it is the one that hurts. */
  const val MAX_GAIN_DB: Float = 12.0f

  private const val DB_PER_AMPLITUDE_DECADE = 20.0f

  /** The decibel adjustment to apply, or `null` when the file does not say. */
  fun gainDbFor(replayGain: ReplayGain?): Float? =
    replayGain?.trackGainDb ?: replayGain?.albumGainDb

  /**
   * [gainDb] as a linear multiplier, clamped so that a corrupt tag cannot deafen anyone and a
   * positive gain cannot push a known peak past full scale.
   *
   * The peak clamp is a **ceiling, not a target**: a quiet-tagged track with a low peak stays
   * quiet. Normalising everything to full scale is a different feature and is not this one.
   */
  fun linearGain(gainDb: Float?, peakAmplitude: Float?): Float {
    if (gainDb == null) return UNCHANGED
    val clamped = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    val linear = 10.0f.pow(clamped / DB_PER_AMPLITUDE_DECADE)
    // A peak of zero or a negative one is a nonsensical tag, not a silent file: ignore it rather
    // than dividing by it.
    if (peakAmplitude == null || peakAmplitude <= 0.0f) return linear
    return minOf(linear, 1.0f / peakAmplitude)
  }
}
