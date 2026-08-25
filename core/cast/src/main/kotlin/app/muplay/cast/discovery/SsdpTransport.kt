package app.muplay.cast.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends M-SEARCH datagrams and collects the replies.
 *
 * A seam, so that [RendererDirectory] can be tested against a transport that answers from a list,
 * while [DatagramSsdpTransport] is exercised for real against a loopback responder.
 *
 * **No `MulticastLock` and no `CHANGE_WIFI_MULTICAST_STATE`, deliberately.** That lock stops Wi-Fi
 * hardware filtering *incoming* multicast frames. MuPlay sends multicast (outbound, unaffected)
 * and receives **unicast** replies -- UPnP requires an M-SEARCH response to be sent by unicast UDP
 * back to the request's source address and port. What the lock would additionally buy is
 * unsolicited `NOTIFY ssdp:alive`/`ssdp:byebye` announcements on the group, i.e. passive
 * discovery. This plan searches when the picker opens and does not use them. Adding a
 * permission the code does not depend on is a cost with no benefit -- it is visible on the store
 * listing -- and it teaches the next reader that the code depends on it. If a later plan wants
 * passive discovery, the permission is what it has to add, and this paragraph is why.
 */
interface SsdpTransport {
  suspend fun search(
    destination: InetSocketAddress,
    targets: List<String>,
    mxSeconds: Int?,
    listenWindowMs: Long,
  ): List<SsdpResponse>
}

/**
 * The real transport: one ephemeral UDP socket, one datagram per search target, then read until
 * the window closes.
 *
 * One socket for every target, not one each, because replies come back to the socket's source
 * port and a second socket would need a second listen window -- doubling the time the picker
 * spends empty for no extra information.
 */
class DatagramSsdpTransport : SsdpTransport {

  override suspend fun search(
    destination: InetSocketAddress,
    targets: List<String>,
    mxSeconds: Int?,
    listenWindowMs: Long,
  ): List<SsdpResponse> = withContext(Dispatchers.IO) {
    val responses = ArrayList<SsdpResponse>()
    DatagramSocket().use { socket ->
      socket.soTimeout = SOCKET_POLL_MS
      targets.forEach { target ->
        val payload = SsdpSearch.request(
          host = destination.address.hostAddress,
          port = destination.port,
          searchTarget = target,
          mxSeconds = mxSeconds,
        )
        // A send that fails -- no route to the group on this interface, most often -- must not
        // stop the other targets or the listen window. A multi-homed phone has interfaces that
        // cannot carry this, and that is the ordinary case rather than an error.
        runCatching { socket.send(DatagramPacket(payload, payload.size, destination)) }
      }

      val deadline = System.nanoTime() + listenWindowMs * NANOS_PER_MILLI
      val buffer = ByteArray(MAX_DATAGRAM_BYTES)
      while (System.nanoTime() < deadline) {
        val packet = DatagramPacket(buffer, buffer.size)
        val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
        if (!received) continue
        // A datagram that exactly fills the buffer is dropped, and this is the one place in the
        // module where "probably fine" is not good enough. `recvfrom` truncates **silently**:
        // there is no flag, no exception and no short-read signal, and a datagram that arrived
        // truncated is byte-identical to one that happened to be exactly this long. The header
        // parser cannot help either -- it is deliberately tolerant of a block that ends without
        // its blank line, because a datagram *is* the whole message, so a `LOCATION` clipped at
        // the buffer boundary parses as a real, shorter URL and this app then fetches a device
        // description from somewhere else entirely, with nothing logged anywhere.
        //
        // The socket readers reject a truncated head; a datagram has no equivalent and cannot
        // have one. Dropping the suspect packet is what replaces it. Nothing is lost in practice:
        // a real M-SEARCH reply is a few hundred bytes (see MAX_DATAGRAM_BYTES), so a 4 KiB
        // datagram is already a device misbehaving.
        if (packet.length == buffer.size) continue
        val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
        SsdpSearch.parseResponse(text, packet.address)?.let(responses::add)
      }
    }
    responses
  }

  companion object {
    /**
     * The receive buffer, and therefore the largest datagram this transport will accept.
     *
     * SSDP replies are a few hundred bytes; 4 KiB is generous and bounds the read. **Do not make
     * this smaller.** It is not only a memory bound: the datagram parser treats the packet as the
     * whole message, so anything the kernel had to truncate to fit here would be parsed as a
     * complete -- and wrong -- reply. The read loop refuses a datagram that exactly fills this
     * buffer for that reason, so shrinking it turns working speakers into invisible ones rather
     * than into a visible error.
     */
    private const val MAX_DATAGRAM_BYTES = 4096

    /** Short enough that the listen window is honoured to within a fifth of a second. */
    private const val SOCKET_POLL_MS = 200

    private const val NANOS_PER_MILLI = 1_000_000L

    /**
     * One multicast endpoint per interface that could plausibly carry it.
     *
     * A phone is multi-homed: Wi-Fi, possibly cellular, possibly a VPN. Sending from a single
     * unbound socket lets the kernel pick by the multicast route, which on Android is not reliably
     * the interface the speaker is on. Sending once per usable interface costs a handful of
     * datagrams and removes the guess.
     *
     * **Returning the same endpoint N times is intentional and looks wrong.** The datagram is
     * addressed to the group; what differs between sends is the *source interface* the kernel
     * picks, which a `DatagramSocket` chooses per send. Do not `distinct()` this -- that is the
     * obvious simplification, and it silently removes the multi-homing behaviour. If a later plan
     * needs a genuine per-interface bind, the shape to reach for is
     * `MulticastSocket.setNetworkInterface`, and it should arrive with a test that can observe the
     * difference, which this repository's CI cannot.
     *
     * Loopback is excluded here and supplied explicitly by the tests, so that a device on the
     * machine's own loopback never appears in a real user's picker.
     */
    fun multicastDestinations(): List<InetSocketAddress> =
      NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        .map { SsdpSearch.MULTICAST_ENDPOINT }
        .ifEmpty { listOf(SsdpSearch.MULTICAST_ENDPOINT) }
  }
}
