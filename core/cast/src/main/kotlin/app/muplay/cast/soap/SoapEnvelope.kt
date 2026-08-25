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
 * Argument **values** are not exempt. They are escaped here, with [XmlText], and this layer is the
 * only place in the module that escapes anything -- because **escaping is framing, and framing
 * belongs to whichever layer owns the envelope.**
 *
 * That is a correction, and the argument it replaces is worth recording so it is not made again.
 * `render` used to insert values verbatim, on the reasoning that `CurrentURIMetaData` arrives
 * already escaped from `DidlLite.renderEscaped` and escaping it twice is the `&amp;lt;DIDL-Lite`
 * defect. The reasoning was sound and the conclusion was wrong, in two measurable ways:
 *
 *  * **It was not true that values are safe.** An ordinary Navidrome stream URL
 *    (`/rest/stream?u=x&t=y&s=z`) is MuPlay's own text, and inserted verbatim it made the envelope
 *    fail to parse at *"The reference to entity `t` must end with ';'"* -- so [parseResponse] could
 *    not read back what `render` had just written, and no device could either. A value of
 *    `"x</CurrentURI><Speed>99</Speed><CurrentURI>y"` silently added a third argument.
 *  * **It made correctness a convention.** Two `DidlLite` functions existed, one safe to pass here
 *    and one not, and every future caller had to remember which. This module's discipline is to
 *    make the forbidden value unrepresentable rather than to document it, so `renderEscaped` is
 *    gone and there is nothing left to pass by mistake.
 *
 * The consequence on the wire is the one UPnP asks for: `CurrentURIMetaData` carries
 * `&lt;DIDL-Lite...`. That is not double escaping. The argument is a **string-typed** value whose
 * content happens to be a document, and a string-typed value inside an XML element is escaped
 * exactly once, by the element's own writer -- here.
 */
object SoapEnvelope {

  /** SOAP requires this exact `Content-Type`, quoted charset included. */
  const val CONTENT_TYPE: String = "text/xml; charset=\"utf-8\""

  /**
   * The envelope for one action, **total**: every call either returns well-formed XML or throws.
   *
   * Argument values are taken as the text they are and escaped once with [XmlText.escape]. Pass
   * the DIDL-Lite document itself for `CurrentURIMetaData`, not an escaped rendering of it --
   * `DidlLite.render` is the whole of what a caller owes this function.
   *
   * @throws MalformedSoapRequestException for a service type, action or argument name outside
   *   [SoapNames]'s alphabets -- the peer-controlled inputs. Argument *values* need no alphabet,
   *   because escaping makes every one of them representable; see this object's own KDoc.
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
      // In order. Not sorted, not a set, not a map. And escaped -- once, here, by the layer that
      // writes the element around it.
      arguments.forEach { (name, value) ->
        append('<').append(name).append('>')
        append(XmlText.escape(value))
        append("</").append(name).append('>')
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
   * The `out` arguments of `<action>Response`, in document order, entity-decoded -- or **`null`
   * when this body carries no response for [action] at all**.
   *
   * `null` and an empty map are two different facts and this function used to answer the same
   * value for both. An empty map is *"the device answered this action, and it has no out
   * arguments"* -- which is what `Play`, `Stop` and `SetAVTransportURI` really do answer. `null` is
   * *"there is no answer to this action in here"*: the body was not XML, or had no `<Body>`, or
   * carried a response for a **different** action. Reading whichever element happened to be there
   * would turn a device bug into a position taken from a volume query, and reporting it as an
   * empty success turns it into a position of zero.
   *
   * The same reasoning [parseFault] applies from the other side, where it answers
   * `UpnpFault(ACTION_FAILED, null)` rather than `null` for a fault it cannot read: **preserve the
   * fact, degrade only the detail.** Here the fact being preserved is that there was no readable
   * result, and [SoapClient.invoke] turns it into the [SoapTransportException] its own KDoc has
   * always promised for a renderer that *"answered something unreadable"* -- a promise this
   * function's empty map used to quietly break.
   */
  fun parseResponse(action: String, xml: String): Map<String, String>? {
    val body = bodyOf(xml) ?: return null
    val response = childElements(body).firstOrNull {
      it.nodeName.substringAfterLast(':') == "${action}Response"
    } ?: return null

    // `child.textContent`, not `child.textContent.orEmpty()`: `textContent` is a platform type, so
    // the `.orEmpty()` inserts a null branch for a value the DOM never produces -- an arm no test
    // can reach. `DeviceDescription` records the same trade-off on its own `childText`.
    return childElements(response).associate { child ->
      child.nodeName.substringAfterLast(':') to child.textContent
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
    val code = trimmedTextOf(descendant(detail, "errorCode"))?.toIntOrNull()
      ?: return UpnpFault(UpnpError.ACTION_FAILED, null)
    return UpnpFault(code, trimmedTextOf(descendant(detail, "errorDescription")))
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
    if (declaresDoctype(xml)) return null
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
    // No null guard on `documentElement`, for the reason `DeviceDescription.parse` records against
    // its own: a `Document` that parsed has one, and an elvis here would be a branch no test could
    // ever reach.
    val root = document.documentElement
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

  /**
   * The trimmed text of [element], or `null` when there is no such element.
   *
   * One nullable check, on the one value that is really nullable. A `?.textContent?.trim()` chain
   * would add a second on `textContent`, which is a platform type the DOM never returns null for.
   */
  private fun trimmedTextOf(element: Element?): String? = element?.let { it.textContent.trim() }

  /**
   * First descendant with this local name, to a depth of [MAX_FAULT_DEPTH] -- fault details nest
   * inconsistently, but they do not nest deeply.
   *
   * The bound is the whole point of this signature. This walk is recursive, it is reached from
   * [parseFault], and `SoapClient.invoke` calls **that** on every response, outside its
   * `try`/`catch` and outside the `runCatching` that guards the parse. So a body of nothing but
   * nested elements -- measured by the review that found this: depth 8000 at the default stack,
   * depth 3000 at `-Xss512k`, which is roughly an Android worker thread -- used to answer with a
   * `StackOverflowError`, from an unauthenticated device on the LAN, at around 56 KB: far inside
   * `CastHttpClient.maxBodyBytes`, so the size guard did not bound it either.
   *
   * That is not a slow parse or a wrong answer. It falsifies this layer's headline contract:
   * [SoapClient]'s KDoc tells Tasks 5, 8 and 9 that *"one `catch (e: IOException)` around a
   * `SoapClient` call is complete"*, and a `StackOverflowError` is an `Error`, so every guard
   * those tasks were told to write misses it and the coroutine dies. It is the same defect, in the
   * same module, as `DeviceDescription.parseDevice`'s unbounded `deviceList` walk, and it is fixed
   * the same way.
   *
   * Stopping rather than throwing, unlike `DeviceDescription`'s: this function's caller is
   * documented never to throw, and the degradation is already a meaning this protocol has. A fault
   * whose `UPnPError` is buried deeper than [MAX_FAULT_DEPTH] is reported as
   * [UpnpError.ACTION_FAILED] -- "the device refused and did not say why" -- which is exactly what
   * [parseFault] already answers for a fault whose detail it cannot read.
   */
  private fun descendant(parent: Element, localName: String, depth: Int = 0): Element? {
    if (depth > MAX_FAULT_DEPTH) return null
    childElements(parent).forEach { child ->
      if (child.nodeName.substringAfterLast(':') == localName) return child
      descendant(child, localName, depth + 1)?.let { return it }
    }
    return null
  }

  /**
   * How deep [descendant] will look. A real fault puts `UPnPError` two elements below `Fault`
   * (`detail/UPnPError`), so this is sixteen times the shape every device actually sends.
   */
  private const val MAX_FAULT_DEPTH = 32

  /**
   * Whether [xml] declares a `DOCTYPE` anywhere in it. `internal` **so that a test can observe
   * this decision directly**, and that is not a convenience.
   *
   * This used to scan `xml.take(4096)`, on the reasoning that a `DOCTYPE` is only legal in the
   * prolog. It is, and the prolog has no length limit: a comment is legal `Misc` before the
   * doctype, so `<!--` + five thousand spaces + `-->` + `<!DOCTYPE ...>` walks straight past a
   * window of any fixed size. `DeviceDescription.rejectDoctype` carried the identical window and
   * the identical hole; both are now the whole document, which is free over a body
   * `CastHttpClient.maxBodyBytes` has already bounded.
   *
   * **And the end-to-end assertion cannot see any of that**, which is why this is `internal`
   * rather than private. [bodyOf] answers `null` for a refused `DOCTYPE` and `null` for a document
   * the parser would not read -- and on the JVM the `disallow-doctype-decl` feature below refuses
   * that document itself, so `parseFault(...) == null` is green whether this scan looks at four
   * kilobytes, at everything, or at nothing at all. The platform where it matters is Android,
   * where that feature is expected to be refused at `setFeature` (which is the entire reason the
   * scan exists) and no tier of this project can observe it. So the test asserts this predicate,
   * exactly as `DeviceDescriptionTest` asserts its sibling's own sentence rather than the word
   * "DOCTYPE" that SAX also says.
   */
  internal fun declaresDoctype(xml: String): Boolean = xml.contains("<!DOCTYPE", ignoreCase = true)
}
