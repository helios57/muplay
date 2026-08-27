package app.muplay.castpicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.cast.discovery.DiscoveryResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The picker's state, and the four things a user can do to it.
 *
 * ### Discovery runs while the sheet is open, and not otherwise
 *
 * An SSDP search is three multicast datagrams and a three-second listen window. Running it
 * continuously would be rude to the network and pointless — this picker is the only thing that
 * reads the answer. [open] starts a search, [refresh] starts another, [close] cancels the one in
 * flight and forgets its result. Nothing polls in the background, and
 * `CastViewModelTest.a closed picker issues no search` is what holds that: with the search moved
 * into an `init` block, that test is the one that goes red.
 *
 * ### The volume is a percentage from here down
 *
 * [setVolume] takes the slider's `Float` and is the **one** place it becomes the 0–100 integer that
 * `RenderingControl`, `Player.setDeviceVolume` and [CastControl] all speak — deliberately here
 * rather than in the adapter, because here a JVM test can watch two different fractions arrive as
 * two different percentages, and in the adapter it would need a speaker.
 */
@HiltViewModel
class CastViewModel @Inject constructor(
  private val discovery: CastDiscovery,
  private val control: CastControl,
) : ViewModel() {

  private val opened = MutableStateFlow(false)
  private val found = MutableStateFlow<DiscoveryResult?>(null)

  /**
   * Bumped by every state-changing command, so that a `setDeviceVolumePercent` — which changes a
   * speaker and nothing observable — still re-reads [CastControl.deviceVolumePercent]. Without it
   * the slider would be seeded once and never corrected.
   */
  private val revision = MutableStateFlow(0)

  private var searchJob: Job? = null

  val uiState: StateFlow<CastUiState> =
    combine(opened, found, control.state, revision) { opened, found, session, _ ->
      // Closed is Hidden whatever else is true. A picker that renders a device list behind a closed
      // sheet is one whose state survived the dismissal that was supposed to end it.
      if (!opened) {
        CastUiState.Hidden
      } else {
        castUiState(
          discovery = found,
          session = session,
          connectedUdn = connectedUdn(found, session),
          volumePercent = control.deviceVolumePercent(),
        )
      }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CastUiState.Hidden)

  /**
   * The speaker the session is on, or `null`.
   *
   * Separate from [uiState] and derived from the session alone, because the **button** needs it
   * while the sheet is closed -- and [uiState]'s first decision is that a closed sheet is
   * [CastUiState.Hidden]. A button that read `uiState` would announce "Cast" while a track was
   * playing in the kitchen.
   */
  val connectedDeviceName: StateFlow<String?> =
    control.state
      .map(::castDeviceName)
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  /** The sheet opened. Start looking. */
  fun open() {
    opened.value = true
    search()
  }

  /** The user asked again — a speaker that was booting when the sheet opened answers this one. */
  fun refresh() = search()

  /**
   * The sheet was dismissed.
   *
   * The in-flight search is **cancelled**, not merely ignored: `DatagramSsdpTransport`'s listen
   * loop checks for cancellation once per poll and closes its socket on the way out, so a picker a
   * user backed out of stops holding a bound UDP socket and an IO thread. Dropping the result on
   * the floor instead would leave both running for the rest of the window.
   */
  fun close() {
    opened.value = false
    searchJob?.cancel()
    searchJob = null
    // Forgotten, so the next open shows "Looking for speakers…" rather than the list as it stood
    // when the user walked into a different room.
    found.value = null
  }

  /**
   * Cast to the device with this UDN.
   *
   * A UDN and not a [CastDeviceRow], because a row is a rendering and this has to find the real
   * `CastDevice` — the one carrying the control URLs — in the discovery result. A UDN that is not
   * in that result does nothing at all: the list a user tapped can be one pass out of date, and
   * throwing there would crash the app for a speaker that was merely unplugged.
   */
  fun select(udn: String) {
    val device = found.value?.devices?.firstOrNull { it.udn == udn } ?: return
    viewModelScope.launch {
      control.castTo(device)
      revision.value += 1
    }
  }

  fun disconnect() {
    viewModelScope.launch {
      control.stopCasting()
      revision.value += 1
    }
  }

  /**
   * Set the speaker's volume from the slider's 0f–1f position.
   *
   * Coerced rather than trusted: `Slider` reports its own `valueRange`, and a future range of
   * `0f..100f` would otherwise send a UPnP `SetVolume` of 10 000 and be answered with an error the
   * user would read as "the speaker is broken".
   */
  fun setVolume(fraction: Float) {
    control.setDeviceVolumePercent((fraction.coerceIn(0f, 1f) * PERCENT).roundToInt())
    revision.value += 1
  }

  private fun search() {
    searchJob?.cancel()
    searchJob = viewModelScope.launch { found.value = discovery.discover() }
  }

  private companion object {
    const val PERCENT = 100f
  }
}
