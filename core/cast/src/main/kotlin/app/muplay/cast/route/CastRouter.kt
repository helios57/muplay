package app.muplay.cast.route

import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.net.LocalAddress
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.ProxyRegistry
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * **Where a renderer is told to fetch from, and how that is established.**
 *
 * Spec section 6 words the rule as *"Same subnet as the speaker -> stream through the phone proxy.
 * Otherwise -> the speaker fetches Navidrome directly"* -- and then gives three situations, **all
 * three of which answer "proxy"**, including "Remote + VPN ... proxy over the tunnel".
 *
 * Row 3 is routinely not subnet-equal. A routed VPN -- WireGuard, Tailscale, OpenVPN in `tun` mode,
 * which is how essentially everyone builds this -- puts the phone on `10.8.0.0/24` or
 * `100.64.0.0/10` and *routes* the home LAN over it. Phone `10.8.0.3`, speaker `192.168.1.50`:
 * different subnets, tunnel routes both ways, proxy works. Applied literally, the rule sends that
 * case down the "otherwise" branch, which is the opposite of what the spec's own table says.
 *
 * Subnet equality is neither necessary (a routed tunnel) nor sufficient (client isolation on a
 * guest network) for the question the rule is really asking, which is:
 *
 * > **can the renderer open a TCP connection back to this phone?**
 *
 * That question has an exact answer and it costs nothing to get, because the answer is a side
 * effect of something that must happen anyway: after `SetAVTransportURI` and `Play`, a renderer
 * that is going to play **fetches**, within a second or two. [MediaProxyServer.awaitRequest]
 * watches for it. Silence is the answer, and it is the only reliable one.
 *
 * [SubnetMatch] is still used, as a **fast path**, so the ordinary case does not wait out a
 * timeout before anything happens. It is an optimisation with a name, not the rule -- and it is an
 * optimisation that can be *wrong* in exactly one direction: a guest network with client isolation
 * is subnet-equal and unreachable, and taking the fast path there reinstates the silent failure
 * this class exists to remove. That is the price of not waiting, it is paid only where the phone
 * and the speaker really do share a prefix, and it is why the parameter defaults to "never".
 *
 * ### What this needs, and what it does not
 *
 * Spec section 6's other detection sentence holds more strongly than before: *"detection is a
 * subnet comparison, not SSID sniffing -- SSID needs `ACCESS_FINE_LOCATION` and fails silently
 * without it"*. Nothing here reads an SSID and nothing here reads a location. The reachability
 * rule needs strictly **less** than the subnet comparison did: no `ConnectivityManager`, no
 * `LinkProperties`, no permission of any kind -- only a socket that either got a request or did
 * not. That is why `:core:cast` can be a pure-JVM module at all.
 *
 * ### Why [app.muplay.cast.didl.MimeAgreement] is not called from here
 *
 * This class mints the proxy URL, so it is the obvious place to check that the format promised to
 * the renderer is the format it will be served. It is the wrong one, for a reason that is
 * structural rather than a preference:
 *
 * - `MimeAgreement.require` re-derives its three legs from **the rendered DIDL document** and the
 *   `Content-Type` string. This class renders no DIDL and constructs no
 *   [app.muplay.cast.didl.CastItem] -- it has no title, artist or duration to put in one. Calling
 *   `require` here would mean inventing a document nobody sends and then checking it, which is the
 *   compare-an-object-with-itself defect `MimeAgreement`'s own documentation names.
 * - For a [CastRoute.Proxied] route the URL leg is not an independent statement at all. The path
 *   is `ProxyRegistry.PATH_PREFIX + served.fileName(token)`, so the extension the URL carries
 *   **is** `served.fileExtension`, one expression evaluated once. There is nothing here for a
 *   three-way check to disagree about.
 * - For a [CastRoute.RendererDirect] route the check would fire, and it fires for a defect this
 *   class cannot fix: a Subsonic stream URL has no extension at all. That is recorded on
 *   [CastRoute.RendererDirect] and measured in `CastRouterTest` against the strict `FakeRenderer`.
 *
 * The three-way check belongs where the document exists, which is the `SetAVTransportURI` path.
 *
 * @param allowRendererDirect whether the user has said a speaker may be handed the Navidrome URL
 *   itself. **A lambda, and read inside [confirm] rather than captured at construction.** The
 *   value behind it is a stored setting a user can change at any moment (`CastSettings` in
 *   `:core:database`, surfaced by `:feature:castpicker`'s `RendererDirectSection`), and this class
 *   is a `@Singleton`: a `Boolean` parameter would be resolved once, when the object graph first
 *   needed a router, so a user who turned the switch on and immediately cast would get the *old*
 *   answer with nothing to explain why. That is the silent-wrong-answer shape this whole class was
 *   written against, and a `@Singleton` is exactly how it gets reintroduced.
 *
 *   A lambda, not a `Flow` or a `suspend` call: this module carries no Android type and no
 *   coroutine on its routing path by design, and `confirm` is called from a `Player`'s load path
 *   which cannot suspend. Whatever supplies the lambda owns being current -- see
 *   `MediaModule.provideRendererDirectPolicy`.
 *
 * @param localAddress which of this phone's addresses routes to the renderer. A seam, because
 *   [LocalAddress.towards] answers `127.0.0.1` for every loopback peer and the interesting cases
 *   -- a VPN address, and no route at all -- are not producible on a loopback-only host.
 * @param sameSubnetFastPath the [SubnetMatch] fast path, called with **this phone's** address and
 *   **the renderer's**, in that order. Defaults to "never", so every route is proved. Wiring it
 *   needs the renderer's prefix length, which on Android comes from
 *   `ConnectivityManager.getLinkProperties(...).linkAddresses[].prefixLength` -- an Android type,
 *   and this module has none by design. Task 9 supplies it from `:core:media` as a lambda.
 */
class CastRouter(
  private val proxy: MediaProxyServer,
  private val registry: ProxyRegistry,
  private val allowRendererDirect: () -> Boolean,
  private val localAddress: (InetAddress) -> InetAddress? = LocalAddress::towards,
  private val sameSubnetFastPath: (InetAddress, InetAddress) -> Boolean = { _, _ -> false },
  private val proofTimeoutMs: Long = DEFAULT_PROOF_TIMEOUT_MS,
) {

  /**
   * Publishes the item and returns the route to try first.
   *
   * Nothing is published when there is no route to the renderer at all: a token nobody can fetch is
   * a capability lying on the network with no owner. Every `return` below the publish therefore
   * has to stay below it, and the order is asserted rather than commented -- see
   * `a renderer with no route from this phone is Unroutable before anything is published`.
   */
  fun candidate(device: CastDevice, upstreamUrl: String, served: ServedMedia): CastRoute {
    val rendererHost = device.avTransportControlUrl.host
      ?: return unroutable(device, UnroutableReason.NO_ROUTE_TO_RENDERER, "its control URL names no host")
    val rendererAddress = runCatching { InetAddress.getByName(rendererHost) }.getOrNull()
      ?: return unroutable(device, UnroutableReason.NO_ROUTE_TO_RENDERER, "its address could not be resolved")
    val phoneAddress = localAddress(rendererAddress)
      ?: return unroutable(device, UnroutableReason.NO_ROUTE_TO_RENDERER, "this phone has no route to it")

    val media = registry.publish(upstreamUrl, served)
    return CastRoute.Proxied(
      url = proxy.urlFor(media, urlHost(phoneAddress)),
      media = media,
      deviceName = device.friendlyName,
      // Asked with the phone's address first and the renderer's second. Passing either one twice
      // would make `SubnetMatch` answer `true` for every device on earth, which switches the proof
      // off for all of them and looks exactly like a working fast path.
      proofRequired = !sameSubnetFastPath(phoneAddress, rendererAddress),
    )
  }

  /**
   * Waits for the renderer to prove it can reach the proxy, and falls back if it does not.
   *
   * Call **after** `SetAVTransportURI` and `Play`. The wait is on **this route's own token**, not
   * on "any request": a proxy that had served the previous track would otherwise confirm a route
   * that has never been fetched.
   *
   * A route this class did not mint -- an [CastRoute.Unroutable] from [candidate], or a
   * [CastRoute.RendererDirect] being re-confirmed -- comes back unchanged. There is nothing to
   * wait for and nothing to revoke.
   */
  fun confirm(route: CastRoute, upstreamUrl: String): CastRoute {
    if (route !is CastRoute.Proxied) return route
    if (!route.proofRequired) return route
    if (proxy.awaitRequest(route.media.token, proofTimeoutMs)) return route

    // It did not fetch. Whatever happens next, this token is no longer wanted.
    registry.revoke(route.media.token)

    return if (allowRendererDirect()) {
      CastRoute.RendererDirect(upstreamUrl)
    } else {
      CastRoute.Unroutable(
        UnroutableReason.PROXY_UNREACHABLE_AND_DIRECT_DISABLED,
        "${route.deviceName} did not fetch anything from this phone within " +
          "${seconds(proofTimeoutMs)} seconds, so it cannot reach it. It is probably on a " +
          "different network, or the network blocks devices from talking to each other.",
      )
    }
  }

  /**
   * Drops every published token.
   *
   * Called when a session ends (`UpnpPlayer` on release, and the session manager on handover
   * back). A proxy still serving after the session that published it has gone is a capability
   * lying on the LAN with nobody watching it.
   */
  fun revokeAll() = registry.revokeAll()

  private fun unroutable(device: CastDevice, reason: UnroutableReason, why: String) =
    CastRoute.Unroutable(reason, "${device.friendlyName} cannot be reached: $why.")

  companion object {
    /**
     * How long to wait for the renderer's first fetch.
     *
     * A Sonos fetches within a second of `Play`; six seconds covers a slow renderer and a busy
     * network without leaving the user watching a spinner. The fast path means the ordinary case
     * never waits at all.
     */
    const val DEFAULT_PROOF_TIMEOUT_MS: Long = 6_000L

    /**
     * The host part of a URL, from an address.
     *
     * Two things `InetAddress.hostAddress` gets wrong for this job, both of which produce a URL
     * that parses to something else or not at all -- i.e. a cast that starts and plays nothing:
     *
     * - **IPv6 needs brackets.** `fd00::1` renders as `fd00:0:0:0:0:0:0:1`, and
     *   `http://fd00:0:...:1:8080/media/x.mp3` is not a URL with that host and that port; a
     *   renderer's parser reads the first colon as the scheme separator's neighbour and gives up.
     * - **A scope id is local to this machine.** A link-local address arrives as `fe80::1%wlan0`,
     *   and `wlan0` means nothing on the speaker. It is dropped rather than sent.
     */
    internal fun urlHost(address: InetAddress): String {
      val literal = address.hostAddress.substringBefore('%')
      return if (address is Inet6Address) "[$literal]" else literal
    }

    /** No branch and no rounding to zero: a 300 ms timeout reads "0.3", not "0". */
    private fun seconds(millis: Long): String = "%.1f".format(Locale.ROOT, millis / 1000.0)
  }
}
