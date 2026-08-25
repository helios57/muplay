package app.muplay.cast.didl

import java.io.IOException
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A renderer was promised one format and served another.
 *
 * An `IOException`, like [app.muplay.cast.soap.MalformedSoapRequestException] and for the same
 * reason: every other way a cast operation can fail is one, `CastHttpClient` raises
 * `IllegalArgumentException` for bytes of a similar kind one layer down, and a caller's
 * `catch (e: IOException)` must not miss this.
 */
class MimeDisagreementException(message: String) : IOException(message)

/**
 * **The three-way MIME invariant, as something that can fail rather than something documented.**
 *
 * Spec section 6 records three parties who each decide what format they are about to receive, and
 * each of them reads a different artifact:
 *
 * | Party | Reads | Gets it wrong as |
 * |---|---|---|
 * | Sonos | the **file extension on the URL** | `714 Illegal MIME-type` |
 * | a generic DLNA renderer | `res/@protocolInfo`'s third field | brand-dependent behaviour |
 * | everything else | the proxy's `Content-Type` | a refused or mis-decoded stream |
 *
 * [ServedMedia] is the one value all three are *built* from, which is the design. This object is
 * what makes it a fact: it re-derives each leg **from the artifact its own party sees** -- the
 * `protocolInfo` attribute parsed back out of the rendered document, the extension of the `<res>`
 * URL taken the way a renderer sniffing a path takes it, and the `Content-Type` header string --
 * and names every way they disagree.
 *
 * That distinction is the whole point. An assertion that `served.protocolInfo` contains
 * `served.mimeType` compares two expressions over the same object and passes for a
 * [ServedMedia] whose extension implies something else entirely; the failure it cannot see is the
 * one where **two legs agree and the third silently differs**. Here the three legs arrive as three
 * unrelated strings and are compared as such.
 *
 * The input is the **rendered document**, not a [CastItem], deliberately: given the item, a caller
 * would ask it for the protocolInfo and the URL and be back to comparing one object with itself.
 */
object MimeAgreement {

  /**
   * Every way the three legs of [didlDocument] and [servedContentType] disagree; empty when they
   * agree.
   *
   * A list rather than a boolean because "they disagree" is not actionable and "protocolInfo says
   * `audio/ogg`, the `.mp3` URL implies `audio/mpeg`" is, and because a caller that gets an empty
   * list has been told something stronger than `true`.
   */
  fun disagreements(didlDocument: String, servedContentType: String?): List<String> {
    val resource = resourceElementOf(didlDocument)
      ?: return listOf(
        "the DIDL document declares no <res> element this client can read, so it states no format at all",
      )

    val problems = mutableListOf<String>()

    val protocolInfo = resource.getAttribute("protocolInfo")
    val declaredMime = mimeOfProtocolInfo(protocolInfo)
    if (declaredMime == null) {
      problems += "res/@protocolInfo (\"$protocolInfo\") names no MIME type in its third field"
    }

    // `textContent`, not `textContent.orEmpty()`: it is a platform type the DOM never returns null
    // for, and the `.orEmpty()` would add an arm no test can reach. Same trade-off `SoapEnvelope`
    // and `DeviceDescription` record on their own text accessors.
    val resourceUrl = resource.textContent.trim()
    val extension = extensionOfUrl(resourceUrl)
    val urlMime = ServedMedia.forExtension(extension)?.mimeType
    if (extension == null) {
      problems += "the resource URL <$resourceUrl> carries no file extension, and Sonos infers the " +
        "MIME type from the URL rather than from Content-Type"
    } else if (urlMime == null) {
      problems += "the resource URL's .$extension extension is not a format this client serves, so " +
        "a renderer that infers the MIME type from the URL cannot arrive at the served format"
    }

    val servedMime = mimeOfContentType(servedContentType)
    if (servedMime == null) {
      problems += "the proxy states no Content-Type, so a renderer that reads one has nothing to read"
    }

    // The comparison itself: the distinct set of what the legs that *did* state something say. One
    // check over the set rather than three pairwise ones -- with all three present, pairwise
    // equality is transitive, so the third comparison could never fail on its own and would be
    // exactly the assertion-that-cannot-fail this project keeps finding.
    if (listOfNotNull(declaredMime, urlMime, servedMime).distinct().size > 1) {
      problems += "the served format is stated three ways and they disagree: protocolInfo says " +
        "$declaredMime, the URL extension .$extension implies $urlMime, and Content-Type says $servedMime"
    }
    return problems
  }

  /**
   * [disagreements], as a refusal.
   *
   * The `throw` is written at this call site rather than inside a `refuse(): Nothing` helper, on a
   * measurement Task 3 paid for: JaCoCo places a probe *after* a call, so a function returning
   * `Nothing` reports zero coverage however thoroughly it is tested.
   *
   * @throws MimeDisagreementException an `IOException`, naming every leg that disagrees.
   */
  fun require(didlDocument: String, servedContentType: String?) {
    val problems = disagreements(didlDocument, servedContentType)
    if (problems.isNotEmpty()) {
      throw MimeDisagreementException(
        "the format promised to this renderer is stated inconsistently: ${problems.joinToString("; ")}",
      )
    }
  }

  /**
   * The MIME type in the third field of a `protocolInfo`, lowercased, or `null` when there is not
   * one.
   *
   * `protocolInfo` is four colon-separated fields (`protocol:network:contentFormat:additionalInfo`)
   * and a MIME type contains no colon, so a value with fewer than four is one this client will not
   * guess at.
   */
  fun mimeOfProtocolInfo(protocolInfo: String): String? {
    val fields = protocolInfo.split(':')
    if (fields.size < PROTOCOL_INFO_FIELDS) return null
    return fields[PROTOCOL_INFO_MIME_FIELD].trim().lowercase().ifEmpty { null }
  }

  /**
   * The extension of the **path** of [url], lowercased, or `null` when the path has none.
   *
   * The path, and not the string: a query parameter carrying `.mp3` is not what a renderer sniffs,
   * and `http://host.example` must not read as an `.example` file. Parsed with [URI] rather than by
   * hand for exactly those two reasons; a URL [URI] will not parse, and an opaque one such as
   * `mailto:`, have no path and therefore no extension.
   *
   * `null` and not [ServedMedia.FALLBACK_EXTENSION]: this answers what a *peer* will conclude, and
   * "nothing" is a different answer from "MP3".
   */
  fun extensionOfUrl(url: String): String? {
    val path = runCatching { URI(url).path }.getOrNull() ?: return null
    val segment = path.substringAfterLast('/')
    if (!segment.contains('.')) return null
    return segment.substringAfterLast('.').lowercase().ifEmpty { null }
  }

  /** The MIME type of a `Content-Type` header, parameters and case dropped, or `null`. */
  fun mimeOfContentType(header: String?): String? =
    header.orEmpty().substringBefore(';').trim().lowercase().ifEmpty { null }

  /**
   * The first `<res>` of [didlDocument], or `null` for anything this client will not read.
   *
   * A `DOCTYPE` is refused **in code**, before the parser sees the document, for the reason
   * `SoapEnvelope.bodyOf` and `DeviceDescription.parse` both record: the factory features below are
   * the documented hardening switch, but a platform that does not recognise one throws rather than
   * ignoring it, so they are a gate that can silently not run. A metadata document is not always
   * MuPlay's own -- a renderer echoes one back from `GetMediaInfo` -- and a DOCTYPE is how an XML
   * parser is talked into reading a local file.
   */
  private fun resourceElementOf(didlDocument: String): Element? {
    if (didlDocument.take(DOCTYPE_SCAN_CHARS).contains("<!DOCTYPE", ignoreCase = true)) return null
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
      }.newDocumentBuilder().parse(didlDocument.byteInputStream(Charsets.UTF_8))
    }.getOrNull() ?: return null
    val elements = document.getElementsByTagName("res")
    if (elements.length == 0) return null
    return elements.item(0) as Element
  }

  /** `protocol:network:contentFormat:additionalInfo`. */
  private const val PROTOCOL_INFO_FIELDS = 4
  private const val PROTOCOL_INFO_MIME_FIELD = 2

  /** The prologue a `DOCTYPE` has to appear in to be one. Same figure as `SoapEnvelope`'s. */
  private const val DOCTYPE_SCAN_CHARS = 4096
}
