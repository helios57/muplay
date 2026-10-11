package io.github.helios57.muplay.binaural

import kotlin.math.PI
import kotlin.math.cos

/** What a program is doing at one instant. */
data class TimelineSample(
  val timeSeconds: Double,
  val segmentIndex: Int,
  val beatHz: Double,
  val carrierHz: Double,
  val band: Band,
  /** Fade-in and fade-out gain, 0..1, before the profile's own amplitude. */
  val envelope: Double,
) {
  val leftHz: Double get() = leftHz(carrierHz, beatHz)
  val rightHz: Double get() = rightHz(carrierHz, beatHz)
}

/** The left ear's frequency: the carrier raised by half the beat. */
fun leftHz(carrierHz: Double, beatHz: Double): Double = carrierHz + beatHz / 2.0

/** The right ear's frequency: the carrier lowered by half the beat. */
fun rightHz(carrierHz: Double, beatHz: Double): Double = carrierHz - beatHz / 2.0

/**
 * A [BinauralProfile] evaluated as a function of time.
 *
 * Pure and allocation-free on the hot path ([beatAt], [carrierAt], [envelopeAt]), because the
 * synthesiser asks for all three once per audio sample. Outside `[0, totalSeconds]` it clamps: before
 * zero it reports the first segment's start, after the end the last segment's end -- which is what
 * [EndBehaviour.HOLD_LAST] plays on, and what a [EndBehaviour.STOP] program is silent over.
 */
class ProfileTimeline(val profile: BinauralProfile) {

  init {
    require(profile.segments.isNotEmpty()) { "a program needs at least one segment" }
  }

  private val segments = profile.segments

  /** `starts[i]` is when segment `i` begins; `starts[size]` is the total. */
  private val starts = DoubleArray(segments.size + 1).also { array ->
    segments.forEachIndexed { i, s -> array[i + 1] = array[i] + s.durationSeconds }
  }

  val totalSeconds: Double get() = starts[segments.size]

  /** Which segment owns time [t]. A seam belongs to the segment that starts there. */
  fun indexAt(t: Double): Int {
    if (t <= 0.0) return 0
    if (t >= totalSeconds) return segments.lastIndex
    var low = 0
    var high = segments.lastIndex
    while (low < high) {
      val mid = (low + high + 1) ushr 1
      if (starts[mid] <= t) low = mid else high = mid - 1
    }
    return low
  }

  fun beatAt(t: Double): Double {
    val i = indexAt(t)
    return segments[i].beatAt(fraction(i, t))
  }

  fun carrierAt(t: Double): Double {
    val i = indexAt(t)
    return segments[i].carrierAt(fraction(i, t))
  }

  private fun fraction(i: Int, t: Double): Double =
    ((t - starts[i]) / segments[i].durationSeconds).coerceIn(0.0, 1.0)

  /**
   * The fade gain at [t]: a raised cosine in over [BinauralProfile.fadeInSeconds], and for
   * [EndBehaviour.STOP] a raised cosine out over the final [BinauralProfile.fadeOutSeconds], zero
   * from the end onward.
   */
  fun envelopeAt(t: Double): Double {
    var gain = 1.0
    val fadeIn = profile.fadeInSeconds
    if (fadeIn > 0.0 && t < fadeIn) gain *= raisedCosine(t / fadeIn)
    if (profile.endBehaviour == EndBehaviour.STOP) {
      val total = totalSeconds
      val fadeOut = profile.fadeOutSeconds
      if (t >= total) return 0.0
      if (fadeOut > 0.0 && t > total - fadeOut) gain *= raisedCosine((total - t) / fadeOut)
    }
    return gain
  }

  fun at(t: Double): TimelineSample {
    val beat = beatAt(t)
    return TimelineSample(
      timeSeconds = t,
      segmentIndex = indexAt(t),
      beatHz = beat,
      carrierHz = carrierAt(t),
      band = Band.of(beat),
      envelope = envelopeAt(t),
    )
  }

  /** 0 at `x = 0`, 1 at `x = 1`, zero slope at both. */
  private fun raisedCosine(x: Double): Double =
    if (x <= 0.0) 0.0 else if (x >= 1.0) 1.0 else 0.5 - 0.5 * cos(PI * x)
}
