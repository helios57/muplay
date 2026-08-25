package app.muplay.cast.discovery

import app.muplay.cast.fake.FakeSsdpResponder
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The real UDP transport, against three real responders on loopback.
 *
 * The destination here is a **unicast** one, and that is not a test-only shape: UPnP Device
 * Architecture 1.1 section 1.3.3's unicast M-SEARCH is the fallback spec section 12 calls
 * required, because multicast does not cross a VPN tunnel. What no tier of this project's CI can
 * exercise is multicast *delivery*; `SsdpSearchTest` shrinks that gap to one constant
 * (`MULTICAST_ENDPOINT`) and one header line (`HOST`), both asserted there.
 */
class DatagramSsdpTransportTest {

  private var responder: FakeSsdpResponder? = null

  @AfterEach
  fun tearDown() {
    responder?.close()
  }

  private val transport = DatagramSsdpTransport()

  @Test
  fun `replies come back parsed, and a device that answers a different target does not`() = runTest {
    // Three responders, one of which must not appear -- the router answers an ST nobody searched
    // for, which is exactly what a home network looks like.
    val ssdp = start(
      FakeSsdpResponder.Responder("http://127.0.0.1:1400/kitchen.xml", "uuid:RINCON_AAA", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder("http://127.0.0.1:1401/zone.xml", "uuid:RINCON_BBB", listOf(SsdpSearch.TARGET_SONOS_ZONE_PLAYER)),
      FakeSsdpResponder.Responder("http://127.0.0.1:1402/router.xml", "uuid:igd-ccc", listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1")),
    )

    val responses = transport.search(ssdp.endpoint, RendererDirectory.SEARCH_TARGETS, null, WINDOW_MS)

    // Arrival order is a property of the network and is deliberately not asserted here -- it is
    // asserted where it is a product requirement, in RendererDirectoryTest's ordering tests.
    assertThat(responses.map { it.udn }).containsExactlyInAnyOrder("uuid:RINCON_AAA", "uuid:RINCON_BBB")
    assertThat(responses.map { it.searchTarget }).containsExactlyInAnyOrder(
      SsdpSearch.TARGET_MEDIA_RENDERER,
      SsdpSearch.TARGET_SONOS_ZONE_PLAYER,
    )
    assertThat(responses.single { it.udn == "uuid:RINCON_AAA" }.location.toString())
      .isEqualTo("http://127.0.0.1:1400/kitchen.xml")
    assertThat(responses.single { it.udn == "uuid:RINCON_AAA" }.server)
      .isEqualTo("Linux UPnP/1.0 MuPlayFake/1.0")
    assertThat(responses.single { it.udn == "uuid:RINCON_AAA" }.from.hostAddress).isEqualTo("127.0.0.1")
  }

  @Test
  fun `the bytes that leave the socket are exactly what SsdpSearch renders, one datagram per target`() = runTest {
    val ssdp = start()

    transport.search(ssdp.endpoint, RendererDirectory.SEARCH_TARGETS, mxSeconds = 2, listenWindowMs = WINDOW_MS)

    // Byte-for-byte, and in target order. Asserted on what the responder *received*, not on what
    // this test passed in: a transport that rendered its own head, or that sent one datagram for
    // two targets, is invisible to any assertion made on the argument side.
    assertThat(ssdp.searches).containsExactly(
      String(SsdpSearch.request("127.0.0.1", ssdp.endpoint.port, SsdpSearch.TARGET_MEDIA_RENDERER, 2), Charsets.US_ASCII),
      String(SsdpSearch.request("127.0.0.1", ssdp.endpoint.port, SsdpSearch.TARGET_SONOS_ZONE_PLAYER, 2), Charsets.US_ASCII),
    )
  }

  @Test
  fun `the mx the caller passes reaches the wire, and null means no MX line at all`() = runTest {
    // Two observations of one argument, at the layer it is actually applied. A transport that
    // dropped `mxSeconds` on the floor and always sent DEFAULT_MX_SECONDS passes every
    // response-side assertion in this class.
    val withMx = start()
    transport.search(withMx.endpoint, listOf(SsdpSearch.TARGET_MEDIA_RENDERER), mxSeconds = 4, listenWindowMs = WINDOW_MS)
    assertThat(withMx.searches.single()).contains("MX: 4\r\n")
    withMx.close()

    val withoutMx = start()
    transport.search(withoutMx.endpoint, listOf(SsdpSearch.TARGET_MEDIA_RENDERER), mxSeconds = null, listenWindowMs = WINDOW_MS)
    assertThat(withoutMx.searches.single()).doesNotContain("MX")
  }

  /**
   * The silent-truncation trap, and the reason this transport refuses a full-buffer datagram.
   *
   * `recvfrom` truncates without a flag, an exception or a short-read signal, and the header
   * parser is deliberately tolerant of a block that ends without its blank line -- so a `LOCATION`
   * clipped at the buffer boundary would parse as a real, shorter URL and this app would fetch a
   * device description from somewhere else, with nothing logged anywhere.
   *
   * Both sides of the boundary are observed here, because "drop everything oversized" and "drop
   * everything" are the same test otherwise: the padded reply is dropped, and a reply padded to
   * just under the buffer from the same responder list comes back intact.
   */
  @Test
  fun `a reply too big for the receive buffer is dropped rather than parsed as a short one`() = runTest {
    val ssdp = start(
      // ~5 KiB of SERVER header: over the 4 KiB receive buffer, so the kernel truncates it.
      FakeSsdpResponder.Responder(
        "http://127.0.0.1:1400/huge.xml", "uuid:huge",
        listOf(SsdpSearch.TARGET_MEDIA_RENDERER), server = "X".repeat(5_000),
      ),
      // ~3 KiB: unpleasant, under the buffer, and still a perfectly good answer.
      FakeSsdpResponder.Responder(
        "http://127.0.0.1:1401/big.xml", "uuid:big",
        listOf(SsdpSearch.TARGET_MEDIA_RENDERER), server = "Y".repeat(3_000),
      ),
      FakeSsdpResponder.Responder(
        "http://127.0.0.1:1402/small.xml", "uuid:small", listOf(SsdpSearch.TARGET_MEDIA_RENDERER),
      ),
    )

    val responses = transport.search(ssdp.endpoint, RendererDirectory.SEARCH_TARGETS, null, WINDOW_MS)

    assertThat(responses.map { it.udn }).containsExactlyInAnyOrder("uuid:big", "uuid:small")
    // Not merely absent from the udn list: a truncated reply must not reach the parser at all,
    // because what it would produce is a *plausible* location rather than a rejected one.
    assertThat(responses.map { it.location.toString() })
      .containsExactlyInAnyOrder("http://127.0.0.1:1401/big.xml", "http://127.0.0.1:1402/small.xml")
  }

  @Test
  fun `a network with nothing on it yields an empty list rather than hanging`() = runTest {
    val ssdp = start()

    assertThat(transport.search(ssdp.endpoint, RendererDirectory.SEARCH_TARGETS, null, WINDOW_MS)).isEmpty()
  }

  /**
   * `multicastDestinations()` returns the same endpoint once per usable interface, which looks
   * like a bug and is not -- see its own KDoc. What is asserted here is the part that would be a
   * real defect: that every destination is the address the protocol reserves, and that the list
   * is never empty on a host with no usable interface at all.
   */
  @Test
  fun `every multicast destination is the reserved endpoint, and there is always at least one`() {
    val destinations = DatagramSsdpTransport.multicastDestinations()

    assertThat(destinations).isNotEmpty
    assertThat(destinations.map { it.address.hostAddress }.distinct()).containsExactly("239.255.255.250")
    assertThat(destinations.map { it.port }.distinct()).containsExactly(1900)
  }

  private fun start(vararg devices: FakeSsdpResponder.Responder) =
    FakeSsdpResponder(devices.toList()).also { responder = it; it.start() }

  private companion object {
    /** Long enough for a loopback round trip, short enough that ten of these are not a minute. */
    const val WINDOW_MS = 500L
  }
}
