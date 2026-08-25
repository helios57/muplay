package app.muplay.cast.discovery

import app.muplay.cast.http.HttpWire
import app.muplay.cast.net.LocalNetworkOnly
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/** One device's answer to one M-SEARCH. */
data class SsdpResponse(
  val location: URI,
  val searchTarget: String,
  val usn: String,
  val server: String?,
  val from: InetAddress,
) {
  /**
   * The device's unique identity, which is the `uuid:` half of the USN.
   *
   * A device answers **once per search target it matches**, so one Sonos answers a search for both
   * `MediaRenderer:1` and `ZonePlayer:1` twice, with two `ST` values, two `USN` values and one
   * `LOCATION`. This is what collapses those into one picker entry.
   */
  val udn: String get() = usn.substringBefore("::")
}

/**
 * The SSDP M-SEARCH datagram and its reply.
 *
 * SSDP is HTTP's syntax over UDP: the request is an HTTP request line plus headers, and the reply
 * is an HTTP status line plus headers, in a datagram. That is why [app.muplay.cast.http.HttpWire]
 * exists as a codec rather than as a server -- no HTTP library will parse a datagram, so this is
 * the third consumer that made writing it worthwhile.
 */
object SsdpSearch {

  const val MULTICAST_IPV4: String = "239.255.255.250"
  const val PORT: Int = 1900

  /** The generic DLNA renderer. Sonos answers this too. */
  const val TARGET_MEDIA_RENDERER: String = "urn:schemas-upnp-org:device:MediaRenderer:1"

  /**
   * Sonos's own root device type. Searched **in addition** because some firmware answers a
   * `ZonePlayer` search more reliably than a `MediaRenderer` one, and because the answer identifies
   * the device as a Sonos before its description has been fetched.
   */
  const val TARGET_SONOS_ZONE_PLAYER: String = "urn:schemas-upnp-org:device:ZonePlayer:1"

  /**
   * Devices wait a random interval up to `MX` seconds before replying, to keep a large household
   * from storming the group. 2 s keeps the picker responsive; 1 s risks losing the slowest device
   * on a busy network, and 5 s is a five-second empty list.
   */
  const val DEFAULT_MX_SECONDS: Int = 2

  val MULTICAST_ENDPOINT: InetSocketAddress =
    InetSocketAddress(InetAddress.getByName(MULTICAST_IPV4), PORT)

  /**
   * One M-SEARCH datagram.
   *
   * @param mxSeconds the response-spreading window for a **multicast** search, or `null` for a
   *   **unicast** one. UPnP Device Architecture 1.1 section 1.3.3 defines the unicast form without
   *   `MX`, since spreading replies is meaningless with one recipient.
   *
   * `MAN: "ssdp:discover"` carries its quotes because the protocol specifies them. Sent unquoted,
   * a conformant device is entitled to ignore the search, and the symptom is an empty picker with
   * nothing logged anywhere.
   */
  fun request(host: String, port: Int, searchTarget: String, mxSeconds: Int?): ByteArray =
    buildString {
      append("M-SEARCH * HTTP/1.1").append(HttpWire.CRLF)
      append("HOST: ").append(host).append(':').append(port).append(HttpWire.CRLF)
      append("MAN: \"ssdp:discover\"").append(HttpWire.CRLF)
      if (mxSeconds != null) append("MX: ").append(mxSeconds).append(HttpWire.CRLF)
      append("ST: ").append(searchTarget).append(HttpWire.CRLF)
      append(HttpWire.CRLF)
    }.toByteArray(Charsets.US_ASCII)

  /**
   * One reply, or `null` when the datagram is not a usable answer to our own search.
   *
   * Six things are dropped, each for its own reason:
   *
   * - anything that is not `HTTP/1.x 200` -- including an unsolicited `NOTIFY`, which shares the
   *   group and would otherwise be misread as an answer;
   * - a reply with no `LOCATION`, no `USN` or no `ST`: nothing to fetch, nothing to identify, and
   *   nothing saying which question it answers;
   * - a `LOCATION` this client cannot parse as a URI;
   * - a `LOCATION` with no host. `URI("/d.xml")` parses, and its host is `null`, and
   *   `InetAddress.getByName(null)` quietly returns **loopback** -- so without this guard a
   *   relative `LOCATION` becomes a fetch against the phone itself;
   * - a `LOCATION` pointing off the local network. A device on the LAN can announce any URL it
   *   likes; the rule from Task 1 applies to what a device *claims* as well as to what this app
   *   dials.
   *
   * The status line is split off by hand rather than through [HttpWire.readResponseHead] because
   * the remainder is then handed to [HttpWire.parseHeaderBlock], whose end-of-input tolerance is
   * exactly what a datagram needs: real replies arrive both with and without the trailing blank
   * line, and on a socket the same shape would be a truncated read.
   */
  fun parseResponse(datagram: String, from: InetAddress): SsdpResponse? {
    val startLine = datagram.substringBefore("\r\n").substringBefore("\n")
    if (!startLine.startsWith("HTTP/1.")) return null
    if (startLine.split(' ').getOrNull(1) != "200") return null

    val headers = runCatching {
      HttpWire.parseHeaderBlock(datagram.substringAfter("\n"))
    }.getOrNull() ?: return null

    val location = headers["LOCATION"] ?: return null
    val usn = headers["USN"] ?: return null
    val searchTarget = headers["ST"] ?: return null
    val uri = runCatching { URI(location) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
    if (!LocalNetworkOnly.isLocal(address)) return null

    return SsdpResponse(uri, searchTarget, usn, headers["SERVER"], from)
  }
}
