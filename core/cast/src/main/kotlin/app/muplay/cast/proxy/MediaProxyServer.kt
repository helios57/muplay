package app.muplay.cast.proxy

import app.muplay.cast.http.HttpHeaders
import app.muplay.cast.http.HttpRequestHead
import app.muplay.cast.http.HttpWire
import app.muplay.cast.http.MalformedHttpException
import app.muplay.cast.net.LocalNetworkOnly
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One request the proxy answered.
 *
 * [token] rather than the path or the upstream URL: the upstream URL carries Subsonic credentials
 * and must never be written anywhere, and the token is the only part of the path that identifies
 * anything. `null` means the request named nothing this proxy publishes.
 *
 * Task 7's routing proof reads this.
 */
data class ProxyRequest(val method: String, val token: String?, val rangeHeader: String?, val status: Int)

/**
 * **The phone's HTTP server**, so a renderer can fetch media it could not otherwise reach.
 *
 * Spec section 6: a renderer on the LAN cannot authenticate to Navidrome the way the phone can, and
 * *"a servlet container to serve range requests to one speaker is a large dependency used for a
 * fraction of its surface"*.
 *
 * A listening socket is **not** governed by `NetworkSecurityPolicy` -- that policy is consulted by
 * the platform's outbound HTTP stacks and has no mechanism to affect a `ServerSocket` -- so this
 * needs no manifest change and no cleartext permission. That is a fact about the platform and not
 * a reason the socket needs no rule of its own: [LocalNetworkOnly.acceptLocal] is the inbound half
 * of the local-network rule and it is what [acceptConnection] defaults to, so a connection from
 * off the LAN is closed rather than served. The capability token in the path is the other half,
 * and it is the half that answers a hostile app on this same phone -- which connects from
 * loopback, and is local by construction.
 *
 * Thread per connection, deliberately. A renderer opens two or three at a time (a `HEAD`, a `GET`,
 * and a second `GET` after a seek), each lives for the length of a track, and each holds an
 * upstream stream that must not be shared -- which a shared reader would do, returning interleaved
 * bytes to two readers with nothing reported anywhere.
 *
 * **One request per connection.** Every response carries `Connection: close` and the socket is
 * closed after it, so no request body is ever read and there is no second message on the
 * connection for a `Content-Length`/`Transfer-Encoding` disagreement to frame differently. Request
 * smuggling needs two messages on one connection; this server never has them.
 *
 * @param acceptConnection how a connection is taken off [ServerSocket]. Defaults to the inbound
 *   local-network guard; a test substitutes it to observe what this loop does with a refusal,
 *   which is the one thing about that guard a loopback-only test can see.
 */
class MediaProxyServer(
  private val upstream: ProxyUpstream,
  private val registry: ProxyRegistry,
  bindAddress: InetAddress = InetAddress.getByName(BIND_ALL),
  requestedPort: Int = 0,
  internal val acceptConnection: (ServerSocket) -> Socket? = LocalNetworkOnly::acceptLocal,
) : Closeable {

  private val server = ServerSocket(requestedPort, BACKLOG, bindAddress)
  private val log = CopyOnWriteArrayList<ProxyRequest>()
  private val fetchLatches = ConcurrentHashMap<String, CountDownLatch>()

  val port: Int get() = server.localPort
  val requestLog: List<ProxyRequest> get() = log.toList()

  fun start(): Int {
    thread(isDaemon = true, name = "media-proxy") {
      while (!server.isClosed) {
        // `null` from either arm means "carry on": the guard refused a peer, or the socket was
        // closed and the loop is about to end. Neither is a reason to stop serving the renderer
        // that is legitimately playing.
        val connection = runCatching { acceptConnection(server) }.getOrNull() ?: continue
        thread(isDaemon = true, name = "media-proxy-conn") {
          runCatching { serve(connection) }
          runCatching { connection.close() }
        }
      }
    }
    return port
  }

  /** The URL to hand a renderer. [host] is the address **the renderer** can reach this phone at. */
  fun urlFor(media: Published, host: String): String = "http://$host:$port${media.path}"

  /**
   * Blocks until a renderer has fetched [token], or the timeout expires.
   *
   * This is Task 7's **proof** that the chosen route works. Nothing else in this system can answer
   * "can that speaker reach this phone" -- a subnet comparison guesses, and a guess that is wrong
   * produces a cast that starts and plays nothing.
   *
   * Only a `GET` or a `HEAD` that resolved to a published item counts. A `POST`, a request for an
   * unknown token and a malformed request line do not: this answers *"did the renderer fetch the
   * media"*, and anything else arriving on the socket answers a different question.
   */
  fun awaitRequest(token: String, timeoutMs: Long): Boolean =
    latchFor(token).await(timeoutMs, TimeUnit.MILLISECONDS)

  override fun close() {
    server.close()
  }

  private fun latchFor(token: String): CountDownLatch =
    fetchLatches.computeIfAbsent(token) { CountDownLatch(1) }

  private fun serve(connection: Socket) {
    val output = connection.getOutputStream()
    val head = try {
      HttpWire.readRequestHead(connection.getInputStream())
    } catch (malformed: MalformedHttpException) {
      // Answered, not dropped. A renderer that gets nothing back retries forever; one that gets a
      // 400 stops and its logs say why. The exception itself is not echoed: it quotes bytes a peer
      // chose, and this response goes back to that peer.
      log += ProxyRequest(UNKNOWN_METHOD, null, null, 400)
      output.write(HttpWire.renderResponseHead(400, "Bad Request", closeHeaders()))
      output.flush()
      return
    }

    val media = registry.resolve(head.target)
    val status = respond(head, media, output)
    log += ProxyRequest(head.method, media?.token, head.headers[RANGE], status)
    if (media != null && head.method in BODY_METHODS) latchFor(media.token).countDown()
    output.flush()
  }

  private fun respond(head: HttpRequestHead, media: Published?, output: OutputStream): Int {
    if (head.method !in BODY_METHODS) {
      output.write(
        HttpWire.renderResponseHead(
          405,
          "Method Not Allowed",
          HttpHeaders.of(
            "Allow" to BODY_METHODS.joinToString(", "),
            "Content-Length" to "0",
            "Connection" to "close",
          ),
        ),
      )
      return 405
    }

    // The whole path is matched against a published one -- see ProxyRegistry.resolve. Traversal,
    // case games and trailing segments all land here, with no separate check for each.
    if (media == null) {
      output.write(HttpWire.renderResponseHead(404, "Not Found", closeHeaders()))
      return 404
    }

    // The two kinds are fetched differently and cannot be merged -- see `PublishedArtwork` for the
    // measurement that forces it. Everything downstream of the fetch (the range arithmetic, the
    // statuses, the headers) is [respondRanged]'s and is shared, so a change to what a 206 looks
    // like cannot apply to a track and not to its cover.
    return when (media) {
      is PublishedMedia -> respondTrack(head, media, output)
      is PublishedArtwork -> respondArtwork(head, media, output)
    }
  }

  /**
   * A track: length probed, then relayed a buffer at a time.
   *
   * Never buffered whole. A FLAC track is 30-40 MB and holding one would be a third of a modest
   * heap, per concurrent renderer -- which is exactly why [respondArtwork] may do the opposite and
   * this may not.
   */
  private fun respondTrack(head: HttpRequestHead, media: PublishedMedia, output: OutputStream): Int {
    val totalLength = try {
      upstream.totalLength(media.upstreamUrl)
    } catch (throttled: UpstreamThrottledException) {
      // 503, not 502. "Try again" is something a renderer can act on; "this is broken" is not, and
      // a renderer that believes the second one stops. Spec section 4 names the unhandled version
      // of this as looking like "random playback failure".
      output.write(HttpWire.renderResponseHead(503, "Service Unavailable", throttledHeaders(throttled)))
      return 503
    } ?: run {
      // A live transcode has no length (spec section 4). Serving it as a 200 with no Content-Length
      // gives the renderer no way to know when the track ends, and Sonos cuts it short. 502 is the
      // honest answer: this proxy cannot serve what the origin will not measure.
      output.write(HttpWire.renderResponseHead(502, "Bad Gateway", closeHeaders()))
      return 502
    }

    return respondRanged(head, totalLength, media.served.mimeType, output) { range, sink ->
      stream(media, range, sink)
    }
  }

  /**
   * A cover image: fetched whole, then served from memory with an accurate `Content-Length`.
   *
   * **This is the credential fix, and its shape is forced by the origin rather than chosen.** Cover
   * art used to reach a renderer as the Navidrome `getCoverArt` URL itself -- carrying `u`, `t` and
   * `s`, i.e. a non-expiring password equivalent -- inside `<upnp:albumArtURI>`, over plain HTTP, to
   * a device with no authentication of any kind. Publishing it as a second capability token is what
   * removes the credential while keeping the picture.
   *
   * It cannot go through [respondTrack]: `getCoverArt` sends no `Content-Length` and ignores
   * `Range`, so the length probe answers `null` and a renderer would receive 502 for every image.
   * See [PublishedArtwork] for the measurement.
   *
   * The `Content-Type` is **the origin's own**, not a guess. Navidrome answers `image/webp` for a
   * sized request whatever the source file was, so a fixed `image/jpeg` here would be a statement
   * about the bytes that is wrong most of the time.
   *
   * `Range` is still answered, out of the buffer, because the arithmetic is already written and a
   * renderer that asks then gets a correct answer for free.
   */
  private fun respondArtwork(head: HttpRequestHead, media: PublishedArtwork, output: OutputStream): Int {
    val body = try {
      upstream.readFully(media.upstreamUrl, MAX_ARTWORK_BYTES)
    } catch (throttled: UpstreamThrottledException) {
      output.write(HttpWire.renderResponseHead(503, "Service Unavailable", throttledHeaders(throttled)))
      return 503
    } ?: run {
      // The origin refused, or sent something larger than a cover image has any business being.
      // 502 rather than a truncated picture: half an image is a renderer retrying forever.
      output.write(HttpWire.renderResponseHead(502, "Bad Gateway", closeHeaders()))
      return 502
    }

    val contentType = body.contentType ?: FALLBACK_IMAGE_TYPE
    return respondRanged(head, body.bytes.size.toLong(), contentType, output) { range, sink ->
      // `runCatching` for the same reason the relay has one: a renderer that gives up mid-image
      // closes its socket and this write throws, which is ordinary rather than an error.
      runCatching {
        sink.write(body.bytes, range.firstByte.toInt(), range.length.toInt())
        sink.flush()
      }
    }
  }

  /**
   * The statuses, the ranges and the headers -- one copy, whatever is being served.
   *
   * Shared rather than duplicated per kind because the failure worth guarding against is the two
   * copies disagreeing: a 206 whose `Content-Range` counts one way for a track and another for its
   * cover is a defect no single-kind test can see.
   *
   * @param writeBody called only for a `GET`, with the range to write. A `HEAD` gets the identical
   *   head and no body, which is the whole point of a `HEAD`.
   */
  private fun respondRanged(
    head: HttpRequestHead,
    totalLength: Long,
    contentType: String,
    output: OutputStream,
    writeBody: (ByteRange, OutputStream) -> Unit,
  ): Int =
    when (val resolution = RangeHeader.resolve(RangeHeader.parse(head.headers[RANGE]), totalLength)) {
      RangeResolution.Unsatisfiable -> {
        output.write(
          HttpWire.renderResponseHead(
            416,
            "Range Not Satisfiable",
            HttpHeaders.of(
              // The asterisk form is the part a renderer actually reads: it says how long the
              // resource really is, so the next request can be a valid one.
              "Content-Range" to "$BYTES_UNIT */$totalLength",
              "Accept-Ranges" to BYTES_UNIT,
              "Content-Type" to contentType,
              "Content-Length" to "0",
              "Connection" to "close",
            ),
          ),
        )
        416
      }

      RangeResolution.Whole -> {
        val range = ByteRange(0, totalLength - 1)
        output.write(
          HttpWire.renderResponseHead(200, "OK", bodyHeaders(contentType, range.length, contentRange = null)),
        )
        if (head.method == "GET") writeBody(range, output)
        200
      }

      is RangeResolution.Partial -> {
        val range = resolution.range
        output.write(
          HttpWire.renderResponseHead(
            206,
            "Partial Content",
            bodyHeaders(
              contentType,
              range.length,
              contentRange = "$BYTES_UNIT ${range.firstByte}-${range.lastByte}/$totalLength",
            ),
          ),
        )
        if (head.method == "GET") writeBody(range, output)
        206
      }
    }

  /**
   * Identical for `GET` and `HEAD`, which is the point of a `HEAD`: a renderer probes for the
   * length and type, then requests the body, and the two answers must agree.
   *
   * `Accept-Ranges: bytes` is a promise `ServedMedia.protocolInfo` already made to the renderer
   * with `DLNA.ORG_OP=01`. The two are asserted against each other in this task's test.
   */
  private fun bodyHeaders(contentType: String, length: Long, contentRange: String?) = HttpHeaders(
    buildList {
      add("Content-Type" to contentType)
      add("Accept-Ranges" to BYTES_UNIT)
      contentRange?.let { add("Content-Range" to it) }
      add("Content-Length" to length.toString())
      add("Connection" to "close")
    },
  )

  private fun closeHeaders() =
    HttpHeaders.of("Content-Length" to "0", "Connection" to "close")

  /**
   * The 503 head, including the origin's own `Retry-After` when it sent one.
   *
   * One builder because both kinds meet a throttle and a renderer must be told the same thing
   * either way -- and because the header is conditional, which is exactly the sort of thing that
   * gets written once and forgotten in the copy.
   */
  private fun throttledHeaders(throttled: UpstreamThrottledException) = HttpHeaders(
    buildList {
      throttled.retryAfterSeconds?.let { add("Retry-After" to it.toString()) }
      add("Content-Length" to "0")
      add("Connection" to "close")
    },
  )

  /**
   * Relays the bytes, one buffer at a time.
   *
   * Never `readBytes()`: a FLAC track is 30-40 MB and buffering one whole would be a third of a
   * modest heap, per concurrent renderer. The stream is opened per request rather than shared,
   * because two renderers -- or one renderer's overlapping probe and read -- sharing a reader get
   * interleaved bytes with nothing reported anywhere.
   */
  private fun stream(media: PublishedMedia, range: ByteRange, output: OutputStream) {
    // The head has already been written by the time this runs, so a throttle here can only close
    // the connection early -- which a renderer reads as a truncated track and retries. That is the
    // correct behaviour and the reason `totalLength` is probed first: the 429 is almost always
    // seen there, where a 503 can still be sent.
    runCatching {
      upstream.open(media.upstreamUrl, range).use { input ->
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        var remaining = range.length
        while (remaining > 0) {
          val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
          if (read <= 0) break
          output.write(buffer, 0, read)
          remaining -= read
        }
        output.flush()
      }
    }
    // A renderer that stops reading mid-track (a seek, a stop, a power cut) closes its socket and
    // this write throws. That is ordinary, not an error, and the connection thread ends either way.
  }

  companion object {
    private const val BIND_ALL = "0.0.0.0"
    private const val BACKLOG = 16
    private const val RANGE = "Range"
    private const val BYTES_UNIT = "bytes"

    /** What a malformed request line's method is recorded as: it never parsed into one. */
    private const val UNKNOWN_METHOD = "?"

    /** The two methods this server answers. The `Allow` header is rendered from this list. */
    private val BODY_METHODS = listOf("GET", "HEAD")

    /** 64 KiB: large enough that the syscall count is irrelevant, small enough to be free. */
    private const val RELAY_BUFFER_BYTES = 64 * 1024

    /**
     * The most cover art this server will hold in memory for one request.
     *
     * The seeded fixtures come back around 70 KB at `QueueRepository.ARTWORK_SIZE_PX`, and a
     * full-resolution scan of a gatefold sleeve is a few megabytes; 8 MiB is generous for a real
     * cover and small enough that a hostile or misconfigured origin cannot exhaust a phone's heap
     * by answering a request this app itself made. Over it, the renderer gets a 502 and shows its
     * own placeholder -- the correct trade for a picture, and not one that would be correct for a
     * track.
     */
    const val MAX_ARTWORK_BYTES: Int = 8 * 1024 * 1024

    /**
     * What a cover image is served as when the origin declared nothing at all.
     *
     * JPEG rather than `application/octet-stream`, which several renderers refuse outright, and
     * rather than WebP, which older ones cannot decode: this arm is reached only when the origin
     * said nothing, and the guess most likely to render is the right one there.
     */
    const val FALLBACK_IMAGE_TYPE: String = "image/jpeg"
  }
}
