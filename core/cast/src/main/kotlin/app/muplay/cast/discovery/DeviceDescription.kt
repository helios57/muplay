package app.muplay.cast.discovery

import java.io.IOException
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Thrown when a device's description is not something this client will act on. */
class MalformedDescriptionException(message: String) : IOException(message)

/** One service on one device, with its URLs already absolute. */
data class UpnpService(
  val serviceType: String,
  val serviceId: String,
  val controlUrl: URI,
  val scpdUrl: URI?,
)

/** One device in a description -- the root, or one nested inside a `deviceList`. */
data class UpnpDevice(
  val deviceType: String,
  val udn: String,
  val friendlyName: String,
  val manufacturer: String?,
  val modelName: String?,
  val services: List<UpnpService>,
  val embedded: List<UpnpDevice>,
) {
  /** This device followed by every device nested inside it, in document order. */
  fun flatten(): List<UpnpDevice> = listOf(this) + embedded.flatMap { it.flatten() }

  fun service(serviceType: String): UpnpService? = services.firstOrNull { it.serviceType == serviceType }
}

/**
 * The UPnP device description parser.
 *
 * Three things it does that the obvious implementation does not:
 *
 * 1. **It recurses into `deviceList`.** Sonos's root device is a `ZonePlayer`, and the
 *    `MediaRenderer` with the `AVTransport` service is *nested inside it*, alongside a
 *    `MediaServer`. A parser that reads `root/device/serviceList` and stops concludes that a Sonos
 *    is not a renderer, and the symptom is the named user requirement quietly missing from the
 *    picker.
 * 2. **It resolves relative URLs against `URLBase` when present and the description URL when not.**
 *    UPnP 1.1 deprecated `URLBase`; devices still send it, and when they do it wins.
 * 3. **It refuses a `DOCTYPE`.** This document arrives from an unauthenticated device on the LAN,
 *    over a protocol where anything that can send a datagram chooses what URL this app fetches.
 *
 * Namespace handling is deliberately by **local name**: real descriptions use the
 * `urn:schemas-upnp-org:device-1-0` default namespace, some use a prefix, and a few omit it. There
 * is nothing to gain from being strict about a namespace that no device disagrees about in
 * meaning, and a great deal to lose from rejecting a working speaker over a prefix.
 */
object DeviceDescription {

  const val DEVICE_MEDIA_RENDERER: String = "urn:schemas-upnp-org:device:MediaRenderer:1"
  const val SERVICE_AV_TRANSPORT: String = "urn:schemas-upnp-org:service:AVTransport:1"
  const val SERVICE_RENDERING_CONTROL: String = "urn:schemas-upnp-org:service:RenderingControl:1"

  /**
   * A real description is 2-20 KB. Half a megabyte is a device misbehaving.
   *
   * Counted in **characters**, which is a conservative bound on bytes: UTF-8 never encodes a
   * character in fewer than one byte, so a document over this many characters is over this many
   * bytes too. The reverse does not hold, so a document of multi-byte characters can be up to
   * three times this in bytes -- still bounded, and still far below anything that hurts.
   */
  const val MAX_DESCRIPTION_BYTES: Int = 512 * 1024

  fun parse(xml: String, descriptionUrl: URI): UpnpDevice {
    if (xml.length > MAX_DESCRIPTION_BYTES) {
      throw MalformedDescriptionException(
        "device description at $descriptionUrl exceeds $MAX_DESCRIPTION_BYTES bytes",
      )
    }
    rejectDoctype(xml, descriptionUrl)

    val document = runCatching {
      hardenedFactory().newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
    }.getOrElse { cause ->
      throw MalformedDescriptionException("device description at $descriptionUrl is not XML: ${cause.message}")
    }

    // No null guard on `documentElement`, deliberately: a `Document` that parsed successfully
    // always has one -- `DocumentBuilder.parse` throws `SAXParseException` on an empty or
    // element-less input, which the `runCatching` above has already turned into a
    // MalformedDescriptionException. An elvis here would be a branch no test could ever reach.
    val root = document.documentElement
    val base = childText(root, "URLBase")?.let { runCatching { URI(it) }.getOrNull() } ?: descriptionUrl
    val deviceElement = childElement(root, "device")
      ?: throw MalformedDescriptionException("device description at $descriptionUrl has no <device> element")

    return parseDevice(deviceElement, base)
  }

  /**
   * The portable half of the XXE defence, and the load-bearing half.
   *
   * `DocumentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl")` is
   * the documented hardening switch, and it is applied below -- but Android's XML implementation is
   * not Xerces, and a feature it does not recognise throws `ParserConfigurationException` at
   * configuration time. A hardening step that has to be wrapped in a `runCatching` to be portable
   * is a gate that can silently not run, which is the defect class this project exists to prevent.
   *
   * So the refusal is also done here, in code that behaves identically on both platforms, and it
   * is **this** check the test asserts against. The factory features stay as defence in depth.
   */
  private fun rejectDoctype(xml: String, descriptionUrl: URI) {
    val prologue = xml.take(DOCTYPE_SCAN_BYTES)
    if (prologue.contains("<!DOCTYPE", ignoreCase = true)) {
      throw MalformedDescriptionException(
        "device description at $descriptionUrl declares a DOCTYPE. MuPlay refuses one: this " +
          "document comes from an unauthenticated device on the local network, and a DOCTYPE is " +
          "how an XML parser is talked into reading a file or opening a connection.",
      )
    }
  }

  private const val DOCTYPE_SCAN_BYTES = 4096

  private fun hardenedFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
      // Namespace-unaware on purpose: matching is by local name (see this object's KDoc).
      isNamespaceAware = false
      isXIncludeAware = false
      isExpandEntityReferences = false
      // Defence in depth. Each is wrapped because an implementation that does not know a feature
      // throws rather than ignoring it, and Android's parser is not Xerces. `rejectDoctype` is
      // the check that is guaranteed to have run.
      listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
      ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
      runCatching { setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "") }
      runCatching { setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
    }

  private fun parseDevice(element: Element, base: URI): UpnpDevice = UpnpDevice(
    deviceType = childText(element, "deviceType").orEmpty(),
    udn = childText(element, "UDN").orEmpty(),
    friendlyName = childText(element, "friendlyName").orEmpty(),
    manufacturer = childText(element, "manufacturer"),
    modelName = childText(element, "modelName"),
    services = childElement(element, "serviceList")
      ?.let { list -> childElements(list, "service").mapNotNull { parseService(it, base) } }
      .orEmpty(),
    embedded = childElement(element, "deviceList")
      ?.let { list -> childElements(list, "device").map { parseDevice(it, base) } }
      .orEmpty(),
  )

  private fun parseService(element: Element, base: URI): UpnpService? {
    val type = childText(element, "serviceType") ?: return null
    val control = childText(element, "controlURL") ?: return null
    val controlUri = runCatching { base.resolve(control) }.getOrNull() ?: return null
    return UpnpService(
      serviceType = type,
      serviceId = childText(element, "serviceId").orEmpty(),
      controlUrl = controlUri,
      scpdUrl = childText(element, "SCPDURL")?.let { runCatching { base.resolve(it) }.getOrNull() },
    )
  }

  /** Direct children only, matched by local name -- never `getElementsByTagName`, which recurses. */
  private fun childElements(parent: Element, localName: String): List<Element> {
    val children = ArrayList<Element>()
    var node: Node? = parent.firstChild
    while (node != null) {
      if (node is Element && node.nodeName.substringAfterLast(':') == localName) children += node
      node = node.nextSibling
    }
    return children
  }

  private fun childElement(parent: Element, localName: String): Element? =
    childElements(parent, localName).firstOrNull()

  private fun childText(parent: Element, localName: String): String? =
    childElement(parent, localName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
