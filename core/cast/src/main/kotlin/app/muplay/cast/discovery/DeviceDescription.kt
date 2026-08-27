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

  /**
   * How deep `deviceList` nesting may go before the description is refused.
   *
   * The root device is depth 0, so a Sonos -- root `ZonePlayer`, embedded `MediaRenderer` and
   * `MediaServer` -- reaches 1, and this is four times the deepest shape anyone has published.
   *
   * It is a **bound on the stack**, not a taste in schemas. [parseDevice] walks the tree by
   * recursion, and a walk with nothing stopping it is not a refusal but a `StackOverflowError`:
   * measured here, `<root><device>` plus ten thousand nested `<deviceList><device>` pairs is
   * 420,030 characters, comfortably under [MAX_DESCRIPTION_BYTES], parses into a DOM without
   * complaint, and then blows the stack in the walk. Android's stacks are smaller, so it blows
   * shallower there.
   *
   * That was contained only by luck -- Kotlin's `runCatching` catches `Throwable`, so
   * `RendererDirectory.describe` happened to drop the device -- and luck is not the contract:
   * [parse] is public API whose KDoc enumerates what it throws, and Task 3's `SoapClient` tells
   * Tasks 5, 8 and 9 that one `catch (e: IOException)` around a call is complete. An `Error`
   * escaping into one of those is a crash, and swallowing one is not a state to carry on from.
   */
  const val MAX_DEVICE_DEPTH: Int = 8

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

    return parseDevice(deviceElement, base, descriptionUrl, depth = 0)
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
   * is **this** check -- by its own wording -- that the tests assert against. The factory features
   * stay as defence in depth.
   *
   * ### The whole document, not a prologue-sized window
   *
   * This used to scan the first 4096 characters, on the reasoning that a `DOCTYPE` is only legal
   * in the prolog. It is, and the prolog has no length limit: a **comment** is legal `Misc` before
   * the doctype, so `<!--` + five thousand spaces + `-->` + `<!DOCTYPE root [<!ENTITY xxe SYSTEM
   * "file:///etc/hostname">]>` is a perfectly well-formed document, five thousand characters
   * short of [MAX_DESCRIPTION_BYTES], with its doctype at index 5008. The window returned false
   * on it.
   *
   * No test in this project could see that hole, and the reason is exactly the reason the window
   * was dangerous: on the JVM the Apache feature above *does* catch it, so the bypass is invisible
   * here and live on Android, where that feature is expected to be refused at `setFeature` and
   * Expat will fetch a `SYSTEM` entity with no `EntityResolver` in the way. The regression test
   * therefore asserts this function's own sentence and not merely the refusal.
   *
   * A `contains` over a string already capped at [MAX_DESCRIPTION_BYTES] is free. Refusing a
   * description that carries the literal `<!DOCTYPE` inside a text node is the right side to fail
   * on: this document comes from an unauthenticated device on the local network, and no renderer
   * has any business putting that string in a friendly name.
   */
  private fun rejectDoctype(xml: String, descriptionUrl: URI) {
    if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
      throw MalformedDescriptionException(
        "device description at $descriptionUrl declares a DOCTYPE. MuPlay refuses one: this " +
          "document comes from an unauthenticated device on the local network, and a DOCTYPE is " +
          "how an XML parser is talked into reading a file or opening a connection.",
      )
    }
  }

  private fun hardenedFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
      // Namespace-unaware on purpose: matching is by local name (see this object's KDoc).
      isNamespaceAware = false
      // `runCatching`, and this one is NOT defence in depth -- it is the difference between this
      // module working on a device and not working at all. `javax.xml.parsers.DocumentBuilderFactory`
      // implements `setXIncludeAware` by throwing, and **Android's parser does not override it**:
      //
      //   java.lang.UnsupportedOperationException: This parser does not support specification
      //   "Unknown" version "0.0"
      //
      // Measured on `muplay37` in Plan 6 Task 9, where it surfaced as
      // `MalformedDescriptionException: ... is not XML` for a document that is perfectly well
      // formed. `:core:cast` is a pure-JVM module whose whole test tier is the JVM, so no gate in
      // this repository could see it: on the JVM, Xerces implements this setter and every one of
      // these three call sites is fine. Swallowing is safe because Android's parser has no XInclude
      // support to switch off, and on the JVM the call succeeds.
      runCatching { isXIncludeAware = false }
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

  /** @param depth 0 for the root device, one more for each `deviceList` it is nested inside. */
  private fun parseDevice(element: Element, base: URI, descriptionUrl: URI, depth: Int): UpnpDevice {
    // Refused rather than walked. See MAX_DEVICE_DEPTH: without this the walk is a
    // StackOverflowError on a document that is well under every other bound here.
    if (depth > MAX_DEVICE_DEPTH) {
      throw MalformedDescriptionException(
        "device description at $descriptionUrl nests devices more than $MAX_DEVICE_DEPTH deep",
      )
    }
    return UpnpDevice(
      deviceType = childText(element, "deviceType").orEmpty(),
      udn = childText(element, "UDN").orEmpty(),
      friendlyName = childText(element, "friendlyName").orEmpty(),
      manufacturer = childText(element, "manufacturer"),
      modelName = childText(element, "modelName"),
      services = childElement(element, "serviceList")
        ?.let { list -> childElements(list, "service").mapNotNull { parseService(it, base) } }
        .orEmpty(),
      embedded = childElement(element, "deviceList")
        ?.let { list ->
          childElements(list, "device").map { parseDevice(it, base, descriptionUrl, depth + 1) }
        }
        .orEmpty(),
    )
  }

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

  /**
   * The trimmed text of a child element, or `null` when there is no such child or it is empty.
   *
   * `.orEmpty()` rather than a `?.` chain through `textContent` and `trim()`: both of those are
   * platform types, so a chain inserts null checks for values the DOM never produces -- branches
   * no test can reach, which is a worse thing to leave behind than one extra call.
   */
  private fun childText(parent: Element, localName: String): String? =
    childElement(parent, localName)?.textContent.orEmpty().trim().takeIf { it.isNotEmpty() }
}
