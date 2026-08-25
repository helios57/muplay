package app.muplay.cast.soap

import java.io.IOException
import java.net.URI

/**
 * A renderer-supplied name or URL this client refuses to put on the wire, or to open a socket to.
 *
 * An **`IOException`**, deliberately and load-bearingly. Talking to a renderer is I/O, every
 * caller in Plan 6 already guards it with `catch (IOException)`, and the alternative -- the
 * `IllegalArgumentException` [app.muplay.cast.http.CastHttpClient] raises for the same inputs --
 * arrives as a crash that such a guard does not see. See [SoapNames] for the whole argument.
 */
class MalformedSoapRequestException(message: String) : IOException(message)

/**
 * **The alphabet of everything peer-controlled that this module writes into a message.**
 *
 * Three inputs to a SOAP call come out of the device-description XML *the renderer itself served*
 * -- its `serviceType`, its action names and its `controlURL` -- over a protocol where anything
 * on the LAN that can send a datagram chooses which URL MuPlay fetches that description from. Each
 * one is then interpolated somewhere that peer-chosen text changes the meaning of the message
 * around it:
 *
 * | Input | Where it lands | What an unfiltered byte does there |
 * |---|---|---|
 * | `serviceType` | the `SOAPACTION` **header value**, and `xmlns:u="..."` | CR/LF ends the header and starts one the device chose; `"` ends the attribute |
 * | action name | `SOAPACTION`, and the element name `<u:Play>` | the same, plus a document that is not well-formed |
 * | `controlURL` | the URL a socket is opened to | a non-`http` scheme, or no host at all |
 *
 * A review of Task 1 demonstrated the first row for real, against a live `ServerSocket`:
 * `"urn:x#Y"\r\nX-Injected: ...` as a `SOAPACTION` value put a genuine extra header on the wire.
 * [app.muplay.cast.http.HttpWire.headerLine] now refuses CR, LF and NUL outright, so that exact
 * bypass is closed at the codec -- **but it closes with `IllegalArgumentException`, thrown before
 * the socket opens.** For this layer that is a crash rather than a bad request, and a caller
 * holding a `catch (e: IOException)` around a renderer call misses it entirely.
 *
 * So this object validates first, and refuses with [MalformedSoapRequestException], which *is* an
 * `IOException`. Two consequences worth stating, because Tasks 4-11 inherit both:
 *
 * 1. **Catching `IOException` around a `SoapClient` call is correct and sufficient.** No
 *    `IllegalArgumentException` can escape `SoapClient.invoke` for a peer-supplied name or URL,
 *    because none of them reaches `CastHttpClient` unvalidated.
 * 2. **The codec's refusal is still there, underneath, unchanged.** Nothing here replaces it and
 *    nothing here assembles a header value by hand: the check is an allowlist *in front of* the
 *    codec's denylist, not instead of it. If these two ever disagree, the codec wins and the
 *    request does not go out.
 *
 * Allowlists, not denylists, and both are far narrower than the wire strictly permits. A UPnP
 * service type is a URN and an action name is an XML `NCName`; a device that needs a byte outside
 * these is a device this client declines to talk to, which is a better failure than guessing.
 */
object SoapNames {

  /**
   * 255 characters. `urn:schemas-upnp-org:service:AVTransport:1` is 41 and the longest vendor type
   * seen on real hardware is under 60, so this is four times the headroom an honest device needs
   * and a hard stop for one that streams a header value.
   */
  const val MAX_SERVICE_TYPE_LENGTH: Int = 255

  /** 64 characters. `SetNextAVTransportURI` is 21; `X_GetZoneGroupState` is 19. */
  const val MAX_NAME_LENGTH: Int = 64

  /**
   * A URN's own alphabet, minus `#` -- which is the separator `SOAPACTION` puts between the
   * service type and the action, so a service type carrying one would produce a header value that
   * splits two ways.
   */
  private val SERVICE_TYPE = Regex("[A-Za-z0-9][A-Za-z0-9._:+~-]{0,${MAX_SERVICE_TYPE_LENGTH - 1}}")

  /**
   * An XML `NCName` restricted to ASCII: a letter or `_` first, then letters, digits, `.`, `-` and
   * `_`. No colon (that would declare a namespace prefix this envelope does not define) and no
   * leading digit (that is not a name at all).
   */
  private val NAME = Regex("[A-Za-z_][A-Za-z0-9._-]{0,${MAX_NAME_LENGTH - 1}}")

  /** @throws MalformedSoapRequestException unless [serviceType] is a URN this client will write. */
  fun requireServiceType(serviceType: String): String {
    if (!SERVICE_TYPE.matches(serviceType)) {
      throw refusal("service type", serviceType, "a UPnP service type is a URN")
    }
    return serviceType
  }

  /** @throws MalformedSoapRequestException unless [action] is an XML name this client will write. */
  fun requireAction(action: String): String {
    if (!NAME.matches(action)) {
      throw refusal("action name", action, "a UPnP action name is an XML name")
    }
    return action
  }

  /**
   * The same rule as [requireAction], for the `in`/`out` argument names that become element names.
   *
   * Nothing peer-derived reaches an argument name today -- Tasks 4-9 all pass literals. It is
   * checked anyway so that [SoapEnvelope.render] is **total**: every call either returns
   * well-formed XML or throws, with no third outcome where a caller's string silently rewrites the
   * document's shape.
   */
  fun requireArgumentName(name: String): String {
    if (!NAME.matches(name)) {
      throw refusal("argument name", name, "a SOAP argument name is an XML name")
    }
    return name
  }

  /**
   * The control URL, checked for exactly what [app.muplay.cast.http.CastHttpClient.exchange] would
   * otherwise refuse with `IllegalArgumentException`: a scheme that is not `http`, and no host.
   *
   * **Not** a locality check. That one lives in
   * [app.muplay.cast.net.LocalNetworkOnly.require], is applied by the client on every request, and
   * already throws an `IOException` -- duplicating it here would give the rule two homes and let
   * them drift.
   */
  fun requireControlUrl(url: URI): URI {
    if (url.scheme?.equals("http", ignoreCase = true) != true) {
      throw refusal("control url", url.toString(), "a renderer has no TLS and this client speaks http only")
    }
    // `== null`, not `isNullOrEmpty()`, and it mirrors `CastHttpClient`'s own `requireNotNull` on
    // purpose: `URI.getHost()` returns null rather than "" for an authority it cannot parse as
    // server-based, so an emptiness check here would be a second arm no input can reach.
    if (url.host == null) {
      throw refusal("control url", url.toString(), "there is no host to open a socket to")
    }
    return url
  }

  /**
   * The refusal, **built and returned rather than thrown from here**.
   *
   * A `Nothing`-returning `refuse()` reads better and measures as dead code: JaCoCo places its
   * probe after the call, a call that never returns never reaches it, and every refusal path in
   * this object reported 0 covered instructions while the tests that drive them were green. That
   * is the "a floor that cannot fail" family of defect arriving from the other direction -- an
   * assertion that cannot be *seen* to pass -- so the throw happens at each call site instead.
   */
  private fun refusal(role: String, value: String, because: String): MalformedSoapRequestException =
    MalformedSoapRequestException(
      "refusing to build a SOAP request from the $role \"${quoteSafely(value)}\": $because, and " +
        "this one is not. It came from a device description an unauthenticated device on the " +
        "local network served, and it would be written into a header value, an XML name or a URL.",
    )

  /**
   * Never echoes a raw control character into a message, a log line or a bug report -- the same
   * rule, and the same spelling, as [app.muplay.cast.http.HttpWire]'s own.
   *
   * A CR in an exception message moves the cursor instead of showing what arrived, which is how a
   * splitting attempt reads as a truncated log line rather than as an attack.
   */
  private fun quoteSafely(text: String): String =
    text.take(MAX_SERVICE_TYPE_LENGTH)
      .map { if (it in ' '..'~') "$it" else "\\u%04x".format(it.code) }
      .joinToString("")
}
