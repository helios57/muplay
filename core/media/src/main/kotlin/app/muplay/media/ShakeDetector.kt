package app.muplay.media

import kotlin.math.sqrt

/**
 * "That was a shake", from raw accelerometer samples.
 *
 * No `SensorManager`, no `SensorEvent`, no Android at all -- which is the only reason this is
 * testable: `SensorEvent` has no public constructor, so a detector that took one could be tested
 * only by shaking a physical phone. [ShakeSensor] is the adapter that unpacks the event and does
 * nothing else.
 *
 * Three things it has to get right, each of which is a real false positive otherwise:
 *
 * - **All three axes.** A phone shaken along x is shaken. Reading only z looks correct because a
 *   resting phone's gravity is on z.
 * - **A minimum gap between peaks.** A 100 Hz accelerometer produces several above-threshold
 *   samples per physical jolt, so without a gap one sharp knock is three peaks.
 * - **A reset after firing.** Otherwise the peak buffer keeps every old peak and the next single
 *   jolt fires, leaving the phone hair-trigger for the life of the process.
 */
class ShakeDetector(
  private val thresholdG: Float = DEFAULT_THRESHOLD_G,
  private val windowMs: Long = WINDOW_MS,
  private val requiredPeaks: Int = REQUIRED_PEAKS,
  private val minPeakGapMs: Long = MIN_PEAK_GAP_MS,
) {

  private val peaks = ArrayDeque<Long>()

  fun onSample(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
    val magnitudeG = sqrt(x * x + y * y + z * z) / GRAVITY
    if (magnitudeG < thresholdG) return false
    if (peaks.isNotEmpty() && timestampMs - peaks.last() < minPeakGapMs) return false

    peaks.addLast(timestampMs)
    while (peaks.isNotEmpty() && timestampMs - peaks.first() > windowMs) peaks.removeFirst()

    if (peaks.size < requiredPeaks) return false
    reset()
    return true
  }

  fun reset() = peaks.clear()

  companion object {
    /** 2.2 g: comfortably above a pocket jostle, comfortably below a deliberate shake. */
    const val DEFAULT_THRESHOLD_G = 2.2f
    const val WINDOW_MS = 1_000L
    const val REQUIRED_PEAKS = 3
    const val MIN_PEAK_GAP_MS = 80L

    /** Standard gravity, so the threshold is in a unit a human can reason about. */
    const val GRAVITY = 9.80665f
  }
}
