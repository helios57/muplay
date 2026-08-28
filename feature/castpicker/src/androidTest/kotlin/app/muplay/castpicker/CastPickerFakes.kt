package app.muplay.castpicker

import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DiscoveryResult
import app.muplay.cast.session.CastSessionState
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hand-written seams for the two instrumented suites, plus the device fixtures they render.
 *
 * A second copy of the JVM tier's fakes rather than a shared one, and that is a deliberate cost:
 * `src/test` and `src/androidTest` are different compilations, sharing one would mean a
 * `testFixtures` source set for two tiny classes, and this project has already recorded what a
 * shared test helper costs when one lane changes its shape (`GaplessTest` against
 * `RealTrackBytes`). No mock framework anywhere — `ConventionTest` rule 3 fails the build on the
 * mere word.
 */
internal object CastPickerFakes {

  class FakeDiscovery(private val result: DiscoveryResult = EMPTY) : CastDiscovery {
    var searchCount = 0
    override suspend fun discover(): DiscoveryResult {
      searchCount += 1
      return result
    }
  }

  class FakeControl : CastControl {
    private val published = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
    override val state: StateFlow<CastSessionState> = published

    val castTargets = mutableListOf<CastDevice>()
    val volumes = mutableListOf<Int>()
    var volumePercent: Int? = null

    override suspend fun castTo(device: CastDevice) {
      castTargets += device
      published.value = CastSessionState.Playing(device.friendlyName)
    }

    override suspend fun stopCasting() {
      published.value = CastSessionState.Idle
    }

    override fun deviceVolumePercent(): Int? = volumePercent

    override fun setDeviceVolumePercent(percent: Int) {
      volumes += percent
      volumePercent = percent
    }

    fun emit(state: CastSessionState) {
      published.value = state
    }
  }

  val EMPTY = DiscoveryResult(emptyList(), emptyList())

  /**
   * The host every fixture device's URLs are built from.
   *
   * `theSheetNeverRendersADeviceUrl` asserts the whole semantics tree contains none of them, so
   * this string existing in exactly one place is what lets that assertion be exact rather than a
   * guess at what a leak would look like.
   */
  const val DEVICE_HOST = "10.77.0.5"

  fun device(
    udn: String,
    name: String,
    model: String?,
    isSonos: Boolean = false,
  ) = CastDevice(
    udn = udn,
    friendlyName = name,
    manufacturer = if (isSonos) "Sonos, Inc." else "Yamaha",
    modelName = model,
    descriptionUrl = URI("http://$DEVICE_HOST:1400/xml/device_description.xml"),
    avTransportControlUrl = URI("http://$DEVICE_HOST:1400/MediaRenderer/AVTransport/Control"),
    avTransportScpdUrl = null,
    renderingControlUrl = URI("http://$DEVICE_HOST:1400/MediaRenderer/RenderingControl/Control"),
    isSonos = isSonos,
  )
}
