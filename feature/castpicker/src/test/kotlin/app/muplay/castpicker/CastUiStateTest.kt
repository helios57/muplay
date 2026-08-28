package app.muplay.castpicker

import app.muplay.cast.control.RendererFollowsAnotherException
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DiscoveryResult
import app.muplay.cast.proxy.ByteRange
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.ProxyRegistry
import app.muplay.cast.proxy.ProxyUpstream
import app.muplay.cast.route.CastRoute
import app.muplay.cast.route.CastRouter
import app.muplay.cast.session.CastSessionState
import app.muplay.model.RememberedRenderer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The mapping from what the cast layer knows to what a user sees.
 *
 * Pure functions in their own file, so this -- the part where a field silently becomes a constant --
 * is gated by a BRANCH floor on Tier 1, while the Composables take a LINE floor on the device. Same
 * split as `SetupScreenKt` and `PlayerUiStateKt`, same reason.
 */
class CastUiStateTest {

  // ---- the device list ------------------------------------------------------------------------

  @Test
  fun `no discovery yet is Searching`() {
    assertThat(castUiState(discovery = null, session = CastSessionState.Idle))
      .isEqualTo(CastUiState.Searching)
  }

  @Test
  fun `every field of every row comes from the device it describes`() {
    // Three fields observed at two values each across one list, so none of them can be a constant.
    val state = castUiState(
      discovery(sonos("uuid:a", "Küche", "Sonos One"), generic("uuid:b", "Study Amp", "WXA-50")),
      CastSessionState.Idle,
    ) as CastUiState.Devices

    assertThat(state.devices.map { it.udn }).containsExactly("uuid:a", "uuid:b")
    assertThat(state.devices.map { it.name }).containsExactly("Küche", "Study Amp")
    assertThat(state.devices.map { it.subtitle }).containsExactly("Sonos One", "WXA-50")
    assertThat(state.devices.map { it.isSonos }).containsExactly(true, false)
  }

  @Test
  fun `the order of the rows is the order discovery produced`() {
    // `RendererDirectory` already sorted these, case-insensitively by name then by UDN, and it is
    // the only place that decides an order -- so this mapping must not re-sort or reverse them.
    //
    // **The fixture is deliberately not in alphabetical order**, and that is this test's whole
    // discriminating power. The plan's own listing used `Aardvark, Mongoose, Zebra`, on which
    // `sortedBy { it.name }` is the identity: the mutation the test exists to catch would have
    // passed it. Measured before the fixture was changed, and recorded in `ci/mutation-probes.sh`
    // as `castui/rows-sorted-by-name`.
    val state = castUiState(
      discovery(
        generic("uuid:z", "Zebra", "X"),
        generic("uuid:a", "Aardvark", "X"),
        generic("uuid:m", "Mongoose", "X"),
      ),
      CastSessionState.Idle,
    ) as CastUiState.Devices

    assertThat(state.devices.map { it.name }).containsExactly("Zebra", "Aardvark", "Mongoose")
  }

  @Test
  fun `an empty network is Devices with an empty list, not Searching`() {
    // Different facts: "still looking" and "looked, found nothing" need different copy, and
    // collapsing them leaves a spinner running forever over an empty room.
    val state = castUiState(discovery(), CastSessionState.Idle)

    assertThat(state).isInstanceOf(CastUiState.Devices::class.java)
    assertThat((state as CastUiState.Devices).devices).isEmpty()
  }

  @Test
  fun `a remembered device that did not answer is listed as unreachable, by name`() {
    val state = castUiState(
      discovery(
        generic("uuid:a", "Study Amp", "X"),
        unreachable = listOf(RememberedRenderer("uuid:z", "Bedroom", "http://10.0.0.9/d.xml")),
      ),
      CastSessionState.Idle,
    ) as CastUiState.Devices

    assertThat(state.devices.map { it.name }).containsExactly("Study Amp")
    assertThat(state.unreachable).containsExactly("Bedroom")
  }

  @Test
  fun `the connected device is marked, and only that one`() {
    // Two observations of one boolean across three rows, so `isConnected` cannot be a constant.
    val state = castUiState(
      discovery(generic("uuid:a", "A", "X"), generic("uuid:b", "B", "X"), generic("uuid:c", "C", "X")),
      CastSessionState.Playing("B"),
      connectedUdn = "uuid:b",
    ) as CastUiState.Devices

    assertThat(state.devices.map { it.isConnected }).containsExactly(false, true, false)
    assertThat(state.connectedUdn).isEqualTo("uuid:b")
  }

  @Test
  fun `no row is connected when nothing is being cast`() {
    val state = castUiState(
      discovery(generic("uuid:a", "A", "X"), generic("uuid:b", "B", "X")),
      CastSessionState.Idle,
    ) as CastUiState.Devices

    assertThat(state.devices.map { it.isConnected }).containsExactly(false, false)
    assertThat(state.connectedUdn).isNull()
  }

  // ---- which device is connected --------------------------------------------------------------

  @Test
  fun `the connected udn is resolved from the session's device name`() {
    val found = discovery(generic("uuid:a", "Küche", "X"), generic("uuid:b", "Study", "X"))

    assertThat(connectedUdn(found, CastSessionState.Playing("Study"))).isEqualTo("uuid:b")
    assertThat(connectedUdn(found, CastSessionState.Connecting("Küche"))).isEqualTo("uuid:a")
  }

  @Test
  fun `a session on a device that is not on this list resolves to nothing rather than to the first`() {
    // The failure this rules out is `devices.firstOrNull()`, which marks the wrong speaker
    // connected on every network where the real one has gone.
    val found = discovery(generic("uuid:a", "Küche", "X"))

    assertThat(connectedUdn(found, CastSessionState.Playing("Bedroom"))).isNull()
    assertThat(connectedUdn(discovery = null, CastSessionState.Playing("Küche"))).isNull()
  }

  @Test
  fun `nothing is connected while idle, failed or lost`() {
    val found = discovery(generic("uuid:a", "Küche", "X"))

    assertThat(connectedUdn(found, CastSessionState.Idle)).isNull()
    assertThat(connectedUdn(found, CastSessionState.Failed("Küche", "refused"))).isNull()
    assertThat(connectedUdn(found, CastSessionState.Lost("Küche", 42_000L, "track-1"))).isNull()
  }

  @Test
  fun `the cast button names the speaker while casting and only says Cast otherwise`() {
    assertThat(castButtonDescription(null)).isEqualTo(CAST_BUTTON_LABEL)
    // Two names, so the description cannot be a constant that merely happens to contain one.
    assertThat(castButtonDescription("Küche")).contains(CAST_BUTTON_LABEL).contains("Küche")
    assertThat(castButtonDescription("Study")).contains(CAST_BUTTON_LABEL).contains("Study")
    assertThat(castButtonDescription("Küche")).isNotEqualTo(castButtonDescription("Study"))
  }

  // ---- the volume -----------------------------------------------------------------------------

  @Test
  fun `the volume is carried only while something is connected`() {
    // A slider over nothing is a control that silently does nothing -- the very defect
    // CAST_SPEED_LIMIT_NOTICE exists to refuse for playback speed.
    val found = discovery(generic("uuid:a", "Küche", "X"))

    val connected = castUiState(found, CastSessionState.Playing("Küche"), "uuid:a", volumePercent = 42)
    assertThat((connected as CastUiState.Devices).volumePercent).isEqualTo(42)

    val idle = castUiState(found, CastSessionState.Idle, connectedUdn = null, volumePercent = 42)
    assertThat((idle as CastUiState.Devices).volumePercent).isNull()
  }

  @Test
  fun `a connected speaker with no reported volume carries none`() {
    val found = discovery(generic("uuid:a", "Küche", "X"))

    val state = castUiState(found, CastSessionState.Playing("Küche"), "uuid:a", volumePercent = null)

    assertThat((state as CastUiState.Devices).volumePercent).isNull()
  }

  // ---- the failures ---------------------------------------------------------------------------

  @Test
  fun `a failed session becomes Failed with the device's name`() {
    val state = castUiState(discovery(), CastSessionState.Failed("Küche", "refused: 714"))

    assertThat(state).isInstanceOf(CastUiState.Failed::class.java)
    assertThat((state as CastUiState.Failed).deviceName).isEqualTo("Küche")
  }

  @Test
  fun `a failure outranks the device list`() {
    // A picker that renders both is one where the row a user just tapped still looks tappable,
    // with the reason it did not work somewhere above it.
    val state = castUiState(
      discovery(generic("uuid:a", "Küche", "X")),
      CastSessionState.Failed("Küche", "refused: 714"),
    )

    assertThat(state).isNotInstanceOf(CastUiState.Devices::class.java)
  }

  @Test
  fun `each of the four failures gets its own sentence, and they are different sentences`() {
    // The whole point of this module. `castFailure` returning one generic string would pass any
    // `isNotNull` assertion and tell a user nothing.
    val messages = listOf(
      CastSessionState.Failed("Küche", groupedReason("Küche")),
      CastSessionState.Failed("Küche", rendererCannotReachPhoneReason("Küche")),
      CastSessionState.Failed("Küche", phoneCannotReachRendererReason("Küche")),
      CastSessionState.Lost("Küche", 42_000L, "track-1"),
    ).map { castFailure(it)?.message }

    assertThat(messages).doesNotContainNull()
    assertThat(messages.distinct()).hasSize(4)
    assertThat(messages[0]).contains("Küche").contains("Ungroup")
    assertThat(messages[1]).contains("Küche").contains("could not reach this phone")
    assertThat(messages[2]).contains("Küche").contains("This phone could not reach")
    assertThat(messages[3]).contains("Küche").contains("moved back to this phone")
  }

  @Test
  fun `a second failure of the same kind carries the second device's name`() {
    // Rule 2 on the field a user actually reads: one sentence built from one device name is not
    // evidence that the name is read at all.
    val first = castFailure(CastSessionState.Failed("Küche", groupedReason("Küche")))?.message
    val second = castFailure(CastSessionState.Failed("Study", groupedReason("Study")))?.message

    assertThat(first).contains("Küche").doesNotContain("Study")
    assertThat(second).contains("Study").doesNotContain("Küche")
  }

  @Test
  fun `a failure this module does not recognise keeps the reason it was given`() {
    // The fallback arm, and it is a deliberate one: "UPnP error 714 (Illegal MIME-type)" is worth
    // showing, and inventing prose in its place would be worse.
    val message = castFailure(
      CastSessionState.Failed("Küche", "Küche refused: UPnP error 714 (Illegal MIME-type)"),
    )?.message

    assertThat(message).contains("Küche").contains("714").contains("Illegal MIME-type")
  }

  @Test
  fun `a classified failure does not carry the protocol string it was classified from`() {
    // The security half. `CastFailure`'s own documentation guarantees a reason is free of a stream
    // URL -- one carries the user's Subsonic `u`, `t` and `s` -- but that guarantee lives in
    // another module and can be edited. Everything this function recognises is rewritten from the
    // device's name, so the recognised arms cannot forward anything at all.
    val coordinator = "x-rincon:RINCON_5CAAFD00000001400"
    val message = castFailure(
      CastSessionState.Failed("Küche", groupedReason("Küche", coordinator)),
    )?.message

    assertThat(message).doesNotContain(coordinator)
    assertThat(message).doesNotContain("x-rincon")
  }

  @Test
  fun `an idle, connecting or playing session has no failure`() {
    // The other direction, so `castFailure` cannot be a constant.
    assertThat(castFailure(CastSessionState.Idle)).isNull()
    assertThat(castFailure(CastSessionState.Playing("Küche"))).isNull()
    assertThat(castFailure(CastSessionState.Connecting("Küche"))).isNull()
  }

  @Test
  fun `the speed notice says a speaker plays at normal speed`() {
    // Task 5: AVTransport's `Speed` must be "1". A per-item speed that is silently not applied is a
    // setting the user believes is on. Left general on purpose -- see the constant's own KDoc for
    // why naming Plan 4's stored number here would mean giving this module a media session.
    assertThat(CAST_SPEED_LIMIT_NOTICE).contains("normal speed")
  }

  /**
   * The anti-drift test for the three phrases [FailurePhrases] classifies on.
   *
   * Each one is a substring of a message **another module composes**, so this drives the real
   * producers -- the real `RendererFollowsAnotherException` and a real `CastRouter` -- and asserts
   * that what came out is still something this file recognises. Without it, a reworded message in
   * `:core:cast` would silently demote all three sentences to the generic fallback with every test
   * in this file still green.
   */
  @Test
  fun `the phrases this file classifies on are the phrases the cast layer actually produces`() {
    assertThat(RendererFollowsAnotherException("x-rincon:RINCON_1").message)
      .contains(FailurePhrases.GROUPED)

    val registry = ProxyRegistry()
    MediaProxyServer(
      upstream = NoUpstream,
      registry = registry,
      bindAddress = InetAddress.getLoopbackAddress(),
    ).use { proxy ->
      val router = CastRouter(proxy, registry, allowRendererDirect = false, proofTimeoutMs = 1L)

      // `candidate` on a device whose control URL names a host that does not resolve.
      val noRoute = router.candidate(
        device = generic("uuid:a", "Küche", "X", host = "no-such-host.invalid"),
        upstreamUrl = UPSTREAM,
        served = ServedMedia("audio/mpeg", "mp3"),
      )
      assertThat(noRoute).isInstanceOf(CastRoute.Unroutable::class.java)
      assertThat((noRoute as CastRoute.Unroutable).detail)
        .contains(FailurePhrases.PHONE_CANNOT_REACH_RENDERER)

      // `confirm` on a route the renderer never fetched.
      val published = registry.publish(UPSTREAM, ServedMedia("audio/mpeg", "mp3"))
      val neverFetched = router.confirm(
        CastRoute.Proxied(
          url = proxy.urlFor(published, "127.0.0.1"),
          media = published,
          deviceName = "Küche",
          proofRequired = true,
        ),
        UPSTREAM,
      )
      assertThat(neverFetched).isInstanceOf(CastRoute.Unroutable::class.java)
      assertThat((neverFetched as CastRoute.Unroutable).detail)
        .contains(FailurePhrases.RENDERER_CANNOT_REACH_PHONE)
    }
  }

  // ---- fixtures -------------------------------------------------------------------------------

  private fun discovery(
    vararg devices: CastDevice,
    unreachable: List<RememberedRenderer> = emptyList(),
  ) = DiscoveryResult(devices.toList(), unreachable)

  private fun generic(
    udn: String,
    name: String,
    model: String?,
    host: String = "10.0.0.5",
  ) = device(udn, name, model, "Yamaha", isSonos = false, host = host)

  private fun sonos(udn: String, name: String, model: String?) =
    device(udn, name, model, "Sonos, Inc.", isSonos = true, host = "10.0.0.6")

  private fun device(
    udn: String,
    name: String,
    model: String?,
    manufacturer: String,
    isSonos: Boolean,
    host: String,
  ) = CastDevice(
    udn = udn,
    friendlyName = name,
    manufacturer = manufacturer,
    modelName = model,
    descriptionUrl = URI("http://$host:1400/xml/device_description.xml"),
    avTransportControlUrl = URI("http://$host:1400/MediaRenderer/AVTransport/Control"),
    avTransportScpdUrl = null,
    renderingControlUrl = URI("http://$host:1400/MediaRenderer/RenderingControl/Control"),
    isSonos = isSonos,
  )

  /** The real message, from the real exception, rather than a copy of it typed in here. */
  private fun groupedReason(deviceName: String, coordinator: String = "x-rincon:RINCON_1") =
    "$deviceName: ${RendererFollowsAnotherException(coordinator).message}"

  private fun rendererCannotReachPhoneReason(deviceName: String) =
    "$deviceName ${FailurePhrases.RENDERER_CANNOT_REACH_PHONE} within 6 seconds, so it cannot " +
      "reach it."

  private fun phoneCannotReachRendererReason(deviceName: String) =
    "$deviceName ${FailurePhrases.PHONE_CANNOT_REACH_RENDERER} this phone has no route to it."

  private object NoUpstream : ProxyUpstream {
    override fun totalLength(url: String): Long? = null
    override fun open(url: String, range: ByteRange): InputStream = ByteArrayInputStream(ByteArray(0))
  }

  private companion object {
    /**
     * Not a real stream URL and not a fixture of one. A Subsonic stream URL carries the user's `u`,
     * `t` and `s`; this module never sees one, and a test that wrote one down would be the first
     * place in the repository where one existed.
     */
    const val UPSTREAM = "http://navidrome.invalid/rest/stream"
  }
}
