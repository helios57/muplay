package app.muplay.cast.discovery

import java.net.InetAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The M-SEARCH datagram, byte for byte, and the reply parser.
 *
 * Byte-exactness is not pedantry here. `MAN: "ssdp:discover"` is quoted **in the protocol**, and a
 * device that receives it unquoted is within its rights to ignore the search entirely -- which
 * manifests as "no speakers found", with no error anywhere, on somebody else's network.
 */
class SsdpSearchTest {

  @Test
  fun `a multicast search is the exact datagram the protocol specifies`() {
    val datagram = String(
      SsdpSearch.request(
        host = SsdpSearch.MULTICAST_IPV4,
        port = SsdpSearch.PORT,
        searchTarget = SsdpSearch.TARGET_MEDIA_RENDERER,
        mxSeconds = 2,
      ),
      Charsets.US_ASCII,
    )

    // Every line, every CRLF, and the terminating blank line. An assertion on `contains("MAN")`
    // would pass with the quotes missing, which is the one thing this test exists to catch.
    assertThat(datagram).isEqualTo(
      "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
        "\r\n",
    )
  }

  @Test
  fun `the search target is the one the caller asked for`() {
    // Two observations. A hardcoded ST passes the test above and fails here.
    assertThat(String(SsdpSearch.request("239.255.255.250", 1900, SsdpSearch.TARGET_SONOS_ZONE_PLAYER, 2)))
      .contains("ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n")
    assertThat(String(SsdpSearch.request("239.255.255.250", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, 2)))
      .contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
  }

  @Test
  fun `mx is the value the caller asked for`() {
    assertThat(String(SsdpSearch.request("239.255.255.250", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, 1)))
      .contains("MX: 1\r\n")
    assertThat(String(SsdpSearch.request("239.255.255.250", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, 5)))
      .contains("MX: 5\r\n")
  }

  /**
   * A **unicast** M-SEARCH (UPnP Device Architecture 1.1, section 1.3.3) omits `MX`: `MX` exists so
   * that many devices spread their replies over a window and do not storm the multicast group, and
   * there is one recipient here. Devices differ in how they treat an `MX` they did not expect, and
   * this is the fallback path spec section 12 calls *required, not optional*, so it is sent exactly
   * as specified.
   */
  @Test
  fun `a unicast search names the unicast host and omits mx entirely`() {
    val datagram = String(
      SsdpSearch.request("192.168.1.50", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, mxSeconds = null),
    )

    assertThat(datagram).isEqualTo(
      "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 192.168.1.50:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
        "\r\n",
    )
    assertThat(datagram).doesNotContain("MX")
  }

  @Test
  fun `the host line carries the destination it was given, port included`() {
    // Two hosts and two ports, because "HOST" hardcoded to the multicast group passes every other
    // assertion in this class and breaks the entire VPN fallback.
    assertThat(String(SsdpSearch.request("10.0.0.9", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, null)))
      .contains("HOST: 10.0.0.9:1900\r\n")
    assertThat(String(SsdpSearch.request("127.0.0.1", 41234, SsdpSearch.TARGET_MEDIA_RENDERER, null)))
      .contains("HOST: 127.0.0.1:41234\r\n")
  }

  @Test
  fun `the multicast endpoint is the one the protocol reserves`() {
    // Pinned as a constant, because it is the one part of the multicast path CI cannot exercise
    // and therefore the one part most worth stating as an assertion rather than a literal.
    assertThat(SsdpSearch.MULTICAST_IPV4).isEqualTo("239.255.255.250")
    assertThat(SsdpSearch.PORT).isEqualTo(1900)
    assertThat(SsdpSearch.MULTICAST_ENDPOINT.address.hostAddress).isEqualTo("239.255.255.250")
    assertThat(SsdpSearch.MULTICAST_ENDPOINT.port).isEqualTo(1900)
  }

  /**
   * The delta between the path CI proves and the path a real network takes, made small enough to
   * read. Everything except `HOST` and `MX` must be identical.
   */
  @Test
  fun `a multicast and a unicast search differ only in the host line and mx`() {
    val multicast = String(SsdpSearch.request("239.255.255.250", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, 2))
    val unicast = String(SsdpSearch.request("127.0.0.1", 45000, SsdpSearch.TARGET_MEDIA_RENDERER, null))

    val stripped = { text: String ->
      text.split("\r\n").filterNot { it.startsWith("HOST:") || it.startsWith("MX:") }
    }
    assertThat(stripped(unicast)).isEqualTo(stripped(multicast))
    assertThat(stripped(multicast)).containsExactly(
      "M-SEARCH * HTTP/1.1",
      "MAN: \"ssdp:discover\"",
      "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
      "",
      "",
    )
  }

  @Test
  fun `a reply is parsed into its location, target, usn and server`() {
    val reply = "HTTP/1.1 200 OK\r\n" +
      "CACHE-CONTROL: max-age=1800\r\n" +
      "EXT:\r\n" +
      "LOCATION: http://192.168.1.50:1400/xml/device_description.xml\r\n" +
      "SERVER: Linux UPnP/1.0 Sonos/84.1-52250\r\n" +
      "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
      "USN: uuid:RINCON_5CAAFD0A1F4A01400::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    val parsed = SsdpSearch.parseResponse(reply, InetAddress.getByName("192.168.1.50"))!!

    // Every field, individually. `isNotNull` on the response would pass with four of them empty.
    assertThat(parsed.location.toString()).isEqualTo("http://192.168.1.50:1400/xml/device_description.xml")
    assertThat(parsed.searchTarget).isEqualTo("urn:schemas-upnp-org:device:MediaRenderer:1")
    assertThat(parsed.usn)
      .isEqualTo("uuid:RINCON_5CAAFD0A1F4A01400::urn:schemas-upnp-org:device:MediaRenderer:1")
    assertThat(parsed.server).isEqualTo("Linux UPnP/1.0 Sonos/84.1-52250")
    assertThat(parsed.from.hostAddress).isEqualTo("192.168.1.50")
  }

  /**
   * The same reply with every header name in a different case. Beyond the plan, and not
   * decoration: `HttpHeaders` is case-insensitive but *this* parser is what asks it, and asking
   * it with the wrong spelling (a `Map` lookup, or `startsWith("LOCATION:")` on the raw text)
   * yields a device that never appears in the picker with nothing logged anywhere. Real firmware
   * sends all three spellings; the test above pins only the upper-case one.
   */
  @Test
  fun `a reply whose headers are in a different case is parsed the same way`() {
    val reply = "HTTP/1.1 200 OK\r\n" +
      "Location: http://192.168.1.50:1400/d.xml\r\n" +
      "server: Linux UPnP/1.0 Sonos/84.1-52250\r\n" +
      "St: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
      "Usn: uuid:RINCON_ABC::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    val parsed = SsdpSearch.parseResponse(reply, InetAddress.getLoopbackAddress())!!

    assertThat(parsed.location.toString()).isEqualTo("http://192.168.1.50:1400/d.xml")
    assertThat(parsed.searchTarget).isEqualTo("urn:schemas-upnp-org:device:MediaRenderer:1")
    assertThat(parsed.usn).isEqualTo("uuid:RINCON_ABC::urn:schemas-upnp-org:device:MediaRenderer:1")
    assertThat(parsed.server).isEqualTo("Linux UPnP/1.0 Sonos/84.1-52250")
  }

  /**
   * Beyond the plan. `SERVER` is optional in an M-SEARCH response and plenty of renderers omit it;
   * the field is nullable for that reason, and without this the null arm is never observed -- a
   * `server = headers["SERVER"].orEmpty()` would pass every other test in this class.
   */
  @Test
  fun `a reply with no server header parses with a null server rather than being discarded`() {
    val parsed = SsdpSearch.parseResponse(
      "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:1400/d.xml\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:x\r\n\r\n",
      InetAddress.getLoopbackAddress(),
    )!!

    assertThat(parsed.server).isNull()
    assertThat(parsed.location.toString()).isEqualTo("http://127.0.0.1:1400/d.xml")
  }

  @Test
  fun `the udn is the uuid half of the usn, which is what deduplicates a device`() {
    // The whole point of extracting it. Two replies from one Sonos differ in `ST` and in the
    // suffix of `USN`, and agree on this.
    val renderer = SsdpSearch.parseResponse(
      reply(usn = "uuid:RINCON_ABC01400::urn:schemas-upnp-org:device:MediaRenderer:1"),
      InetAddress.getLoopbackAddress(),
    )!!
    val zonePlayer = SsdpSearch.parseResponse(
      reply(usn = "uuid:RINCON_ABC01400::urn:schemas-upnp-org:device:ZonePlayer:1"),
      InetAddress.getLoopbackAddress(),
    )!!

    assertThat(renderer.udn).isEqualTo("uuid:RINCON_ABC01400")
    assertThat(zonePlayer.udn).isEqualTo("uuid:RINCON_ABC01400")
    assertThat(renderer.udn).isEqualTo(zonePlayer.udn)
  }

  @Test
  fun `a usn with no service suffix is its own udn`() {
    // A root-device announcement carries `USN: uuid:x` with nothing after it. Splitting on "::"
    // and taking index 1 would throw here; taking `substringBefore` is why this passes.
    val parsed = SsdpSearch.parseResponse(reply(usn = "uuid:plain-device"), InetAddress.getLoopbackAddress())!!

    assertThat(parsed.udn).isEqualTo("uuid:plain-device")
  }

  @Test
  fun `a reply with no location is discarded rather than half-parsed`() {
    // Without a LOCATION there is nothing to fetch, so there is no device. Returning a
    // half-populated object here would push the failure into the description fetcher, where the
    // message would be about a URL rather than about a malformed announcement.
    val noLocation = "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
      "USN: uuid:x\r\n\r\n"

    assertThat(SsdpSearch.parseResponse(noLocation, InetAddress.getLoopbackAddress())).isNull()
  }

  /**
   * Beyond the plan: the other two required headers, each dropped on its own.
   *
   * Three `?: return null` arms of the same shape sit next to each other, and the plan observes
   * exactly one of them. A parser that had lost the `USN` guard would still pass every plan test
   * -- and would then produce a device with an empty `udn`, which deduplicates every anonymous
   * announcement on the network into one picker entry.
   */
  @Test
  fun `a reply with no usn is discarded, because nothing would identify the device`() {
    assertThat(
      SsdpSearch.parseResponse(
        "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:1400/d.xml\r\n" +
          "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        InetAddress.getLoopbackAddress(),
      ),
    ).isNull()
  }

  @Test
  fun `a reply with no search target is discarded`() {
    assertThat(
      SsdpSearch.parseResponse(
        "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:1400/d.xml\r\nUSN: uuid:x\r\n\r\n",
        InetAddress.getLoopbackAddress(),
      ),
    ).isNull()
  }

  @Test
  fun `a reply whose location is not a local address is discarded`() {
    // A hostile or misconfigured device on the LAN can announce any LOCATION it likes, including
    // one on the public internet, and this client would then fetch it in cleartext. The address
    // rule from Task 1 applies to announcements as well as to connections.
    val remote = reply(location = "http://93.184.216.34/desc.xml")

    assertThat(SsdpSearch.parseResponse(remote, InetAddress.getLoopbackAddress())).isNull()
  }

  /**
   * Beyond the plan, and the other side of the same guard: a private address *is* accepted. The
   * plan observes the rule refusing but never permitting anything except loopback, so a
   * `LocalNetworkOnly.isLocal` reduced to `isLoopbackAddress` would pass the whole plan suite and
   * find no speaker on any real network.
   */
  @Test
  fun `a reply from an rfc 1918 address is kept`() {
    val parsed = SsdpSearch.parseResponse(
      reply(location = "http://192.168.4.7:1400/d.xml"),
      InetAddress.getByName("192.168.4.7"),
    )

    assertThat(parsed).isNotNull
    assertThat(parsed!!.location.toString()).isEqualTo("http://192.168.4.7:1400/d.xml")
  }

  /**
   * Beyond the plan: a `LOCATION` with no host at all. `URI("/desc.xml")` parses fine and its
   * `host` is null, so without the null guard this reaches `InetAddress.getByName(null)`, which
   * quietly returns **loopback** -- the same trap Task 1 recorded in `CastHttpClient`. The device
   * would then be described by fetching a URL on the phone itself.
   */
  @Test
  fun `a reply whose location has no host is discarded rather than resolved to loopback`() {
    assertThat(SsdpSearch.parseResponse(reply(location = "/xml/device_description.xml"), InetAddress.getLoopbackAddress()))
      .isNull()
  }

  @Test
  fun `a reply whose location is not a uri at all is discarded`() {
    assertThat(SsdpSearch.parseResponse(reply(location = "http://[not a uri"), InetAddress.getLoopbackAddress()))
      .isNull()
  }

  @Test
  fun `a non-200 reply is discarded`() {
    assertThat(
      SsdpSearch.parseResponse(
        "HTTP/1.1 404 Not Found\r\nLOCATION: http://127.0.0.1:1400/d.xml\r\nUSN: uuid:x\r\n" +
          "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        InetAddress.getLoopbackAddress(),
      ),
    ).isNull()
  }

  @Test
  fun `a notify datagram is discarded, because this transport only asked a question`() {
    // Unsolicited `NOTIFY * HTTP/1.1` announcements arrive on the multicast group. This client
    // does not subscribe to them (see SsdpTransport's KDoc on MulticastLock) and must not
    // misparse one that leaks through as an answer to its own search.
    val notify = "NOTIFY * HTTP/1.1\r\nLOCATION: http://127.0.0.1:1400/d.xml\r\nUSN: uuid:x\r\n" +
      "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    assertThat(SsdpSearch.parseResponse(notify, InetAddress.getLoopbackAddress())).isNull()
  }

  /**
   * Beyond the plan. SSDP is specified with CRLF, and real firmware has shipped bare LF. Task 1's
   * `HttpWire` is deliberately tolerant on read, and this is the assertion that the tolerance
   * actually reaches SSDP: the status line is split off by hand here, and a split that assumed
   * CRLF would take the *whole datagram* as the status line and reject a working speaker.
   */
  @Test
  fun `a reply that uses bare line feeds is still parsed`() {
    val parsed = SsdpSearch.parseResponse(
      "HTTP/1.1 200 OK\nLOCATION: http://127.0.0.1:1400/d.xml\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\nUSN: uuid:lf\n\n",
      InetAddress.getLoopbackAddress(),
    )!!

    assertThat(parsed.udn).isEqualTo("uuid:lf")
    assertThat(parsed.location.toString()).isEqualTo("http://127.0.0.1:1400/d.xml")
  }

  /**
   * Beyond the plan: a datagram that stops after its last header with no blank line at all. This
   * is the shape Task 1's `parseHeaderBlock` fix exists for, and SSDP is its first real consumer
   * -- some devices send it, and `readRequestHead`/`readResponseHead` would reject it as a
   * truncated read.
   */
  @Test
  fun `a reply with no trailing blank line is still a complete datagram`() {
    val parsed = SsdpSearch.parseResponse(
      "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:1400/d.xml\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:terse\r\n",
      InetAddress.getLoopbackAddress(),
    )!!

    assertThat(parsed.udn).isEqualTo("uuid:terse")
  }

  @Test
  fun `a datagram that is not http at all is discarded`() {
    assertThat(SsdpSearch.parseResponse("garbage", InetAddress.getLoopbackAddress())).isNull()
    assertThat(SsdpSearch.parseResponse("", InetAddress.getLoopbackAddress())).isNull()
  }

  private fun reply(
    location: String = "http://127.0.0.1:1400/xml/device_description.xml",
    usn: String = "uuid:x::urn:schemas-upnp-org:device:MediaRenderer:1",
  ) = "HTTP/1.1 200 OK\r\nLOCATION: $location\r\nUSN: $usn\r\n" +
    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nEXT:\r\n\r\n"
}
