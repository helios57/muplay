package app.muplay.cast.fake

import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.http.HttpHeaders
import app.muplay.cast.http.HttpWire
import app.muplay.cast.soap.SoapArgument
import app.muplay.cast.soap.SoapEnvelope
import app.muplay.cast.soap.UpnpError
import app.muplay.cast.soap.UpnpTime
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.thread
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * One SOAP request, **as the bytes that arrived on the socket**, so a test may assert on what was
 * actually sent rather than on a re-rendering of what this fake managed to parse.
 *
 * That distinction is not pedantry: the single most important assertion in this task is that the
 * `SOAPACTION` header value carries its double quotes, and a recording that round-tripped the
 * header through a parser and back could normalise exactly the byte under test.
 */
class RecordedSoap(val headBytes: ByteArray, val bodyBytes: ByteArray) {
  val headText: String get() = String(headBytes, Charsets.US_ASCII)
  val bodyText: String get() = String(bodyBytes, Charsets.UTF_8)

  /** The raw `SOAPACTION` header **value**, quotes included, exactly as it arrived. */
  val rawSoapAction: String?
    get() = headText.lineSequence()
      .firstOrNull { it.startsWith("SOAPACTION:", ignoreCase = true) }
      ?.substringAfter(':')?.trim()

  /** The raw `Content-Type` header value, likewise. SOAP requires the quoted charset. */
  val rawContentType: String?
    get() = headText.lineSequence()
      .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
      ?.substringAfter(':')?.trim()

  val action: String? get() = rawSoapAction?.trim('"')?.substringAfterLast('#')

  /**
   * The `in` arguments, **in the order they arrived and entity-decoded**, or `null` when the body
   * is not a document a real device could have read.
   *
   * `null` rather than an empty list, and that is the whole point of this property's shape. This
   * used to be a regex -- `<(\w+)>(.*?)</\1>` over the text between `<s:Body>` and `</s:Body>` --
   * and a regex cannot tell well-formed XML from malformed XML. So this renderer, whose class KDoc
   * advertises it as *"strict by default"*, happily accepted a `SetAVTransportURI` whose
   * `CurrentURI` was an ordinary Navidrome stream URL: `...?u=x&t=y&s=z` unescaped inside an
   * element makes the document fail at *"The reference to entity `t` must end with ';'"*, no device
   * on earth would have parsed it, and 311 green tests could not see it because the only parser in
   * the loop was a pattern match. An empty list would have kept exactly that blindness -- "no
   * arguments" and "no document" are different facts, and only one of them is a request.
   *
   * Hardened the same way [app.muplay.cast.soap.SoapEnvelope] hardens the response parse -- no
   * DOCTYPE, no external entities -- because a fake that is laxer about XXE than the production
   * parser teaches the suite the wrong lesson about which documents are acceptable.
   *
   * Decoded, not raw: `textContent` returns what the sender *meant*, which is what a real renderer
   * acts on. So `CurrentURIMetaData` reads back as `<DIDL-Lite ...`, and a caller that
   * double-escaped it reads back as `&lt;DIDL-Lite ...` -- still visibly wrong, one layer later.
   *
   * [Strictness.requireWellFormedBody] decides what the renderer does with a `null`, and it
   * answers **401**, which is a measurement of real stacks rather than a preference: a device that
   * cannot parse the body cannot find an action element in it, and UPnP's 401 *"Invalid Action --
   * no action by that name at this service"* is what the common implementations send for exactly
   * that (libupnp's `soap_device.c` answers `SOAP_INVALID_ACTION` when `ixmlParseBufferEx` fails
   * on the request body).
   */
  val arguments: List<Pair<String, String>>? get() = soapArgumentsOf(bodyText)
}

/**
 * The `in` arguments of the one action element inside `<Body>`, or `null` for a body no device
 * could have read.
 *
 * A real `DocumentBuilder`, with the hardening `SoapEnvelope`'s own response parse applies: the
 * DOCTYPE is refused **in code**, through [app.muplay.cast.soap.SoapEnvelope.declaresDoctype],
 * before the parser sees the document (a platform that does not recognise `disallow-doctype-decl`
 * throws at `setFeature` rather than ignoring it, so the feature alone is a gate that can silently
 * not run), and external entities are switched off besides.
 */
private fun soapArgumentsOf(bodyText: String): List<Pair<String, String>>? {
  // `SoapEnvelope`'s own predicate, not a second copy of it. Two copies of one guard is how both
  // this module's DOCTYPE scans came to carry the same 4096-character window, and how both
  // recursive walks came to be unbounded; the predicate is `internal` precisely so it can be
  // observed and reused rather than re-typed.
  if (SoapEnvelope.declaresDoctype(bodyText)) return null
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
    }.newDocumentBuilder().parse(bodyText.byteInputStream(Charsets.UTF_8))
  }.getOrNull() ?: return null
  val body = childElements(document.documentElement)
    .firstOrNull { it.nodeName.substringAfterLast(':') == "Body" } ?: return null
  val action = childElements(body).firstOrNull() ?: return null
  return childElements(action).map { it.nodeName.substringAfterLast(':') to it.textContent }
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

/** One media fetch the renderer made, as the proxy saw it. */
data class RecordedMedia(val method: String, val target: String, val range: String?)

/**
 * **A real UPnP MediaRenderer, in this process, on loopback.**
 *
 * Spec section 10 files this at rung 2 of the test hierarchy -- *"an in-process **real** UPnP
 * renderer"* -- not at rung 4 with the fakes, and this class is written to deserve that. It is a
 * real `ServerSocket` speaking real HTTP/1.1 and real SOAP, serving a real device description, and
 * running a real transport state machine. The only thing about it that is not real is the
 * loudspeaker.
 *
 * **It is strict by default, and its strictness has its own test class**
 * (`FakeRendererStrictnessTest`). That is deliberate and it is the point: a fake that accepts
 * everything executes no rejection path, so the client's error handling is never exercised and the
 * fake's own permissiveness is invisible. Each knob in [Strictness] corresponds to something a
 * real Sonos rejects with the UPnP error named beside it. **Turn one off only in a test whose
 * subject is the lenient behaviour, and never as a way to make a red test green.**
 */
class FakeRenderer(
  private val strictness: Strictness = Strictness(),
  private val identity: Identity = Identity(),
) : Closeable {

  data class Strictness(
    /** SOAP 1.1 quotes the `SOAPACTION` value. Sonos enforces it. Violation: 401. */
    val requireQuotedSoapAction: Boolean = true,
    /** A control body is XML and a device parses it. See [RecordedSoap.arguments]. Violation: 401. */
    val requireWellFormedBody: Boolean = true,
    /** UPnP argument lists are ordered by the service description. Violation: 402. */
    val requireArgumentOrder: Boolean = true,
    /** Spec section 6: *"DIDL-Lite mandatory"*. Violation: 714. */
    val requireNonEmptyMetadata: Boolean = true,
    /** Spec section 6: Sonos infers MIME from the URL. Violation: 714. */
    val requireUrlExtension: Boolean = true,
    /** Only `InstanceID` 0 exists on a single-zone renderer. Violation: 718. */
    val requireInstanceIdZero: Boolean = true,
    /** What `A_ARG_TYPE_SeekMode` allows. Anything else: 710. */
    val supportedSeekModes: List<String> = listOf("REL_TIME"),
    /**
     * What the SCPD **advertises**, when that differs from what the device actually accepts.
     *
     * `null` -- the default -- means the two agree, which is the honest device. Setting it models
     * **an SCPD that lies**, which is the reason `UpnpRenderer.seek` catches `710` at all despite
     * reading the capability first: firmware has advertised modes it then refuses. Without this
     * knob the two lists cannot disagree, and the `710` arm of that catch is unreachable from any
     * test -- which is a strictness the fake would be claiming and not providing.
     */
    val declaredSeekModes: List<String>? = null,
    /** Spec section 4: *"Never Opus. Sonos cannot decode it."* Violation: 714. */
    val rejectedMimeTypes: Set<String> = setOf("audio/ogg", "audio/opus", "audio/webm"),
  )

  data class Identity(
    val udn: String = "uuid:RINCON_FAKE0000001400",
    val friendlyName: String = "Fake Speaker",
    val manufacturer: String = "Sonos, Inc.",
    val modelName: String = "Fake One",
    val hasRenderingControl: Boolean = true,
    /** `true` reproduces Sonos's shape: the renderer nested inside a `ZonePlayer`'s `deviceList`. */
    val embedRenderer: Boolean = true,
    /** When set, `GetPositionInfo` reports this as `TrackURI` -- the Sonos group-follower case. */
    val followingCoordinator: String? = null,
    // ---- Task 5 additions, kept together ------------------------------------------------------
    /**
     * Whether this device declares `SetNextAVTransportURI` in its SCPD **and** answers it.
     *
     * `false` by default because that is the honest majority: plenty of renderers -- and several
     * Sonos firmwares -- carry no such action, and a client that assumed otherwise would get 401
     * at every track transition. Both arms exist so `RendererCapabilities.supportsSetNextUri` can
     * be observed at two values against a real device rather than only against a string.
     */
    val supportsSetNextUri: Boolean = false,
    /**
     * `CurrentTransportStatus`, which is a **separate variable** from the transport state.
     *
     * A renderer that could not fetch or decode what it was given answers `ERROR_OCCURRED` here
     * while `CurrentTransportState` still reads an ordinary `STOPPED`. Held as a knob rather than
     * derived from a failed media fetch on purpose: the fetch happens on a background thread, so
     * deriving it would make every `Play` assertion in the suite race a socket timeout.
     */
    val transportStatus: String = "OK",
  )

  private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
  private val soap = CopyOnWriteArrayList<RecordedSoap>()
  private val media = CopyOnWriteArrayList<RecordedMedia>()
  private val documents = CopyOnWriteArrayList<String>()
  private val firstMedia = CountDownLatch(1)

  /** Whether the renderer actually fetches `CurrentURI` on `Play`. Task 7 turns this off. */
  @Volatile var fetchesMedia: Boolean = true

  /** Set by [disappear]. The socket stays bound; every connection is hung up on. */
  @Volatile private var vanished: Boolean = false

  @Volatile private var currentUri: String? = null
  @Volatile private var currentMetadata: String = ""
  @Volatile private var nextUri: String? = null
  @Volatile private var transportState: String = "STOPPED"
  @Volatile private var positionMs: Long = 0L
  @Volatile private var durationMs: Long = 0L
  @Volatile private var volume: Int = 30
  @Volatile private var muted: Boolean = false

  val port: Int get() = server.localPort
  val soapRequests: List<RecordedSoap> get() = soap.toList()
  val mediaRequests: List<RecordedMedia> get() = media.toList()

  /**
   * Every **document** this renderer served -- its description and its SCPDs -- by request target,
   * in order.
   *
   * Task 5 counts SCPD fetches with it, and the counting is the assertion rather than a
   * convenience: `capabilities()` caches, and the only way to tell a cache from a re-derivation
   * that happens to return an equal value is to ask the device how many times it was asked. An
   * identity check on the returned object would go green against a client that re-fetched and
   * memoised the *second* answer.
   */
  val documentRequests: List<String> get() = documents.toList()

  /** What `SetNextAVTransportURI` last queued, or `null` if it was cleared or never called. */
  fun queuedNextUri(): String? = nextUri

  val descriptionUrl: URI get() = URI("http://127.0.0.1:$port/xml/device_description.xml")
  val controlUrl: URI get() = URI("http://127.0.0.1:$port/MediaRenderer/AVTransport/Control")
  val renderingControlUrl: URI get() = URI("http://127.0.0.1:$port/MediaRenderer/RenderingControl/Control")

  fun start(): Int {
    thread(isDaemon = true, name = "fake-renderer") {
      while (!server.isClosed) {
        val connection = runCatching { server.accept() }.getOrNull() ?: continue
        thread(isDaemon = true) { runCatching { serve(connection) }; runCatching { connection.close() } }
      }
    }
    return port
  }

  /**
   * Stops answering, without a clean shutdown -- what a speaker losing power looks like.
   *
   * It **keeps its port**, and hangs up on every connection instead of releasing the listener. The
   * difference is not cosmetic and it was measured: releasing an ephemeral port and then asserting
   * that connecting to it fails is a race against every other process on the machine, and this
   * repository's own fleet runs many JVMs at once. `a seek at a speaker that has gone away is not
   * swallowed as a false` failed exactly once that way -- `seek` returned `true`, which means
   * *something else on the box had taken the port and answered the Seek*. Holding the socket bound
   * makes the failure this fake is imitating the only one a client can observe.
   *
   * From the client's side an immediate hang-up is what a rebooting speaker looks like anyway: a
   * connect that succeeds and a stream that ends before a status line -- `MalformedHttpException`,
   * which is an `IOException`, which `SoapClient` reports as a `SoapTransportException`.
   */
  fun disappear() {
    vanished = true
  }

  fun awaitMediaRequest(timeoutMs: Long): RecordedMedia? =
    if (firstMedia.await(timeoutMs, TimeUnit.MILLISECONDS)) media.firstOrNull() else null

  /** Advances the renderer's own clock, as if audio had played. */
  fun advance(millis: Long) {
    positionMs += millis
  }

  fun currentTransportState(): String = transportState

  override fun close() = server.close()

  // ---- the server --------------------------------------------------------------------------

  private fun serve(connection: Socket) {
    if (vanished) return
    val input = RecordingInputStream(connection.getInputStream())
    val head = HttpWire.readRequestHead(input)
    // Exactly the bytes of the head, terminating blank line included, taken off the socket before
    // anything parsed them.
    val headBytes = input.takeRecording()
    val body = head.headers.contentLength()?.let { input.readNBytes(it.toInt()) } ?: ByteArray(0)

    val response = when {
      head.target.endsWith("device_description.xml") ->
        ok("text/xml", description()).also { documents += head.target }
      head.target.endsWith("AVTransport1.xml") ->
        ok("text/xml", avTransportScpd()).also { documents += head.target }
      head.target.contains("AVTransport/Control") -> control(headBytes, body, avTransport = true)
      head.target.contains("RenderingControl/Control") -> control(headBytes, body, avTransport = false)
      else -> HttpWire.renderResponseHead(404, "Not Found", HttpHeaders.of("Content-Length" to "0"))
    }
    connection.getOutputStream().apply { write(response); flush() }
  }

  private fun ok(contentType: String, body: String): ByteArray {
    val bytes = body.toByteArray(Charsets.UTF_8)
    return HttpWire.renderResponseHead(
      HttpURLConnection.HTTP_OK,
      "OK",
      HttpHeaders.of("Content-Type" to contentType, "Content-Length" to "${bytes.size}"),
    ) + bytes
  }

  private fun fault(code: Int): ByteArray {
    val body = "<?xml version=\"1.0\"?>" +
      "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>" +
      "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
      "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
      "<errorCode>$code</errorCode><errorDescription>${UpnpError.describe(code)}</errorDescription>" +
      "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"
    val bytes = body.toByteArray(Charsets.UTF_8)
    return HttpWire.renderResponseHead(
      HttpURLConnection.HTTP_INTERNAL_ERROR,
      "Internal Server Error",
      HttpHeaders.of("Content-Type" to "text/xml", "Content-Length" to "${bytes.size}"),
    ) + bytes
  }

  private fun control(headBytes: ByteArray, body: ByteArray, avTransport: Boolean): ByteArray {
    val recorded = RecordedSoap(headBytes, body)
    soap += recorded

    val raw = recorded.rawSoapAction
      ?: return fault(UpnpError.INVALID_ACTION)
    // Strictness 1: the quotes are part of the value.
    if (strictness.requireQuotedSoapAction && !(raw.startsWith("\"") && raw.endsWith("\""))) {
      return fault(UpnpError.INVALID_ACTION)
    }
    val action = raw.trim('"').substringAfterLast('#')
    // Strictness 2: the body is XML, and a device reads it with a parser.
    //
    // A lenient renderer is one that acts on whatever it managed to salvage, which for a body that
    // did not parse is nothing at all -- so the knob turned off means "no arguments", never "the
    // arguments a regex thought it saw". Reintroducing a pattern match on this path would put back
    // exactly the blindness this knob exists to remove.
    val arguments = recorded.arguments
      ?: if (strictness.requireWellFormedBody) return fault(UpnpError.INVALID_ACTION) else emptyList()

    // Strictness 6: only instance 0 exists.
    val instance = arguments.firstOrNull { it.first == "InstanceID" }?.second
    if (strictness.requireInstanceIdZero && instance != null && instance != "0") {
      return fault(UpnpError.INVALID_INSTANCE_ID)
    }

    return if (avTransport) avTransportAction(action, arguments) else renderingAction(action, arguments)
  }

  private fun avTransportAction(action: String, arguments: List<Pair<String, String>>): ByteArray =
    when (action) {
      "SetAVTransportURI" -> {
        // Strictness 3: the declared order is InstanceID, CurrentURI, CurrentURIMetaData.
        if (strictness.requireArgumentOrder &&
          arguments.map { it.first } != listOf("InstanceID", "CurrentURI", "CurrentURIMetaData")
        ) {
          return fault(UpnpError.INVALID_ARGS)
        }
        val uri = arguments.firstOrNull { it.first == "CurrentURI" }?.second.orEmpty()
        val metadata = arguments.firstOrNull { it.first == "CurrentURIMetaData" }?.second.orEmpty()
        // Strictness 4: spec section 6, "DIDL-Lite mandatory".
        if (strictness.requireNonEmptyMetadata && metadata.isBlank()) return fault(UpnpError.ILLEGAL_MIME_TYPE)
        // ...and it must have been escaped exactly once on the wire, which -- now that
        // `RecordedSoap.arguments` decodes what it reads, as a device does -- is stated on the
        // DECODED value: a renderer that escaped once sees `<DIDL-Lite`, one that escaped twice
        // sees `&lt;DIDL-Lite`, and one that escaped not at all sees the document's bare TEXT
        // (the parser having read the DIDL elements as children of `CurrentURIMetaData` rather
        // than as its content). Only the first of those three starts with `<DIDL-Lite`.
        if (strictness.requireNonEmptyMetadata && !metadata.startsWith("<DIDL-Lite")) {
          return fault(UpnpError.ILLEGAL_MIME_TYPE)
        }
        // Strictness 5: Sonos infers MIME from the URL's extension.
        val extension = uri.substringAfterLast('/').substringAfterLast('.', "")
        if (strictness.requireUrlExtension && extension.isEmpty()) return fault(UpnpError.ILLEGAL_MIME_TYPE)
        // Strictness 7: never Opus.
        val declaredMime = Regex("protocolInfo=\"http-get:\\*:([^:]+):").find(metadata)?.groupValues?.get(1)
        if (declaredMime != null && declaredMime in strictness.rejectedMimeTypes) {
          return fault(UpnpError.ILLEGAL_MIME_TYPE)
        }
        currentUri = uri
        currentMetadata = metadata
        durationMs = UpnpTime.parseClock(
          Regex("duration=\"([^\"]+)\"").find(metadata)?.groupValues?.get(1),
        ) ?: 0L
        positionMs = 0L
        transportState = "STOPPED"
        ok("text/xml", responseEnvelope("SetAVTransportURI", emptyList()))
      }

      // Task 5. Present ONLY when the identity declares it, and a 401 otherwise -- which is what a
      // real renderer without the action answers, and the reason `UpnpRenderer.setNextUri` reads
      // the SCPD before calling rather than trying and catching.
      "SetNextAVTransportURI" -> {
        if (!identity.supportsSetNextUri) return fault(UpnpError.INVALID_ACTION)
        if (strictness.requireArgumentOrder &&
          arguments.map { it.first } != listOf("InstanceID", "NextURI", "NextURIMetaData")
        ) {
          return fault(UpnpError.INVALID_ARGS)
        }
        nextUri = arguments.firstOrNull { it.first == "NextURI" }?.second?.takeIf { it.isNotBlank() }
        ok("text/xml", responseEnvelope("SetNextAVTransportURI", emptyList()))
      }

      "Play" -> {
        val uri = currentUri ?: return fault(UpnpError.TRANSITION_NOT_AVAILABLE)
        // A real Sonos requires Speed, and requires it to be "1".
        val speed = arguments.firstOrNull { it.first == "Speed" }?.second
        if (speed == null) return fault(UpnpError.INVALID_ARGS)
        if (speed != "1") return fault(UpnpError.PLAY_SPEED_NOT_SUPPORTED)
        transportState = "PLAYING"
        if (fetchesMedia) fetchMedia(uri)
        ok("text/xml", responseEnvelope("Play", emptyList()))
      }

      "Pause" -> {
        transportState = "PAUSED_PLAYBACK"
        ok("text/xml", responseEnvelope("Pause", emptyList()))
      }

      "Stop" -> {
        transportState = "STOPPED"
        ok("text/xml", responseEnvelope("Stop", emptyList()))
      }

      "Seek" -> {
        // Nothing loaded, nothing to seek within: a real device answers 701 here, the same as it
        // does to `Play`. Task 5 relies on it -- `UpnpRenderer.seek` swallows exactly 710 and 711
        // and must let every other refusal through, and without a refusal that is neither of those
        // that arm of the catch is unreachable from any test.
        currentUri ?: return fault(UpnpError.TRANSITION_NOT_AVAILABLE)
        val unit = arguments.firstOrNull { it.first == "Unit" }?.second.orEmpty()
        if (unit !in strictness.supportedSeekModes) return fault(UpnpError.SEEK_MODE_NOT_SUPPORTED)
        val target = UpnpTime.parseClock(arguments.firstOrNull { it.first == "Target" }?.second)
          ?: return fault(UpnpError.ILLEGAL_SEEK_TARGET)
        if (durationMs > 0 && target > durationMs) return fault(UpnpError.ILLEGAL_SEEK_TARGET)
        positionMs = target
        ok("text/xml", responseEnvelope("Seek", emptyList()))
      }

      "GetTransportInfo" -> ok(
        "text/xml",
        responseEnvelope(
          "GetTransportInfo",
          listOf(
            "CurrentTransportState" to transportState,
            "CurrentTransportStatus" to identity.transportStatus,
            "CurrentSpeed" to "1",
          ),
        ),
      )

      "GetPositionInfo" -> ok(
        "text/xml",
        responseEnvelope(
          "GetPositionInfo",
          listOf(
            "Track" to "1",
            "TrackDuration" to UpnpTime.formatClock(durationMs),
            "TrackMetaData" to currentMetadata,
            "TrackURI" to (identity.followingCoordinator ?: currentUri.orEmpty()),
            "RelTime" to UpnpTime.formatClock(positionMs),
            "AbsTime" to UpnpTime.NOT_IMPLEMENTED,
            "RelCount" to "2147483647",
            "AbsCount" to "2147483647",
          ),
        ),
      )

      else -> fault(UpnpError.INVALID_ACTION)
    }

  private fun renderingAction(action: String, arguments: List<Pair<String, String>>): ByteArray {
    if (!identity.hasRenderingControl) return fault(UpnpError.INVALID_ACTION)
    return when (action) {
      "SetVolume" -> {
        val requested = arguments.firstOrNull { it.first == "DesiredVolume" }?.second?.toIntOrNull()
          ?: return fault(UpnpError.INVALID_ARGS)
        if (requested !in 0..MAX_VOLUME) return fault(UpnpError.INVALID_ARGS)
        volume = requested
        ok("text/xml", responseEnvelope("SetVolume", emptyList()))
      }
      "GetVolume" -> ok("text/xml", responseEnvelope("GetVolume", listOf("CurrentVolume" to "$volume")))
      "SetMute" -> {
        muted = arguments.firstOrNull { it.first == "DesiredMute" }?.second == "1"
        ok("text/xml", responseEnvelope("SetMute", emptyList()))
      }
      "GetMute" -> ok("text/xml", responseEnvelope("GetMute", listOf("CurrentMute" to if (muted) "1" else "0")))
      else -> fault(UpnpError.INVALID_ACTION)
    }
  }

  /**
   * What a renderer really does with a `CurrentURI`: a `HEAD` to learn the length and type, then a
   * ranged `GET`. Sonos issues both, which is why the proxy owes `HEAD` a real answer.
   */
  private fun fetchMedia(uri: String) {
    thread(isDaemon = true, name = "fake-renderer-fetch") {
      runCatching {
        val target = URI(uri)
        listOf("HEAD" to null, "GET" to "bytes=0-").forEach { (method, range) ->
          Socket(target.host, target.port).use { socket ->
            val head = buildString {
              append(method).append(' ').append(target.rawPath).append(" HTTP/1.1").append(HttpWire.CRLF)
              append("Host: ").append(target.host).append(':').append(target.port).append(HttpWire.CRLF)
              append("Connection: close").append(HttpWire.CRLF)
              if (range != null) append("Range: ").append(range).append(HttpWire.CRLF)
              append(HttpWire.CRLF)
            }
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); flush() }
            HttpWire.readResponseHead(socket.getInputStream())
            media += RecordedMedia(method, target.rawPath, range)
            firstMedia.countDown()
          }
        }
      }
    }
  }

  private fun responseEnvelope(action: String, out: List<Pair<String, String>>): String =
    SoapEnvelope.render(
      DeviceDescription.SERVICE_AV_TRANSPORT,
      "${action}Response",
      out.map { SoapArgument(it.first, it.second) },
    )

  /**
   * Sonos-shaped when [Identity.embedRenderer] -- the `MediaRenderer` nested inside a `ZonePlayer`'s
   * `deviceList`, alongside a `MediaServer` with no `AVTransport` -- and a flat generic renderer
   * otherwise. Both shapes are real, and a parser written against only the second one reports that
   * a Sonos is not a renderer.
   *
   * **The control URLs are relative on purpose.** `/MediaRenderer/AVTransport/Control` is exactly
   * what a real Sonos sends, and it is `DeviceDescription`'s job to resolve it against the
   * `LOCATION` this fake served it from. A fake that emitted absolute URLs would let a broken
   * resolver pass every test in this plan and fail against real hardware.
   */
  private fun description(): String {
    val rendererServices = buildString {
      if (identity.hasRenderingControl) {
        append(
          "<service>" +
            "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>" +
            "<controlURL>/MediaRenderer/RenderingControl/Control</controlURL>" +
            "<SCPDURL>/xml/RenderingControl1.xml</SCPDURL>" +
            "</service>",
        )
      }
      append(
        "<service>" +
          "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>" +
          "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>" +
          "<controlURL>/MediaRenderer/AVTransport/Control</controlURL>" +
          "<SCPDURL>/xml/AVTransport1.xml</SCPDURL>" +
          "</service>",
      )
    }

    val body = if (identity.embedRenderer) {
      "<device>" +
        "<deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>" +
        "<friendlyName>${identity.friendlyName}</friendlyName>" +
        "<manufacturer>${identity.manufacturer}</manufacturer>" +
        "<modelName>${identity.modelName}</modelName>" +
        "<UDN>${identity.udn}</UDN>" +
        "<serviceList><service>" +
        "<serviceType>urn:schemas-upnp-org:service:ZoneGroupTopology:1</serviceType>" +
        "<serviceId>urn:upnp-org:serviceId:ZoneGroupTopology</serviceId>" +
        "<controlURL>/ZoneGroupTopology/Control</controlURL>" +
        "<SCPDURL>/xml/ZoneGroupTopology1.xml</SCPDURL>" +
        "</service></serviceList>" +
        "<deviceList>" +
        // A MediaServer with no AVTransport, exactly as a real Sonos advertises. `CastDevice.from`
        // must skip it, and this is what gives it something to skip.
        "<device>" +
        "<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>" +
        "<friendlyName>${identity.friendlyName} Media Server</friendlyName>" +
        "<UDN>${identity.udn}_MS</UDN>" +
        "<serviceList><service>" +
        "<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>" +
        "<serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>" +
        "<controlURL>/MediaServer/ContentDirectory/Control</controlURL>" +
        "</service></serviceList>" +
        "</device>" +
        "<device>" +
        "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
        "<friendlyName>${identity.friendlyName} Media Renderer</friendlyName>" +
        "<UDN>${identity.udn}_MR</UDN>" +
        "<serviceList>$rendererServices</serviceList>" +
        "</device>" +
        "</deviceList>" +
        "</device>"
    } else {
      "<device>" +
        "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
        "<friendlyName>${identity.friendlyName}</friendlyName>" +
        "<manufacturer>${identity.manufacturer}</manufacturer>" +
        "<modelName>${identity.modelName}</modelName>" +
        "<UDN>${identity.udn}</UDN>" +
        "<serviceList>$rendererServices</serviceList>" +
        "</device>"
    }

    return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
      "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">" +
      "<specVersion><major>1</major><minor>0</minor></specVersion>" +
      body +
      "</root>"
  }

  /**
   * The `AVTransport` service description.
   *
   * Only two things in it are load-bearing and both are read by Task 5's `RendererCapabilities`:
   * `A_ARG_TYPE_SeekMode`'s `allowedValueList`, which decides how -- and whether -- this device can
   * be seeked, and whether `SetNextAVTransportURI` appears in the action list.
   */
  private fun avTransportScpd(): String =
    "<?xml version=\"1.0\"?>" +
      "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
      "<specVersion><major>1</major><minor>0</minor></specVersion>" +
      "<actionList>" +
      (
        listOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetTransportInfo", "GetPositionInfo") +
          listOfNotNull("SetNextAVTransportURI".takeIf { identity.supportsSetNextUri })
        ).joinToString("") { "<action><name>$it</name></action>" } +
      "</actionList>" +
      "<serviceStateTable>" +
      "<stateVariable sendEvents=\"no\">" +
      "<name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType>" +
      "<allowedValueList>" +
      (strictness.declaredSeekModes ?: strictness.supportedSeekModes)
        .joinToString("") { "<allowedValue>$it</allowedValue>" } +
      "</allowedValueList>" +
      "</stateVariable>" +
      "</serviceStateTable>" +
      "</scpd>"

  /**
   * Every byte read through it, kept, until someone takes the recording.
   *
   * This is what lets [RecordedSoap] hold the head **as it arrived** rather than as a re-rendering
   * of a parsed `HttpHeaders`. A re-rendering would be honest about the names and values and would
   * still normalise the one byte this task cares most about -- whether `SOAPACTION` arrived
   * quoted -- because it would put the value back through code that has already decided what a
   * value is.
   */
  private class RecordingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    private var recording = ByteArrayOutputStream()

    override fun read(): Int = super.read().also { if (it != -1) recording.write(it) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
      super.read(b, off, len).also { if (it > 0) recording.write(b, off, it) }

    fun takeRecording(): ByteArray = recording.toByteArray().also { recording = ByteArrayOutputStream() }
  }

  private companion object {
    const val BACKLOG = 8
    const val MAX_VOLUME = 100
  }
}
