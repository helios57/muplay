package app.muplay.cast.soap

import app.muplay.cast.discovery.DeviceDescription
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test

/**
 * The SOAP envelope, byte for byte, and the SOAPACTION header, quotes included.
 *
 * Byte-exactness is the assertion because everything weaker is satisfied by an envelope a real
 * Sonos answers 500 to. `contains("SetAVTransportURI")` passes with the namespace wrong, the
 * arguments reordered and the metadata unescaped.
 */
class SoapEnvelopeTest {

  private val avTransport = DeviceDescription.SERVICE_AV_TRANSPORT

  @Test
  fun `the envelope is exactly this`() {
    val xml = SoapEnvelope.render(
      serviceType = avTransport,
      action = "Play",
      arguments = listOf(SoapArgument("InstanceID", "0"), SoapArgument("Speed", "1")),
    )

    assertThat(xml).isEqualTo(
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
        "<s:Body>" +
        "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
        "<InstanceID>0</InstanceID>" +
        "<Speed>1</Speed>" +
        "</u:Play>" +
        "</s:Body>" +
        "</s:Envelope>",
    )
  }

  @Test
  fun `the action name and the service namespace are the ones the caller gave`() {
    // Two observations of each, so neither can be a constant. `RenderingControl` really is a
    // different namespace on the same device, and mixing them up returns 401 from every action.
    val rendering = SoapEnvelope.render(
      DeviceDescription.SERVICE_RENDERING_CONTROL,
      "SetVolume",
      listOf(SoapArgument("InstanceID", "0")),
    )

    assertThat(rendering).contains(
      "<u:SetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">",
    )
    assertThat(rendering).contains("</u:SetVolume>")
    assertThat(SoapEnvelope.render(avTransport, "Stop", emptyList()))
      .contains("<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
  }

  /**
   * **Order is the protocol here, not a preference.** UPnP argument lists are ordered by the
   * service description, and implementations parse positionally. A strict device answers 402 to a
   * reordered list; a lenient one treats the metadata document as the URL.
   */
  @Test
  fun `arguments appear in the order they were given, and reordering them changes the bytes`() {
    val correct = SoapEnvelope.render(
      avTransport,
      "SetAVTransportURI",
      listOf(
        SoapArgument("InstanceID", "0"),
        SoapArgument("CurrentURI", "http://10.0.0.2:8080/media/a.mp3"),
        SoapArgument("CurrentURIMetaData", "&lt;DIDL-Lite/&gt;"),
      ),
    )
    val reordered = SoapEnvelope.render(
      avTransport,
      "SetAVTransportURI",
      listOf(
        SoapArgument("InstanceID", "0"),
        SoapArgument("CurrentURIMetaData", "&lt;DIDL-Lite/&gt;"),
        SoapArgument("CurrentURI", "http://10.0.0.2:8080/media/a.mp3"),
      ),
    )

    assertThat(correct).contains(
      "<InstanceID>0</InstanceID>" +
        "<CurrentURI>http://10.0.0.2:8080/media/a.mp3</CurrentURI>" +
        "<CurrentURIMetaData>&lt;DIDL-Lite/&gt;</CurrentURIMetaData>",
    )
    // The renderer would not agree these are the same request, and neither does this assertion.
    // An implementation that sorted arguments, or that took a `Map` a caller had sorted, produces
    // identical output here and fails.
    assertThat(reordered).isNotEqualTo(correct)
  }

  @Test
  fun `an argument value is inserted verbatim, because escaping is the caller's decision`() {
    // Deliberate: `CurrentURIMetaData` arrives ALREADY escaped from `DidlLite` (Task 4), and
    // escaping it again here is the `&amp;lt;DIDL-Lite` defect. The envelope's job is framing.
    val xml = SoapEnvelope.render(
      avTransport,
      "SetAVTransportURI",
      listOf(SoapArgument("CurrentURIMetaData", "&lt;DIDL-Lite&gt;")),
    )

    assertThat(xml).contains("<CurrentURIMetaData>&lt;DIDL-Lite&gt;</CurrentURIMetaData>")
    assertThat(xml).doesNotContain("&amp;lt;")
  }

  @Test
  fun `an action with no arguments is still a well-formed empty element pair`() {
    assertThat(SoapEnvelope.render(avTransport, "Stop", emptyList()))
      .contains("<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"></u:Stop>")
  }

  /**
   * The quotes are part of the header **value**. This is the single most commonly-omitted detail in
   * a hand-written UPnP client, and the failure it causes is distributed: some renderers accept it
   * and Sonos does not.
   */
  @Test
  fun `the soapaction header value is quoted`() {
    assertThat(SoapEnvelope.soapActionHeader(avTransport, "Play"))
      .isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
    assertThat(SoapEnvelope.soapActionHeader(DeviceDescription.SERVICE_RENDERING_CONTROL, "SetVolume"))
      .isEqualTo("\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"")
    // Stated separately, because `isEqualTo` on a string with quotes in it is easy to misread.
    assertThat(SoapEnvelope.soapActionHeader(avTransport, "Play")).startsWith("\"").endsWith("\"")
  }

  /**
   * **The service type and the action name are attacker-controlled**: both are parsed out of the
   * device-description XML the renderer itself served. `soapActionHeader` interpolates them into a
   * header value and `render` interpolates them into an element name and an attribute value, so
   * both refuse anything outside [SoapNames]'s alphabets rather than emitting it.
   *
   * The refusal is a `MalformedSoapRequestException`, which is an `IOException`, so a caller
   * guarding a renderer call catches it. Left unvalidated it would reach
   * `CastHttpClient.exchange` as an `IllegalArgumentException` -- a crash, not a bad request.
   */
  @Test
  fun `a hostile service type or action name never reaches a header value or an element name`() {
    val splitting = "urn:x\"\r\nX-Injected: 1\r\n"

    assertThatThrownBy { SoapEnvelope.soapActionHeader(splitting, "Play") }
      .isInstanceOf(MalformedSoapRequestException::class.java)
    assertThatThrownBy { SoapEnvelope.soapActionHeader(avTransport, "Play\r\nX-Injected: 1") }
      .isInstanceOf(MalformedSoapRequestException::class.java)
    assertThatThrownBy { SoapEnvelope.render(splitting, "Play", emptyList()) }
      .isInstanceOf(MalformedSoapRequestException::class.java)
    assertThatThrownBy { SoapEnvelope.render(avTransport, "Play xmlns:u=\"x\"", emptyList()) }
      .isInstanceOf(MalformedSoapRequestException::class.java)
    // The argument names are element names too. Nothing peer-derived reaches them today; this is
    // the assertion that keeps `render` total -- well-formed XML out, or an exception.
    assertThatThrownBy {
      SoapEnvelope.render(avTransport, "Play", listOf(SoapArgument("Speed><evil", "1")))
    }.isInstanceOf(MalformedSoapRequestException::class.java)
  }

  @Test
  fun `the content type is the one soap requires, charset included`() {
    assertThat(SoapEnvelope.CONTENT_TYPE).isEqualTo("text/xml; charset=\"utf-8\"")
  }

  @Test
  fun `a response's out arguments are read by name`() {
    val response = """
      <?xml version="1.0"?>
      <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
        <s:Body>
          <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <Track>1</Track>
            <TrackDuration>0:05:00</TrackDuration>
            <TrackMetaData>&lt;DIDL-Lite/&gt;</TrackMetaData>
            <TrackURI>http://10.0.0.2:8080/media/a.mp3</TrackURI>
            <RelTime>0:01:23</RelTime>
            <AbsTime>NOT_IMPLEMENTED</AbsTime>
            <RelCount>2147483647</RelCount>
            <AbsCount>2147483647</AbsCount>
          </u:GetPositionInfoResponse>
        </s:Body>
      </s:Envelope>
    """.trimIndent()

    val out = SoapEnvelope.parseResponse("GetPositionInfo", response)

    // The exact key set, in document order, and then the values. `containsKey("RelTime")` alone
    // would pass with every other field silently dropped.
    assertThat(out.keys).containsExactly(
      "Track", "TrackDuration", "TrackMetaData", "TrackURI", "RelTime", "AbsTime", "RelCount", "AbsCount",
    )
    assertThat(out["RelTime"]).isEqualTo("0:01:23")
    assertThat(out["TrackDuration"]).isEqualTo("0:05:00")
    assertThat(out["AbsTime"]).isEqualTo("NOT_IMPLEMENTED")
    assertThat(out["TrackURI"]).isEqualTo("http://10.0.0.2:8080/media/a.mp3")
    // Entity-decoded on the way out: the metadata was escaped once by the device, and the parser
    // returns what it meant rather than what it wrote.
    assertThat(out["TrackMetaData"]).isEqualTo("<DIDL-Lite/>")
  }

  @Test
  fun `a response for a different action is not accepted as this one`() {
    // A renderer answering the wrong response element is a device bug, and reading it anyway would
    // produce a position taken from a volume query. Loud is better.
    val wrong = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
      "<u:GetVolumeResponse xmlns:u=\"x\"><CurrentVolume>30</CurrentVolume></u:GetVolumeResponse>" +
      "</s:Body></s:Envelope>"

    assertThat(SoapEnvelope.parseResponse("GetPositionInfo", wrong)).isEmpty()
    // The same document read as the action it really carries, so "empty" above is a decision about
    // the action rather than a parser that returns empty for everything.
    assertThat(SoapEnvelope.parseResponse("GetVolume", wrong)).containsExactly(entry("CurrentVolume", "30"))
  }

  @Test
  fun `a body that is not xml at all, or has no Body element, is empty rather than an exception`() {
    // A renderer answering an HTML error page is a device this client keeps talking to, not a
    // crash. Both parsers are total.
    assertThat(SoapEnvelope.parseResponse("Play", "<html><body>go away")).isEmpty()
    assertThat(SoapEnvelope.parseFault("<html><body>go away")).isNull()
    assertThat(SoapEnvelope.parseResponse("Play", "")).isEmpty()
    assertThat(
      SoapEnvelope.parseResponse("Play", "<s:Envelope xmlns:s=\"x\"><s:Header/></s:Envelope>"),
    ).isEmpty()
  }

  @Test
  fun `a fault is parsed into its upnp error code and description`() {
    val fault = """
      <?xml version="1.0"?>
      <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
        <s:Body>
          <s:Fault>
            <faultcode>s:Client</faultcode>
            <faultstring>UPnPError</faultstring>
            <detail>
              <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                <errorCode>714</errorCode>
                <errorDescription>Illegal MIME-type</errorDescription>
              </UPnPError>
            </detail>
          </s:Fault>
        </s:Body>
      </s:Envelope>
    """.trimIndent()

    val parsed = SoapEnvelope.parseFault(fault)!!

    assertThat(parsed.errorCode).isEqualTo(714)
    assertThat(parsed.errorDescription).isEqualTo("Illegal MIME-type")
  }

  @Test
  fun `a second fault code parses to a second number`() {
    // The observation that stops `errorCode` being 714 forever.
    assertThat(
      SoapEnvelope.parseFault(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail>" +
          "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>701</errorCode>" +
          "</UPnPError></detail></s:Fault></s:Body></s:Envelope>",
      )!!.errorCode,
    ).isEqualTo(701)
  }

  @Test
  fun `a fault this client cannot read a code out of is still a fault, at ACTION_FAILED`() {
    // Three shapes, all real: a bare SOAP fault with no UPnP detail at all, a detail whose
    // errorCode is not a number, and a detail with no errorCode element. None of them may be
    // reported as success -- the device refused, and only the reason is missing.
    val bare = "<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault><faultcode>s:Server</faultcode>" +
      "</s:Fault></s:Body></s:Envelope>"
    val notANumber = "<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault><detail><UPnPError>" +
      "<errorCode>oops</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
    val noCode = "<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault><detail><UPnPError>" +
      "<errorDescription>something</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    listOf(bare, notANumber, noCode).forEach { xml ->
      assertThat(SoapEnvelope.parseFault(xml))
        .describedAs("fault parsed from %s", xml)
        .isEqualTo(UpnpFault(UpnpError.ACTION_FAILED, null))
    }
  }

  @Test
  fun `a fault with an error code but no description carries a null description`() {
    // Null, not "": the caller falls back to `UpnpError.describe` on null, and an empty string
    // would produce "714 ()" in the picker's failure line.
    assertThat(
      SoapEnvelope.parseFault(
        "<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault><detail><UPnPError>" +
          "<errorCode>718</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>",
      ),
    ).isEqualTo(UpnpFault(718, null))
  }

  /**
   * The response body comes from an unauthenticated device on the LAN, and a `DOCTYPE` is how an
   * XML parser is talked into reading a local file or opening a connection. `DeviceDescription`
   * refuses one in code rather than by a parser feature, for the portability reason recorded on
   * its own `rejectDoctype`; this parser refuses one the same way and for the same reason.
   */
  @Test
  fun `a fault carrying a DOCTYPE is refused rather than parsed`() {
    val xxe = "<?xml version=\"1.0\"?>" +
      "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
      "<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault><detail><UPnPError>" +
      "<errorCode>714</errorCode><errorDescription>&xxe;</errorDescription>" +
      "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    assertThat(SoapEnvelope.parseFault(xxe)).isNull()
    assertThat(
      SoapEnvelope.parseResponse(
        "Play",
        "<!DOCTYPE s:Envelope><s:Envelope xmlns:s=\"x\"><s:Body><u:PlayResponse xmlns:u=\"x\">" +
          "<Ok>1</Ok></u:PlayResponse></s:Body></s:Envelope>",
      ),
    ).isEmpty()
  }

  /**
   * **The same 4 KiB blind spot `DeviceDescription` carried, in the second copy of the guard.**
   *
   * A comment is legal `Misc` in the prolog, and the prolog has no length limit, so `<!--` + five
   * thousand spaces + `-->` + a doctype is well-formed XML whose doctype no fixed-size window can
   * see.
   *
   * **Asserted on the predicate, not through `parseFault`, and that is the point of the test.**
   * `bodyOf` answers `null` for a refused doctype and `null` for a document the parser would not
   * read -- and on this JVM the `disallow-doctype-decl` feature refuses that document itself, so
   * `parseFault(hostile) == null` is green with the scan looking at four kilobytes, at everything,
   * or at nothing whatsoever. The platform the scan exists for is Android, where that feature is
   * expected to be refused at `setFeature`, and this project has no tier that can observe it. So
   * the guard's own decision is what is asserted -- the same move `DeviceDescriptionTest` makes
   * when it asserts its sibling's own sentence instead of the word "DOCTYPE" that SAX also says.
   */
  @Test
  fun `a doctype hidden behind a five kilobyte comment is still seen by the guard`() {
    val hostile = "<!--" + " ".repeat(5_000) + "-->" +
      "<!DOCTYPE s:Envelope [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>" +
      "<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault><detail><UPnPError>" +
      "<errorCode>714</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    // Past any prologue-sized window -- the fact that makes this a regression and not a restatement.
    assertThat(hostile.indexOf("<!DOCTYPE")).isGreaterThan(4_096)

    assertThat(SoapEnvelope.declaresDoctype(hostile)).isTrue
    // And the ordinary shape still answers true, so this is not a predicate that says yes to
    // everything.
    assertThat(SoapEnvelope.declaresDoctype("<!DOCTYPE foo><s:Envelope/>")).isTrue
    assertThat(SoapEnvelope.declaresDoctype("<s:Envelope xmlns:s=\"x\"><s:Body/></s:Envelope>")).isFalse

    // The end-to-end contract, stated because it is the contract -- not because it discriminates.
    assertThat(SoapEnvelope.parseFault(hostile)).isNull()
  }

  /**
   * **A fault body that is nothing but nesting is a refusal, not a `StackOverflowError`.**
   *
   * `SoapClient.invoke` calls `parseFault` on **every** response, outside its `try`/`catch` and
   * outside the `runCatching` that guards the parse -- so before the depth bound, an
   * unauthenticated device on the LAN could answer any action with ~56 KB of nested elements and
   * take the calling coroutine down with an `Error`. That falsifies this layer's headline
   * contract: `SoapClient`'s KDoc tells Tasks 5, 8 and 9 that one `catch (e: IOException)` around
   * a call is complete, and no `IOException` catch sees a `StackOverflowError`.
   *
   * The body is far inside `CastHttpClient.maxBodyBytes`, which is why the size guard upstream is
   * no help. `assertThatCode` catches `Throwable`, so removing the bound fails this test rather
   * than killing the runner.
   */
  @Test
  fun `a fault nested twenty thousand deep is a plain refusal rather than a stack overflow`() {
    val deep = "<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault>" +
      "<a>".repeat(20_000) + "</a>".repeat(20_000) +
      "</s:Fault></s:Body></s:Envelope>"

    // Well under the 1 MiB body cap CastHttpClient applies, so nothing upstream refuses this.
    assertThat(deep.length).isLessThan(1024 * 1024)

    // "The device refused and did not say why" -- the answer `parseFault` already gives for a
    // fault whose detail it cannot read, which is precisely what a fault this deep is.
    assertThat(SoapEnvelope.parseFault(deep)).isEqualTo(UpnpFault(UpnpError.ACTION_FAILED, null))
  }

  /**
   * Both sides of the depth bound, because "stop at 32" and "stop at 0" are the same test
   * otherwise -- and a real fault nests, so a bound that refused all nesting would report every
   * device error as ACTION_FAILED and lose the code Task 5 branches on.
   */
  @Test
  fun `a fault detail nested within the bound is still read, and one past it is not`() {
    fun fault(depth: Int) = "<s:Envelope xmlns:s=\"x\"><s:Body><s:Fault>" +
      "<a>".repeat(depth) +
      "<UPnPError><errorCode>714</errorCode></UPnPError>" +
      "</a>".repeat(depth) +
      "</s:Fault></s:Body></s:Envelope>"

    // 32 levels of padding puts `UPnPError` at depth 32 counting from `Fault`'s children.
    assertThat(SoapEnvelope.parseFault(fault(32))).isEqualTo(UpnpFault(714, null))
    assertThat(SoapEnvelope.parseFault(fault(33)))
      .isEqualTo(UpnpFault(UpnpError.ACTION_FAILED, null))
  }

  @Test
  fun `a successful response is not a fault`() {
    assertThat(
      SoapEnvelope.parseFault(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
          "<u:PlayResponse xmlns:u=\"x\"/></s:Body></s:Envelope>",
      ),
    ).isNull()
  }

  @Test
  fun `the named error codes say what they mean`() {
    // These strings reach a user through the cast picker (Task 10), so they are asserted rather
    // than left to whatever `toString` a future refactor produces.
    assertThat(UpnpError.describe(UpnpError.INVALID_ACTION)).isEqualTo("Invalid Action")
    assertThat(UpnpError.describe(UpnpError.TRANSITION_NOT_AVAILABLE)).isEqualTo("Transition not available")
    assertThat(UpnpError.describe(UpnpError.ILLEGAL_MIME_TYPE)).isEqualTo("Illegal MIME-type")
    assertThat(UpnpError.describe(UpnpError.SEEK_MODE_NOT_SUPPORTED)).isEqualTo("Seek mode not supported")
    assertThat(UpnpError.describe(UpnpError.RESOURCE_NOT_FOUND)).isEqualTo("Resource not found")
    assertThat(UpnpError.describe(UpnpError.INVALID_INSTANCE_ID)).isEqualTo("Invalid InstanceID")
    assertThat(UpnpError.describe(UpnpError.INVALID_ARGS)).isEqualTo("Invalid Args")
    assertThat(UpnpError.describe(UpnpError.ACTION_FAILED)).isEqualTo("Action Failed")
    assertThat(UpnpError.describe(UpnpError.NO_CONTENTS)).isEqualTo("No contents")
    assertThat(UpnpError.describe(UpnpError.READ_ERROR)).isEqualTo("Read error")
    assertThat(UpnpError.describe(UpnpError.FORMAT_NOT_SUPPORTED))
      .isEqualTo("Format not supported for playback")
    assertThat(UpnpError.describe(UpnpError.TRANSPORT_IS_LOCKED)).isEqualTo("Transport is locked")
    assertThat(UpnpError.describe(UpnpError.ILLEGAL_SEEK_TARGET)).isEqualTo("Illegal seek target")
    assertThat(UpnpError.describe(UpnpError.PLAY_SPEED_NOT_SUPPORTED))
      .isEqualTo("Play speed not supported")
    // And the numbers themselves, because a wrong constant is a wrong branch in Task 5.
    assertThat(UpnpError.INVALID_ACTION).isEqualTo(401)
    assertThat(UpnpError.INVALID_ARGS).isEqualTo(402)
    assertThat(UpnpError.ACTION_FAILED).isEqualTo(501)
    assertThat(UpnpError.TRANSITION_NOT_AVAILABLE).isEqualTo(701)
    assertThat(UpnpError.NO_CONTENTS).isEqualTo(702)
    assertThat(UpnpError.READ_ERROR).isEqualTo(703)
    assertThat(UpnpError.FORMAT_NOT_SUPPORTED).isEqualTo(704)
    assertThat(UpnpError.TRANSPORT_IS_LOCKED).isEqualTo(705)
    assertThat(UpnpError.SEEK_MODE_NOT_SUPPORTED).isEqualTo(710)
    assertThat(UpnpError.ILLEGAL_SEEK_TARGET).isEqualTo(711)
    assertThat(UpnpError.ILLEGAL_MIME_TYPE).isEqualTo(714)
    assertThat(UpnpError.RESOURCE_NOT_FOUND).isEqualTo(716)
    assertThat(UpnpError.PLAY_SPEED_NOT_SUPPORTED).isEqualTo(717)
    assertThat(UpnpError.INVALID_INSTANCE_ID).isEqualTo(718)
    // An unknown code is reported as itself rather than as "unknown", so a device's own number
    // reaches the log.
    assertThat(UpnpError.describe(999)).contains("999")
  }

  @Test
  fun `the two exception types say which action failed and how`() {
    // Task 8's handover branches on these two types, and Task 10 prints their messages. Both are
    // `IOException`, so one `catch` around a renderer call sees either.
    val refused = UpnpErrorException("Seek", UpnpFault(710, "Seek mode not supported"))
    assertThat(refused.action).isEqualTo("Seek")
    assertThat(refused.fault.errorCode).isEqualTo(710)
    assertThat(refused.message).contains("Seek", "710", "Seek mode not supported")

    // With no description of its own, the message falls back to this client's own table rather
    // than printing "null".
    assertThat(UpnpErrorException("Play", UpnpFault(701, null)).message)
      .contains("701", "Transition not available")

    val gone = SoapTransportException("Play", statusCode = 503)
    assertThat(gone.action).isEqualTo("Play")
    assertThat(gone.statusCode).isEqualTo(503)
    assertThat(gone.message).contains("Play", "503")
    assertThat(gone.cause).isNull()

    val cause = java.net.SocketTimeoutException("read timed out")
    assertThat(SoapTransportException("Stop", statusCode = 0, cause = cause).cause).isSameAs(cause)
  }
}
