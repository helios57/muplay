package app.muplay.cast.discovery

import app.muplay.cast.fake.FakeDescriptions
import app.muplay.cast.fake.FakeSsdpResponder
import app.muplay.cast.fake.genericDescription
import app.muplay.cast.fake.internetGatewayDescription
import app.muplay.cast.fake.mediaServerDescription
import app.muplay.cast.fake.sonosDescription
import app.muplay.model.RememberedRenderer
import app.muplay.model.RememberedRenderers
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Discovery end to end over a real UDP socket and a real HTTP socket, with **four** devices on the
 * network and only two of them castable.
 *
 * Both of the transports under test are real code paths, not test scaffolding: the loopback
 * destination is the **unicast** M-SEARCH, which spec section 12 calls a required fallback because
 * multicast never crosses a VPN tunnel. What CI cannot exercise is multicast *delivery*, and
 * `SsdpSearchTest` reduces that gap to one constant and one header line.
 *
 * Four things a single-device discovery test cannot prove, and every one of them is a defect this
 * project would otherwise ship: filtering, deduplication, ordering, and the `MediaServer`
 * exclusion. So every test below puts more than one device on the network, and in every one of
 * them at least one of those devices does not appear in the result.
 */
class RendererDirectoryTest {

  private val responders = mutableListOf<FakeSsdpResponder>()
  private val servers = mutableListOf<FakeDescriptions>()

  @AfterEach
  fun tearDown() {
    responders.forEach { it.close() }
    servers.forEach { it.close() }
  }

  @Test
  fun `four devices on the network become two picker entries, in name order`() = runTest {
    val serving = startDescriptions(
      "/kitchen.xml" to sonosDescription("uuid:RINCON_AAA", "Küche"),
      "/study.xml" to genericDescription("uuid:generic-bbb", "Study Amp"),
      "/nas.xml" to mediaServerDescription("uuid:nas-ccc", "NAS"),
      "/router.xml" to internetGatewayDescription("uuid:igd-ddd", "FRITZ!Box"),
    )
    val ssdp = startResponder(
      // The Sonos answers BOTH search targets, from one LOCATION -- the deduplication case.
      FakeSsdpResponder.Responder(
        serving.url("/kitchen.xml"), "uuid:RINCON_AAA",
        listOf(SsdpSearch.TARGET_MEDIA_RENDERER, SsdpSearch.TARGET_SONOS_ZONE_PLAYER),
      ),
      FakeSsdpResponder.Responder(
        serving.url("/study.xml"), "uuid:generic-bbb", listOf(SsdpSearch.TARGET_MEDIA_RENDERER),
      ),
      // A Sonos MediaServer answers a MediaRenderer search on some firmware. It has no AVTransport.
      FakeSsdpResponder.Responder(
        serving.url("/nas.xml"), "uuid:nas-ccc", listOf(SsdpSearch.TARGET_MEDIA_RENDERER),
      ),
      // And a router, which answers a different ST entirely and must never be searched for.
      FakeSsdpResponder.Responder(
        serving.url("/router.xml"), "uuid:igd-ddd",
        listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1"),
      ),
    )

    val result = directory(ssdp.endpoint, serving).discover(mxSeconds = null)

    // The exact list, in order. `hasSize(2)` would pass with the NAS in and the Sonos out;
    // `anyMatch` would pass with either one missing; and neither would notice the ordering, which
    // is what stops the picker reshuffling itself between openings.
    assertThat(result.devices.map { it.friendlyName }).containsExactly("Küche", "Study Amp")
    assertThat(result.devices.map { it.udn }).containsExactly("uuid:RINCON_AAA", "uuid:generic-bbb")
    assertThat(result.devices.map { it.isSonos }).containsExactly(true, false)
    // The Sonos's control URL comes from its *embedded* renderer, resolved against the LOCATION
    // it announced -- the whole Sonos path, observed end to end rather than only in the parser.
    assertThat(result.devices.first().avTransportControlUrl.toString())
      .isEqualTo(serving.url("/MediaRenderer/AVTransport/Control"))
    assertThat(result.unreachable).isEmpty()
  }

  @Test
  fun `the order is by name and not by arrival, proved by renaming one device`() = runTest {
    // The same network, one device renamed, and the order flips. Without this, an implementation
    // that simply preserved arrival order would pass the test above whenever the fake happened to
    // answer in that sequence -- which it usually would.
    val serving = startDescriptions(
      "/a.xml" to genericDescription("uuid:aaa", "Zebra"),
      "/b.xml" to genericDescription("uuid:bbb", "Aardvark"),
      "/c.xml" to genericDescription("uuid:ccc", "Mongoose"),
      "/d.xml" to mediaServerDescription("uuid:ddd", "Basement NAS"),
    )
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/a.xml"), "uuid:aaa", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/b.xml"), "uuid:bbb", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/c.xml"), "uuid:ccc", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/d.xml"), "uuid:ddd", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )

    // "Basement NAS" would sort first of all four if it were kept, so this also fails on a
    // directory that stopped excluding a MediaServer.
    assertThat(directory(ssdp.endpoint, serving).discover(mxSeconds = null).devices.map { it.friendlyName })
      .containsExactly("Aardvark", "Mongoose", "Zebra")
  }

  @Test
  fun `the ordering is case-insensitive, with the udn breaking a tie`() = runTest {
    // Two devices with the same name is what a household with two identical speakers looks like
    // before either is renamed. Ties broken by UDN make the order stable rather than arbitrary.
    val serving = startDescriptions(
      "/1.xml" to genericDescription("uuid:zzz", "amp"),
      "/2.xml" to genericDescription("uuid:aaa", "Amp"),
      "/3.xml" to genericDescription("uuid:mmm", "Bass"),
      "/4.xml" to internetGatewayDescription("uuid:bbb", "AAA Router"),
    )
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/1.xml"), "uuid:zzz", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/2.xml"), "uuid:aaa", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/3.xml"), "uuid:mmm", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/4.xml"), "uuid:bbb", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )

    // A case-SENSITIVE sort puts "Amp" before "Bass" before "amp"; this order is only produced by
    // a case-insensitive one with the UDN breaking the "amp"/"Amp" tie.
    assertThat(directory(ssdp.endpoint, serving).discover(mxSeconds = null).devices.map { it.udn })
      .containsExactly("uuid:aaa", "uuid:zzz", "uuid:mmm")
  }

  @Test
  fun `a device whose description cannot be fetched is left out, and does not take the others with it`() = runTest {
    // One dead device must not empty the picker. This is the difference between `mapNotNull` over
    // per-device failures and one try/catch around the whole discovery.
    val serving = startDescriptions(
      "/ok.xml" to genericDescription("uuid:ok", "Working"),
      "/nas.xml" to mediaServerDescription("uuid:nas", "NAS"),
    )
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/ok.xml"), "uuid:ok", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      // A 404 over a real socket, not a fake returning null.
      FakeSsdpResponder.Responder(serving.url("/missing.xml"), "uuid:gone", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/nas.xml"), "uuid:nas", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )

    assertThat(directory(ssdp.endpoint, serving).discover(mxSeconds = null).devices.map { it.friendlyName })
      .containsExactly("Working")
    // The dead one really was asked for, rather than skipped before the fetch.
    assertThat(serving.requests).contains("/missing.xml")
  }

  @Test
  fun `a description that is not a device description at all is left out`() = runTest {
    // A renderer whose HTTP server answers its own 404 page with status 200 is a real thing, and
    // so is a device that serves an SCPD document at the LOCATION by mistake.
    val serving = startDescriptions(
      "/ok.xml" to genericDescription("uuid:ok", "Working"),
      "/html.xml" to "<html><body>Not Found</body></html>",
      "/nas.xml" to mediaServerDescription("uuid:nas", "NAS"),
    )
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/ok.xml"), "uuid:ok", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/html.xml"), "uuid:html", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/nas.xml"), "uuid:nas", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )

    assertThat(directory(ssdp.endpoint, serving).discover(mxSeconds = null).devices.map { it.udn })
      .containsExactly("uuid:ok")
  }

  @Test
  fun `both search targets are sent, in one search`() = runTest {
    val serving = startDescriptions(
      "/x.xml" to genericDescription("uuid:x", "X"),
      "/nas.xml" to mediaServerDescription("uuid:nas", "NAS"),
      "/router.xml" to internetGatewayDescription("uuid:igd", "Router"),
    )
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/x.xml"), "uuid:x", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/nas.xml"), "uuid:nas", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(
        serving.url("/router.xml"), "uuid:igd", listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1"),
      ),
    )

    directory(ssdp.endpoint, serving).discover(mxSeconds = null)

    // Asserted on the datagrams the responder actually received, not on an argument this test
    // passed in. The exact list, because a directory that searched only ZonePlayer would find no
    // generic renderer and one that searched only MediaRenderer would miss Sonos firmware that
    // answers the other.
    assertThat(ssdp.searches.map { it.lineSequence().first { line -> line.startsWith("ST:") }.trim() })
      .containsExactly(
        "ST: ${SsdpSearch.TARGET_MEDIA_RENDERER}",
        "ST: ${SsdpSearch.TARGET_SONOS_ZONE_PLAYER}",
      )
  }

  /**
   * Beyond the plan, and the observation the real-socket tests structurally cannot make: what the
   * directory hands the transport, argument by argument.
   *
   * Every one of these four is a value a delegating method can quietly substitute a constant for.
   * `listenWindowMs` in particular is a `Long` next to an `Int?` in one call, which is this
   * repository's recorded wrong-argument shape.
   */
  @Test
  fun `every destination is searched, with the targets, the mx and the window the caller gave`() = runTest {
    val wifi = InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)
    val vpn = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 41900)
    val transport = RecordingSsdpTransport()

    RendererDirectory(
      transport = transport,
      destinations = { listOf(wifi, vpn) },
      http = { null },
      remembered = FakeRememberedRenderers(emptyList()),
      listenWindowMs = 1_234L,
    ).discover(mxSeconds = 7)

    assertThat(transport.searches).containsExactly(
      RecordingSsdpTransport.Search(wifi, RendererDirectory.SEARCH_TARGETS, 7, 1_234L),
      RecordingSsdpTransport.Search(vpn, RendererDirectory.SEARCH_TARGETS, 7, 1_234L),
    )
    assertThat(RendererDirectory.SEARCH_TARGETS).containsExactly(
      "urn:schemas-upnp-org:device:MediaRenderer:1",
      "urn:schemas-upnp-org:device:ZonePlayer:1",
    )
  }

  @Test
  fun `a remembered device that ssdp did not find is fetched directly and appears anyway`() = runTest {
    // Spec section 12: multicast never crosses a VPN tunnel, so the fallback is required, not
    // optional. This is that fallback's first layer -- re-fetch the LOCATION we stored last time.
    val serving = startDescriptions(
      "/vpn.xml" to genericDescription("uuid:over-vpn", "Bedroom"),
      "/nas.xml" to mediaServerDescription("uuid:nas", "NAS"),
      "/router.xml" to internetGatewayDescription("uuid:igd", "Router"),
    )
    // Two devices are on the air and neither is castable; the tunnel ate nothing, there is simply
    // nothing there to find.
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/nas.xml"), "uuid:nas", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(
        serving.url("/router.xml"), "uuid:igd", listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1"),
      ),
    )
    val remembered = FakeRememberedRenderers(
      listOf(RememberedRenderer("uuid:over-vpn", "Bedroom", serving.url("/vpn.xml"))),
    )

    val result = directory(ssdp.endpoint, serving, remembered).discover(mxSeconds = null)

    assertThat(result.devices.map { it.friendlyName }).containsExactly("Bedroom")
    assertThat(result.devices.single().avTransportControlUrl.toString())
      .isEqualTo(serving.url("/AVTransport/ctrl"))
    assertThat(result.unreachable).isEmpty()
  }

  @Test
  fun `a remembered device that is really gone is reported unreachable rather than silently dropped`() = runTest {
    // The other direction, and the one that matters for the user: "Bedroom is not answering" is
    // information; an empty list is not. Without this branch the fallback is unobservable.
    val serving = startDescriptions("/nas.xml" to mediaServerDescription("uuid:nas", "NAS"))
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/nas.xml"), "uuid:nas", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )
    val remembered = FakeRememberedRenderers(
      listOf(
        RememberedRenderer("uuid:dead", "Bedroom", serving.url("/never-existed.xml")),
        RememberedRenderer("uuid:also-dead", "Attic", serving.url("/also-never-existed.xml")),
      ),
    )

    val result = directory(ssdp.endpoint, serving, remembered).discover(mxSeconds = null)

    assertThat(result.devices).isEmpty()
    // In name order, like the devices list: this is a second section of the same picker, and it
    // reshuffling between openings is the same defect.
    assertThat(result.unreachable.map { it.friendlyName }).containsExactly("Attic", "Bedroom")
  }

  /**
   * Beyond the plan, and the bug the plan's own listing would have shipped: writing only the
   * devices that answered back to the store forgets a speaker the first time one pass fails to
   * reach it. The next open then has no URL to re-fetch and nothing to name in the "not
   * answering" list -- the fallback deletes itself exactly when it is needed.
   */
  @Test
  fun `a remembered device that did not answer is still remembered, so the next run can still name it`() = runTest {
    val serving = startDescriptions(
      "/here.xml" to genericDescription("uuid:here", "Kitchen"),
      "/nas.xml" to mediaServerDescription("uuid:nas", "NAS"),
    )
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/here.xml"), "uuid:here", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/nas.xml"), "uuid:nas", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )
    val remembered = FakeRememberedRenderers(
      listOf(RememberedRenderer("uuid:dead", "Bedroom", serving.url("/gone.xml"))),
    )

    directory(ssdp.endpoint, serving, remembered).discover(mxSeconds = null)

    // The device that answered first, then the one that did not: the store is bounded, and it
    // truncates from the end, so a speaker on the air must outrank one that is not.
    assertThat(remembered.saved.map { it.udn }).containsExactly("uuid:here", "uuid:dead")
    assertThat(remembered.saved.map { it.friendlyName }).containsExactly("Kitchen", "Bedroom")
  }

  @Test
  fun `a remembered device that ssdp also found is not listed twice`() = runTest {
    val serving = startDescriptions(
      "/dup.xml" to genericDescription("uuid:dup", "Kitchen"),
      "/nas.xml" to mediaServerDescription("uuid:nas", "NAS"),
      "/router.xml" to internetGatewayDescription("uuid:igd", "Router"),
    )
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/dup.xml"), "uuid:dup", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/nas.xml"), "uuid:nas", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(
        serving.url("/router.xml"), "uuid:igd", listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1"),
      ),
    )
    val remembered = FakeRememberedRenderers(
      listOf(RememberedRenderer("uuid:dup", "Kitchen", serving.url("/dup.xml"))),
    )

    val result = directory(ssdp.endpoint, serving, remembered).discover(mxSeconds = null)

    assertThat(result.devices.map { it.udn }).containsExactly("uuid:dup")
    assertThat(result.unreachable).isEmpty()
  }

  @Test
  fun `what was discovered is remembered, so the next run has a fallback to use`() = runTest {
    val serving = startDescriptions(
      "/a.xml" to genericDescription("uuid:a", "Alpha"),
      "/b.xml" to genericDescription("uuid:b", "Beta"),
      "/nas.xml" to mediaServerDescription("uuid:nas", "NAS"),
    )
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/a.xml"), "uuid:a", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/b.xml"), "uuid:b", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/nas.xml"), "uuid:nas", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )
    val remembered = FakeRememberedRenderers(emptyList())

    directory(ssdp.endpoint, serving, remembered).discover(mxSeconds = null)

    // The exact remembered list, with its URLs -- the fallback is worth nothing if the URL is not
    // the one that will be fetched next time. The NAS must not be in it either: a store that
    // remembered everything SSDP answered would put it back in the picker on the next VPN run,
    // through a path that never consults `CastDevice.from`.
    assertThat(remembered.saved.map { it.udn }).containsExactly("uuid:a", "uuid:b")
    assertThat(remembered.saved.map { it.friendlyName }).containsExactly("Alpha", "Beta")
    assertThat(remembered.saved.map { it.descriptionUrl })
      .containsExactly(serving.url("/a.xml"), serving.url("/b.xml"))
  }

  /**
   * Layer 3 of the fallback: the generic renderer that binds an **ephemeral** port for its
   * description server, so the LOCATION we stored last time is now a dead port and layer 2 cannot
   * work. The unicast M-SEARCH carries the new LOCATION back.
   *
   * The transport is a recording one here rather than a real socket, and that is the stronger
   * choice rather than the weaker one: what has to be observed is that the datagram goes to the
   * remembered host **on port 1900** with **no MX**, and a test cannot bind 1900 on a machine
   * several agents share. Recording the arguments observes exactly those two facts; a real socket
   * on a port chosen by the test would observe neither.
   */
  @Test
  fun `a remembered device that moved to a new port is found again by a unicast search`() = runTest {
    val serving = startDescriptions("/moved.xml" to genericDescription("uuid:moved", "Bedroom"))
    val stale = "http://127.0.0.1:${serving.port}/gone.xml"
    val multicast = InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)
    val unicast = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1900)
    val transport = RecordingSsdpTransport(
      mapOf(unicast to listOf(response("uuid:moved", serving.url("/moved.xml")))),
    )

    val result = RendererDirectory(
      transport = transport,
      destinations = { listOf(multicast) },
      http = serving.client(),
      remembered = FakeRememberedRenderers(listOf(RememberedRenderer("uuid:moved", "Bedroom", stale))),
      listenWindowMs = WINDOW_MS,
    ).discover(mxSeconds = null)

    assertThat(result.devices.map { it.friendlyName }).containsExactly("Bedroom")
    assertThat(result.devices.single().descriptionUrl.toString()).isEqualTo(serving.url("/moved.xml"))
    assertThat(result.unreachable).isEmpty()
    // The multicast search first, then the unicast recovery to the remembered host on the SSDP
    // port, with no MX -- section 1.3.3's unicast form, and the two facts this test exists for.
    assertThat(transport.searches.map { it.destination }).containsExactly(multicast, unicast)
    assertThat(transport.searches.last().mxSeconds).isNull()
    // Layer 2 was tried first and really did fetch the stale URL, rather than being skipped.
    assertThat(serving.requests).containsExactly("/gone.xml", "/moved.xml")
  }

  /**
   * Beyond the plan. A remembered LOCATION is an IP and a port, and a DHCP lease moves. The device
   * answering it now may be somebody else's speaker, and casting a track to it is the failure
   * nobody would ever diagnose. The UDN in the description is what says "same device".
   */
  @Test
  fun `a remembered url now answered by a different device is not mistaken for it`() = runTest {
    val serving = startDescriptions(
      "/lease.xml" to genericDescription("uuid:someone-else", "Neighbour's Amp"),
      "/nas.xml" to mediaServerDescription("uuid:nas", "NAS"),
    )
    val transport = RecordingSsdpTransport()

    val result = RendererDirectory(
      transport = transport,
      destinations = { listOf(InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)) },
      http = serving.client(),
      remembered = FakeRememberedRenderers(
        listOf(RememberedRenderer("uuid:mine", "Bedroom", serving.url("/lease.xml"))),
      ),
      listenWindowMs = WINDOW_MS,
    ).discover(mxSeconds = null)

    assertThat(result.devices).isEmpty()
    assertThat(result.unreachable.map { it.udn }).containsExactly("uuid:mine")
    // It really did fetch and really did reject, rather than never asking.
    assertThat(serving.requests).containsExactly("/lease.xml")
  }

  /**
   * Beyond the plan, and a security assertion rather than a functional one. A remembered URL has
   * been through disk since it was announced. `LocalNetworkOnly` refuses the *fetch* (that is
   * Task 1's client), but the unicast M-SEARCH is a datagram this class sends on its own account,
   * and nothing outside this method would stop it leaving for the internet.
   */
  @Test
  fun `a remembered url off the local network is never dialled by the unicast fallback`() = runTest {
    val serving = startDescriptions("/nas.xml" to mediaServerDescription("uuid:nas", "NAS"))
    val multicast = InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)
    val transport = RecordingSsdpTransport()

    val result = RendererDirectory(
      transport = transport,
      destinations = { listOf(multicast) },
      http = serving.client(),
      remembered = FakeRememberedRenderers(
        listOf(RememberedRenderer("uuid:poisoned", "Bedroom", "http://93.184.216.34/desc.xml")),
      ),
      listenWindowMs = WINDOW_MS,
    ).discover(mxSeconds = null)

    assertThat(result.unreachable.map { it.udn }).containsExactly("uuid:poisoned")
    // Exactly one search, and it is the multicast one. A second entry here would be MuPlay
    // sending UDP to a public address on the strength of a stored string.
    assertThat(transport.searches.map { it.destination }).containsExactly(multicast)
  }

  // ---- scaffolding -------------------------------------------------------------------------

  private fun startResponder(vararg devices: FakeSsdpResponder.Responder) =
    FakeSsdpResponder(devices.toList()).also { responders += it; it.start() }

  private fun startDescriptions(vararg documents: Pair<String, String>) =
    FakeDescriptions(documents.toMap()).also { servers += it; it.start() }

  private fun directory(
    endpoint: InetSocketAddress,
    serving: FakeDescriptions,
    remembered: RememberedRenderers = FakeRememberedRenderers(emptyList()),
  ) = RendererDirectory(
    transport = DatagramSsdpTransport(),
    destinations = { listOf(endpoint) },
    http = serving.client(),
    remembered = remembered,
    listenWindowMs = WINDOW_MS,
  )

  private fun response(udn: String, location: String) = SsdpResponse(
    location = URI(location),
    searchTarget = SsdpSearch.TARGET_MEDIA_RENDERER,
    usn = "$udn::${SsdpSearch.TARGET_MEDIA_RENDERER}",
    server = "Linux UPnP/1.0 MuPlayFake/1.0",
    from = InetAddress.getLoopbackAddress(),
  )

  private class FakeRememberedRenderers(initial: List<RememberedRenderer>) : RememberedRenderers {
    private var stored = initial
    var saved: List<RememberedRenderer> = emptyList()
      private set

    override suspend fun load(): List<RememberedRenderer> = stored

    override suspend fun remember(renderers: List<RememberedRenderer>) {
      saved = renderers
      stored = saved
    }

    override suspend fun forget(udn: String) {
      stored = stored.filterNot { it.udn == udn }
    }
  }

  /**
   * A transport that records what it was asked and answers from a table.
   *
   * The one observation a real socket cannot make: the exact destination, target list, `MX` and
   * listen window the directory passes down. Everything else in this class runs against
   * [DatagramSsdpTransport] for real.
   */
  private class RecordingSsdpTransport(
    private val answers: Map<InetSocketAddress, List<SsdpResponse>> = emptyMap(),
  ) : SsdpTransport {

    data class Search(
      val destination: InetSocketAddress,
      val targets: List<String>,
      val mxSeconds: Int?,
      val listenWindowMs: Long,
    )

    private val recorded = CopyOnWriteArrayList<Search>()
    val searches: List<Search> get() = recorded.toList()

    override suspend fun search(
      destination: InetSocketAddress,
      targets: List<String>,
      mxSeconds: Int?,
      listenWindowMs: Long,
    ): List<SsdpResponse> {
      recorded += Search(destination, targets, mxSeconds, listenWindowMs)
      return answers[destination].orEmpty()
    }
  }

  private companion object {
    /** Long enough for a loopback round trip, short enough that a dozen of these is not a minute. */
    const val WINDOW_MS = 500L
  }
}
