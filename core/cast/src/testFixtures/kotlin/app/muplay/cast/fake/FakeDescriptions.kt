package app.muplay.cast.fake

import app.muplay.cast.discovery.DescriptionFetcher
import app.muplay.cast.http.HttpHeaders
import app.muplay.cast.http.HttpWire
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A loopback HTTP server that serves UPnP device descriptions and nothing else.
 *
 * **Not a `MockWebServer`**, and the reason is structural rather than a preference: a description
 * URL is chosen by the *device*, so a discovery test has to serve several documents at several
 * paths at once and be asked for them in whatever order the datagrams happen to arrive.
 * `MockWebServer` dispatches from a queue by default, which is exactly the wrong shape -- the
 * first fetch would get the Sonos document whichever device it was asking about.
 *
 * It answers `200` with the document for a path it knows and `404` for one it does not, so the
 * "a device whose description cannot be fetched is left out" case is a real 404 over a real socket
 * rather than a fake returning `null`.
 */
class FakeDescriptions(private val documents: Map<String, String>) : Closeable {

  private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
  private val requested = CopyOnWriteArrayList<String>()

  val port: Int get() = server.localPort

  /** Every request target this server was asked for, in arrival order. */
  val requests: List<String> get() = requested.toList()

  fun url(path: String): String = "http://127.0.0.1:$port$path"

  /**
   * The **production** fetcher, over a real [app.muplay.cast.http.CastHttpClient], not a lambda
   * that reaches into [documents]. The decision "a 404 is not a description" then lives where it
   * ships instead of in this fake -- a test whose own fetcher returned `null` for a 404 would
   * prove nothing about the one the app wires up.
   */
  fun client(): (URI) -> String? = DescriptionFetcher.overHttp()

  fun start() {
    thread(isDaemon = true, name = "fake-descriptions") {
      while (!server.isClosed) {
        val socket = runCatching { server.accept() }.getOrNull() ?: continue
        thread(isDaemon = true, name = "fake-descriptions-conn") {
          socket.use {
            runCatching {
              val head = HttpWire.readRequestHead(it.getInputStream())
              requested += head.target
              val document = documents[head.target]
              val body = document?.toByteArray(Charsets.UTF_8) ?: NOT_FOUND
              val status = if (document == null) 404 else 200
              val reason = if (document == null) "Not Found" else "OK"
              it.getOutputStream().apply {
                write(
                  HttpWire.renderResponseHead(
                    status,
                    reason,
                    HttpHeaders.of(
                      // `charset="utf-8"` because a friendly name is "Küche" more often than not
                      // in this project's own household.
                      "CONTENT-TYPE" to "text/xml; charset=\"utf-8\"",
                      "Content-Length" to body.size.toString(),
                      "Connection" to "close",
                    ),
                  ),
                )
                write(body)
                flush()
              }
            }
          }
        }
      }
    }
  }

  override fun close() = server.close()

  private companion object {
    const val BACKLOG = 16
    val NOT_FOUND = "not found".toByteArray(Charsets.UTF_8)
  }
}

/**
 * A generic DLNA renderer: the root device **is** the `MediaRenderer`, which is the shape every
 * UPnP example shows and the shape Sonos does not have.
 */
fun genericDescription(udn: String, friendlyName: String): String = """
  <?xml version="1.0"?>
  <root xmlns="urn:schemas-upnp-org:device-1-0">
    <specVersion><major>1</major><minor>0</minor></specVersion>
    <device>
      <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
      <friendlyName>$friendlyName</friendlyName>
      <manufacturer>Yamaha Corporation</manufacturer>
      <modelName>WXA-50</modelName>
      <UDN>$udn</UDN>
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

/**
 * A Sonos: a `ZonePlayer` root with a `MediaServer` **and** a `MediaRenderer` nested inside its
 * `deviceList`, and the `AVTransport` only on the latter.
 */
fun sonosDescription(udn: String, friendlyName: String): String = """
  <?xml version="1.0" encoding="utf-8"?>
  <root xmlns="urn:schemas-upnp-org:device-1-0">
    <specVersion><major>1</major><minor>0</minor></specVersion>
    <device>
      <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
      <friendlyName>$friendlyName</friendlyName>
      <manufacturer>Sonos, Inc.</manufacturer>
      <modelName>Sonos One</modelName>
      <UDN>$udn</UDN>
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
          <friendlyName>$friendlyName Media Server</friendlyName>
          <UDN>${udn}_MS</UDN>
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
          <friendlyName>$friendlyName Media Renderer</friendlyName>
          <UDN>${udn}_MR</UDN>
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

/**
 * A `MediaServer` -- a NAS, or the one Sonos advertises alongside its renderer. It has a
 * `ContentDirectory` and **no `AVTransport`**, so nothing can be cast to it.
 */
fun mediaServerDescription(udn: String, friendlyName: String): String = """
  <?xml version="1.0"?>
  <root xmlns="urn:schemas-upnp-org:device-1-0">
    <specVersion><major>1</major><minor>0</minor></specVersion>
    <device>
      <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
      <friendlyName>$friendlyName</friendlyName>
      <manufacturer>Synology</manufacturer>
      <modelName>DS220+</modelName>
      <UDN>$udn</UDN>
      <serviceList>
        <service>
          <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
          <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
          <controlURL>/MediaServer/ContentDirectory/Control</controlURL>
          <SCPDURL>/xml/ContentDirectory1.xml</SCPDURL>
        </service>
        <service>
          <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
          <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
          <controlURL>/MediaServer/ConnectionManager/Control</controlURL>
          <SCPDURL>/xml/ConnectionManager1.xml</SCPDURL>
        </service>
      </serviceList>
    </device>
  </root>
""".trimIndent()

/**
 * A home router's UPnP IGD. It answers SSDP loudly, has a `deviceList` of its own (so a parser
 * that recursed *and* accepted anything would still have to reject it), and nothing in it can
 * play audio.
 */
fun internetGatewayDescription(udn: String, friendlyName: String): String = """
  <?xml version="1.0"?>
  <root xmlns="urn:schemas-upnp-org:device-1-0">
    <specVersion><major>1</major><minor>0</minor></specVersion>
    <device>
      <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
      <friendlyName>$friendlyName</friendlyName>
      <manufacturer>AVM Berlin</manufacturer>
      <modelName>FRITZ!Box 7590</modelName>
      <UDN>$udn</UDN>
      <serviceList>
        <service>
          <serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
          <serviceId>urn:upnp-org:serviceId:Layer3Forwarding1</serviceId>
          <controlURL>/igdupnp/control/Layer3Forwarding</controlURL>
          <SCPDURL>/igdupnp/l3fwd.xml</SCPDURL>
        </service>
      </serviceList>
      <deviceList>
        <device>
          <deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType>
          <friendlyName>$friendlyName WAN</friendlyName>
          <UDN>${udn}_WAN</UDN>
          <serviceList>
            <service>
              <serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType>
              <serviceId>urn:upnp-org:serviceId:WANCommonIFC1</serviceId>
              <controlURL>/igdupnp/control/WANCommonIFC1</controlURL>
              <SCPDURL>/igdupnp/wancommonifc.xml</SCPDURL>
            </service>
          </serviceList>
        </device>
      </deviceList>
    </device>
  </root>
""".trimIndent()
