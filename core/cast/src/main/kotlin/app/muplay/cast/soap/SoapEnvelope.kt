package app.muplay.cast.soap

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * One `in` argument of a UPnP action.
 *
 * A `List<SoapArgument>` rather than a `Map<String, String>` in every signature that takes these,
 * and that is structural rather than stylistic: **UPnP argument order is part of the protocol**.
 * The order is the one the service description declares, and many implementations parse
 * positionally. A strict device answers `402 Invalid Args` to a reordered `SetAVTransportURI`; a
 * lenient one plays the metadata document as if it were the URL. A `LinkedHashMap` happens to
 * preserve insertion order -- and nothing stops a caller handing over a sorted map, and nothing in
 * the type says it would matter.
 */
data class SoapArgument(val name: String, val value: String)

/**
 * SOAP 1.1 envelopes for UPnP control, and the parsing of what comes back.
 *
 * Rendered by string building rather than by a DOM serialiser, deliberately. The document is
 * fixed-shape and tiny, a serialiser's output varies with its implementation (self-closing tags,
 * attribute order, whether an XML declaration is emitted), and **this plan asserts the envelope
 * byte for byte** -- which is only possible if the bytes are chosen here rather than by whichever
 * `TransformerFactory` the platform supplies. Parsing is a real DOM parse, because the input comes
 * from a device.
 *
 * ### Where peer-controlled text enters, and what stops it
 *
 * [render] and [soapActionHeader] both interpolate a `serviceType` and an `action` that were
 * **parsed out of the device description the renderer served** -- into an XML attribute value, an
 * XML element name and an HTTP header value respectively. Every one of those is a place a `"`, a
 * `<` or a CR changes the meaning of the message around it. Both functions therefore run their
 * inputs through [SoapNames] first and refuse with [MalformedSoapRequestException] (an
 * `IOException`) rather than emitting them; see that object for the whole argument, including why
 * the refusal is deliberately not the `IllegalArgumentException`
 * [app.muplay.cast.http.CastHttpClient] would raise for the same bytes one layer down.
 *
 * Argument **values** are the exception, and it is a documented one rather than an oversight: they
 * are inserted verbatim because `CurrentURIMetaData` arrives already XML-escaped from `DidlLite`,
 * and escaping it a second time here is precisely the `&amp;lt;DIDL-Lite` defect. Values are
 * MuPlay's own text; names are not.
 */
object SoapEnvelope {

  /** SOAP requires this exact `Content-Type`, quoted charset included. */
  const val CONTENT_TYPE: String = "text/xml; charset=\"utf-8\""

  /**
   * @throws MalformedSoapRequestException for a service type, action or argument name outside
   *   [SoapNames]'s alphabets -- the peer-controlled inputs. Argument *values* are not checked;
   *   see this object's own KDoc for why that asymmetry is the correct one.
   */
  fun render(serviceType: String, action: String, arguments: List<SoapArgument>): String {
    SoapNames.requireServiceType(serviceType)
    SoapNames.requireAction(action)
    arguments.forEach { SoapNames.requireArgumentName(it.name) }
    return buildString {
      append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
      append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
      append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
      append("<s:Body>")
      append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
      // In order. Not sorted, not a set, not a map.
      arguments.forEach { (name, value) ->
        append('<').append(name).append('>').append(value).append("</").append(name).append('>')
      }
      append("</u:").append(action).append('>')
      append("</s:Body>")
      append("</s:Envelope>")
    }
  }

  /**
   * The `SOAPACTION` header value, **with its quotes**.
   *
   * The quotes are part of the value, not of the Kotlin literal. Sent unquoted, a conformant device
   * answers `401 Invalid Action` -- and a lenient one does not, which is why this is the detail
   * most likely to work on the developer's speaker and fail on the user's.
   *
   * @throws MalformedSoapRequestException for a service type or action outside [SoapNames]'s
   *   alphabets. This is the value the request-splitting demonstration used, so the check is here
   *   rather than left to the caller.
   */
  fun soapActionHeader(serviceType: String, action: String): String =
    "\"${SoapNames.requireServiceType(serviceType)}#${SoapNames.requireAction(action)}\""

  /**
   * The `out` arguments of `<action>Response`, in document order, entity-decoded.
   *
   * Returns an empty map when the body does not carry a response for [action] -- including when it
   * carries a response for a *different* action. Reading whatever element happened to be there
   * would turn a device bug into a position value taken from a volume query.
   */
  fun parseResponse(action: String, xml: String): Map<String, String> {
    val body = bodyOf(xml) ?: return emptyMap()
    val response = childElements(body).firstOrNull {
      it.nodeName.substringAfterLast(':') == "${action}Response"
    } ?: return emptyMap()

    return childElements(response).associate { child ->
      child.nodeName.substringAfterLast(':') to child.textContent.orEmpty()
    }
  }

  /**
   * The UPnP error inside a SOAP fault, or `null` when the body is not a fault.
   *
   * Every UPnP error arrives as **HTTP 500 with a body**, which is why
   * [app.muplay.cast.http.CastHttpClient] returns 5xx rather than throwing: throwing there would
   * turn "Sonos said 714, illegal MIME type" into "the network failed".
   *
   * A fault whose detail this client cannot read is still a fault, reported at
   * [UpnpError.ACTION_FAILED]. "The device refused and did not say why" and "the device did not
   * refuse" are different answers, and only the second one may be `null`.
   */
  fun parseFault(xml: String): UpnpFault? {
    val body = bodyOf(xml) ?: return null
    val fault = childElements(body).firstOrNull { it.nodeName.substringAfterLast(':') == "Fault" }
      ?: return null
    val detail = descendant(fault, "UPnPError") ?: return UpnpFault(UpnpError.ACTION_FAILED, null)
    val code = descendant(detail, "errorCode")?.textContent?.trim()?.toIntOrNull()
      ?: return UpnpFault(UpnpError.ACTION_FAILED, null)
    return UpnpFault(code, descendant(detail, "errorDescription")?.textContent?.trim())
  }

  /**
   * The `<Body>` of [xml], or `null` for anything this client will not parse.
   *
   * `null` rather than an exception, at both call sites: a renderer that answers an HTML error
   * page is a device MuPlay keeps talking to, and the layer above already distinguishes "no fault"
   * from "no response" by the HTTP status code.
   *
   * A `DOCTYPE` is refused **in code**, before the parser sees the document, exactly as
   * [app.muplay.cast.discovery.DeviceDescription.parse] refuses one and for the reason recorded
   * there: the factory features below are the documented hardening switch, but a platform that
   * does not recognise a feature throws rather than ignoring it, so they have to be wrapped, so
   * they are a gate that can silently not run. This body arrives from an unauthenticated device on
   * the LAN and a `DOCTYPE` is how an XML parser is talked into reading a local file or opening a
   * connection. The size of the document is already bounded upstream, by
   * `CastHttpClient.maxBodyBytes`.
   */
  private fun bodyOf(xml: String): Element? {
    if (xml.take(DOCTYPE_SCAN_CHARS).contains("<!DOCTYPE", ignoreCase = true)) return null
    val document = runCatching {
      DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        isExpandEntityReferences = false
        listOf(
          "http://apache.org/xml/features/disallow-doctype-decl" to true,
          "http://xml.org/sax/features/external-general-entities" to false,
          "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
      }.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
    }.getOrNull() ?: return null
    val root = document.documentElement ?: return null
    return childElements(root).firstOrNull { it.nodeName.substringAfterLast(':') == "Body" }
  }

  private fun childElements(parent: Element): List<Element> {
    val children = ArrayList<Element>()
    var node: Node? = parent.firstChild
    while (node != null) {
      (node as? Element)?.let(children::add)
      node = node.nextSibling
    }
    return children
  }

  /** First descendant with this local name, at any depth -- fault details nest inconsistently. */
  private fun descendant(parent: Element, localName: String): Element? {
    childElements(parent).forEach { child ->
      if (child.nodeName.substringAfterLast(':') == localName) return child
      descendant(child, localName)?.let { return it }
    }
    return null
  }

  /** The prologue a `DOCTYPE` has to appear in to be one. Same figure as `DeviceDescription`'s. */
  private const val DOCTYPE_SCAN_CHARS = 4096
}
