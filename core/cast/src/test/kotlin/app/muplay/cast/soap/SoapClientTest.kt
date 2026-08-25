package app.muplay.cast.soap

import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.fake.FakeRenderer
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `SoapClient` against the strict in-process renderer, not against a stub.
 *
 * Every assertion below is either on the **bytes the renderer recorded** or on the exception the
 * renderer's own refusal produced. `assertThat(fake.soapRequests).hasSize(1)` is green against an
 * unquoted `SOAPACTION`, reordered arguments and double-escaped metadata alike, so it is never the
 * assertion here.
 */
class SoapClientTest {

  private lateinit var renderer: FakeRenderer
  private val client = SoapClient()

  private val avTransport = DeviceDescription.SERVICE_AV_TRANSPORT
  private val renderingControl = DeviceDescription.SERVICE_RENDERING_CONTROL

  // The metadata argument is the DIDL document itself. `SoapEnvelope.render` frames it; nothing
  // above this line escapes anything, which is the property Tasks 5, 8 and 9 inherit.
  private val setUriArguments = listOf(
    SoapArgument("InstanceID", "0"),
    SoapArgument("CurrentURI", "http://127.0.0.1:9/media/a.mp3"),
    SoapArgument("CurrentURIMetaData", "<DIDL-Lite></DIDL-Lite>"),
  )

  @BeforeEach
  fun setUp() {
    renderer = FakeRenderer().also { it.start() }
    renderer.fetchesMedia = false
  }

  @AfterEach
  fun tearDown() = renderer.close()

  @Test
  fun `a successful action returns its out arguments`() = runTest {
    val out = client.invoke(
      renderer.renderingControlUrl,
      renderingControl,
      "GetVolume",
      listOf(SoapArgument("InstanceID", "0"), SoapArgument("Channel", "Master")),
    )

    assertThat(out).containsExactly(org.assertj.core.api.Assertions.entry("CurrentVolume", "30"))

    // And a second value for the same key, so `CurrentVolume` cannot be a constant: set it first,
    // then read it back.
    client.invoke(
      renderer.renderingControlUrl,
      renderingControl,
      "SetVolume",
      listOf(
        SoapArgument("InstanceID", "0"), SoapArgument("Channel", "Master"),
        SoapArgument("DesiredVolume", "7"),
      ),
    )
    assertThat(
      client.invoke(
        renderer.renderingControlUrl,
        renderingControl,
        "GetVolume",
        listOf(SoapArgument("InstanceID", "0"), SoapArgument("Channel", "Master")),
      ),
    ).containsExactly(org.assertj.core.api.Assertions.entry("CurrentVolume", "7"))
  }

  @Test
  fun `an action with several out arguments returns all of them, in document order`() = runTest {
    client.invoke(renderer.controlUrl, avTransport, "SetAVTransportURI", setUriArguments)

    val out = client.invoke(
      renderer.controlUrl,
      avTransport,
      "GetPositionInfo",
      listOf(SoapArgument("InstanceID", "0")),
    )

    assertThat(out.keys).containsExactly(
      "Track", "TrackDuration", "TrackMetaData", "TrackURI", "RelTime", "AbsTime", "RelCount", "AbsCount",
    )
    assertThat(out["TrackURI"]).isEqualTo("http://127.0.0.1:9/media/a.mp3")
    assertThat(out["RelTime"]).isEqualTo("0:00:00")
    assertThat(out["AbsTime"]).isEqualTo(UpnpTime.NOT_IMPLEMENTED)
    // Escaped once on the wire, decoded once on the way out -- which is the round trip Task 4's
    // DIDL document depends on and the place `&amp;lt;DIDL-Lite` would show up.
    assertThat(out["TrackMetaData"]).isEqualTo("<DIDL-Lite></DIDL-Lite>")
  }

  @Test
  fun `the request that reached the device carries a quoted soapaction`() = runTest {
    client.invoke(renderer.controlUrl, avTransport, "Stop", listOf(SoapArgument("InstanceID", "0")))

    val recorded = renderer.soapRequests.single()
    assertThat(recorded.rawSoapAction)
      .isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"")
    assertThat(recorded.rawSoapAction).startsWith("\"").endsWith("\"")
  }

  @Test
  fun `the soapaction that reached the device names the service the caller asked for`() = runTest {
    // Two services, two namespaces, one device. Reading the second half of the header off the wire
    // is what stops `soapActionHeader` being a constant that happens to name AVTransport.
    client.invoke(renderer.controlUrl, avTransport, "Stop", listOf(SoapArgument("InstanceID", "0")))
    client.invoke(
      renderer.renderingControlUrl,
      renderingControl,
      "GetVolume",
      listOf(SoapArgument("InstanceID", "0"), SoapArgument("Channel", "Master")),
    )

    assertThat(renderer.soapRequests.map { it.rawSoapAction }).containsExactly(
      "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"",
      "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\"",
    )
  }

  @Test
  fun `the request that reached the device carries the arguments in order`() = runTest {
    client.invoke(renderer.controlUrl, avTransport, "SetAVTransportURI", setUriArguments)

    // Names in order -- the fake would have answered 402 otherwise, so this assertion and the
    // absence of an exception are two independent observations of the same property.
    assertThat(renderer.soapRequests.single().arguments?.map { it.first })
      .containsExactly("InstanceID", "CurrentURI", "CurrentURIMetaData")
    // And the values, paired with their own names, because a list of names alone would pass with
    // the URL and the metadata swapped between them.
    assertThat(renderer.soapRequests.single().arguments).containsExactly(
      "InstanceID" to "0",
      "CurrentURI" to "http://127.0.0.1:9/media/a.mp3",
      "CurrentURIMetaData" to "<DIDL-Lite></DIDL-Lite>",
    )
  }

  /**
   * **A real Navidrome stream URL, end to end, through a renderer that actually parses.**
   *
   * `/rest/stream?u=...&t=...&s=...` is the URL this app already builds for every track, and until
   * this fix `SoapEnvelope.render` put those ampersands into element content untouched. The
   * envelope was then not well-formed XML -- *"The reference to entity `t` must end with ';'"* --
   * so no device could read it and `SoapEnvelope.parseResponse` could not read back what `render`
   * had just written. Against the old regex-based fake it was invisible; against a fake that
   * parses, this is a 401.
   *
   * Two observations of the same fact, deliberately: the request is accepted at all, and the URL
   * the device read out is byte-for-byte the one that went in. The first alone would pass with the
   * ampersand silently dropped.
   */
  @Test
  fun `a navidrome stream url reaches the device intact, ampersands and all`() = runTest {
    val streamUrl = "http://127.0.0.1:9/rest/stream.mp3?u=muplay&t=9f2a1c&s=abc123&id=tr-7"

    client.invoke(
      renderer.controlUrl,
      avTransport,
      "SetAVTransportURI",
      listOf(
        SoapArgument("InstanceID", "0"),
        SoapArgument("CurrentURI", streamUrl),
        SoapArgument("CurrentURIMetaData", "<DIDL-Lite><item id=\"tr-7\"></item></DIDL-Lite>"),
      ),
    )

    assertThat(renderer.soapRequests.single().arguments).containsExactly(
      "InstanceID" to "0",
      "CurrentURI" to streamUrl,
      "CurrentURIMetaData" to "<DIDL-Lite><item id=\"tr-7\"></item></DIDL-Lite>",
    )
    // And what actually went on the wire: escaped exactly once, never twice.
    val body = renderer.soapRequests.single().bodyText
    assertThat(body).contains(
      "<CurrentURI>http://127.0.0.1:9/rest/stream.mp3" +
        "?u=muplay&amp;t=9f2a1c&amp;s=abc123&amp;id=tr-7</CurrentURI>",
    )
    assertThat(body).contains("<CurrentURIMetaData>&lt;DIDL-Lite&gt;")
    assertThat(body).doesNotContain("&amp;lt;DIDL-Lite")
  }

  @Test
  fun `the request that reached the device carries the soap content type`() = runTest {
    client.invoke(renderer.controlUrl, avTransport, "Stop", listOf(SoapArgument("InstanceID", "0")))

    assertThat(renderer.soapRequests.single().rawContentType).isEqualTo("text/xml; charset=\"utf-8\"")
    assertThat(renderer.soapRequests.single().headText).startsWith("POST ")
  }

  @Test
  fun `a refused action throws with the device's own error code`() = runTest {
    client.invoke(renderer.controlUrl, avTransport, "SetAVTransportURI", setUriArguments)

    val thrown = failureOf {
      client.invoke(
        renderer.controlUrl,
        avTransport,
        "Seek",
        listOf(
          SoapArgument("InstanceID", "0"), SoapArgument("Unit", "ABS_TIME"),
          SoapArgument("Target", "0:00:10"),
        ),
      )
    }

    assertThat(thrown)
      .isInstanceOf(UpnpErrorException::class.java)
      .hasMessageContaining("Seek mode not supported")
    assertThat((thrown as UpnpErrorException).fault.errorCode).isEqualTo(UpnpError.SEEK_MODE_NOT_SUPPORTED)
    assertThat(thrown.fault.errorDescription).isEqualTo("Seek mode not supported")
    assertThat(thrown.action).isEqualTo("Seek")
  }

  @Test
  fun `a second refusal carries a second code`() = runTest {
    // So `errorCode` cannot be a constant, and so the 500-with-a-body path is read twice.
    val thrown = failureOf {
      client.invoke(
        renderer.controlUrl,
        avTransport,
        "SetAVTransportURI",
        listOf(setUriArguments[0], setUriArguments[1], SoapArgument("CurrentURIMetaData", "")),
      )
    }

    assertThat(thrown).isInstanceOf(UpnpErrorException::class.java)
    assertThat((thrown as UpnpErrorException).fault.errorCode).isEqualTo(UpnpError.ILLEGAL_MIME_TYPE)
    assertThat(thrown.action).isEqualTo("SetAVTransportURI")
  }

  /**
   * **An unreadable 200 is not an empty result**, and until this fix it was reported as one.
   *
   * `parseResponse` answered `emptyMap()` both for *"the device answered this action and it has no
   * out arguments"* and for *"there is no answer to this action in this body"*. Task 5 reads
   * `RelTime` out of a `GetPositionInfo`; given the second fact dressed up as the first, it reads
   * nothing and reports a position of zero for a device that never answered.
   *
   * The body here is real rather than contrived: a description whose `controlURL` resolves to the
   * description document itself is a device-description bug this client cannot rule out, and the
   * fake answers that endpoint with `200` and an XML document containing no `<s:Body>` at all.
   *
   * Both halves are asserted, because only the pair discriminates: the unreadable answer fails,
   * and a void action whose response element really is empty still succeeds.
   */
  @Test
  fun `a 200 with no response element is a transport failure, while an empty response is a success`() =
    runTest {
      val thrown = failureOf {
        client.invoke(
          renderer.descriptionUrl,
          avTransport,
          "GetPositionInfo",
          listOf(SoapArgument("InstanceID", "0")),
        )
      }

      assertThat(thrown)
        .isInstanceOf(SoapTransportException::class.java)
        .isNotInstanceOf(UpnpErrorException::class.java)
        // The status it carries is the one the device really sent, and it is a success status --
        // which is the whole point: nothing about the transport failed except the answer.
        .hasMessageContaining("HTTP 200")
      assertThat((thrown as SoapTransportException).statusCode).isEqualTo(200)

      // The other half. `Stop` answers `<u:StopResponse/>`: a response element with no children,
      // which is a result and must not throw.
      assertThat(client.invoke(renderer.controlUrl, avTransport, "Stop", listOf(SoapArgument("InstanceID", "0"))))
        .isEmpty()
    }

  @Test
  fun `a renderer that has gone away throws a transport failure and not a upnp error`() = runTest {
    // **This distinction is the one Task 8's fallback branches on**, so it is pinned here where it
    // is cheap. A `UpnpErrorException` means the speaker refused and is still there; a
    // `SoapTransportException` means there is nothing to talk to and playback goes back to the
    // phone.
    renderer.disappear()

    val thrown = failureOf {
      client.invoke(renderer.controlUrl, avTransport, "Stop", listOf(SoapArgument("InstanceID", "0")))
    }

    assertThat(thrown)
      .isInstanceOf(SoapTransportException::class.java)
      .isNotInstanceOf(UpnpErrorException::class.java)
      .isInstanceOf(IOException::class.java)
      .hasCauseInstanceOf(IOException::class.java)
    assertThat((thrown as SoapTransportException).action).isEqualTo("Stop")
    // No status to report, because no response arrived to take one from -- distinct from the 404
    // below, which really did carry one.
    assertThat(thrown.statusCode).isZero()
  }

  @Test
  fun `a status this client cannot read is a transport failure carrying that status`() = runTest {
    // A 404 with no fault body: the control URL was resolved to something that is not a control
    // endpoint. Reported as the transport failure it is, with the device's own number on it.
    val thrown = failureOf {
      client.invoke(
        URI("http://127.0.0.1:${renderer.port}/nowhere"),
        avTransport,
        "Stop",
        listOf(SoapArgument("InstanceID", "0")),
      )
    }

    assertThat(thrown).isInstanceOf(SoapTransportException::class.java).hasMessageContaining("404")
    assertThat((thrown as SoapTransportException).statusCode).isEqualTo(404)
  }

  /**
   * **The security property, end to end.**
   *
   * The service type and the action name arrive from the device-description XML the renderer
   * served -- see the parsing in `DeviceDescription`, which is happy to hand back whatever
   * `<serviceType>` a device put there. A hostile one reaches
   * [app.muplay.cast.http.CastHttpClient.exchange] as an `IllegalArgumentException`, which is a
   * crash rather than a bad request and which no `catch (IOException)` sees.
   *
   * Three observations, and all three matter:
   *
   * 1. it is refused at all;
   * 2. it is refused as an `IOException` (a [MalformedSoapRequestException]) and **not** as an
   *    `IllegalArgumentException` -- which is the whole difference between "the caller handles it"
   *    and "the app crashes";
   * 3. **nothing reached the device** -- the renderer recorded no request, so the refusal happened
   *    before the socket rather than after a half-written head.
   */
  @Test
  fun `a hostile service type parsed from a device description never reaches the wire`() = runTest {
    val hostileDescription = """
      <?xml version="1.0"?>
      <root xmlns="urn:schemas-upnp-org:device-1-0">
        <device>
          <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
          <UDN>uuid:evil</UDN>
          <friendlyName>Speaker</friendlyName>
          <serviceList><service>
            <serviceType>urn:x&#34;&#13;&#10;X-Injected: yes</serviceType>
            <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
            <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
          </service></serviceList>
        </device>
      </root>
    """.trimIndent()
    val parsed = DeviceDescription.parse(hostileDescription, renderer.descriptionUrl)
    val hostileType = parsed.services.single().serviceType

    // The premise, asserted rather than assumed: the parser really does hand this back intact.
    assertThat(hostileType).isEqualTo("urn:x\"\r\nX-Injected: yes")

    assertThat(
      failureOf {
        client.invoke(
          parsed.services.single().controlUrl,
          hostileType,
          "Stop",
          listOf(SoapArgument("InstanceID", "0")),
        )
      },
    ).isInstanceOf(MalformedSoapRequestException::class.java)
      .isInstanceOf(IOException::class.java)
      .isNotInstanceOf(IllegalArgumentException::class.java)

    assertThat(renderer.soapRequests)
      .describedAs("nothing may reach the device before the service type has been checked")
      .isEmpty()
  }

  @Test
  fun `a hostile action name never reaches the wire either`() = runTest {
    assertThat(
      failureOf { client.invoke(renderer.controlUrl, avTransport, "Stop\r\nX-Injected: yes", emptyList()) },
    ).isInstanceOf(MalformedSoapRequestException::class.java)
      .isInstanceOf(IOException::class.java)
      .isNotInstanceOf(IllegalArgumentException::class.java)

    assertThat(renderer.soapRequests).isEmpty()
  }

  /**
   * The third peer-controlled input. A description carrying
   * `<controlURL>https://attacker.example/x</controlURL>` resolves to an absolute `https` URL, and
   * `CastHttpClient` refuses that with `IllegalArgumentException` -- so without this check the
   * hostile description crashes the caller instead of failing it.
   */
  @Test
  fun `a control url that is not plain http is refused as an IOException, not a crash`() = runTest {
    listOf("https://attacker.example/x", "file:///etc/passwd", "http:///nohost").forEach { candidate ->
      assertThat(failureOf { client.invoke(URI(candidate), avTransport, "Stop", emptyList()) })
        .describedAs("control url %s", candidate)
        .isInstanceOf(MalformedSoapRequestException::class.java)
        .isInstanceOf(IOException::class.java)
        .isNotInstanceOf(IllegalArgumentException::class.java)
    }
  }

  @Test
  fun `a control url outside the local network is refused, and the refusal survives this layer`() =
    runTest {
      // `LocalNetworkOnly` owns this rule and throws its own `IOException` from inside the client.
      // Wrapped here as a transport failure with the original preserved as the cause, so Task 10
      // can still say why. 203.0.113.0/24 is TEST-NET-3: routable-looking, and never routed.
      assertThat(failureOf { client.invoke(URI("http://203.0.113.9:1400/x"), avTransport, "Stop", emptyList()) })
        .isInstanceOf(SoapTransportException::class.java)
        .hasCauseInstanceOf(app.muplay.cast.net.NonLocalAddressException::class.java)
    }

  /**
   * The exception [block] raised, or an assertion failure if it raised none.
   *
   * `assertThatThrownBy { runTest { ... } }` cannot be used here: nesting one `runTest` inside
   * another throws `IllegalStateException: Only a single call to runTest can be performed during
   * one test`, and AssertJ then reports *that* as the exception under test -- so an assertion
   * written that way passes or fails for reasons unrelated to the code it names. Measured, on this
   * file, before this helper existed.
   */
  private suspend fun failureOf(block: suspend () -> Unit): Throwable =
    requireNotNull(runCatching { block() }.exceptionOrNull()) {
      "expected the call to fail, and it returned normally"
    }
}
