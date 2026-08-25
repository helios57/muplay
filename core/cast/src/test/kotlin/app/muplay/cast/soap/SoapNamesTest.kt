package app.muplay.cast.soap

import app.muplay.cast.discovery.DeviceDescription
import java.io.IOException
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * **The validation of peer-controlled text, tested as the security control it is.**
 *
 * A renderer's `serviceType`, its action names and its `controlURL` all arrive from the
 * device-description XML the renderer itself served, over a protocol where anything that can send
 * a datagram gets to say where the description lives. They are then interpolated into a
 * `SOAPACTION` header value, into an XML element name and an attribute value, and into a URL a
 * socket is opened to. Every one of those is a place peer-chosen text changes the meaning of the
 * message around it.
 *
 * The review that produced this class demonstrated real request splitting through a `SOAPACTION`
 * value against a live `ServerSocket`: `"urn:x#Y"\r\nX-Injected: ...` put a genuine extra header
 * on the wire. `CastHttpClient` closed that by refusing CR, LF and NUL -- but it refuses with
 * `IllegalArgumentException`, so an unvalidated hostile description reaches a caller as a **crash**
 * rather than as a bad request, and a caller guarding a renderer call with `catch (IOException)`
 * does not see it at all.
 *
 * So the refusal happens here instead, before anything is rendered or opened, and it is an
 * `IOException` -- which is what makes "catch `IOException` around a renderer call" correct rather
 * than a hole. Both properties are asserted below.
 */
class SoapNamesTest {

  /**
   * The hostile inputs, one per mechanism, shared by the service-type and action assertions.
   *
   * The first two are the request-splitting vector itself; the rest are the XML ones -- a `"` ends
   * `xmlns:u="`, a `<` or `>` ends an element name, an `&` starts an entity -- plus the
   * non-printing and non-ASCII bytes `HttpWire` refuses on the wire.
   */
  private val hostile = listOf(
    "urn:x\r\nX-Injected: 1",
    "urn:x\nX-Injected: 1",
    "urn:x\u0000",
    "urn:x\"",
    "urn:x<script>",
    "urn:x&amp;",
    "urn:x with a space",
    "urn:x\ttab",
    "urn:königin",
    "",
  )

  @Test
  fun `the two real service types are accepted, so the refusals below are not a refusal of everything`() {
    // The control observation. Without it a `require { false }` passes every hostile case here.
    assertThat(SoapNames.requireServiceType(DeviceDescription.SERVICE_AV_TRANSPORT))
      .isEqualTo(DeviceDescription.SERVICE_AV_TRANSPORT)
    assertThat(SoapNames.requireServiceType(DeviceDescription.SERVICE_RENDERING_CONTROL))
      .isEqualTo(DeviceDescription.SERVICE_RENDERING_CONTROL)
    // A vendor service type off a real Sonos, which is not one of this project's own constants.
    assertThat(SoapNames.requireServiceType("urn:schemas-sonos-com:service:Queue:1"))
      .isEqualTo("urn:schemas-sonos-com:service:Queue:1")
  }

  @Test
  fun `a hostile service type is refused, one mechanism at a time`() {
    hostile.forEach { candidate ->
      assertThatThrownBy { SoapNames.requireServiceType(candidate) }
        .describedAs("service type %s", candidate.replace("\r", "\\r").replace("\n", "\\n"))
        .isInstanceOf(MalformedSoapRequestException::class.java)
        // The property the whole design rests on: a caller that catches IOException catches this.
        .isInstanceOf(IOException::class.java)
    }
  }

  @Test
  fun `a service type longer than a device could honestly need is refused`() {
    val long = "urn:" + "a".repeat(SoapNames.MAX_SERVICE_TYPE_LENGTH)

    assertThat(long.length).isGreaterThan(SoapNames.MAX_SERVICE_TYPE_LENGTH)
    assertThatThrownBy { SoapNames.requireServiceType(long) }
      .isInstanceOf(MalformedSoapRequestException::class.java)
    // And the longest acceptable one really is accepted, so the bound is a bound and not an
    // off-by-one that refuses everything near it.
    assertThat(SoapNames.requireServiceType("a".repeat(SoapNames.MAX_SERVICE_TYPE_LENGTH)))
      .hasSize(SoapNames.MAX_SERVICE_TYPE_LENGTH)
  }

  @Test
  fun `real action names are accepted, vendor prefixes included`() {
    listOf("Play", "Stop", "SetAVTransportURI", "GetPositionInfo", "X_GetZoneGroupState")
      .forEach { assertThat(SoapNames.requireAction(it)).isEqualTo(it) }
  }

  @Test
  fun `a hostile action name is refused, one mechanism at a time`() {
    hostile.forEach { candidate ->
      assertThatThrownBy { SoapNames.requireAction(candidate) }
        .describedAs("action %s", candidate.replace("\r", "\\r").replace("\n", "\\n"))
        .isInstanceOf(MalformedSoapRequestException::class.java)
        .isInstanceOf(IOException::class.java)
    }
    // An action name is an XML element name as well as half a header value, so it may not begin
    // with a digit and may not carry a colon -- either would produce a document that is not
    // well-formed, which a device answers 500 to before it reads anything.
    assertThatThrownBy { SoapNames.requireAction("1Play") }
      .isInstanceOf(MalformedSoapRequestException::class.java)
    assertThatThrownBy { SoapNames.requireAction("u:Play") }
      .isInstanceOf(MalformedSoapRequestException::class.java)
    assertThatThrownBy { SoapNames.requireAction("a".repeat(SoapNames.MAX_NAME_LENGTH + 1)) }
      .isInstanceOf(MalformedSoapRequestException::class.java)
  }

  @Test
  fun `an argument name is held to the element-name rule, and the real ones pass it`() {
    listOf("InstanceID", "CurrentURI", "CurrentURIMetaData", "DesiredVolume")
      .forEach { assertThat(SoapNames.requireArgumentName(it)).isEqualTo(it) }
    assertThatThrownBy { SoapNames.requireArgumentName("Current URI") }
      .isInstanceOf(MalformedSoapRequestException::class.java)
  }

  /**
   * The `controlURL` is peer-controlled too, and it is the input that decides **which host a
   * socket is opened to**.
   *
   * `CastHttpClient.exchange` refuses a non-`http` scheme and a URL with no host -- with
   * `IllegalArgumentException`, again. A hostile description carrying
   * `<controlURL>https://attacker.example/x</controlURL>` therefore crashed a caller instead of
   * failing it. Checked here, in the same place and with the same exception as the names.
   */
  @Test
  fun `a control url that is not plain http to a named host is refused`() {
    val good = URI("http://127.0.0.1:1400/MediaRenderer/AVTransport/Control")
    assertThat(SoapNames.requireControlUrl(good)).isEqualTo(good)
    // Scheme comparison is case-insensitive, the way `CastHttpClient` does it, so a device that
    // shouts its scheme is not refused for shouting.
    assertThat(SoapNames.requireControlUrl(URI("HTTP://10.0.0.2:1400/x"))).isNotNull()

    listOf(
      "https://attacker.example/x",
      "file:///etc/passwd",
      "ftp://10.0.0.2/x",
      "/MediaRenderer/AVTransport/Control",
      "http:///nohost",
      "mailto:someone@example.com",
    ).forEach { candidate ->
      assertThatThrownBy { SoapNames.requireControlUrl(URI(candidate)) }
        .describedAs("control url %s", candidate)
        .isInstanceOf(MalformedSoapRequestException::class.java)
        .isInstanceOf(IOException::class.java)
    }
  }

  @Test
  fun `the refusal names what it refused, without echoing a control character into a log`() {
    val thrown = runCatching { SoapNames.requireServiceType("urn:x\r\nX-Injected: 1") }.exceptionOrNull()

    assertThat(thrown).isNotNull()
    assertThat(thrown!!.message).contains("urn:x")
    // A bug report is where these messages end up, and a raw CR in one moves the cursor rather
    // than showing what arrived.
    assertThat(thrown.message).doesNotContain("\r").doesNotContain("\n")
    assertThat(thrown.message).contains("\\u000d")
  }
}
