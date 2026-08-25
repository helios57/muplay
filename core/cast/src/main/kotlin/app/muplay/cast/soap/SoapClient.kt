package app.muplay.cast.soap

import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.http.HttpHeaders
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One SOAP action against one control URL.
 *
 * Returns the `out` arguments on success, throws [UpnpErrorException] when the device refused and
 * said why, and [SoapTransportException] when it could not be reached or answered something
 * unreadable. Those are two different facts and the caller (Task 5) branches on them differently:
 * a 710 means "this device cannot seek that way, use another mode"; a socket timeout means "the
 * speaker is gone, hand playback back to the phone".
 *
 * ### Every failure out of [invoke] is an `IOException`, and that is a designed property
 *
 * [controlUrl], [serviceType] and [action] are all **peer-controlled**: each is parsed out of the
 * device-description XML the renderer itself served. [CastHttpClient] refuses the dangerous shapes
 * of all three -- a header value carrying CR, LF or NUL, a non-`http` scheme, a URL with no host --
 * but it refuses them with `IllegalArgumentException`, thrown before the socket opens. Left to
 * that, a hostile device description would reach a caller as a **crash**, and a caller holding the
 * `catch (e: IOException)` that every other renderer failure needs would not see it.
 *
 * So [invoke] validates all three through [SoapNames] first, and [SoapNames] refuses with
 * [MalformedSoapRequestException], which is an `IOException`. Nothing here assembles a header
 * value by hand -- the `SOAPACTION` value still goes through [SoapEnvelope.soapActionHeader] and
 * then through [app.muplay.cast.http.HttpWire.headerLine], so the codec's own refusal stays in
 * place underneath as the backstop it was built to be.
 *
 * The consequence for Tasks 5, 8 and 9: **one `catch (e: IOException)` around a `SoapClient` call
 * is complete.** There is no second exception family to remember.
 */
class SoapClient(private val http: CastHttpClient = CastHttpClient()) {

  /**
   * @throws MalformedSoapRequestException for a control URL, service type or action name this
   *   client will not put on the wire -- checked before anything is rendered, resolved or opened.
   * @throws UpnpErrorException when the renderer answered a SOAP fault.
   * @throws SoapTransportException when the renderer could not be reached, answered a status this
   *   client cannot read as either success or a fault, or answered `200` with a body carrying no
   *   response for [action] -- an unreadable answer is not an empty one.
   */
  suspend fun invoke(
    controlUrl: URI,
    serviceType: String,
    action: String,
    arguments: List<SoapArgument>,
  ): Map<String, String> = withContext(Dispatchers.IO) {
    // First, and before a socket, a DNS lookup or a single rendered byte: everything below this
    // line treats these three as safe, and this is where that becomes true.
    SoapNames.requireControlUrl(controlUrl)
    val soapAction = SoapEnvelope.soapActionHeader(serviceType, action)
    val body = SoapEnvelope.render(serviceType, action, arguments).toByteArray(Charsets.UTF_8)

    val response = try {
      http.exchange(
        url = controlUrl,
        method = "POST",
        headers = HttpHeaders.of(
          "Content-Type" to SoapEnvelope.CONTENT_TYPE,
          // Quoted. See SoapEnvelope.soapActionHeader.
          "SOAPACTION" to soapAction,
        ),
        body = body,
      )
    } catch (cause: IOException) {
      // `IOException` and nothing wider. An `IllegalArgumentException` out of `exchange` would
      // mean a peer-supplied value got past `SoapNames` -- a hole in the validation above -- and
      // dressing that up as a transport failure would hide it behind "the speaker is flaky".
      // Unreachable today, loud if it ever stops being.
      throw SoapTransportException(action, statusCode = TRANSPORT_FAILED, cause = cause)
    }

    // The fault check comes FIRST and is not conditional on the status code: a UPnP error is HTTP
    // 500 with a body, and a handful of renderers answer 200 with a fault body instead.
    SoapEnvelope.parseFault(response.bodyText())?.let { throw UpnpErrorException(action, it) }

    if (response.code != HttpURLConnection.HTTP_OK) {
      throw SoapTransportException(action, response.code)
    }
    // A 200 whose body carries no response for this action is not a result. It is the third thing
    // this method's KDoc has always named -- "answered something unreadable" -- and reporting it
    // as an empty success would hand Task 5 a position of zero for a body it never read. An action
    // with genuinely no out arguments still answers `<u:PlayResponse/>`, which parses to an empty
    // map and is a success; only the absence of the element itself lands here.
    SoapEnvelope.parseResponse(action, response.bodyText())
      ?: throw SoapTransportException(action, response.code)
  }

  private companion object {
    /** [SoapTransportException.statusCode] when there was no response at all to take one from. */
    const val TRANSPORT_FAILED = 0
  }
}
