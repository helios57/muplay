package io.github.helios57.muplay.waves

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.helios57.muplay.binaural.BinauralProfile
import io.github.helios57.muplay.binaural.ProfileLibrary
import io.github.helios57.muplay.binaural.ProfileOrigin
import io.github.helios57.muplay.binaural.TimelineSample
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileRow(
  val id: String,
  val name: String,
  val summary: String,
  val detail: String,
  val edited: Boolean,
  val playing: Boolean,
)

data class NowPlaying(
  val id: String,
  val name: String,
  val profile: BinauralProfile,
  val sample: TimelineSample,
  val positionSeconds: Double,
  val stopping: Boolean,
) {
  val beat: String get() = "${sample.band.label} · ${formatHz(sample.beatHz)} Hz"
  val tones: String get() = leftRightText(sample.carrierHz, sample.beatHz)
  val clock: String get() = "${formatClock(positionSeconds)} of ${formatClock(profile.totalSeconds)}"
  val progress: Float
    get() = (positionSeconds / profile.totalSeconds).coerceIn(0.0, 1.0).toFloat()
}

data class WavesUiState(
  val safetyAcknowledged: Boolean = true,
  val programs: List<ProfileRow> = emptyList(),
  val tones: List<ProfileRow> = emptyList(),
  val yours: List<ProfileRow> = emptyList(),
  val current: NowPlaying? = null,
  val volume: Double = 1.0,
)

@HiltViewModel
class WavesViewModel @Inject constructor(
  private val library: ProfileLibrary,
  private val playback: BinauralPlayback,
) : ViewModel() {

  val uiState: StateFlow<WavesUiState> = combine(library.document, playback.status) { doc, status ->
    val playingId = (status as? BinauralStatus.Playing)?.profile?.id
    fun row(entry: io.github.helios57.muplay.binaural.ProfileEntry) = ProfileRow(
      id = entry.profile.id, name = entry.profile.name,
      summary = summaryOf(entry.profile), detail = detailOf(entry.profile),
      edited = entry.origin == ProfileOrigin.EDITED, playing = entry.profile.id == playingId,
    )
    val (own, shipped) = doc.entries.partition { it.origin == ProfileOrigin.CUSTOM }
    WavesUiState(
      safetyAcknowledged = doc.safetyAcknowledged,
      programs = shipped.filterNot { it.profile.isStatic }.map(::row),
      tones = shipped.filter { it.profile.isStatic }.map(::row),
      yours = own.map(::row),
      current = (status as? BinauralStatus.Playing)?.let {
        NowPlaying(it.profile.id, it.profile.name, it.profile, it.sample, it.positionSeconds, it.stopping)
      },
      volume = doc.masterVolume,
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, WavesUiState())

  init {
    viewModelScope.launch {
      library.load()
      playback.setVolume(library.document.value.masterVolume)
    }
  }

  fun play(id: String) {
    library.document.value.find(id)?.let { playback.play(it.profile) }
  }

  fun stop() = playback.stop()

  fun acknowledgeSafety() {
    viewModelScope.launch { library.acknowledgeSafety() }
  }

  /** While the slider moves: the sound follows at once, nothing is written. */
  fun previewVolume(volume: Double) = playback.setVolume(volume)

  /** When the slider is let go: remembered for next time. */
  fun setVolume(volume: Double) {
    playback.setVolume(volume)
    viewModelScope.launch { library.setMasterVolume(volume) }
  }

  /** A new profile of the listener's own, steady alpha for half an hour, returned to be edited. */
  fun create(onCreated: (String) -> Unit) {
    viewModelScope.launch {
      val existing = library.document.value.entries.map { it.profile.name }.toSet()
      val name = generateSequence(1) { it + 1 }.map { if (it == 1) "My profile" else "My profile $it" }
        .first { it !in existing }
      val profile = BinauralProfile.static(
        id = "custom-" + UUID.randomUUID(), name = name, beatHz = 10.0, carrierHz = 200.0,
        durationSeconds = 30 * 60.0,
      )
      library.save(profile)
      onCreated(profile.id)
    }
  }
}
