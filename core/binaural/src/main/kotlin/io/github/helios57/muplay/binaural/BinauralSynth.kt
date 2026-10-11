package io.github.helios57.muplay.binaural

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Renders a [BinauralProfile] to interleaved stereo `float` PCM. Pure and deterministic: the same
 * profile, rate and seed give the same samples on every run, which is what the tests assert on.
 *
 * ### Why there are no clicks
 *
 * - **Each ear is one phase accumulator in 64-bit double, and only the increment changes.** The
 *   frequency is re-evaluated from the [ProfileTimeline] for **every sample** and the phase is
 *   advanced by `2 pi f / fs`; it is never assigned. A segment seam, a curve change, a restart of the
 *   gain ramp -- none of them touches the phase, so the waveform is continuous and its derivative is
 *   bounded by the frequency alone.
 * - **Nothing steps.** Amplitude and noise level go through a one-pole smoother (about 20 ms), the
 *   fades are raised cosines, and [replaceProfile] glides from the frequency that was sounding to
 *   the new profile's over [SLEW_SECONDS] rather than jumping.
 * - **A stop is a fade.** [release] ramps to silence with a raised cosine and only then finishes.
 *
 * ### Time
 *
 * Time is `frame / sampleRate`, derived rather than accumulated, so no rounding drifts over an
 * eight-hour night. The phase is the one accumulated quantity, and doubles hold it to about 1e-12 of
 * a cycle after 48000 x 28800 additions.
 *
 * Not thread-safe: one thread renders, and anything that wants to change the synthesiser asks that
 * thread to (`:feature:binaural`'s `BinauralPlayer` does).
 */
class BinauralSynth(
  profile: BinauralProfile,
  val sampleRate: Int = SAMPLE_RATE,
  seed: Long = DEFAULT_SEED,
) {
  var profile: BinauralProfile = profile
    private set
  private var timeline = ProfileTimeline(profile)

  private var frame = 0L
  private var phaseLeft = 0.0
  private var phaseRight = 0.0
  private var lastLeftHz = 0.0
  private var lastRightHz = 0.0

  private var amplitude = profile.amplitude

  /**
   * The listener's own volume for the tones, 0 to 1, on top of the profile's levels. Separate from
   * the profile so turning the tones down under music is not an edit of the program, and smoothed
   * like everything else so dragging a slider does not crackle.
   */
  var masterGain: Double = 1.0
    set(value) {
      field = value.coerceIn(0.0, 1.0)
    }
  private var master = masterGain
  private val noiseGain = DoubleArray(NoiseType.entries.size)
  private val noiseLeft = NoiseType.entries.map { NoiseGenerator(it, seed) }
  private val noiseRight = NoiseType.entries.map { NoiseGenerator(it, seed xor RIGHT_SEED_MASK) }

  private var slewStartFrame = Long.MIN_VALUE
  private var slewFromLeftHz = 0.0
  private var slewFromRightHz = 0.0

  private var releaseStartFrame = -1L
  private var releaseFrames = 0L

  private val smoothingCoefficient = 1.0 - exp(-1.0 / (SMOOTHING_SECONDS * sampleRate))
  private val phaseStep = 2.0 * PI / sampleRate

  init {
    NoiseType.entries.forEach { noiseGain[it.ordinal] = targetNoiseGain(it) }
  }

  /** Seconds of audio rendered so far. */
  val positionSeconds: Double get() = frame.toDouble() / sampleRate

  /** True once a [EndBehaviour.STOP] program has played out, or a [release] has completed. */
  val isFinished: Boolean
    get() = (releaseStartFrame >= 0 && frame - releaseStartFrame >= releaseFrames) ||
      (profile.endBehaviour == EndBehaviour.STOP && frame >= totalFrames())

  /** What the program is doing now. */
  fun sample(): TimelineSample = timeline.at(positionSeconds)

  /** Begin a fade to silence over [seconds], after which the synthesiser is finished. */
  fun release(seconds: Double) {
    if (releaseStartFrame >= 0) return
    releaseStartFrame = frame
    releaseFrames = (seconds * sampleRate).roundToLong().coerceAtLeast(1L)
  }

  /**
   * Switch to an edited [replacement] without a click and without losing the place: the position
   * carries over, the phase carries over, and the frequency glides from what was sounding to what
   * the new program says over [SLEW_SECONDS].
   */
  fun replaceProfile(replacement: BinauralProfile) {
    slewFromLeftHz = lastLeftHz
    slewFromRightHz = lastRightHz
    slewStartFrame = frame
    profile = replacement
    timeline = ProfileTimeline(replacement)
  }

  /**
   * Fill [out] with up to [frames] stereo frames (`out.size >= 2 * frames`) and return how many were
   * written. Fewer than asked only when a [EndBehaviour.STOP] program or a [release] ends.
   */
  fun render(out: FloatArray, frames: Int): Int {
    require(out.size >= frames * 2) { "buffer holds ${out.size / 2} frames, asked for $frames" }
    var written = 0
    while (written < frames && !isFinished) {
      val t = frame.toDouble() / sampleRate
      val carrier = timeline.carrierAt(t)
      val beat = timeline.beatAt(t)
      var leftHz = leftHz(carrier, beat)
      var rightHz = rightHz(carrier, beat)
      if (slewStartFrame != Long.MIN_VALUE) {
        val x = (frame - slewStartFrame).toDouble() / (SLEW_SECONDS * sampleRate)
        if (x >= 1.0) {
          slewStartFrame = Long.MIN_VALUE
        } else {
          val w = x * x * (3.0 - 2.0 * x)
          leftHz = slewFromLeftHz * (1.0 - w) + leftHz * w
          rightHz = slewFromRightHz * (1.0 - w) + rightHz * w
        }
      }
      lastLeftHz = leftHz
      lastRightHz = rightHz

      amplitude += (profile.amplitude - amplitude) * smoothingCoefficient
      var bedLeft = 0.0
      var bedRight = 0.0
      for (type in NoiseType.entries) {
        val i = type.ordinal
        val target = targetNoiseGain(type)
        noiseGain[i] += (target - noiseGain[i]) * smoothingCoefficient
        if (noiseGain[i] > SILENT_GAIN || target > 0.0) {
          bedLeft += noiseLeft[i].next() * noiseGain[i]
          bedRight += noiseRight[i].next() * noiseGain[i]
        }
      }

      master += (masterGain - master) * smoothingCoefficient
      val gain = timeline.envelopeAt(t) * releaseGain() * master
      out[2 * written] = limit((sin(phaseLeft) * amplitude + bedLeft) * gain).toFloat()
      out[2 * written + 1] = limit((sin(phaseRight) * amplitude + bedRight) * gain).toFloat()

      phaseLeft = wrap(phaseLeft + phaseStep * leftHz)
      phaseRight = wrap(phaseRight + phaseStep * rightHz)
      frame++
      written++
    }
    return written
  }

  private fun totalFrames(): Long = (timeline.totalSeconds * sampleRate).roundToLong()

  private fun targetNoiseGain(type: NoiseType): Double =
    if (type == profile.noiseType && type != NoiseType.NONE) profile.noiseLevel else 0.0

  private fun releaseGain(): Double {
    if (releaseStartFrame < 0) return 1.0
    val x = (frame - releaseStartFrame).toDouble() / releaseFrames
    return if (x >= 1.0) 0.0 else 0.5 + 0.5 * cos(PI * x)
  }

  private fun wrap(phase: Double): Double = if (phase >= TWO_PI) phase - TWO_PI else phase

  /** Identity up to 0.9, then a smooth tanh knee to 1.0: a bed on top of the tones cannot clip. */
  private fun limit(x: Double): Double {
    val a = abs(x)
    if (a <= KNEE) return x
    val shaped = KNEE + (1.0 - KNEE) * tanh((a - KNEE) / (1.0 - KNEE))
    return if (x < 0) -shaped else shaped
  }

  companion object {
    const val SAMPLE_RATE = 48_000
    const val DEFAULT_SEED = 0x5EEDB1AL

    /** How long a changed amplitude or noise level takes to settle. */
    const val SMOOTHING_SECONDS = 0.02

    /** How long an edited program takes to glide from the old frequency to the new. */
    const val SLEW_SECONDS = 0.5

    private const val TWO_PI = 2.0 * PI
    private const val SILENT_GAIN = 1e-6
    private const val KNEE = 0.9
    private const val RIGHT_SEED_MASK = 0x5DEECE66DL
  }
}
