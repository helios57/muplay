package app.muplay.media

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three lines that turn a `SensorEvent` into three floats, and nothing else.
 *
 * Everything that can be wrong about shake detection is in [ShakeDetector], which has no Android in
 * it and is gated on the fast tier. This class exists because `SensorEvent` has no public
 * constructor and its `values` array is package-private to write -- so any logic living here could
 * be tested only by shaking a physical phone.
 *
 * `TYPE_ACCELEROMETER` includes gravity, which is why the detector's threshold is in g and why a
 * resting phone reads 1. `SENSOR_DELAY_UI` is deliberate: `SENSOR_DELAY_GAME` and `_FASTEST` wake
 * the CPU far more often, for a feature whose whole purpose is to run while someone falls asleep.
 * A device with no accelerometer -- rare, but they exist -- silently has no shake affordance rather
 * than crashing.
 *
 * [start] is idempotent so that a caller which collects [SleepTimerController.state] can call it on
 * every `Running` emission without stacking listeners; the countdown emits four times a second.
 */
@Singleton
class ShakeSensor @Inject constructor(@ApplicationContext private val context: Context) {

  private val manager: SensorManager? =
    context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

  private val detector = ShakeDetector()
  private var listener: SensorEventListener? = null

  fun start(onShake: () -> Unit) {
    val manager = manager ?: return
    val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
    if (listener != null) return
    detector.reset()
    listener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) =
        onSample(event.values, event.timestamp, onShake)

      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
  }

  /**
   * The body of `onSensorChanged`, with the `SensorEvent` already unpacked.
   *
   * `internal` and separate for one reason: `SensorEvent` has no public constructor and its
   * `values` array cannot be written from a test, so anything left inside that callback can only be
   * reached by shaking a physical phone. What lives here is small but not nothing -- three array
   * indices in the right order, and a **nanoseconds-to-milliseconds** conversion without which
   * every peak is a million times further apart than [ShakeDetector]'s window and no shake is ever
   * detected. `ShakeSensorTest` drives this directly, so both of those are observed rather than
   * asserted from the armchair.
   */
  internal fun onSample(values: FloatArray, timestampNanos: Long, onShake: () -> Unit) {
    if (detector.onSample(values[0], values[1], values[2], timestampNanos / NANOS_PER_MILLI)) {
      onShake()
    }
  }

  fun stop() {
    listener?.let { manager?.unregisterListener(it) }
    listener = null
  }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000L
  }
}
