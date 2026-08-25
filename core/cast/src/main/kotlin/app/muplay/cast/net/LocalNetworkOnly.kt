package app.muplay.cast.net

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Thrown when a cast connection would leave the local network. */
class NonLocalAddressException(host: String, address: InetAddress) : IOException(
  "refusing to open a cleartext connection to $host ($address): MuPlay speaks plain HTTP only " +
    "to devices on the local network. See LocalNetworkOnly's documentation for why this rule " +
    "lives in code rather than in the manifest.",
)

/**
 * **The rule that lets MuPlay cast without weakening the app's cleartext posture.**
 *
 * Renderers have no TLS and never will. Talking to one means plain HTTP. The project's constraint
 * is that cleartext is debug-only and must never reach the release manifest, enforced by
 * `verifyReleaseManifest`. Those two facts collide, and the collision has exactly one honest
 * resolution.
 *
 * `android:usesCleartextTraffic` is **host-blind**: it is one boolean for the whole process, so
 * turning it on to reach a speaker also turns it on for Navidrome, which is the one host the
 * constraint exists to protect. A release `network_security_config.xml` cannot help either -- the
 * format takes `<domain>` entries (host names and IP literals) and has **no way to express a
 * subnet**, so "RFC 1918 only" is unwritable in it. Both mechanisms are therefore strictly weaker
 * than the rule the project wants.
 *
 * This is that rule, stated once, in code, with a test that observes it refusing as well as
 * permitting. The cast control client ([app.muplay.cast.http.CastHttpClient]) is a plain
 * `java.net.Socket`, so it never consults `NetworkSecurityPolicy` -- and that is not a loophole
 * being exploited but the point: the platform's switch cannot express the requirement, so the
 * requirement is enforced where it can be. Everything MuPlay sends to **Navidrome** still goes
 * through OkHttp, which does consult the policy, and the release manifest still permits it nothing
 * in cleartext.
 *
 * ### The rule has two directions, and the inbound one is the dangerous half
 *
 * `NetworkSecurityPolicy` governs outbound connections made by the platform's HTTP stacks and has
 * no mechanism to affect a `ServerSocket` -- so the platform offers the proxy's listening socket
 * nothing, which is *not* the same as the listening socket needing nothing. (This KDoc said the
 * latter until the review that added [acceptLocal]. It was true about the platform and wrong as
 * guidance, and the next author it steered was Task 6's.)
 *
 * Task 6 binds a `ServerSocket` that serves **Navidrome-authenticated audio**. A bound socket is
 * reachable by every device on the LAN -- coffee-shop Wi-Fi included -- and by every other app on
 * the phone. So the inbound direction gets its own guard: [acceptLocal] is the accept call the
 * proxy makes, and it hands back only connections from an address [isLocal] admits. It is not the
 * whole of the proxy's authorisation story (a capability token in the URL is Task 6's own job, and
 * a hostile app on the same phone connects from loopback, which is local by construction) -- it is
 * the network-shaped half of it, in the same place as its outbound twin so neither can be found
 * without the other.
 *
 * Local means, exactly:
 *
 * | Range | Why |
 * |---|---|
 * | `127.0.0.0/8`, `::1` | the in-process renderer of the Tier 1 suite, and `adb reverse` |
 * | `10/8`, `172.16/12`, `192.168/16` | RFC 1918 -- an ordinary home or office LAN |
 * | `169.254/16`, `fe80::/10` | link-local: a renderer that never got a DHCP lease |
 * | `100.64/10` | RFC 6598 CGNAT -- **what Tailscale hands out**, and spec section 6's VPN row |
 * | `fc00::/7` | IPv6 unique local addresses |
 */
object LocalNetworkOnly {

  fun isLocal(address: InetAddress): Boolean = when (address) {
    is Inet4Address -> isLocalIpv4(address)
    is Inet6Address -> address.isLoopbackAddress || address.isLinkLocalAddress || isUniqueLocalIpv6(address)
    else -> false
  }

  fun require(host: String, address: InetAddress) {
    if (!isLocal(address)) throw NonLocalAddressException(host, address)
  }

  /**
   * True when [socket]'s peer is on the local network.
   *
   * A socket with no peer address -- one already closed by the time it was asked -- is **not**
   * local. That direction is the fail-closed one, and it is the case a `?: true` would get
   * backwards while every ordinary test stayed green.
   */
  fun isLocalPeer(socket: Socket): Boolean {
    val peer = socket.inetAddress ?: return false
    return isLocal(peer)
  }

  /**
   * Accepts one connection from [server] and returns it **only** if the peer is on the local
   * network. A peer that is not is closed, and `null` comes back instead.
   *
   * `null` rather than an exception because the caller is an accept loop serving audio: one
   * connection from somewhere it should not be is a thing to drop and carry on from, not a reason
   * to stop serving the renderer that is legitimately playing. The closing is the point -- a
   * refused connection that stays open is a file descriptor a peer can hold as many of as it
   * likes.
   *
   * The `ServerSocket` itself should still be bound to the interface the renderer is on rather
   * than to the wildcard, which is Task 6's decision to make with [LocalAddress]; this is the
   * check that holds whatever it binds to the same rule as the outbound side.
   */
  fun acceptLocal(server: ServerSocket): Socket? {
    val socket = server.accept()
    if (isLocalPeer(socket)) return socket
    socket.close()
    return null
  }

  private fun isLocalIpv4(address: Inet4Address): Boolean {
    if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
      return true
    }
    // RFC 6598, 100.64.0.0/10. `isSiteLocalAddress()` says false for the whole block, and this is
    // the block a routed VPN most often puts the phone in.
    val bytes = address.address
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    return first == 100 && second in 64..127
  }

  /** `fc00::/7`: the top seven bits are `1111110`. `isSiteLocalAddress()` covers only `fec0::/10`. */
  private fun isUniqueLocalIpv6(address: Inet6Address): Boolean =
    (address.address[0].toInt() and 0xFE) == 0xFC
}
