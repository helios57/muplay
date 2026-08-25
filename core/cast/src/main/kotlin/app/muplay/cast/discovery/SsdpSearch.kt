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
   * Eight things are dropped, each for its own reason:
   *
   * - anything that is not `HTTP/1.x 200` -- including an unsolicited `NOTIFY`, which shares the
   *   group and would otherwise be misread as an answer;
   * - a reply with no `LOCATION`, no `USN` or no `ST`: nothing to fetch, nothing to identify, and
   *   nothing saying which question it answers;
   * - a `LOCATION` this client cannot parse as a URI;
   * - a `LOCATION` with no host. `URI("/d.xml")` parses, and its host is `null`, and
   *   `InetAddress.getByName(null)` quietly returns **loopback** -- so without this guard a
   *   relative `LOCATION` becomes a fetch against the phone itself;
   * - a `LOCATION` whose host is **not an IP literal** (see [isIpLiteral]) -- so this function
   *   never makes a DNS query;
   * - a `LOCATION` whose host is not the address the datagram came **from**;
   * - a `LOCATION` pointing off the local network. A device on the LAN can announce any URL it
   *   likes; the rule from Task 1 applies to what a device *claims* as well as to what this app
   *   dials.
   *
   * ### The last two are one fix, and they close a denial of service rather than a nicety
   *
   * The M-SEARCH goes to a multicast group, so **every device on the segment learns this phone's
   * source address and ephemeral port**, and the reply socket is an unconnected `DatagramSocket`
   * that anything can then write to. No spoofing is needed. One datagram carrying
   * `LOCATION: http://a1.example.invalid/d.xml`, served by a nameserver that simply drops queries,
   * used to park this function inside `InetAddress.getByName` for a resolver timeout -- seconds,
   * with retries -- **inside [DatagramSsdpTransport]'s read loop**, which parses synchronously and
   * checks its deadline only between packets. N datagrams naming N distinct labels cost N timeouts
   * (varying the label defeats negative caching), the listen window closes with every genuine
   * speaker's reply still unread in the socket buffer, and the symptom is an empty picker with
   * nothing logged: the failure mode this module's comments keep calling the worst one available.
   * It is equally an ordinary bug -- a phone on a VPN with no reachable resolver does it to itself.
   *
   * Requiring an IP literal removes the resolver from the path entirely, and requiring that
   * literal to equal [from] removes the redirection: a device may announce **its own** address and
   * nothing else. Real UPnP `LOCATION`s are IP literals essentially without exception, because a
   * device cannot rely on a control point being able to resolve a name it invents.
   *
   * What this deliberately does **not** fix is an impostor that answers from its own address with
   * its own `LOCATION` and a `USN` it copied from the real speaker: that passes both checks
   * perfectly, and which announcement wins is a picker-trust decision rather than a parser one.
   *
   * The status line is split off by hand rather than through [HttpWire.readResponseHead] because
   * the remainder is then handed to [HttpWire.parseHeaderBlock], whose end-of-input tolerance is
   * exactly what a datagram needs: real replies arrive both with and without the trailing blank
   * line, and on a socket the same shape would be a truncated read.
   *
   * **Precondition, and it is the caller's to keep: [datagram] must be the whole datagram.** That
   * tolerance is what makes a short read undetectable here -- a `LOCATION` clipped by a receive
   * buffer too small for the packet parses as a real, shorter URL, and this function has no way
   * to tell it from a device that announced that URL. `recvfrom` truncates silently, so the only
   * place the difference can be seen is at the socket: see [DatagramSsdpTransport]'s read loop,
   * which refuses a datagram that exactly fills its buffer.
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
    if (!isIpLiteral(host)) return null
    // Safe now, and only now: `getByName` on a literal is a parse of the text, with no query.
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
    if (address != from) return null
    if (!LocalNetworkOnly.isLocal(address)) return null

    return SsdpResponse(uri, searchTarget, usn, headers["SERVER"], from)
  }

  /**
   * True when [host] is an address **written out**, so that resolving it is a parse of the text
   * and never a DNS query.
   *
   * Two regular expressions rather than a trial call to [InetAddress.getByName], because that call
   * is exactly what must not happen: `getByName` falls back to the resolver for **anything it does
   * not recognise as a literal**, and what it recognises differs between this JDK and Android's
   * libcore. So the question is answered here, from the text, before any resolver can see it.
   *
   * Both patterns are therefore deliberately **stricter than any resolver**: whatever they are not
   * sure about is refused rather than resolved. `010.1.1.1` is read as decimal by this JDK and as
   * ambiguous octal elsewhere, so leading zeros are out; a bracketed host must actually contain a
   * colon, because Android's `getByName` strips the brackets only when it finds one and otherwise
   * hands the whole bracketed string to the resolver.
   */
  private fun isIpLiteral(host: String): Boolean =
    IPV4_LITERAL.matches(host) || IPV6_LITERAL.matches(host)

  /** One octet: `0`-`255`, no leading zeros. */
  private const val OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])"

  /** A dotted quad, and nothing else -- not `1`, not `1.2.3`, not `0x7f.1`. */
  private val IPV4_LITERAL = Regex("$OCTET(\\.$OCTET){3}")

  /**
   * A bracketed IPv6 literal, as `URI.getHost` hands one back -- hex groups, colons, an embedded
   * dotted quad, and RFC 6874's `%25`-escaped zone id, which a link-local renderer really does
   * announce.
   */
  private val IPV6_LITERAL = Regex("\\[[0-9A-Fa-f.:]*:[0-9A-Fa-f.:]*(%25[0-9A-Za-z._~-]+)?]")
}
