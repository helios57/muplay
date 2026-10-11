package io.github.helios57.muplay.binaural

import kotlin.math.pow
import kotlinx.serialization.Serializable

/**
 * How a quantity moves between a segment's start value and its end value.
 *
 * All three return exactly the start at `u = 0` and exactly the end at `u = 1`, so two adjacent
 * segments that agree at the seam agree to the last bit and the frequency is continuous there
 * whatever curves they use.
 */
@Serializable
enum class CurveShape(val label: String) {
  /** Straight line in Hz. The default, and what Gnaural's schedule editor draws. */
  LINEAR("Linear"),

  /** Smoothstep: starts and ends with zero slope, so a glide leaves and joins a hold gently. */
  SMOOTH("Smooth"),

  /**
   * A straight line in **log**-frequency, i.e. a constant ratio per second. Hearing and, by
   * convention, the brain-wave bands are roughly logarithmic in frequency, so a descent from 10 Hz
   * to 2.5 Hz spends as long between 10 and 5 as between 5 and 2.5. Needs both ends positive, which
   * validation guarantees.
   */
  EXPONENTIAL("Exponential"),
  ;

  /** The value at fraction [u] of the way from [from] to [to]. */
  fun interpolate(from: Double, to: Double, u: Double): Double = when {
    u <= 0.0 -> from
    u >= 1.0 -> to
    else -> when (this) {
      LINEAR -> from + (to - from) * u
      SMOOTH -> from + (to - from) * (u * u * (3.0 - 2.0 * u))
      EXPONENTIAL -> from * (to / from).pow(u)
    }
  }
}

/** The optional bed under the tones. */
@Serializable
enum class NoiseType(val label: String) {
  NONE("None"),
  PINK("Pink"),
  BROWN("Brown"),
  WHITE("White"),
}

/** What happens when the last segment ends. */
@Serializable
enum class EndBehaviour(val label: String) {
  /** Fade out over [BinauralProfile.fadeOutSeconds] (the last seconds of the program) and stop. */
  STOP("Fade out and stop"),

  /** Keep playing the last segment's end values until the user stops it. No fade-out. */
  HOLD_LAST("Hold the last tone"),
}

/**
 * One leg of a program: beat and carrier glide from their start to their end values over
 * [durationSeconds], each along [curve]. A hold is a segment whose start and end are equal.
 */
@Serializable
data class Segment(
  val startBeatHz: Double,
  val endBeatHz: Double,
  val startCarrierHz: Double,
  val endCarrierHz: Double,
  val durationSeconds: Double,
  val curve: CurveShape = CurveShape.LINEAR,
) {
  /** A segment that holds one beat and one carrier for [durationSeconds]. */
  constructor(beatHz: Double, carrierHz: Double, durationSeconds: Double) :
    this(beatHz, beatHz, carrierHz, carrierHz, durationSeconds)

  fun beatAt(u: Double): Double = curve.interpolate(startBeatHz, endBeatHz, u)
  fun carrierAt(u: Double): Double = curve.interpolate(startCarrierHz, endCarrierHz, u)
}

/**
 * A named program: an ordered list of [segments] plus how loud it is and what surrounds it.
 *
 * The left ear gets `carrier + beat / 2` and the right ear `carrier - beat / 2`, the SBaGen
 * convention, which reproduces the reference example exactly: carrier 151.71 and beat 13.46 give
 * left 158.44 and right 144.98. The stored numbers are carrier and beat -- never L and R -- so
 * editing one cannot leave the other disagreeing.
 *
 * @property id Stable identity. Built-ins use `builtin.<slug>`; user profiles a random UUID.
 * @property amplitude Peak linear amplitude of **each ear's sine**, 0..[Limits.MAX_AMPLITUDE].
 * @property noiseLevel RMS linear level of the noise bed, 0..[Limits.MAX_NOISE_LEVEL].
 * @property fadeInSeconds Raised-cosine fade from silence at the start.
 * @property fadeOutSeconds Raised-cosine fade to silence, occupying the **last** seconds of the
 *   program (it is inside the duration, not appended to it). Ignored for [EndBehaviour.HOLD_LAST].
 * @property note A line of honest context shown with the profile, e.g. the weak-evidence caveat.
 */
@Serializable
data class BinauralProfile(
  val id: String,
  val name: String,
  val segments: List<Segment>,
  val amplitude: Double = Defaults.AMPLITUDE,
  val noiseType: NoiseType = NoiseType.NONE,
  val noiseLevel: Double = 0.0,
  val fadeInSeconds: Double = Defaults.FADE_IN_SECONDS,
  val fadeOutSeconds: Double = Defaults.FADE_OUT_SECONDS,
  val endBehaviour: EndBehaviour = EndBehaviour.STOP,
  val description: String = "",
  val note: String = "",
) {
  /** Total length of the program in seconds. */
  val totalSeconds: Double get() = segments.sumOf { it.durationSeconds }

  /** One segment whose ends agree: a tone held for a while, as opposed to a program that moves. */
  val isStatic: Boolean
    get() = segments.size == 1 && segments[0].let {
      it.startBeatHz == it.endBeatHz && it.startCarrierHz == it.endCarrierHz
    }

  companion object {
    /** A profile of one held tone, the shape every "Meditate"-style preset has. */
    fun static(
      id: String,
      name: String,
      beatHz: Double,
      carrierHz: Double,
      durationSeconds: Double,
      amplitude: Double = Defaults.AMPLITUDE,
      noiseType: NoiseType = NoiseType.NONE,
      noiseLevel: Double = 0.0,
      fadeInSeconds: Double = Defaults.FADE_IN_SECONDS,
      fadeOutSeconds: Double = Defaults.FADE_OUT_SECONDS,
      description: String = "",
      note: String = "",
    ): BinauralProfile = BinauralProfile(
      id = id,
      name = name,
      segments = listOf(Segment(beatHz, carrierHz, durationSeconds)),
      amplitude = amplitude,
      noiseType = noiseType,
      noiseLevel = noiseLevel,
      fadeInSeconds = fadeInSeconds,
      fadeOutSeconds = fadeOutSeconds,
      description = description,
      note = note,
    )
  }
}

object Defaults {
  const val AMPLITUDE = 0.10
  const val FADE_IN_SECONDS = 10.0
  const val FADE_OUT_SECONDS = 30.0
}

/** The hard and advisory limits, in one place so the editor, the validator and the docs agree. */
object Limits {
  const val MIN_BEAT_HZ = 0.1
  const val MAX_BEAT_HZ = 40.0

  /** Above this the beat is still allowed, but is near the perceptual limit (about 30-35 Hz). */
  const val WARN_BEAT_HZ = 30.0

  const val MIN_CARRIER_HZ = 40.0
  const val MAX_CARRIER_HZ = 1000.0
  const val RECOMMENDED_CARRIER_MIN_HZ = 100.0
  const val RECOMMENDED_CARRIER_MAX_HZ = 500.0

  /** A cap on each ear's peak, well under full scale so tones plus a bed cannot clip. */
  const val MAX_AMPLITUDE = 0.5

  /** Above this the tones are loud enough to be the wrong thing to fall asleep to. */
  const val WARN_AMPLITUDE = 0.25
  const val MAX_NOISE_LEVEL = 0.3

  const val MIN_SEGMENT_SECONDS = 1.0
  const val MAX_SEGMENTS = 64
  const val MAX_TOTAL_SECONDS = 12.0 * 3600.0
  const val MAX_FADE_SECONDS = 1800.0
  const val MAX_NAME_LENGTH = 60
}

/** The conventional brain-wave band a beat frequency falls in. Edges are conventions, not physiology. */
enum class Band(val label: String, val rangeLabel: String) {
  DELTA("Delta", "below 4 Hz"),
  THETA("Theta", "4 to 8 Hz"),
  ALPHA("Alpha", "8 to 12 Hz"),
  SMR("SMR", "12 to 15 Hz"),
  BETA("Beta", "15 to 30 Hz"),
  GAMMA("Gamma", "30 Hz and up"),
  ;

  /** The lower edge of this band in Hz (Delta's is 0). */
  val lowerHz: Double
    get() = when (this) {
      DELTA -> 0.0
      THETA -> 4.0
      ALPHA -> 8.0
      SMR -> 12.0
      BETA -> 15.0
      GAMMA -> 30.0
    }

  companion object {
    fun of(beatHz: Double): Band = entries.last { beatHz >= it.lowerHz }
  }
}
