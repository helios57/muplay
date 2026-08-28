package app.muplay.castpicker

import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DiscoveryResult
import app.muplay.cast.session.CastSessionState
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [CastViewModel]'s own decisions, on the JVM.
 *
 * Reachable at all because the view model is constructed over [CastDiscovery] and [CastControl]
 * rather than over `RendererDirectory` and `CastSessionManager` directly — see `CastSeams.kt` for
 * why neither of those two can be built here. No mock framework: both fakes below are written by
 * hand, which is what `ConventionTest` rule 3 requires and what makes them record *order* and
 * *count*, not merely "was called".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val directory = FakeDiscovery()
  private val session = FakeControl()

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  // ---- when discovery runs --------------------------------------------------------------------

  @Test
  fun `a closed picker issues no search`() = runTest(dispatcher) {
    // Discovery is three multicast datagrams and a three-second listen. Running it in the
    // background would be rude to the network and read by nothing.
    val viewModel = CastViewModel(directory, session)

    advanceTimeBy(10_000)
    advanceUntilIdle()

    assertThat(directory.searchCount).isZero()
    assertThat(viewModel.uiState.value).isEqualTo(CastUiState.Hidden)
  }

  @Test
  fun `opening searches once, and refreshing searches again`() = runTest(dispatcher) {
    val viewModel = CastViewModel(directory, session)

    viewModel.open()
    advanceUntilIdle()
    assertThat(directory.searchCount).isEqualTo(1)

    viewModel.refresh()
    advanceUntilIdle()
    assertThat(directory.searchCount).isEqualTo(2)
  }

  @Test
  fun `closing cancels the search in flight rather than letting it run out`() = runTest(dispatcher) {
    // `DatagramSsdpTransport`'s listen loop checks for cancellation once per poll and closes its
    // socket on the way out. A picker that merely ignored the result would leave a bound UDP socket
    // and an IO thread running for the rest of the window, for an answer nobody will read.
    directory.searchDurationMs = 3_000L
    val viewModel = CastViewModel(directory, session)
    viewModel.open()
    advanceTimeBy(100)

    viewModel.close()
    advanceTimeBy(10_000)
    advanceUntilIdle()

    assertThat(directory.cancelledSearches).isEqualTo(1)
    assertThat(directory.searchCount).isEqualTo(1)
    assertThat(viewModel.uiState.value).isEqualTo(CastUiState.Hidden)
  }

  @Test
  fun `reopening searches again rather than showing the list from the last room`() =
    runTest(dispatcher) {
      directory.result = DiscoveryResult(listOf(deviceA), emptyList())
      val viewModel = CastViewModel(directory, session)

      viewModel.open()
      advanceUntilIdle()
      assertThat(viewModel.uiState.value).isInstanceOf(CastUiState.Devices::class.java)

      viewModel.close()
      directory.searchDurationMs = 3_000L
      viewModel.open()
      advanceTimeBy(100)

      // Searching, not the list as it stood in the other room -- a stale list is one a user taps.
      assertThat(viewModel.uiState.value).isEqualTo(CastUiState.Searching)
      assertThat(directory.searchCount).isEqualTo(2)
    }

  // ---- choosing a device ----------------------------------------------------------------------

  @Test
  fun `selecting a device casts to that device and not to another`() = runTest(dispatcher) {
    // Two observations, so `select` cannot cast to a constant.
    directory.result = DiscoveryResult(listOf(deviceA, deviceB), emptyList())
    val viewModel = CastViewModel(directory, session)
    viewModel.open()
    advanceUntilIdle()

    viewModel.select("uuid:b")
    advanceUntilIdle()
    assertThat(session.castTargets.map { it.udn }).containsExactly("uuid:b")

    viewModel.select("uuid:a")
    advanceUntilIdle()
    assertThat(session.castTargets.map { it.udn }).containsExactly("uuid:b", "uuid:a")
  }

  @Test
  fun `selecting a udn that is not on the network does nothing rather than throwing`() =
    runTest(dispatcher) {
      // The list a user tapped can be one discovery pass out of date, and throwing there would
      // crash the app for a speaker that was merely unplugged.
      directory.result = DiscoveryResult(listOf(deviceA), emptyList())
      val viewModel = CastViewModel(directory, session)
      viewModel.open()
      advanceUntilIdle()

      viewModel.select("uuid:ghost")
      advanceUntilIdle()

      assertThat(session.castTargets).isEmpty()
    }

  @Test
  fun `selecting before any search has finished does nothing rather than throwing`() {
    // The picker's own button is not the only way in: `:app` hosts the sheet behind a
    // `rememberSaveable` boolean, so a process death with the sheet open restores a picker whose
    // first search has not landed yet. `found.value` is `null` there, and this is that path.
    runTest(dispatcher) {
      val viewModel = CastViewModel(directory, session)

      viewModel.select("uuid:a")
      advanceUntilIdle()

      assertThat(session.castTargets).isEmpty()
    }
  }

  @Test
  fun `closing a picker that never opened cancels nothing and is harmless`() {
    // `:app` drives `close()` from a `LaunchedEffect` keyed on the sheet's visibility, which fires
    // once with `false` on first composition -- before any search exists to cancel.
    runTest(dispatcher) {
      val viewModel = CastViewModel(directory, session)

      viewModel.close()
      advanceUntilIdle()

      assertThat(directory.searchCount).isZero()
      assertThat(directory.cancelledSearches).isZero()
      assertThat(viewModel.uiState.value).isEqualTo(CastUiState.Hidden)
    }
  }

  @Test
  fun `disconnecting ends the session`() = runTest(dispatcher) {
    directory.result = DiscoveryResult(listOf(deviceA), emptyList())
    val viewModel = CastViewModel(directory, session)
    viewModel.open()
    advanceUntilIdle()
    viewModel.select("uuid:a")
    advanceUntilIdle()

    viewModel.disconnect()
    advanceUntilIdle()

    assertThat(session.stopped).isEqualTo(1)
    assertThat(session.state.value).isEqualTo(CastSessionState.Idle)
  }

  // ---- following the session ------------------------------------------------------------------

  @Test
  fun `the ui state follows the session, in both directions`() = runTest(dispatcher) {
    directory.result = DiscoveryResult(listOf(deviceA), emptyList())
    val viewModel = CastViewModel(directory, session)
    viewModel.open()
    advanceUntilIdle()

    session.emit(CastSessionState.Playing("Küche"))
    advanceUntilIdle()
    assertThat((viewModel.uiState.value as CastUiState.Devices).connectedUdn).isEqualTo("uuid:a")

    session.emit(CastSessionState.Failed("Küche", "Küche refused: UPnP error 714"))
    advanceUntilIdle()
    assertThat(viewModel.uiState.value).isInstanceOf(CastUiState.Failed::class.java)

    // And back: a picker that cannot leave its own failure state is one a user has to kill the app
    // to escape.
    session.emit(CastSessionState.Idle)
    advanceUntilIdle()
    assertThat(viewModel.uiState.value).isInstanceOf(CastUiState.Devices::class.java)
  }

  @Test
  fun `the button names the connected speaker, and names nothing when idle`() = runTest(dispatcher) {
    val viewModel = CastViewModel(directory, session)
    advanceUntilIdle()
    assertThat(viewModel.connectedDeviceName.value).isNull()

    // Two names, so the flow cannot be a constant that happens to contain one of them.
    session.emit(CastSessionState.Playing("Küche"))
    advanceUntilIdle()
    assertThat(viewModel.connectedDeviceName.value).isEqualTo("Küche")

    session.emit(CastSessionState.Playing("Study"))
    advanceUntilIdle()
    assertThat(viewModel.connectedDeviceName.value).isEqualTo("Study")

    session.emit(CastSessionState.Lost("Study", 42_000L, "track-1"))
    advanceUntilIdle()
    assertThat(viewModel.connectedDeviceName.value).isNull()
  }

  // ---- the volume -----------------------------------------------------------------------------

  @Test
  fun `setting the volume passes a percentage through, and a different fraction gives a different one`() =
    runTest(dispatcher) {
      // Two values, so the slider's argument is proved to have an effect.
      val viewModel = CastViewModel(directory, session)

      viewModel.setVolume(0.5f)
      viewModel.setVolume(0.17f)
      advanceUntilIdle()

      assertThat(session.volumes).containsExactly(50, 17)
    }

  @Test
  fun `a slider value outside zero to one is coerced rather than sent to the speaker`() =
    runTest(dispatcher) {
      // `Slider` reports its own `valueRange`, and a range of `0f..100f` would otherwise send a
      // UPnP SetVolume of 10 000 -- answered with an error a user reads as "the speaker is broken".
      val viewModel = CastViewModel(directory, session)

      viewModel.setVolume(1.5f)
      viewModel.setVolume(-0.2f)
      advanceUntilIdle()

      assertThat(session.volumes).containsExactly(100, 0)
    }

  @Test
  fun `the volume reaches the ui only once something is connected`() = runTest(dispatcher) {
    directory.result = DiscoveryResult(listOf(deviceA), emptyList())
    val viewModel = CastViewModel(directory, session)
    session.volumePercent = 42
    viewModel.open()
    advanceUntilIdle()

    // Nothing is cast: a slider here would be a control that silently does nothing.
    assertThat((viewModel.uiState.value as CastUiState.Devices).volumePercent).isNull()

    viewModel.select("uuid:a")
    advanceUntilIdle()

    assertThat((viewModel.uiState.value as CastUiState.Devices).volumePercent).isEqualTo(42)
  }

  @Test
  fun `a volume the user just set is what the picker reads back`() = runTest(dispatcher) {
    // The revision counter's whole job. `deviceVolumePercent()` is a plain read with no observable
    // of its own, so without a bump the slider would be seeded once and never move again.
    directory.result = DiscoveryResult(listOf(deviceA), emptyList())
    val viewModel = CastViewModel(directory, session)
    session.volumePercent = 42
    viewModel.open()
    advanceUntilIdle()
    viewModel.select("uuid:a")
    advanceUntilIdle()

    viewModel.setVolume(0.7f)
    advanceUntilIdle()

    assertThat((viewModel.uiState.value as CastUiState.Devices).volumePercent).isEqualTo(70)
  }

  // ---- fakes ----------------------------------------------------------------------------------

  /**
   * A discovery that counts what it was asked and notices being cancelled.
   *
   * [cancelledSearches] is the observation that makes `closing cancels the search in flight` mean
   * something: a view model that merely dropped the result would leave [searchCount] at 1 too.
   */
  private class FakeDiscovery : CastDiscovery {
    var result: DiscoveryResult = DiscoveryResult(emptyList(), emptyList())
    var searchDurationMs: Long = 0L
    var searchCount = 0
    var cancelledSearches = 0

    override suspend fun discover(): DiscoveryResult {
      searchCount += 1
      try {
        delay(searchDurationMs)
      } catch (cancelled: CancellationException) {
        cancelledSearches += 1
        throw cancelled
      }
      return result
    }
  }

  private class FakeControl : CastControl {
    private val published = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
    override val state: StateFlow<CastSessionState> = published

    val castTargets = mutableListOf<CastDevice>()
    val volumes = mutableListOf<Int>()
    var stopped = 0
    var volumePercent: Int? = null

    /** `null` until something is cast, exactly as `CastSessionManager.castPlayer` is. */
    private var connected = false

    override suspend fun castTo(device: CastDevice) {
      castTargets += device
      connected = true
      published.value = CastSessionState.Playing(device.friendlyName)
    }

    override suspend fun stopCasting() {
      stopped += 1
      connected = false
      published.value = CastSessionState.Idle
    }

    override fun deviceVolumePercent(): Int? = volumePercent.takeIf { connected }

    override fun setDeviceVolumePercent(percent: Int) {
      volumes += percent
      volumePercent = percent
    }

    fun emit(state: CastSessionState) {
      published.value = state
    }
  }

  private val deviceA = device("uuid:a", "Küche")
  private val deviceB = device("uuid:b", "Study Amp")

  private fun device(udn: String, name: String) = CastDevice(
    udn = udn,
    friendlyName = name,
    manufacturer = "Sonos, Inc.",
    modelName = "Sonos One",
    descriptionUrl = URI("http://10.0.0.5:1400/xml/device_description.xml"),
    avTransportControlUrl = URI("http://10.0.0.5:1400/MediaRenderer/AVTransport/Control"),
    avTransportScpdUrl = null,
    renderingControlUrl = URI("http://10.0.0.5:1400/MediaRenderer/RenderingControl/Control"),
    isSonos = true,
  )
}
