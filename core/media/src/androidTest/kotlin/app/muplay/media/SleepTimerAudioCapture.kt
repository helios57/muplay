package app.muplay.media

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The apparatus `SleepTimerFadeAudioTest` measures the fade with: **when** each PCM buffer reached
 * the sink, and **what gain the player had applied** by then.
 *
 * ### Why the two halves have to be paired, and cannot simply be captured together
 *
 * `player.volume` is not applied anywhere this process can capture. Media3 forwards it to
 * `DefaultAudioSink.setVolume`, which calls `AudioTrack.setVolume` -- the platform mixer, below
 * every API an app can tap without `MediaProjection` and `RECORD_AUDIO`. A `TeeAudioProcessor`
 * sits *inside* the sink's processor chain, upstream of that `AudioTrack`, so a capture there is
 * the audio **before** the volume is applied, and it is byte-identical whether the volume is 1.0 or
 * 0.0. (That is also exactly why `GainAudioProcessor` -- which is in the chain -- *can* be measured
 * directly, and why the sleep timer cannot be measured the same way.)
 *
 * So the audible signal is reconstructed rather than captured: the PCM the decoder produced, times
 * the scalar the player had applied to it. Both halves are real measurements of the shipping
 * player. The multiplication is the one step performed here rather than by the device, and it is
 * the multiplication `AudioTrack.setVolume` is documented to perform -- a linear scalar on the
 * whole track.
 *
 * ### The one approximation, and why it does not affect the answer
 *
 * A buffer handed to the sink is *heard* later than it is handed over, by however much audio the
 * `AudioTrack` already holds -- measured at roughly **700 ms** on this project's emulator, in Plan
 * 3 Task 11. So [VolumeTimeline.volumeAt] pairs a buffer with the gain in effect when it was
 * **written**, where the device pairs it with the gain in effect when it is **played**.
 *
 * That would matter if the source amplitude varied, and it does not: every seeded fixture is a
 * constant-amplitude sine. `SleepTimerFadeAudioTest` does not take that on trust -- it asserts the
 * raw (pre-gain) RMS is flat across the whole capture, which is what makes the two pairings produce
 * the same envelope.
 */
class TimedAudioCapture : TeeAudioProcessor.AudioBufferSink {

  /** One buffer of PCM, and the moment the sink received it. */
  class Chunk(val atNanos: Long, val bytes: ByteArray)

  private val chunks = ConcurrentLinkedQueue<Chunk>()

  @Volatile var sampleRateHz: Int = 0
    private set

  @Volatile var channelCount: Int = 0
    private set

  @Volatile var encoding: Int = 0
    private set

  override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
    this.sampleRateHz = sampleRateHz
    this.channelCount = channelCount
    this.encoding = encoding
  }

  override fun handleBuffer(buffer: ByteBuffer) {
    // `TeeAudioProcessor.queueInput` hands over a read-only view of the input and only then copies
    // the original downstream, so draining this one cannot starve the chain -- the same fact
    // `CapturingAudioSink` records against the 1.11.0 bytecode.
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (bytes.isNotEmpty()) chunks += Chunk(SystemClock.elapsedRealtimeNanos(), bytes)
  }

  fun snapshot(): List<Chunk> = chunks.toList()
}

/**
 * Every gain the player applied, and when.
 *
 * `onVolumeChanged` is the player's own report of the value it dispatched to the audio renderer --
 * `ExoPlayerImpl.setVolume` sets the field, sends `MSG_SET_VOLUME` to the renderer and notifies
 * listeners from the same call -- so this is the applied gain rather than a value read back off a
 * getter the timer also wrote. It is deduplicated by Media3 itself: setting the volume it already
 * has is an early return, so a restore that changes nothing produces no entry here.
 */
class VolumeTimeline : Player.Listener {

  class Change(val atNanos: Long, val volume: Float)

  private val changes = CopyOnWriteArrayList<Change>()

  override fun onVolumeChanged(volume: Float) {
    changes += Change(SystemClock.elapsedRealtimeNanos(), volume)
  }

  fun snapshot(): List<Change> = changes.toList()

  /**
   * The gain in effect at [atNanos].
   *
   * [FULL] before the first change, because that is what a freshly built `ExoPlayer` starts at and
   * what `onVolumeChanged` therefore never reports.
   */
  fun volumeAt(atNanos: Long): Float =
    changes.lastOrNull { it.atNanos <= atNanos }?.volume ?: FULL

  companion object {
    const val FULL = 1f
  }
}
