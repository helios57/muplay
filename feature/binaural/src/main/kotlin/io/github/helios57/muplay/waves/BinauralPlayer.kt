package io.github.helios57.muplay.waves

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.helios57.muplay.binaural.BinauralProfile
import io.github.helios57.muplay.binaural.BinauralSynth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Plays a [BinauralProfile] through its own `AudioTrack`, beside the music.**
 *
 * The tones are a second stream, not a Media3 player and not part of the play queue, for three
 * reasons that are all about falling asleep to music with the beats underneath it:
 *
 * - **It never asks for audio focus.** Asking would pause the music, which is the opposite of what
 *   the listener wants. Android mixes the two streams; the profile's level and [setVolume] set how
 *   loud the tones sit under the music.
 * - **No effect of the music chain touches it.** Replay gain, crossfade-free gapless handling and
 *   any future equaliser all live in the music player. A binaural beat needs the two ears' tones
 *   exactly as rendered, and a stereo effect that mixed them would destroy the beat.
 * - **It is `USAGE_MEDIA`,** so the hardware volume keys move it with the music.
 *
 * One thread renders and writes; everything else asks it to change through volatile fields that
 * it reads between blocks, so [BinauralSynth] stays single-threaded as it requires. A new profile
 * starts a new session and fades the old one out briefly, so switching never clicks.
 *
 * [BinauralService] holds the process in the foreground while a session runs.
 */
@Singleton
class BinauralPlayer @Inject constructor(
  @ApplicationContext private val context: Context,
) : BinauralPlayback {

  private val published = MutableStateFlow<BinauralStatus>(BinauralStatus.Idle)
  override val status: StateFlow<BinauralStatus> = published.asStateFlow()

  @Volatile
  private var volume = 1.0

  @Volatile
  private var session: Session? = null

  @Synchronized
  override fun play(profile: BinauralProfile) {
    session?.requestRelease(SWITCH_FADE_SECONDS)
    session = Session(profile).also { it.start() }
    context.startForegroundService(Intent(context, BinauralService::class.java))
  }

  override fun update(profile: BinauralProfile) {
    session?.takeIf { it.profileId == profile.id }?.requestProfile(profile)
  }

  override fun setVolume(volume: Double) {
    this.volume = volume.coerceIn(0.0, 1.0)
  }

  @Synchronized
  override fun stop() {
    session?.requestRelease(STOP_FADE_SECONDS)
  }

  @Synchronized
  private fun finished(ended: Session) {
    if (session === ended) {
      session = null
      published.value = BinauralStatus.Idle
    }
  }

  private inner class Session(initial: BinauralProfile) : Thread("binaural") {
    val profileId = initial.id
    private val synth = BinauralSynth(initial, sampleRate = SAMPLE_RATE)

    @Volatile
    private var pendingProfile: BinauralProfile? = null

    @Volatile
    private var pendingRelease: Double? = null
    private var releasing = false

    fun requestProfile(profile: BinauralProfile) {
      pendingProfile = profile
    }

    fun requestRelease(seconds: Double) {
      pendingRelease = seconds
    }

    override fun run() {
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
      val track = buildTrack()
      try {
        if (track.state != AudioTrack.STATE_INITIALIZED) return
        track.play()
        val buffer = FloatArray(BLOCK_FRAMES * 2)
        var sincePublish = PUBLISH_FRAMES
        while (true) {
          pendingProfile?.let {
            pendingProfile = null
            synth.replaceProfile(it)
          }
          pendingRelease?.let {
            pendingRelease = null
            releasing = true
            synth.release(it)
          }
          synth.masterGain = volume
          if (sincePublish >= PUBLISH_FRAMES) {
            sincePublish = 0
            publish()
          }
          val frames = synth.render(buffer, BLOCK_FRAMES)
          if (frames == 0) break
          var offset = 0
          while (offset < frames * 2) {
            val written = track.write(buffer, offset, frames * 2 - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) return
            offset += written
          }
          sincePublish += frames
        }
      } finally {
        // Everything written has already faded to silence -- a program's own fade-out, or the
        // release ramp -- so releasing the track here cuts nothing audible.
        runCatching { track.stop() }
        track.release()
        finished(this)
      }
    }

    private fun publish() {
      if (session !== this) return
      published.value = BinauralStatus.Playing(
        profile = synth.profile,
        positionSeconds = synth.positionSeconds,
        sample = synth.sample(),
        stopping = releasing,
      )
    }

    private fun buildTrack(): AudioTrack {
      val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()
      val minimum = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
      )
      return AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build(),
        )
        .setAudioFormat(format)
        .setTransferMode(AudioTrack.MODE_STREAM)
        // A night-long stream that nobody is interacting with: let the platform use its deep
        // buffer and sleep between refills. A volume change takes a quarter of a second to land.
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
        .setBufferSizeInBytes(maxOf(minimum, BUFFER_FRAMES * BYTES_PER_FRAME))
        .build()
    }
  }

  companion object {
    const val SAMPLE_RATE = 48_000
    const val STOP_FADE_SECONDS = 3.0
    private const val SWITCH_FADE_SECONDS = 0.3
    private const val BLOCK_FRAMES = 2_048
    private const val BUFFER_FRAMES = SAMPLE_RATE / 4
    private const val PUBLISH_FRAMES = SAMPLE_RATE / 4
    private const val BYTES_PER_FRAME = 2 * 4
  }
}
