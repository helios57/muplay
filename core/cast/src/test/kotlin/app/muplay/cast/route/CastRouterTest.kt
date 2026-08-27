package app.muplay.cast.route

import app.muplay.cast.didl.CastItem
import app.muplay.cast.didl.DidlLite
import app.muplay.cast.didl.MimeAgreement
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.fake.FakeRenderer
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.net.LocalAddress
import app.muplay.cast.proxy.ByteRange
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.ProxyRegistry
import app.muplay.cast.proxy.ProxyUpstream
import app.muplay.cast.soap.SoapClient
import app.muplay.cast.soap.UpnpError
import app.muplay.cast.soap.UpnpErrorException
import app.muplay.cast.control.UpnpRenderer
import app.muplay.model.StreamFormat
import java.io.Closeable
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * **The routing decision, proved by observation rather than by comparing addresses.**
 *
 * Everything here runs against a **real** [MediaProxyServer] on loopback and the **real**
 * `FakeRenderer` from Task 3, because the whole subject is whether the renderer actually fetched.
 * A fake router, or a fake proxy, would make every assertion below a statement about this test.
 *
 * The three outcomes are asserted in both directions each, and the third one -- `Unroutable` -- is
 * the one that matters: without it a cast that cannot work starts, reports success, and plays
 * nothing forever.
 */
class CastRouterTest {

  private val closeables = mutableListOf<Closeable>()
  private val registry = ProxyRegistry()
  private val proxy = MediaProxyServer(ConstantUpstream(CONTENT), registry, InetAddress.getLoopbackAddress())
    .also { closeables += it; it.start() }
  private val http = CastHttpClient()
  private val fake = FakeRenderer().also { closeables += it; it.start() }
  private val device: CastDevice = CastDevice.from(
    DeviceDescription.parse(http.exchange(fake.descriptionUrl, "GET").bodyText(), fake.descriptionUrl),
    fake.descriptionUrl,
  )!!
  private val upnp = UpnpRenderer(device, SoapClient(http), http)

  @AfterEach
  fun tearDown() {
    closeables.forEach { runCatching { it.close() } }
  }

  // ---- candidate: what the renderer is told to fetch -------------------------------------------

  @Test
  fun `the default route is proxied, and it names the phone address the renderer can reach`() {
    val route = router().candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    // The host is the source address the kernel would use to reach the renderer -- not an
    // enumerated interface. Against a loopback renderer that is 127.0.0.1; on a phone with Wi-Fi
    // and a VPN up it is whichever one routes to the speaker (the test below varies it).
    assertThat(route.url).isEqualTo("http://127.0.0.1:${proxy.port}${route.media.path}")
    assertThat(route.url).endsWith(".mp3")
    assertThat(route.media.upstreamUrl).isEqualTo(UPSTREAM)
    assertThat(route.deviceName).isEqualTo(device.friendlyName)
    // Proof is required unless the fast path said otherwise, and by default it never does.
    assertThat(route.proofRequired).isTrue

    // The extension at a second value, so the URL cannot carry a constant one -- and Sonos infers
    // the MIME type from exactly that.
    val flac = router().candidate(device, UPSTREAM, ServedMedia.of("flac", StreamFormat.Raw))
    assertThat((flac as CastRoute.Proxied).url).endsWith(".flac")
  }

  @Test
  fun `the url names the address that routes to this renderer, and not a constant`() {
    // The second observation of the host, made through the `localAddress` seam because a
    // loopback-only test bed can otherwise only ever see 127.0.0.1 -- at which point a `towards`
    // that returned a constant would satisfy the test above.
    val vpn = router(localAddress = { InetAddress.getByName("10.8.0.3") })
      .candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    assertThat(vpn.url).startsWith("http://10.8.0.3:${proxy.port}/media/")
  }

  @Test
  fun `a second candidate for a second track gets its own token`() {
    // Two observations, so `candidate` cannot return a constant path.
    val first = router().candidate(device, "https://nav.example/rest/stream?id=1", MP3) as CastRoute.Proxied
    val second = router().candidate(device, "https://nav.example/rest/stream?id=2", MP3) as CastRoute.Proxied

    assertThat(first.media.token).isNotEqualTo(second.media.token)
    assertThat(first.url).isNotEqualTo(second.url)
  }

  @Test
  fun `an ipv6 phone address is bracketed and unscoped, so the url a renderer is handed parses`() {
    // `InetAddress.hostAddress` renders `fd00::1` as `fd00:0:0:0:0:0:0:1`, and
    // `http://fd00:0:0:0:0:0:0:1:PORT/...` is not a URL naming that host and that port -- measured:
    // `URI.getHost()` on the unbracketed form is **null**. A renderer handed it fetches nothing,
    // which is a cast that starts and plays silence.
    val v6 = router(localAddress = { InetAddress.getByName("fd00::1") })
      .candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    assertThat(v6.url).isEqualTo("http://[fd00:0:0:0:0:0:0:1]:${proxy.port}${v6.media.path}")
    assertThat(URI(v6.url).host).isEqualTo("[fd00:0:0:0:0:0:0:1]")
    assertThat(URI(v6.url).port).isEqualTo(proxy.port)

    // ...and a scope id is this machine's own name for an interface. `fe80::1%7` means nothing on
    // the speaker, and it is not a legal URI host either.
    val scoped = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 7)
    val linkLocal = router(localAddress = { scoped }).candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    assertThat(scoped.hostAddress).contains("%7")
    assertThat(linkLocal.url).doesNotContain("%")
    assertThat(URI(linkLocal.url).port).isEqualTo(proxy.port)
  }

  // ---- confirm: the proof ----------------------------------------------------------------------

  @Test
  fun `a renderer that fetches confirms the proxied route`() {
    // The proof, in the direction where it succeeds. `fetchesMedia` is on, so the fake behaves as
    // a real renderer does: HEAD, then a ranged GET.
    fake.fetchesMedia = true
    val router = router()
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    play(route.url)
    val confirmed = router.confirm(route, UPSTREAM)

    assertThat(confirmed).isSameAs(route)
    awaitProxyMethods("HEAD", "GET")
    // The bytes that arrived, not "a request was made": method, token and range, in order.
    assertThat(proxy.requestLog.map { Triple(it.method, it.token, it.rangeHeader) })
      .containsExactly(
        Triple("HEAD", route.media.token, null),
        Triple("GET", route.media.token, "bytes=0-"),
      )
  }

  @Test
  fun `a renderer that is not subnet-equal still fetches from the phone, which is spec section 6's vpn row`() {
    // THE RESOLUTION OF THE SPEC'S SELF-CONTRADICTION, as an executed observation.
    //
    // Spec section 6's rule sentence sends a phone-on-a-VPN / speaker-on-the-LAN pair down the
    // "otherwise" branch -- the speaker fetches Navidrome directly. Its own table says row 3 is
    // "proxy over the tunnel". This test is the table winning: the subnet comparison is asked, it
    // says NO for exactly the pair the spec's row 3 describes, and the proxy is used anyway
    // because the renderer proves it can reach the phone.
    //
    // The fast-path lambda is given the spec's own pair rather than the router's arguments,
    // because this test bed is loopback and the router therefore only ever sees 127.0.0.1 on both
    // sides. What is under test is which answer decides the route, not which addresses reach
    // `SubnetMatch` -- that has its own test below.
    fake.fetchesMedia = true
    val router = CastRouter(
      proxy,
      registry,
      allowRendererDirect = { false },
      sameSubnetFastPath = { _, _ -> SubnetMatch.sameSubnet(VPN_PHONE, HOME_SPEAKER, HOME_PREFIX) },
      proofTimeoutMs = PROOF_TIMEOUT_MS,
    )
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    // The comparison really did say no, which is what makes the rest of this test the interesting case.
    assertThat(SubnetMatch.sameSubnet(VPN_PHONE, HOME_SPEAKER, HOME_PREFIX)).isFalse
    assertThat(route.proofRequired).isTrue

    play(route.url)

    assertThat(router.confirm(route, UPSTREAM)).isSameAs(route)
  }

  @Test
  fun `a renderer that cannot reach the phone falls back to renderer-direct when that is allowed`() {
    // The proof, in the direction where it fails. Without this test the fallback is dead code, and
    // dead code that only runs on a stranger's network is the worst kind.
    fake.fetchesMedia = false
    val router = router(allowRendererDirect = true)
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    play(route.url)
    val confirmed = router.confirm(route, UPSTREAM)

    assertThat(confirmed).isInstanceOf(CastRoute.RendererDirect::class.java)
    assertThat((confirmed as CastRoute.RendererDirect).url).isEqualTo(UPSTREAM)
    // The upstream URL is the one it was given, not a remembered one: a second call with a second
    // URL must answer with that one.
    val other = router.candidate(device, OTHER_UPSTREAM, MP3) as CastRoute.Proxied
    assertThat((router.confirm(other, OTHER_UPSTREAM) as CastRoute.RendererDirect).url)
      .isEqualTo(OTHER_UPSTREAM)
  }

  @Test
  fun `a renderer that cannot reach the phone is Unroutable when direct is not allowed`() {
    // The default, and the outcome the spec does not have. Without it, a cast that cannot work
    // starts, reports success, and plays nothing.
    fake.fetchesMedia = false
    val router = router(allowRendererDirect = false)
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    play(route.url)
    val confirmed = router.confirm(route, UPSTREAM)

    assertThat(confirmed).isInstanceOf(CastRoute.Unroutable::class.java)
    val unroutable = confirmed as CastRoute.Unroutable
    assertThat(unroutable.reason).isEqualTo(UnroutableReason.PROXY_UNREACHABLE_AND_DIRECT_DISABLED)
    // The detail reaches the user. A reason enum with an empty detail would leave the picker
    // saying "something went wrong".
    assertThat(unroutable.detail).contains(device.friendlyName)
    // ...and it says what was waited for, in seconds a human reads, rather than in milliseconds
    // or in a `0` from an integer division.
    assertThat(unroutable.detail).contains("0.3 seconds")
    assertThat(unroutable.detail).contains("different network")
  }

  @Test
  fun `confirming a route revokes the proxy token when it falls back`() {
    // A token for a route nobody is using is a capability left lying on the LAN.
    fake.fetchesMedia = false
    val router = router(allowRendererDirect = true)
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    router.confirm(route, UPSTREAM)

    assertThat(registry.resolve(route.media.path)).isNull()
  }

  @Test
  fun `a confirmed route keeps its token, because the renderer is about to fetch the rest of it`() {
    // The other direction of the test above. Without it, a `confirm` that revoked unconditionally
    // is green there and cuts every cast off after its first buffer -- the renderer's second range
    // request 404s, mid-track, with nothing reported anywhere.
    fake.fetchesMedia = true
    val router = router()
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    play(route.url)
    assertThat(router.confirm(route, UPSTREAM)).isSameAs(route)

    assertThat(registry.resolve(route.media.path)).isNotNull
    // Not just present in a map: still served, over the socket, after the confirm.
    assertThat(http.exchange(URI(route.url), "HEAD").code).isEqualTo(200)
  }

  @Test
  fun `a renderer with no route from this phone is Unroutable before anything is published`() {
    // `LocalAddress.towards` returning null. Nothing should be minted, because nothing can be
    // fetched -- and a published token that is never used is a capability with no owner.
    //
    // The registry is given a random that is not random, so the path a publish WOULD have minted
    // is known exactly. Asserting that `resolve` of a guessed path is null would pass against any
    // registry at all; asserting it of the one path this registry mints, and then minting it to
    // show the assertion can fail, does not.
    val fixed = ProxyRegistry(FixedRandom(TOKEN_BYTES_VALUE))
    val expectedPath = "/media/$EXPECTED_TOKEN.mp3"
    val router = router(localAddress = { null }, registry = fixed)

    val route = router.candidate(device, UPSTREAM, MP3)

    assertThat(route).isInstanceOf(CastRoute.Unroutable::class.java)
    assertThat((route as CastRoute.Unroutable).reason).isEqualTo(UnroutableReason.NO_ROUTE_TO_RENDERER)
    assertThat(route.detail).contains(device.friendlyName)
    assertThat(fixed.resolve(expectedPath)).isNull()

    // The assertion above is not vacuous: this is the path that registry mints, and once minted it
    // resolves.
    assertThat(fixed.publish(UPSTREAM, MP3).path).isEqualTo(expectedPath)
    assertThat(fixed.resolve(expectedPath)).isNotNull
  }

  @Test
  fun `a control url with no host and one that cannot be resolved are both unroutable, and say which`() {
    // The other two ways there is no route. Both are real: `DeviceDescription` builds the control
    // URL by resolving a relative `controlURL` against whatever `LOCATION` the device announced,
    // and a device can announce something this client cannot turn into an address.
    //
    // The unresolvable case is a link-local IPv6 literal naming an interface this host does not
    // have. That is deliberate: it fails inside `InetAddress.getByName` in about a millisecond
    // with no DNS query at all, so this test needs no network and cannot hang.
    val noHost = device.copy(avTransportControlUrl = URI("http://0.0.0.0.0:1400/ctrl"))
    val unresolvable = device.copy(avTransportControlUrl = URI("http://[fe80::1%25eth99]:1400/ctrl"))

    val fromNoHost = router().candidate(noHost, UPSTREAM, MP3) as CastRoute.Unroutable
    val fromUnresolvable = router().candidate(unresolvable, UPSTREAM, MP3) as CastRoute.Unroutable
    val fromNoRoute = router(localAddress = { null }).candidate(device, UPSTREAM, MP3) as CastRoute.Unroutable

    assertThat(listOf(fromNoHost, fromUnresolvable, fromNoRoute).map { it.reason })
      .containsOnly(UnroutableReason.NO_ROUTE_TO_RENDERER)
    // Three arms, three different things said. One shared constant message would leave a bug
    // report unable to say which of the three happened, and would pass every assertion above.
    assertThat(listOf(fromNoHost, fromUnresolvable, fromNoRoute).map { it.detail }).doesNotHaveDuplicates()
    assertThat(fromNoHost.detail).contains("names no host")
    assertThat(fromUnresolvable.detail).contains("could not be resolved")
    assertThat(fromNoRoute.detail).contains("no route")
  }

  @Test
  fun `the proof waits for this renderer's own token and not for any request at all`() {
    // Two tracks published, only the second fetched. A proof that counted requests rather than
    // matching the token would confirm the wrong route -- and would confirm a route on the strength
    // of a stale request from the previous track.
    val router = router()
    val stale = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied
    val fresh = router.candidate(device, OTHER_UPSTREAM, MP3) as CastRoute.Proxied

    assertThat(http.exchange(URI(fresh.url), "GET").code).isEqualTo(200) // only the fresh one

    assertThat(router.confirm(fresh, OTHER_UPSTREAM)).isSameAs(fresh)
    assertThat(router.confirm(stale, UPSTREAM)).isInstanceOf(CastRoute.Unroutable::class.java)
  }

  @Test
  fun `confirm returns a route it did not mint unchanged, and does not wait for one`() {
    // `candidate` can answer `Unroutable`, and Task 8 calls `confirm` on whatever it got. A
    // `confirm` that waited out its timeout on those would add the full proof delay to a failure
    // that is already known, on every track.
    val unroutable = CastRoute.Unroutable(UnroutableReason.NO_ROUTE_TO_RENDERER, "already decided")
    val direct = CastRoute.RendererDirect(UPSTREAM)
    val router = router(proofTimeoutMs = 5_000L)

    val elapsed = measureTimeMillis {
      assertThat(router.confirm(unroutable, UPSTREAM)).isSameAs(unroutable)
      assertThat(router.confirm(direct, UPSTREAM)).isSameAs(direct)
    }

    assertThat(elapsed).isLessThan(1_000L)
  }

  // ---- the fast path ---------------------------------------------------------------------------

  @Test
  fun `the fast path skips the proof for a renderer on this phone's own subnet`() {
    // Measured, not asserted by inspection: with `fetchesMedia` off and the fast path engaged, a
    // confirm must return promptly and still be `Proxied`. Without a fast path this call would
    // block for the whole proof timeout before answering.
    fake.fetchesMedia = false
    val router = router(proofTimeoutMs = 5_000L, sameSubnet = true)
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    assertThat(route.proofRequired).isFalse

    val elapsed = measureTimeMillis { assertThat(router.confirm(route, UPSTREAM)).isSameAs(route) }

    assertThat(elapsed).isLessThan(1_000L)
  }

  @Test
  fun `the fast path is not taken when the renderer is on another subnet`() {
    // The other direction, so `sameSubnet` cannot be hardcoded true -- which would disable the
    // proof entirely and reinstate the silent failure this whole task exists to remove.
    fake.fetchesMedia = false
    val router = router(sameSubnet = false)
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    assertThat(route.proofRequired).isTrue
    assertThat(router.confirm(route, UPSTREAM)).isInstanceOf(CastRoute.Unroutable::class.java)
  }

  @Test
  fun `the fast path is asked about this phone's address and the renderer's, in that order`() {
    // The arguments, not just the answer. `sameSubnetFastPath(renderer, renderer)` -- or
    // `(phone, phone)` -- is `true` for every device on earth, which switches the proof off
    // globally while looking exactly like a working optimisation. Nothing else in this file could
    // see that: both addresses are 127.0.0.1 on a loopback test bed, so the seam supplies a phone
    // address that differs from the renderer's.
    val seen = mutableListOf<Pair<InetAddress, InetAddress>>()
    val phone = InetAddress.getByName("10.8.0.3")
    val router = CastRouter(
      proxy,
      registry,
      allowRendererDirect = { false },
      localAddress = { phone },
      sameSubnetFastPath = { a, b -> seen += a to b; false },
      proofTimeoutMs = PROOF_TIMEOUT_MS,
    )

    router.candidate(device, UPSTREAM, MP3)

    assertThat(seen).containsExactly(phone to InetAddress.getByName("127.0.0.1"))
  }

  // ---- what renderer-direct actually hands a speaker -------------------------------------------

  @Test
  fun `a renderer-direct url states no format on its path, and a strict renderer refuses it`() {
    // FOUND, NOT FIXED, and measured rather than argued. A Subsonic stream URL is
    // `/rest/stream?id=...`: its PATH carries no file extension, and spec section 6 records that
    // Sonos infers the MIME type from the URL rather than from `Content-Type`. So the fallback
    // this task's `allowRendererDirect` branch produces is a fallback for renderers that read
    // `protocolInfo`, not for the one brand the spec names.
    fake.fetchesMedia = false
    val router = router(allowRendererDirect = true)
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied
    val direct = router.confirm(route, UPSTREAM) as CastRoute.RendererDirect

    // The URL leg, read the way `MimeAgreement` models a renderer reading it: the path, not the
    // string. Both spellings answer the same, including the one `SubsonicClient.streamUrl` really
    // mints -- whose `v=1.16.1` puts a dot in the QUERY, which is not an extension.
    assertThat(MimeAgreement.extensionOfUrl(direct.url)).isNull()
    assertThat(MimeAgreement.extensionOfUrl(FULL_SHAPED_STREAM_URL)).isNull()
    // ...and the proxied URL it replaced does state one, so this is a discrimination.
    assertThat(MimeAgreement.extensionOfUrl(route.url)).isEqualTo("mp3")

    // The consequence, on the strictest renderer this project can run: `714 Illegal MIME-type`.
    val refusal = assertThrows<UpnpErrorException> { runBlocking { upnp.setUri(castItem(direct.url)) } }
    assertThat(refusal.fault.errorCode).isEqualTo(UpnpError.ILLEGAL_MIME_TYPE)
  }

  // ---- the shipped defaults ---------------------------------------------------------------------

  @Test
  fun `a router built with nothing but its defaults proves the route and does not fall back`() {
    // The constructor as production uses it: `localAddress` defaulting to `LocalAddress.towards`,
    // no fast path at all, and the shipped six-second proof. Every other test in this file
    // substitutes at least one of those, so without this one the defaults are untested wiring.
    fake.fetchesMedia = true
    val router = CastRouter(proxy, registry, allowRendererDirect = { false })
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    assertThat(route.url).startsWith("http://127.0.0.1:${proxy.port}/media/")
    assertThat(route.proofRequired).isTrue

    play(route.url)

    assertThat(router.confirm(route, UPSTREAM)).isSameAs(route)
    assertThat(CastRouter.DEFAULT_PROOF_TIMEOUT_MS).isEqualTo(6_000L)
  }

  @Test
  fun `revokeAll drops every token, so nothing is served after the session that published it`() {
    val router = router()
    val first = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied
    val second = router.candidate(device, OTHER_UPSTREAM, MP3) as CastRoute.Proxied
    assertThat(http.exchange(URI(first.url), "HEAD").code).isEqualTo(200)

    router.revokeAll()

    assertThat(registry.resolve(first.media.path)).isNull()
    assertThat(registry.resolve(second.media.path)).isNull()
    assertThat(http.exchange(URI(first.url), "HEAD").code).isEqualTo(404)
  }

  // ---- the setting, read late -------------------------------------------------------------------

  @Test
  fun `the renderer-direct setting is read when the fallback is taken, not when the router is built`() {
    // Plan 6 Task 12's central wiring decision, as an executed observation.
    //
    // `CastRouter` is a `@Singleton`. A `Boolean` parameter would be resolved once, the first time
    // anything in the app needed a router, and a user who turned the switch on and cast a second
    // later would get the answer from before they touched it -- silently, with the failure
    // message telling them about a setting they had already changed. That defect passes every
    // other test in this file.
    //
    // Observed by counting, not by trusting: the router is constructed, nothing is read yet, and
    // the first read happens inside `confirm`.
    fake.fetchesMedia = false
    var reads = 0
    var allowed = false
    val router = CastRouter(
      proxy,
      registry,
      allowRendererDirect = { reads++; allowed },
      proofTimeoutMs = PROOF_TIMEOUT_MS,
    )

    // Constructing and minting a candidate must not consult the setting at all: `candidate` runs
    // before `SetAVTransportURI`, and a route is proxied regardless of what this setting says.
    val first = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied
    assertThat(reads).isZero

    play(first.url)
    assertThat(router.confirm(first, UPSTREAM)).isInstanceOf(CastRoute.Unroutable::class.java)
    assertThat(reads).isEqualTo(1)

    // The user changes their mind, on the same router object, and the very next cast obeys.
    allowed = true
    val second = router.candidate(device, OTHER_UPSTREAM, MP3) as CastRoute.Proxied
    play(second.url)

    assertThat(router.confirm(second, OTHER_UPSTREAM))
      .isEqualTo(CastRoute.RendererDirect(OTHER_UPSTREAM))
    assertThat(reads).isEqualTo(2)
  }

  @Test
  fun `a route that never needed a proof does not consult the setting either`() {
    // The other direction, and it is about a capability rather than tidiness: reading the setting
    // is a disk read behind a `runBlocking` in production (`MediaModule.provideRendererDirectPolicy`).
    // A `confirm` that consulted it on every call would pay that on the fast path -- the one that
    // exists precisely so the ordinary cast does not wait for anything.
    var reads = 0
    val router = CastRouter(
      proxy,
      registry,
      allowRendererDirect = { reads++; true },
      sameSubnetFastPath = { _, _ -> true },
      proofTimeoutMs = PROOF_TIMEOUT_MS,
    )
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied
    assertThat(route.proofRequired).isFalse

    assertThat(router.confirm(route, UPSTREAM)).isSameAs(route)

    assertThat(reads).isZero
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun router(
    allowRendererDirect: Boolean = false,
    proofTimeoutMs: Long = PROOF_TIMEOUT_MS,
    sameSubnet: Boolean = false,
    localAddress: (InetAddress) -> InetAddress? = LocalAddress::towards,
    registry: ProxyRegistry = this.registry,
  ) = CastRouter(
    proxy,
    registry,
    // The parameter is a lambda as of Plan 6 Task 12 -- read inside `confirm`, because the value
    // behind it is a setting a user can change between one cast and the next. Every call site in
    // this file still passes a plain `Boolean`, so every assertion Task 7 wrote keeps its meaning;
    // the two tests that care that it is *read late* rather than captured build their own router.
    { allowRendererDirect },
    localAddress,
    { _, _ -> sameSubnet },
    proofTimeoutMs,
  )

  private fun castItem(url: String) = CastItem(
    mediaId = "track-1",
    title = "Test Track",
    artist = "Test Artist",
    albumTitle = "Test Album",
    artworkUri = null,
    durationMs = 300_000L,
    upnpClass = DidlLite.CLASS_MUSIC_TRACK,
    resourceUrl = url,
    served = MP3,
  )

  /** `SetAVTransportURI` then `Play`, which is the moment a renderer that can reach the phone does. */
  private fun play(url: String) = runBlocking {
    upnp.setUri(castItem(url))
    upnp.play()
  }

  /**
   * Bounded wait for the renderer's fetches to land in the proxy's log.
   *
   * The fake fetches on its own thread, exactly as a real renderer fetches on its own schedule, so
   * `awaitRequest` returning after the `HEAD` says nothing yet about the `GET`. A bounded poll that
   * fails loudly with what it did see, rather than a sleep that hopes.
   */
  private fun awaitProxyMethods(vararg methods: String) {
    val deadline = System.nanoTime() + AWAIT_TIMEOUT_MS * 1_000_000L
    while (System.nanoTime() < deadline) {
      if (proxy.requestLog.map { it.method }.containsAll(methods.toList())) return
      Thread.sleep(POLL_MS)
    }
    throw AssertionError("the proxy never saw ${methods.toList()}; it saw ${proxy.requestLog}")
  }

  /** Serves whatever range it is asked for, out of one fixed body. */
  private class ConstantUpstream(private val content: ByteArray) : ProxyUpstream {
    override fun totalLength(url: String): Long = content.size.toLong()

    override fun open(url: String, range: ByteRange): InputStream =
      content.copyOfRange(range.firstByte.toInt(), range.lastByte.toInt() + 1).inputStream()
  }

  /** A `SecureRandom` that is not random, so a minted path is known before it is minted. */
  private class FixedRandom(private val bytes: ByteArray) : SecureRandom() {
    override fun nextBytes(target: ByteArray) {
      bytes.copyInto(target)
    }
  }

  private companion object {
    val MP3: ServedMedia = ServedMedia.of("mp3", StreamFormat.Raw)

    const val UPSTREAM = "https://nav.example/rest/stream?id=1&format=raw"
    const val OTHER_UPSTREAM = "https://nav.example/rest/stream?id=2&format=raw"

    /**
     * The shape `SubsonicClient.streamUrl` really mints, minus its authentication parameters --
     * which are deliberately not written down even as fabrications, because a stream URL carrying
     * `u`, `t` and `s` must never be committed. What is under test here is `v=1.16.1`: a dot in
     * the query string that is not, and must not read as, a file extension.
     */
    const val FULL_SHAPED_STREAM_URL =
      "https://nav.example/rest/stream?id=1&format=raw&v=1.16.1&c=MuPlay&f=json"

    /** Short enough that a failing proof costs a third of a second, long enough not to be racy. */
    const val PROOF_TIMEOUT_MS = 300L

    const val AWAIT_TIMEOUT_MS = 5_000L
    const val POLL_MS = 10L

    /** Spec section 6's row 3, as two addresses. Neither prefix a home network uses makes them equal. */
    val VPN_PHONE: InetAddress = InetAddress.getByName("10.8.0.3")
    val HOME_SPEAKER: InetAddress = InetAddress.getByName("192.168.1.50")
    const val HOME_PREFIX = 24

    /** Not a constant body: a slice of this is checkable, and a slice of `ByteArray(64)` is not. */
    val CONTENT: ByteArray = ByteArray(1_000) { (it % 251).toByte() }

    val TOKEN_BYTES_VALUE: ByteArray =
      ByteArray(ProxyRegistry.TOKEN_BYTES) { 0 }.also { it[0] = 0xAB.toByte(); it[1] = 0x0C }
    val EXPECTED_TOKEN: String = "ab0c" + "0".repeat((ProxyRegistry.TOKEN_BYTES - 2) * 2)
  }
}
