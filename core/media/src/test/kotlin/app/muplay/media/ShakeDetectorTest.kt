package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Shake detection over real accelerometer numbers, with no `SensorManager` anywhere.
 *
 * That split is what makes this testable at all: `SensorEvent` has no public constructor, so a
 * detector that took one could only be tested by shaking a physical phone. [ShakeSensor] is the
 * three-line adapter that unpacks the event; everything that can be wrong is here.
 *
 * A phone lying still reads ~9.81 m/s^2 on one axis -- 1 g -- which is why the threshold is in g
 * and why "at rest" is the first test in the file.
 */
class ShakeDetectorTest {

  private fun detector() = ShakeDetector()

  /** A resting phone: 1 g straight down, nothing on the other axes. */
  private fun rest(detector: ShakeDetector, atMs: Long) =
    detector.onSample(0f, 0f, ShakeDetector.GRAVITY, atMs)

  /** A jolt: 3 g. */
  private fun jolt(detector: ShakeDetector, atMs: Long) =
    detector.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, atMs)

  @Test
  fun `a phone lying on a bedside table never fires`() {
    val subject = detector()

    val fired = (0..100).map { rest(subject, it * 20L) }

    // The exact list, not `noneMatch`: `noneMatch` over an empty list is true, and a detector that
    // consumed no samples would produce one.
    assertThat(fired).hasSize(101)
    assertThat(fired).containsOnly(false)
  }

  @Test
  fun `two jolts are not a shake`() {
    val subject = detector()

    assertThat(jolt(subject, 0L)).isFalse
    assertThat(jolt(subject, 200L)).isFalse
  }

  @Test
  fun `three jolts inside the window are a shake`() {
    val subject = detector()

    jolt(subject, 0L)
    jolt(subject, 200L)

    assertThat(jolt(subject, 400L)).isTrue
  }

  @Test
  fun `three jolts spread beyond the window are not`() {
    // Picking the phone up, putting it down, and picking it up again over three seconds is not a
    // shake. Same three samples as the test above, moved apart -- so the window is what is being
    // asserted, not the count.
    val subject = detector()

    jolt(subject, 0L)
    jolt(subject, 900L)

    assertThat(jolt(subject, 1_800L)).isFalse
  }

  @Test
  fun `the window is a parameter and it moves the answer`() {
    // The same three samples, two windows, two answers. Without this, `windowMs` could be replaced
    // by the constant `WINDOW_MS` and every assertion above would still pass.
    val wide = ShakeDetector(windowMs = 4_000L)
    val narrow = ShakeDetector(windowMs = 1_000L)
    listOf(0L, 900L).forEach {
      wide.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, it)
      narrow.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, it)
    }

    assertThat(wide.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, 1_800L)).isTrue
    assertThat(narrow.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, 1_800L)).isFalse
  }

  @Test
  fun `two samples from the same jolt do not count twice`() {
    // An accelerometer at 100 Hz produces several samples above threshold per physical jolt.
    // Without a minimum gap, one sharp knock is three peaks and every knock is a shake.
    val subject = detector()

    jolt(subject, 0L)
    jolt(subject, 10L)
    jolt(subject, 20L)

    assertThat(jolt(subject, 30L)).isFalse
  }

  @Test
  fun `the minimum peak gap is a parameter and it moves the answer`() {
    // The same three closely spaced samples, two gaps, two answers -- so `minPeakGapMs` is observed
    // at more than one value rather than only at its default. The loose detector counts all three
    // as peaks; the strict one counts only the first, and swallows the other two as the tail of the
    // same physical knock.
    val strict = ShakeDetector(minPeakGapMs = 80L)
    val loose = ShakeDetector(minPeakGapMs = 5L)
    listOf(0L, 10L).forEach {
      strict.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, it)
      loose.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, it)
    }

    assertThat(strict.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, 30L)).isFalse
    assertThat(loose.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, 30L)).isTrue
  }

  @Test
  fun `the required peak count is a parameter and it moves the answer`() {
    // Two jolts fire a two-peak detector and not a three-peak one, at the identical samples.
    val two = ShakeDetector(requiredPeaks = 2)
    val three = ShakeDetector(requiredPeaks = 3)
    two.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, 0L)
    three.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, 0L)

    assertThat(two.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, 200L)).isTrue
    assertThat(three.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, 200L)).isFalse
  }

  @Test
  fun `a second shake is detected after the first`() {
    // Without a reset on fire, the peak buffer keeps every old peak and the next single jolt fires
    // -- so one shake makes the phone hair-trigger for as long as the app runs.
    val subject = detector()
    jolt(subject, 0L)
    jolt(subject, 200L)
    assertThat(jolt(subject, 400L)).isTrue

    // **This is the assertion that sees the reset**, and the two-seconds-later pair below is not:
    // measured, by deleting `reset()` from `onSample` and running this file. With the three fired
    // peaks still in the buffer, a fourth jolt 200 ms later makes four inside the window and fires
    // again immediately; with the reset it is one peak and nothing happens. The 2 000 ms pair
    // stayed green against that same deletion, because the window had pruned the stale peaks by
    // itself -- which is exactly the shape of a test that looks like it gates something and does
    // not.
    assertThat(jolt(subject, 600L))
      .describedAs("a single jolt straight after a shake must not be a second shake")
      .isFalse

    jolt(subject, 2_000L)
    jolt(subject, 2_200L)
    assertThat(jolt(subject, 2_400L)).isTrue
    // ...and one jolt on its own still is not a shake.
    assertThat(jolt(subject, 4_000L)).isFalse
  }

  @Test
  fun `an explicit reset forgets the peaks already seen`() {
    // What `ShakeSensor.start` calls before registering its listener, so a timer started an hour
    // after the last one does not inherit that one's half-finished shake.
    val subject = detector()
    jolt(subject, 0L)
    jolt(subject, 200L)

    subject.reset()

    assertThat(jolt(subject, 400L)).isFalse
    // ...and it is a reset, not a disable: three fresh jolts still fire.
    jolt(subject, 600L)
    assertThat(jolt(subject, 800L)).isTrue
  }

  @Test
  fun `the threshold is a parameter and it moves the answer`() {
    // The same three samples, two thresholds, two answers. Without this, `thresholdG` could be
    // ignored and every test above would still pass.
    val gentle = 1.5f * ShakeDetector.GRAVITY
    val sensitive = ShakeDetector(thresholdG = 1.2f)
    val strict = ShakeDetector(thresholdG = 2.5f)

    listOf(0L, 200L).forEach {
      sensitive.onSample(0f, 0f, gentle, it)
      strict.onSample(0f, 0f, gentle, it)
    }

    assertThat(sensitive.onSample(0f, 0f, gentle, 400L)).isTrue
    assertThat(strict.onSample(0f, 0f, gentle, 400L)).isFalse
  }

  @Test
  fun `the magnitude uses all three axes`() {
    // A shake along x is a shake. A detector reading only z -- easy to write, and correct-looking
    // because a resting phone's gravity is on z -- fails here and passes every other test in this
    // file, because every other test jolts z.
    val onX = detector()
    onX.onSample(3f * ShakeDetector.GRAVITY, 0f, 0f, 0L)
    onX.onSample(3f * ShakeDetector.GRAVITY, 0f, 0f, 200L)
    assertThat(onX.onSample(3f * ShakeDetector.GRAVITY, 0f, 0f, 400L)).isTrue

    val onY = detector()
    onY.onSample(0f, 3f * ShakeDetector.GRAVITY, 0f, 0L)
    onY.onSample(0f, 3f * ShakeDetector.GRAVITY, 0f, 200L)
    assertThat(onY.onSample(0f, 3f * ShakeDetector.GRAVITY, 0f, 400L)).isTrue

    // And the axes combine rather than being taken one at a time: 1.5 g on each of three axes is
    // 2.6 g of magnitude, over the 2.2 g default that no single axis reaches.
    val combined = detector()
    val perAxis = 1.5f * ShakeDetector.GRAVITY
    combined.onSample(perAxis, perAxis, perAxis, 0L)
    combined.onSample(perAxis, perAxis, perAxis, 200L)
    assertThat(combined.onSample(perAxis, perAxis, perAxis, 400L)).isTrue
  }
}
