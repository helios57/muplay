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
 * `values` array cannot be written from a test, so the only way to synthesise a jolt is the
 * emulator console (`adb emu sensor set acceleration`), which changes global device state -- the
 * orientation every other lane's UI test reads -- on an emulator this project shares between
 * agents. So the `onShake()` arm of the listener is **not** reachable here, and this class says so
 * rather than dressing up a weaker observation as that one. The floor for [ShakeSensor] is LINE and
 * measured, for the same reason.
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
    val deadline = System.currentTimeMillis() + OBSERVATION_MS
    while (System.currentTimeMillis() < deadline && delivered.get() == 0) Thread.sleep(POLL_MS)

    assertThat(delivered.get())
      .describedAs("accelerometer events delivered to a listener registered exactly as this class registers its own")
      .isGreaterThan(0)
    assertThat(shakes.get())
      .describedAs("shakes reported by a device sitting on a desk")
      .isZero
  }

  @Test
  fun startingTwiceLeavesOneListenerAndStoppingTwiceIsSafe() {
    // The idempotence matters because the caller is a state collector: the countdown emits four
    // times a second, and a `start` per emission that stacked listeners would wake the CPU harder
    // every second the timer runs.
    subject.start { }
    subject.start { }

    subject.stop()
    // A second stop, and a stop with nothing registered, must both be no-ops rather than throw:
    // `SensorManager.unregisterListener` on an unregistered listener is quietly fine, and this is
    // the only place that is observed rather than assumed.
    subject.stop()
    ShakeSensor(context).stop()

    // Restartable afterwards, which is what makes a second sleep timer work at all.
    subject.start { }
    subject.stop()
  }

  private companion object {
    const val OBSERVATION_MS = 5_000L
    const val POLL_MS = 50L
  }
}
