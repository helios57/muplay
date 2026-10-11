package io.github.helios57.muplay.waves

import io.github.helios57.muplay.binaural.BinauralProfile
import io.github.helios57.muplay.binaural.ProfileTimeline
import kotlinx.coroutines.flow.MutableStateFlow

/** Records what the screens asked of the generator, and plays nothing. */
class FakePlayback : BinauralPlayback {
  override val status = MutableStateFlow<BinauralStatus>(BinauralStatus.Idle)
  val played = mutableListOf<BinauralProfile>()
  val updated = mutableListOf<BinauralProfile>()
  val volumes = mutableListOf<Double>()
  var stops = 0

  override fun play(profile: BinauralProfile) {
    played += profile
    status.value = BinauralStatus.Playing(profile, 0.0, ProfileTimeline(profile).at(0.0))
  }

  override fun update(profile: BinauralProfile) {
    updated += profile
  }

  override fun setVolume(volume: Double) {
    volumes += volume
  }

  override fun stop() {
    stops++
    status.value = BinauralStatus.Idle
  }
}
