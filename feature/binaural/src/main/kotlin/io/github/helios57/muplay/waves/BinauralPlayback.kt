package io.github.helios57.muplay.waves

import io.github.helios57.muplay.binaural.BinauralProfile
import io.github.helios57.muplay.binaural.TimelineSample
import kotlinx.coroutines.flow.StateFlow

/** What the generator is doing, as the screens and the notification show it. */
sealed interface BinauralStatus {
  data object Idle : BinauralStatus

  data class Playing(
    val profile: BinauralProfile,
    val positionSeconds: Double,
    val sample: TimelineSample,
    val stopping: Boolean = false,
  ) : BinauralStatus
}

/**
 * The seam between the screens and the sound. [BinauralPlayer] is the one implementation; the
 * view models take this interface so their rules can be held on the JVM tier without an
 * `AudioTrack`.
 */
interface BinauralPlayback {
  val status: StateFlow<BinauralStatus>

  /** Starts [profile] from its beginning, replacing whatever was sounding. */
  fun play(profile: BinauralProfile)

  /** Applies an edit to the profile that is playing, without restarting it. Ignored otherwise. */
  fun update(profile: BinauralProfile)

  /** The listener's volume for the tones, 0 to 1, applied at once and smoothly. */
  fun setVolume(volume: Double)

  /** Fades out and stops. */
  fun stop()
}
