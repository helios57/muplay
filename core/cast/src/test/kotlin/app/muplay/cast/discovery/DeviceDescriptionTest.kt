package app.muplay.cast.discovery

import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

/**
 * The UPnP device description parser.
 *
 * Two documents, deliberately: a **generic** renderer whose root device is the `MediaRenderer`, and
 * a **Sonos-shaped** one whose root is a `ZonePlayer` with the `MediaRenderer` and a `MediaServer`
 * nested inside `deviceList`. A parser written against the first alone reports that a Sonos speaker
 * has no `AVTransport` -- which is "Sonos does not appear in the picker", the named user
 * requirement, silently absent.
 *
 * The two documents are spelled out here in full rather than taken from
 * `app.muplay.cast.fake.FakeDescriptions`, on purpose: a change to the fake's documents must not
 * silently change what this class asserts about the parser.
 */
class DeviceDescriptionTest {

  private val genericUrl = URI("http://192.168.1.77:2869/upnp/desc.xml")
  private val sonosUrl = URI("http://192.168.1.50:1400/xml/device_description.xml")

  private val generic = """
    <?xml version="1.0"?>
    <root xmlns="urn:schemas-upnp-org:device-1-0">
      <specVersion><major>1</major><minor>0</minor></specVersion>
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>Study Amp</friendlyName>
        <manufacturer>Yamaha Corporation</manufacturer>
        <modelName>WXA-50</modelName>
        <UDN>uuid:9ab0c000-f668-11de-9976-00a0ded1e211</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
            <SCPDURL>/RenderingControl/desc.xml</SCPDURL>
            <controlURL>/RenderingControl/ctrl</controlURL>
            <eventSubURL>/RenderingControl/evt</eventSubURL>
          </service>
          <service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
            <SCPDURL>/AVTransport/desc.xml</SCPDURL>
            <controlURL>/AVTransport/ctrl</controlURL>
            <eventSubURL>/AVTransport/evt</eventSubURL>
          </service>
        </serviceList>
      </device>
    </root>
  """.trimIndent()

  private val sonos = """
    <?xml version="1.0" encoding="utf-8"?>
    <root xmlns="urn:schemas-upnp-org:device-1-0">
      <specVersion><major>1</major><minor>0</minor></specVersion>
      <device>
        <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
        <friendlyName>192.168.1.50 - Sonos One</friendlyName>
        <manufacturer>Sonos, Inc.</manufacturer>
        <modelName>Sonos One</modelName>
        <roomName>K&#252;che</roomName>
        <UDN>uuid:RINCON_5CAAFD0A1F4A01400</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:ZoneGroupTopology:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:ZoneGroupTopology</serviceId>
            <controlURL>/ZoneGroupTopology/Control</controlURL>
            <SCPDURL>/xml/ZoneGroupTopology1.xml</SCPDURL>
          </service>
        </serviceList>
        <deviceList>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>192.168.1.50 - Sonos One Media Server</friendlyName>
            <UDN>uuid:RINCON_5CAAFD0A1F4A01400_MS</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                <controlURL>/MediaServer/ContentDirectory/Control</controlURL>
                <SCPDURL>/xml/ContentDirectory1.xml</SCPDURL>
              </service>
            </serviceList>
          </device>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>192.168.1.50 - Sonos One Media Renderer</friendlyName>
            <UDN>uuid:RINCON_5CAAFD0A1F4A01400_MR</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/MediaRenderer/RenderingControl/Control</controlURL>
                <SCPDURL>/xml/RenderingControl1.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
                <SCPDURL>/xml/AVTransport1.xml</SCPDURL>
              </service>
            </serviceList>
          </device>
        </deviceList>
      </device>
    </root>
  """.trimIndent()

  @Test
  fun `a generic renderer's own fields are read, each of them`() {
    val device = DeviceDescription.parse(generic, genericUrl)

    // Field by field. `isNotNull` would pass with every string empty.
    assertThat(device.deviceType).isEqualTo("urn:schemas-upnp-org:device:MediaRenderer:1")
    assertThat(device.friendlyName).isEqualTo("Study Amp")
    assertThat(device.manufacturer).isEqualTo("Yamaha Corporation")
    assertThat(device.modelName).isEqualTo("WXA-50")
    assertThat(device.udn).isEqualTo("uuid:9ab0c000-f668-11de-9976-00a0ded1e211")
  }

  @Test
  fun `services come back in document order, as an exact list`() {
    val device = DeviceDescription.parse(generic, genericUrl)

    // The exact list of types, in order. `anyMatch { it.serviceType.contains("AVTransport") }`
    // would pass on a parser that dropped RenderingControl, and vacuously on one that returned an
    // empty list if the matcher were `allMatch`.
    assertThat(device.services.map { it.serviceType }).containsExactly(
      "urn:schemas-upnp-org:service:RenderingControl:1",
      "urn:schemas-upnp-org:service:AVTransport:1",
    )
  }

  /**
   * Beyond the plan. `serviceId` is read from its own element and nothing in the plan's suite ever
   * looks at it, so `serviceId = childText(element, "serviceType")` -- a one-word slip between two
   * adjacent lines -- would ship green. Task 3 puts this string in the `SOAPAction` header's
   * neighbourhood, so it is not an unused field.
   */
  @Test
  fun `each service's id is read from its own element, not from its type`() {
    val device = DeviceDescription.parse(generic, genericUrl)

    assertThat(device.services.map { it.serviceId }).containsExactly(
      "urn:upnp-org:serviceId:RenderingControl",
      "urn:upnp-org:serviceId:AVTransport",
    )
  }

  @Test
  fun `a relative control url is resolved against the description url`() {
    val device = DeviceDescription.parse(generic, genericUrl)

    // Two different services, so a resolver that returned a constant fails.
    assertThat(device.service("urn:schemas-upnp-org:service:AVTransport:1")!!.controlUrl.toString())
      .isEqualTo("http://192.168.1.77:2869/AVTransport/ctrl")
    assertThat(device.service("urn:schemas-upnp-org:service:RenderingControl:1")!!.controlUrl.toString())
      .isEqualTo("http://192.168.1.77:2869/RenderingControl/ctrl")
    assertThat(device.service("urn:schemas-upnp-org:service:AVTransport:1")!!.scpdUrl.toString())
      .isEqualTo("http://192.168.1.77:2869/AVTransport/desc.xml")
  }

  @Test
  fun `an absolute control url is left alone`() {
    val absolute = generic.replace(
      "<controlURL>/AVTransport/ctrl</controlURL>",
      "<controlURL>http://192.168.1.77:8080/other/ctrl</controlURL>",
    )

    assertThat(
      DeviceDescription.parse(absolute, genericUrl)
        .service("urn:schemas-upnp-org:service:AVTransport:1")!!.controlUrl.toString(),
    ).isEqualTo("http://192.168.1.77:8080/other/ctrl")
  }

  @Test
  fun `a URLBase wins over the description url when the device sends one`() {
    // Deprecated in UPnP 1.1 and still emitted. Both branches are observed, because "present" and
    // "absent" are two behaviours and only one of them appears in the textbook example.
    val withBase = generic.replace(
      "<specVersion><major>1</major><minor>0</minor></specVersion>",
      "<specVersion><major>1</major><minor>0</minor></specVersion>" +
        "<URLBase>http://192.168.1.77:9999/base/</URLBase>",
    )

    assertThat(
      DeviceDescription.parse(withBase, genericUrl)
        .service("urn:schemas-upnp-org:service:AVTransport:1")!!.controlUrl.toString(),
    ).isEqualTo("http://192.168.1.77:9999/AVTransport/ctrl")
  }

  /**
   * Beyond the plan: the third `URLBase` branch. A device that sends a `URLBase` this client
   * cannot parse must fall back to the description URL rather than losing every control URL --
   * "the speaker is there but nothing works" is a worse outcome than ignoring one deprecated
   * element.
   */
  @Test
  fun `a URLBase that is not a uri falls back to the description url`() {
    val badBase = generic.replace(
      "<specVersion><major>1</major><minor>0</minor></specVersion>",
      "<specVersion><major>1</major><minor>0</minor></specVersion><URLBase>http://[nonsense</URLBase>",
    )

    assertThat(
      DeviceDescription.parse(badBase, genericUrl)
        .service("urn:schemas-upnp-org:service:AVTransport:1")!!.controlUrl.toString(),
    ).isEqualTo("http://192.168.1.77:2869/AVTransport/ctrl")
  }

  /**
   * The Sonos shape. This is the test that decides whether the named user requirement works.
   */
  @Test
  fun `a sonos root device carries the media renderer inside its device list`() {
    val root = DeviceDescription.parse(sonos, sonosUrl)

    assertThat(root.deviceType).isEqualTo("urn:schemas-upnp-org:device:ZonePlayer:1")
    // The root itself has no AVTransport -- which is exactly why a non-recursive parser reports
    // "not a renderer".
    assertThat(root.service("urn:schemas-upnp-org:service:AVTransport:1")).isNull()

    // The exact flattened list, in document order: root, then MediaServer, then MediaRenderer.
    assertThat(root.flatten().map { it.deviceType }).containsExactly(
      "urn:schemas-upnp-org:device:ZonePlayer:1",
      "urn:schemas-upnp-org:device:MediaServer:1",
      "urn:schemas-upnp-org:device:MediaRenderer:1",
    )

    val renderer = root.flatten().single { it.deviceType == DeviceDescription.DEVICE_MEDIA_RENDERER }
    assertThat(renderer.service(DeviceDescription.SERVICE_AV_TRANSPORT)!!.controlUrl.toString())
      .isEqualTo("http://192.168.1.50:1400/MediaRenderer/AVTransport/Control")
    assertThat(renderer.service(DeviceDescription.SERVICE_RENDERING_CONTROL)!!.controlUrl.toString())
      .isEqualTo("http://192.168.1.50:1400/MediaRenderer/RenderingControl/Control")
  }

  /**
   * Beyond the plan, and the other observation of `flatten()`: a device with no `deviceList` is
   * its own whole tree. A `flatten()` implemented as `embedded.flatMap { it.flatten() }` -- the
   * missing `listOf(this) +` -- returns an empty list here and the plan's Sonos assertion would
   * still find its renderer.
   */
  @Test
  fun `a device with no embedded devices flattens to exactly itself`() {
    val device = DeviceDescription.parse(generic, genericUrl)

    assertThat(device.embedded).isEmpty()
    assertThat(device.flatten().map { it.udn })
      .containsExactly("uuid:9ab0c000-f668-11de-9976-00a0ded1e211")
  }

  /**
   * Beyond the plan: `manufacturer` and `modelName` are nullable and every fixture in the plan
   * supplies both on the device it inspects. Sonos's embedded devices carry neither, and a
   * `.orEmpty()` on either would put an empty string where a caller reads `null` -- which
   * `CastDevice.isSonos`'s second signal then evaluates against.
   */
  @Test
  fun `a device that names no manufacturer or model reports null rather than an empty string`() {
    val mediaServer = DeviceDescription.parse(sonos, sonosUrl).flatten()
      .single { it.deviceType == "urn:schemas-upnp-org:device:MediaServer:1" }

    assertThat(mediaServer.manufacturer).isNull()
    assertThat(mediaServer.modelName).isNull()
    assertThat(mediaServer.friendlyName).isEqualTo("192.168.1.50 - Sonos One Media Server")
  }

  /**
   * Beyond the plan: the `SCPDURL` half of the same nullability. Sonos's `Queue` service and a
   * handful of embedded devices omit it; a non-null `scpdUrl` there would be a URL resolved from
   * an empty string, i.e. the description URL itself, which Task 3 would then fetch as if it were
   * a service description.
   */
  @Test
  fun `a service with no SCPDURL has a null one rather than a resolved empty path`() {
    val noScpd = generic.replace("<SCPDURL>/AVTransport/desc.xml</SCPDURL>", "")

    val service = DeviceDescription.parse(noScpd, genericUrl)
      .service("urn:schemas-upnp-org:service:AVTransport:1")!!

    assertThat(service.scpdUrl).isNull()
    assertThat(service.controlUrl.toString()).isEqualTo("http://192.168.1.77:2869/AVTransport/ctrl")
  }

  /**
   * Beyond the plan: a service element that names no `controlURL` is dropped, and the ones beside
   * it survive. There is nothing to POST to, so keeping it would put a service in the list whose
   * `controlUrl` had to be invented.
   */
  @Test
  fun `a service with no control url is dropped and its neighbours are kept`() {
    val broken = generic.replace("<controlURL>/RenderingControl/ctrl</controlURL>", "")

    assertThat(DeviceDescription.parse(broken, genericUrl).services.map { it.serviceType })
      .containsExactly("urn:schemas-upnp-org:service:AVTransport:1")
  }

  @Test
  fun `a service with no type is dropped and its neighbours are kept`() {
    val broken = generic.replace(
      "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>",
      "",
    )

    assertThat(DeviceDescription.parse(broken, genericUrl).services.map { it.serviceType })
      .containsExactly("urn:schemas-upnp-org:service:AVTransport:1")
  }

  @Test
  fun `a cast device is built from the sonos root and knows it is a sonos`() {
    val device = CastDevice.from(DeviceDescription.parse(sonos, sonosUrl), sonosUrl)!!

    // The *root's* identity and name, not the embedded renderer's: the user recognises
    // "Sonos One", and the root UDN is what SSDP's USN deduplicates on.
    assertThat(device.udn).isEqualTo("uuid:RINCON_5CAAFD0A1F4A01400")
    assertThat(device.friendlyName).isEqualTo("192.168.1.50 - Sonos One")
    assertThat(device.manufacturer).isEqualTo("Sonos, Inc.")
    assertThat(device.modelName).isEqualTo("Sonos One")
    assertThat(device.descriptionUrl).isEqualTo(sonosUrl)
    assertThat(device.avTransportControlUrl.toString())
      .isEqualTo("http://192.168.1.50:1400/MediaRenderer/AVTransport/Control")
    assertThat(device.avTransportScpdUrl.toString())
      .isEqualTo("http://192.168.1.50:1400/xml/AVTransport1.xml")
    assertThat(device.renderingControlUrl!!.toString())
      .isEqualTo("http://192.168.1.50:1400/MediaRenderer/RenderingControl/Control")
    assertThat(device.isSonos).isTrue
  }

  @Test
  fun `a generic renderer is not a sonos`() {
    // The other observation. `isSonos` hardcoded either way passes exactly one of these two tests.
    assertThat(CastDevice.from(DeviceDescription.parse(generic, genericUrl), genericUrl)!!.isSonos)
      .isFalse
  }

  @Test
  fun `a sonos is recognised by its udn even when the manufacturer string changes`() {
    // Two independent signals, because firmware has changed the manufacturer string before
    // ("Sonos, Inc." vs "Sonos Inc.") and the RINCON_ prefix has not changed in fifteen years.
    val relabelled = sonos.replace("<manufacturer>Sonos, Inc.</manufacturer>", "<manufacturer>S</manufacturer>")

    assertThat(CastDevice.from(DeviceDescription.parse(relabelled, sonosUrl), sonosUrl)!!.isSonos).isTrue
  }

  /**
   * Beyond the plan: the *second* operand of that `||`, on its own. The plan observes the UDN
   * signal alone and the neither-signal case; without this, `isSonos` could be
   * `udn.startsWith("uuid:RINCON_")` with the manufacturer clause deleted and the whole suite
   * stays green. A future Sonos product that changes the UDN prefix is exactly what the second
   * signal is for.
   */
  @Test
  fun `a device with a non-RINCON udn whose manufacturer says sonos is still a sonos`() {
    val renamedUdn = sonos
      .replace("<UDN>uuid:RINCON_5CAAFD0A1F4A01400</UDN>", "<UDN>uuid:sonos-next-gen</UDN>")

    val device = CastDevice.from(DeviceDescription.parse(renamedUdn, sonosUrl), sonosUrl)!!

    assertThat(device.udn).isEqualTo("uuid:sonos-next-gen")
    assertThat(device.isSonos).isTrue
  }

  @Test
  fun `a device with no AVTransport anywhere is not a cast device`() {
    // A Sonos MediaServer, a printer, a router's UPnP IGD -- all of them answer SSDP and none of
    // them can be cast to. Returning null here is what keeps them out of the picker, rather than
    // failing at SetAVTransportURI after the user has chosen one.
    val serverOnly = """
      <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
        <friendlyName>NAS</friendlyName>
        <UDN>uuid:nas</UDN>
        <serviceList><service>
          <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
          <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
          <controlURL>/cd</controlURL>
        </service></serviceList>
      </device></root>
    """.trimIndent()

    assertThat(CastDevice.from(DeviceDescription.parse(serverOnly, genericUrl), genericUrl)).isNull()
  }

  @Test
  fun `a renderer with no RenderingControl is still a cast device, with no volume`() {
    // Volume is optional; transport is not. A device missing RenderingControl must still be
    // castable -- with the volume slider absent rather than a control that silently does nothing.
    val noVolume = generic.replace(
      Regex("<service>\\s*<serviceType>urn:schemas-upnp-org:service:RenderingControl:1.*?</service>", RegexOption.DOT_MATCHES_ALL),
      "",
    )

    val device = CastDevice.from(DeviceDescription.parse(noVolume, genericUrl), genericUrl)!!

    assertThat(device.avTransportControlUrl.toString()).isEqualTo("http://192.168.1.77:2869/AVTransport/ctrl")
    assertThat(device.renderingControlUrl).isNull()
  }

  /**
   * Beyond the plan: the `friendlyName.ifEmpty { … }` fallback. A root device that names itself
   * nothing at all is not hypothetical -- Sonos's own `MediaServer` sub-device is named and its
   * root is, but several generic renderers ship a root with an empty `friendlyName` and put the
   * name on the embedded renderer. Without the fallback the picker shows a blank row.
   */
  @Test
  fun `a root with no name of its own takes the renderer's name`() {
    val nameless = sonos.replace(
      "<friendlyName>192.168.1.50 - Sonos One</friendlyName>",
      "<friendlyName></friendlyName>",
    )

    assertThat(CastDevice.from(DeviceDescription.parse(nameless, sonosUrl), sonosUrl)!!.friendlyName)
      .isEqualTo("192.168.1.50 - Sonos One Media Renderer")
  }

  /**
   * Beyond the plan: the mapping to the record that is persisted. Three fields, each asserted --
   * this is the one place `CastDevice` becomes a `RememberedRenderer`, and a `descriptionUrl` that
   * came from anywhere else is a fallback that fetches the wrong URL next time.
   */
  @Test
  fun `a cast device remembers its udn, its name and the url it was described at`() {
    val remembered = CastDevice.from(DeviceDescription.parse(sonos, sonosUrl), sonosUrl)!!.remembered()

    assertThat(remembered.udn).isEqualTo("uuid:RINCON_5CAAFD0A1F4A01400")
    assertThat(remembered.friendlyName).isEqualTo("192.168.1.50 - Sonos One")
    assertThat(remembered.descriptionUrl).isEqualTo("http://192.168.1.50:1400/xml/device_description.xml")
  }

  @Test
  fun `a non-ascii friendly name survives as utf-8`() {
    // The description body is UTF-8 (headers are not -- see HttpWire). A speaker called "Küche" is
    // the ordinary case in this project's own household, and mojibake here is a user-visible bug.
    val named = generic.replace("<friendlyName>Study Amp</friendlyName>", "<friendlyName>Büro</friendlyName>")

    assertThat(DeviceDescription.parse(named, genericUrl).friendlyName).isEqualTo("Büro")
  }

  @Test
  fun `a numeric character reference is decoded`() {
    // Sonos writes `K&#252;che` rather than the raw character. A parser that returned the raw text
    // node without entity resolution shows the user "K&#252;che".
    assertThat(DeviceDescription.parse(sonos, sonosUrl).flatten().first().friendlyName)
      .isEqualTo("192.168.1.50 - Sonos One")
    val referenced = generic.replace("<friendlyName>Study Amp</friendlyName>", "<friendlyName>B&#252;ro</friendlyName>")
    assertThat(DeviceDescription.parse(referenced, genericUrl).friendlyName).isEqualTo("Büro")
  }

  /**
   * XXE. This XML comes from a device on the LAN that MuPlay did not write, over a protocol with
   * no authentication of any kind: **anything** that can send a UDP datagram can make this app
   * fetch and parse a document of its choosing.
   */
  @Test
  fun `a description carrying a doctype is refused outright`() {
    val hostile = """
      <?xml version="1.0"?>
      <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
      <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>&xxe;</friendlyName><UDN>uuid:x</UDN>
        <serviceList/></device></root>
    """.trimIndent()

    assertThatExceptionOfType(MalformedDescriptionException::class.java)
      .isThrownBy { DeviceDescription.parse(hostile, genericUrl) }
      .withMessageContaining("DOCTYPE")
  }

  /**
   * Beyond the plan, and not a stylistic point: XML declares element and keyword names to be
   * case-sensitive, but `<!doctype` in lower case is what a hand-rolled generator emits and what
   * a lenient parser still honours. A `contains("<!DOCTYPE")` without `ignoreCase` refuses the
   * upper-case attack and admits the lower-case one, which is the worst of both.
   */
  @Test
  fun `a doctype in lower case is refused too`() {
    val hostile = """
      <?xml version="1.0"?>
      <!doctype root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
      <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>&xxe;</friendlyName><UDN>uuid:x</UDN>
        <serviceList/></device></root>
    """.trimIndent()

    assertThatExceptionOfType(MalformedDescriptionException::class.java)
      .isThrownBy { DeviceDescription.parse(hostile, genericUrl) }
      .withMessageContaining("DOCTYPE")
  }

  @Test
  fun `an oversized description is refused before it is parsed`() {
    val padded = generic.replace(
      "<friendlyName>Study Amp</friendlyName>",
      "<friendlyName>" + "A".repeat(DeviceDescription.MAX_DESCRIPTION_BYTES) + "</friendlyName>",
    )

    assertThatExceptionOfType(MalformedDescriptionException::class.java)
      .isThrownBy { DeviceDescription.parse(padded, genericUrl) }
      .withMessageContaining("${DeviceDescription.MAX_DESCRIPTION_BYTES}")
  }

  /**
   * Beyond the plan: the accepting side of the same bound. A guard written `>=` rather than `>`
   * on a length nothing ever reaches exactly is unobservable, and this fixes which side of the
   * limit is "still fine" so a later change has to be deliberate.
   */
  @Test
  fun `a description of exactly the maximum size is still parsed`() {
    val padding = DeviceDescription.MAX_DESCRIPTION_BYTES -
      generic.replace("<friendlyName>Study Amp</friendlyName>", "<friendlyName></friendlyName>").length
    val exact = generic.replace(
      "<friendlyName>Study Amp</friendlyName>",
      "<friendlyName>" + "A".repeat(padding) + "</friendlyName>",
    )
    assertThat(exact).hasSize(DeviceDescription.MAX_DESCRIPTION_BYTES)

    assertThat(DeviceDescription.parse(exact, genericUrl).friendlyName).hasSize(padding)
  }

  @Test
  fun `a description that is not xml at all is refused with a readable message`() {
    // A renderer whose HTTP server answers a 404 page with status 200 is a real thing.
    assertThatExceptionOfType(MalformedDescriptionException::class.java)
      .isThrownBy { DeviceDescription.parse("<html><body>Not Found</body></html>", genericUrl) }
  }

  @Test
  fun `a description with no root device element is refused`() {
    assertThatExceptionOfType(MalformedDescriptionException::class.java)
      .isThrownBy { DeviceDescription.parse("<root xmlns=\"urn:schemas-upnp-org:device-1-0\"/>", genericUrl) }
      .withMessageContaining("device")
  }
}
