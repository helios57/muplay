package app.muplay.cast.fake

import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.http.HttpHeaders
import app.muplay.cast.soap.SoapArgument
import app.muplay.cast.soap.SoapEnvelope
import app.muplay.cast.soap.UpnpError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * **The test of the test double.**
 *
 * Every other cast test in this plan is only as good as this renderer's willingness to say no. A
 * fake that accepts everything executes no rejection path, leaves the client's error handling
 * unexercised, and -- worst -- makes its own permissiveness invisible, because a permissive fake
 * produces exactly the same green suite as a strict one right up until real hardware disagrees.
 *
 * So the rejections are asserted directly, by sending malformed requests **by hand** rather than
 * through `SoapClient`. Doing it by hand matters: a request built by the real client can never be
 * malformed, so a test that went through it could only ever observe acceptance.
 */
class FakeRendererStrictnessTest {

  private lateinit var renderer: FakeRenderer
  private val http = CastHttpClient()

  @BeforeEach
  fun setUp() {
    renderer = FakeRenderer().also { it.start() }
  }

  @AfterEach
  fun tearDown() = renderer.close()

  private fun post(action: String, arguments: List<SoapArgument>, soapAction: String): Int {
    val body = SoapEnvelope.render(DeviceDescription.SERVICE_AV_TRANSPORT, action, arguments)
    return http.exchange(
      renderer.controlUrl,
      "POST",
      HttpHeaders.of("Content-Type" to SoapEnvelope.CONTENT_TYPE, "SOAPACTION" to soapAction),
      body.toByteArray(Charsets.UTF_8),
    ).let { response ->
      SoapEnvelope.parseFault(response.bodyText())?.errorCode ?: response.code
    }
  }

  private val goodArguments = listOf(
    SoapArgument("InstanceID", "0"),
    SoapArgument("CurrentURI", "http://127.0.0.1:9/media/a.mp3"),
    SoapArgument("CurrentURIMetaData", "&lt;DIDL-Lite&gt;&lt;/DIDL-Lite&gt;"),
  )

  @Test
  fun `a well-formed request is accepted, so the rejections below mean something`() {
    // The control observation. Without it, a renderer that rejected everything would pass all the
    // rejection tests and prove nothing at all.
    assertThat(post("SetAVTransportURI", goodArguments, quoted("SetAVTransportURI"))).isEqualTo(200)
  }

  @Test
  fun `an unquoted soapaction is rejected with 401`() {
    assertThat(
      post(
        "SetAVTransportURI",
        goodArguments,
        soapAction = "${DeviceDescription.SERVICE_AV_TRANSPORT}#SetAVTransportURI",
      ),
    ).isEqualTo(UpnpError.INVALID_ACTION)
  }

  @Test
  fun `a request with no soapaction at all is rejected with 401`() {
    val body = SoapEnvelope.render(DeviceDescription.SERVICE_AV_TRANSPORT, "Stop", emptyList())
    val response = http.exchange(
      renderer.controlUrl,
      "POST",
      HttpHeaders.of("Content-Type" to SoapEnvelope.CONTENT_TYPE),
      body.toByteArray(Charsets.UTF_8),
    )

    assertThat(SoapEnvelope.parseFault(response.bodyText())?.errorCode).isEqualTo(UpnpError.INVALID_ACTION)
  }

  @Test
  fun `arguments in the wrong order are rejected with 402`() {
    assertThat(
      post(
        "SetAVTransportURI",
        listOf(goodArguments[0], goodArguments[2], goodArguments[1]),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(UpnpError.INVALID_ARGS)
  }

  @Test
  fun `empty metadata is rejected with 714`() {
    assertThat(
      post(
        "SetAVTransportURI",
        listOf(goodArguments[0], goodArguments[1], SoapArgument("CurrentURIMetaData", "")),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(UpnpError.ILLEGAL_MIME_TYPE)
  }

  @Test
  fun `double-escaped metadata is rejected with 714`() {
    // `&amp;lt;DIDL-Lite` is what escaping twice produces, and it is the defect a round-trip test
    // through the real client cannot produce on purpose.
    assertThat(
      post(
        "SetAVTransportURI",
        listOf(
          goodArguments[0],
          goodArguments[1],
          SoapArgument("CurrentURIMetaData", "&amp;lt;DIDL-Lite&amp;gt;"),
        ),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(UpnpError.ILLEGAL_MIME_TYPE)
  }

  @Test
  fun `a url with no file extension is rejected with 714`() {
    // Spec section 6: Sonos infers MIME from the URL. This is the rejection that makes Task 6's
    // token-with-an-extension a requirement rather than a nicety.
    assertThat(
      post(
        "SetAVTransportURI",
        listOf(goodArguments[0], SoapArgument("CurrentURI", "http://127.0.0.1:9/media/abc"), goodArguments[2]),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(UpnpError.ILLEGAL_MIME_TYPE)
  }

  @Test
  fun `an opus protocolInfo is rejected with 714`() {
    // Spec section 4: "Never Opus. Sonos cannot decode it and Navidrome mislabels it audio/ogg."
    val opus = "&lt;DIDL-Lite&gt;&lt;item&gt;&lt;res protocolInfo=&quot;http-get:*:audio/ogg:*&quot;&gt;" +
      "http://127.0.0.1:9/media/a.ogg&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"

    assertThat(
      post(
        "SetAVTransportURI",
        listOf(
          goodArguments[0], SoapArgument("CurrentURI", "http://127.0.0.1:9/media/a.ogg"),
          SoapArgument("CurrentURIMetaData", opus),
        ),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(UpnpError.ILLEGAL_MIME_TYPE)
  }

  @Test
  fun `an mp3 protocolInfo on the same shaped document is accepted`() {
    // The other half of the Opus rejection. Without it, `rejectedMimeTypes` could be
    // "reject every document with a protocolInfo in it" and every test above would still pass.
    val mp3 = "&lt;DIDL-Lite&gt;&lt;item&gt;&lt;res protocolInfo=&quot;http-get:*:audio/mpeg:*&quot;&gt;" +
      "http://127.0.0.1:9/media/a.mp3&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"

    assertThat(
      post(
        "SetAVTransportURI",
        listOf(goodArguments[0], goodArguments[1], SoapArgument("CurrentURIMetaData", mp3)),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(200)
  }

  @Test
  fun `an instance id that is not zero is rejected with 718`() {
    assertThat(
      post("Stop", listOf(SoapArgument("InstanceID", "1")), quoted("Stop")),
    ).isEqualTo(UpnpError.INVALID_INSTANCE_ID)
  }

  @Test
  fun `an unknown seek mode is rejected with 710`() {
    post("SetAVTransportURI", goodArguments, quoted("SetAVTransportURI"))

    assertThat(
      post(
        "Seek",
        listOf(
          SoapArgument("InstanceID", "0"), SoapArgument("Unit", "ABS_TIME"),
          SoapArgument("Target", "0:00:10"),
        ),
        quoted("Seek"),
      ),
    ).isEqualTo(UpnpError.SEEK_MODE_NOT_SUPPORTED)
  }

  @Test
  fun `a seek target that is not a clock is rejected with 711`() {
    post("SetAVTransportURI", goodArguments, quoted("SetAVTransportURI"))

    assertThat(
      post(
        "Seek",
        listOf(
          SoapArgument("InstanceID", "0"), SoapArgument("Unit", "REL_TIME"),
          SoapArgument("Target", "NOT_IMPLEMENTED"),
        ),
        quoted("Seek"),
      ),
    ).isEqualTo(UpnpError.ILLEGAL_SEEK_TARGET)
    // And a well-formed one in the supported mode is accepted, so 711 above is about the target
    // rather than about `Seek` being refused outright.
    assertThat(
      post(
        "Seek",
        listOf(
          SoapArgument("InstanceID", "0"), SoapArgument("Unit", "REL_TIME"),
          SoapArgument("Target", "0:00:10"),
        ),
        quoted("Seek"),
      ),
    ).isEqualTo(200)
  }

  @Test
  fun `a play with a speed other than 1 is rejected with 717`() {
    // The Plan 4 interaction, made concrete: a book's stored playback speed cannot be delivered to
    // a renderer, and the renderer says so rather than quietly playing at 1x.
    renderer.fetchesMedia = false
    post("SetAVTransportURI", goodArguments, quoted("SetAVTransportURI"))

    assertThat(
      post("Play", listOf(SoapArgument("InstanceID", "0"), SoapArgument("Speed", "1.5")), quoted("Play")),
    ).isEqualTo(UpnpError.PLAY_SPEED_NOT_SUPPORTED)
  }

  @Test
  fun `a play with no speed at all is rejected with 402`() {
    renderer.fetchesMedia = false
    post("SetAVTransportURI", goodArguments, quoted("SetAVTransportURI"))

    assertThat(post("Play", listOf(SoapArgument("InstanceID", "0")), quoted("Play")))
      .isEqualTo(UpnpError.INVALID_ARGS)
  }

  @Test
  fun `an unknown action is rejected with 401`() {
    assertThat(post("Teleport", emptyList(), quoted("Teleport"))).isEqualTo(UpnpError.INVALID_ACTION)
  }

  @Test
  fun `a play before any uri has been set is rejected with 701`() {
    assertThat(
      post("Play", listOf(SoapArgument("InstanceID", "0"), SoapArgument("Speed", "1")), quoted("Play")),
    ).isEqualTo(UpnpError.TRANSITION_NOT_AVAILABLE)
  }

  @Test
  fun `an out-of-range volume is rejected with 402 on the RenderingControl endpoint`() {
    val body = SoapEnvelope.render(
      DeviceDescription.SERVICE_RENDERING_CONTROL,
      "SetVolume",
      listOf(
        SoapArgument("InstanceID", "0"), SoapArgument("Channel", "Master"),
        SoapArgument("DesiredVolume", "101"),
      ),
    )
    val response = http.exchange(
      renderer.renderingControlUrl,
      "POST",
      HttpHeaders.of(
        "Content-Type" to SoapEnvelope.CONTENT_TYPE,
        "SOAPACTION" to SoapEnvelope.soapActionHeader(DeviceDescription.SERVICE_RENDERING_CONTROL, "SetVolume"),
      ),
      body.toByteArray(Charsets.UTF_8),
    )

    assertThat(SoapEnvelope.parseFault(response.bodyText())?.errorCode).isEqualTo(UpnpError.INVALID_ARGS)
  }

  @Test
  fun `turning a strictness knob off really does turn it off`() {
    // Rule 4 applied to the knobs themselves: a `Strictness` field nothing reads would leave every
    // test above passing and the knob silently inert, which is the shape of the defect this whole
    // class exists to prevent one level up.
    val lenient = FakeRenderer(FakeRenderer.Strictness(requireQuotedSoapAction = false))
    lenient.use {
      it.start()
      val body = SoapEnvelope.render(DeviceDescription.SERVICE_AV_TRANSPORT, "SetAVTransportURI", goodArguments)
      val response = http.exchange(
        it.controlUrl,
        "POST",
        HttpHeaders.of(
          "Content-Type" to SoapEnvelope.CONTENT_TYPE,
          "SOAPACTION" to "${DeviceDescription.SERVICE_AV_TRANSPORT}#SetAVTransportURI",
        ),
        body.toByteArray(Charsets.UTF_8),
      )
      assertThat(response.code).isEqualTo(200)
    }
  }

  @Test
  fun `each of the other five knobs turns its own rejection off, and only its own`() {
    // One lenient renderer per knob, each asked for the exact request that knob rejects. Without
    // this, five of the seven `Strictness` fields could be read nowhere at all -- the "a field
    // nothing reads" defect, which no coverage number reports because the field is still written.
    assertAccepted(
      FakeRenderer.Strictness(requireArgumentOrder = false),
      "SetAVTransportURI",
      listOf(goodArguments[0], goodArguments[2], goodArguments[1]),
    )
    assertAccepted(
      FakeRenderer.Strictness(requireNonEmptyMetadata = false),
      "SetAVTransportURI",
      listOf(goodArguments[0], goodArguments[1], SoapArgument("CurrentURIMetaData", "")),
    )
    assertAccepted(
      FakeRenderer.Strictness(requireUrlExtension = false),
      "SetAVTransportURI",
      listOf(goodArguments[0], SoapArgument("CurrentURI", "http://127.0.0.1:9/media/abc"), goodArguments[2]),
    )
    assertAccepted(
      FakeRenderer.Strictness(requireInstanceIdZero = false),
      "Stop",
      listOf(SoapArgument("InstanceID", "1")),
    )
    assertAccepted(
      FakeRenderer.Strictness(supportedSeekModes = listOf("REL_TIME", "ABS_TIME")),
      "Seek",
      listOf(
        SoapArgument("InstanceID", "0"), SoapArgument("Unit", "ABS_TIME"),
        SoapArgument("Target", "0:00:10"),
      ),
    )
    assertAccepted(
      FakeRenderer.Strictness(rejectedMimeTypes = emptySet()),
      "SetAVTransportURI",
      listOf(
        goodArguments[0], SoapArgument("CurrentURI", "http://127.0.0.1:9/media/a.ogg"),
        SoapArgument(
          "CurrentURIMetaData",
          "&lt;DIDL-Lite&gt;&lt;res protocolInfo=&quot;http-get:*:audio/ogg:*&quot;&gt;x&lt;/res&gt;&lt;/DIDL-Lite&gt;",
        ),
      ),
    )
  }

  /** Sends [action] to a renderer built with [strictness] and asserts it was answered 200. */
  private fun assertAccepted(
    strictness: FakeRenderer.Strictness,
    action: String,
    arguments: List<SoapArgument>,
  ) {
    FakeRenderer(strictness).use { lenient ->
      lenient.start()
      val response = http.exchange(
        lenient.controlUrl,
        "POST",
        HttpHeaders.of(
          "Content-Type" to SoapEnvelope.CONTENT_TYPE,
          "SOAPACTION" to quoted(action),
        ),
        SoapEnvelope.render(DeviceDescription.SERVICE_AV_TRANSPORT, action, arguments)
          .toByteArray(Charsets.UTF_8),
      )
      assertThat(SoapEnvelope.parseFault(response.bodyText()))
        .describedAs("%s with %s should have been accepted", action, strictness)
        .isNull()
      assertThat(response.code).isEqualTo(200)
    }
  }

  /**
   * The head this fake recorded is **the bytes off the socket**, which is what makes every
   * assertion above about the request rather than about this fake's own parser.
   */
  @Test
  fun `the recorded head is the raw request, quotes and all`() {
    post("Stop", listOf(SoapArgument("InstanceID", "0")), quoted("Stop"))

    val recorded = renderer.soapRequests.single()
    assertThat(recorded.headText).startsWith("POST /MediaRenderer/AVTransport/Control HTTP/1.1\r\n")
    assertThat(recorded.headText)
      .contains("SOAPACTION: \"urn:schemas-upnp-org:service:AVTransport:1#Stop\"\r\n")
    assertThat(recorded.headText).endsWith("\r\n\r\n")
    assertThat(recorded.rawSoapAction).isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"")
    assertThat(recorded.rawContentType).isEqualTo("text/xml; charset=\"utf-8\"")
    assertThat(recorded.action).isEqualTo("Stop")
    assertThat(recorded.arguments).containsExactly("InstanceID" to "0")
    assertThat(recorded.bodyText).isEqualTo(
      SoapEnvelope.render(
        DeviceDescription.SERVICE_AV_TRANSPORT,
        "Stop",
        listOf(SoapArgument("InstanceID", "0")),
      ),
    )
  }

  private fun quoted(action: String) =
    SoapEnvelope.soapActionHeader(DeviceDescription.SERVICE_AV_TRANSPORT, action)
}
