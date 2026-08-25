package app.muplay.cast.net

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The address of *this* machine that a given peer would see traffic arrive from.
 *
 * Needed because the proxy has to advertise a URL the renderer can actually fetch, and on a phone
 * "the local address" is not one thing: there is a Wi-Fi address, possibly a cellular one, and --
 * in spec section 6's third routing situation -- a VPN one. Handing a speaker on the home LAN the
 * phone's cellular address produces a cast that appears to start and plays nothing.
 *
 * The technique is to **ask the kernel**: `connect` an unbound UDP socket to the peer and read the
 * local address it chose. No packet is sent (a connected `DatagramSocket` only fixes the peer and
 * selects a route), and the answer is by construction the source address of the route to that
 * peer -- which is right for Wi-Fi, right for a VPN tunnel, and right for loopback.
 *
 * The alternative, enumerating `NetworkInterface`s and picking the first non-loopback IPv4
 * address, is what most implementations do and it is wrong on a multi-homed device: it picks by
 * enumeration order, which has nothing to do with which interface routes to the speaker.
 *
 * Returns `null` when no route exists, which is a real answer the router (Task 7) turns into a
 * named failure rather than an exception.
 */
object LocalAddress {

  /** The UDP port is irrelevant -- no datagram is sent -- but it must be a legal one. */
  private const val ROUTE_PROBE_PORT = 1900

  fun towards(peer: InetAddress): InetAddress? =
    runCatching {
      DatagramSocket().use { socket ->
        socket.connect(InetSocketAddress(peer, ROUTE_PROBE_PORT))
        socket.localAddress.takeUnless { it.isAnyLocalAddress }
      }
    }.getOrNull()
}
