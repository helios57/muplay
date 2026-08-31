package app.muplay.media

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ShakeSensor] against a real `SensorManager`.
 *
 * There is deliberately almost nothing here, because there is deliberately almost nothing in the
 * class: every decision about what counts as a shake lives in [ShakeDetector], which has no Android
 * in it and is gated on the fast tier with twelve tests. What this suite can add is the half that
 * only a device has -- that the listener really is registered against the real accelerometer, that
 * the events really are delivered to it, and that a phone lying still is not a shake all the way
 * through the real sensor stack rather than only over hand-written samples.
 *
 * **What it cannot do is shake the phone.** `SensorEvent` has no public constructor and its
 * `values` array cannot be written from a test, so the only way to synthesise a real jolt through
 * the framework is the emulator console (`adb emu sensor set acceleration`), which changes global
 * device state -- the orientation every other lane's UI test reads -- on an emulator this project
 * shares between agents.
 *
 * That is why the unpacking lives in [ShakeSensor.onSample] rather than inside the anonymous
 * `SensorEventListener`: everything a jolt would exercise is reachable from here, and what is left
 * in the callback is two expression bodies that a still device covers. What genuinely stays
 * unreachable is the pair of "this device has no accelerometer" early returns, which is why the
 * floor for this class is LINE rather than BRANCH -- see the coverage table's own note.
 */
@RunWith(AndroidJUnit4::class)
class ShakeSensorTest {

  private lateinit var context: Context
  private lateinit var manager: SensorManager
  private lateinit var subject: ShakeSensor
  private var ownListener: SensorEventListener? = null

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    subject = ShakeSensor(context)
  }

  @After
  fun tearDown() {
    if (::subject.isInitialized) subject.stop()
    ownListener?.let { manager.unregisterListener(it) }
  }

  /**
   * The premise of everything below, and the reason [ShakeSensor]'s floor is LINE rather than
   * BRANCH: this device has the sensor, so neither of the class's two "no sensor" early returns is
   * reachable from any test that can run here.
   */
  @Test
  fun theDeviceHasTheAccelerometerThisClassAsksFor() {
    assertThat(manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
      .describedAs("the accelerometer `ShakeSensor.start` registers against")
      .isNotNull
  }

  @Test
  fun aDeviceLyingStillNeverReportsAShake() {
    val shakes = AtomicInteger()
    val delivered = AtomicInteger()
    // The positive control. Without it, "zero shakes" is equally satisfied by a sensor stack that
    // delivered nothing at all -- which is the vacuous half of every absence assertion, and the
    // shape this project keeps finding.
    ownListener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        delivered.incrementAndGet()
      }

      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(
      ownListener,
      manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
      SensorManager.SENSOR_DELAY_UI,
    )

    subject.start { shakes.incrementAndGet() }
    // A fixed window rather than "until the first event": an absence claim needs a duration, and
    // stopping at the first event delivered to the *control* listener would leave the subject's own
    // listener with a few milliseconds of samples -- measured, as 1 of 9 lines of it covered.
    Thread.sleep(OBSERVATION_MS)

    assertThat(delivered.get())
      .describedAs("accelerometer events delivered to a listener registered exactly as this class registers its own")
      .isGreaterThan(0)
    assertThat(shakes.get())
      .describedAs("shakes reported by a device sitting on a desk")
      .isZero
  }

  /**
   * The unpacking, driven directly -- the half of `onSensorChanged` that a real jolt would reach.
   *
   * Both things this function does are defects if they are wrong and invisible if they are not
   * tested: the three axes in the right order, and **nanoseconds to milliseconds**. Without the
   * conversion these three samples are 200 000 000 and 400 000 000 apart rather than 200 and 400,
   * which is outside [ShakeDetector]'s one-second window, and no shake is ever reported at all.
   */
  @Test
  fun aJoltDeliveredThroughTheUnpackerIsReportedAsAShake() {
    val shakes = AtomicInteger()
    val jolt = floatArrayOf(0f, 0f, 3f * ShakeDetector.GRAVITY)

    subject.onSample(jolt, 0L, { shakes.incrementAndGet() })
    subject.onSample(jolt, 200L * NANOS_PER_MILLI, { shakes.incrementAndGet() })
    assertThat(shakes.get()).describedAs("two jolts are not a shake").isZero

    subject.onSample(jolt, 400L * NANOS_PER_MILLI, { shakes.incrementAndGet() })

    assertThat(shakes.get()).describedAs("three jolts inside the window are").isEqualTo(1)
  }

  @Test
  fun aRestingSampleDeliveredThroughTheUnpackerIsNotAShake() {
    // The other arm of the same `if`, at the same call: without it, "reported a shake" and "reports
    // a shake for anything at all" are the same observation.
    val shakes = AtomicInteger()
    val atRest = floatArrayOf(0f, 0f, ShakeDetector.GRAVITY)

    repeat(5) { subject.onSample(atRest, it * 200L * NANOS_PER_MILLI, { shakes.incrementAndGet() }) }

    assertThat(shakes.get()).isZero
  }

  @Test
  fun theUnpackerReadsTheAxesInOrder() {
    // A jolt on x only. An unpacker that read `values[2]` three times sees 1 g here and reports
    // nothing, while passing every other test in this file -- they all jolt z.
    val shakes = AtomicInteger()
    val onX = floatArrayOf(3f * ShakeDetector.GRAVITY, 0f, 0f)

    repeat(3) { subject.onSample(onX, it * 200L * NANOS_PER_MILLI, { shakes.incrementAndGet() }) }

    assertThat(shakes.get()).isEqualTo(1)
  }

  @Test
  fun startingTwiceLeavesOneListenerAndStoppingTwiceIsSafe() {
    // The idempotence matters because the caller is a state collector: the countdown emits four
    // times a second, and a `start` per emission that stacked listeners would wake the CPU harder
    // every second the timer runs.
    subject.start { }
    subject.start { }
    // `isListening` is what `MuPlaybackService`'s collector is observed through -- see
    // `MuPlaybackServiceTest.theSleepTimerTurnsOnTheShakeSensorThatMakesTheGestureReachable` --
    // so the flag itself is asserted here, against the class that owns it, rather than only in the
    // wiring test that trusts it.
    assertThat(subject.isListening).describedAs("after two starts").isTrue

    subject.stop()
    assertThat(subject.isListening).describedAs("after a stop").isFalse
    // A second stop, and a stop with nothing registered, must both be no-ops rather than throw:
    // `SensorManager.unregisterListener` on an unregistered listener is quietly fine, and this is
    // the only place that is observed rather than assumed.
    subject.stop()
    ShakeSensor(context).stop()
    assertThat(subject.isListening).describedAs("after a second, redundant stop").isFalse

    // Restartable afterwards, which is what makes a second sleep timer work at all.
    subject.start { }
    assertThat(subject.isListening).describedAs("restarted after a stop").isTrue
    subject.stop()
  }

  private companion object {
    const val OBSERVATION_MS = 2_000L
    const val NANOS_PER_MILLI = 1_000_000L
  }
}
