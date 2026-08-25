package app.muplay.cast.proxy

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Navidrome refused because too many transcodes are already running.
 *
 * Spec section 4: *"Handle HTTP 429 -- Navidrome 0.62.0 added `Transcoding.MaxConcurrent`.
 * Unhandled, this looks like random playback failure."* Its own type, so [MediaProxyServer] can
 * answer **503 with `Retry-After`** rather than 502: 503 tells a renderer to try again, which it
 * can act on; 502 tells it the resource is broken, and a renderer that believes that stops.
 *
 * The message names no URL, on purpose. Every URL this package handles is a Navidrome stream URL
 * carrying `u`, `t` and `s`, and an exception message is the one string in this project that
 * reliably ends up in a bug report.
 */
class UpstreamThrottledException(val retryAfterSeconds: Long?) : IOException(
  // An `if` rather than `?.let { } ?: ""`: the `let` cannot return null, so the elvis's null arm is
  // a branch nothing can reach, and an unreachable arm is a coverage floor somebody later lowers.
  if (retryAfterSeconds == null) {
    "the upstream server is throttling this client"
  } else {
    "the upstream server is throttling this client, and asked for $retryAfterSeconds s before the next attempt"
  },
)

/**
 * When to retry an upstream refusal, and how long to wait.
 *
 * A separate object from the fetch for the same reason Plan 3 split `StreamRetryPolicy` out of its
 * Media3 adapter: it is arithmetic, and arithmetic belongs where a fast tier can hold it to a floor.
 *
 * **Deliberately not shared with Plan 3's `StreamRetryPolicy`**, which lives in `:core:media`.
 * `:core:cast` must not depend on `:core:media` -- the dependency runs the other way, and that is
 * what keeps this module free of Media3 and therefore inside Tier 1. Two small pieces of backoff
 * arithmetic in two modules is the price of that boundary. If a third appears, promote one to
 * `:core:model`.
 */
object ProxyRetry {

  const val TOO_MANY_REQUESTS: Int = 429

  /**
   * The last attempt number that may be followed by another, so **at most `MAX_ATTEMPTS + 1`
   * requests** reach the origin and at most `MAX_ATTEMPTS` waits happen between them.
   *
   * Spelled out because the off-by-one is the whole question: at 4 this is five requests and
   * 0.5 + 1 + 2 + 4 = 7.5 s of patience, which is about a renderer's own timeout. Pinned by
   * `ProxyUpstreamTest`'s `an origin that never stops refusing gives up...`, which counts the
   * requests an origin really received rather than reading this comment.
   */
  const val MAX_ATTEMPTS: Int = 4
  const val BASE_BACKOFF_MS: Long = 500L
  const val MAX_BACKOFF_MS: Long = 8_000L

  /**
   * How long to wait before attempt [attempt] + 1, or `null` to stop.
   *
   * `null` for any status that is not 429: a 404 is not transient and retrying it wastes a
   * renderer's patience on a track that will never arrive.
   *
   * The result is clamped into `0..`[MAX_BACKOFF_MS] from **both** ends. The upper clamp is the
   * obvious one; the lower one is not decoration either -- `Retry-After: -5` is a header a peer can
   * send, and a negative delay reaches `Thread.sleep` as an `IllegalArgumentException` thrown out
   * of a fetch whose caller is guarding an `IOException`.
   */
  fun retryDelayMs(responseCode: Int, retryAfterHeader: String?, attempt: Int): Long? {
    if (responseCode != TOO_MANY_REQUESTS) return null
    if (attempt > MAX_ATTEMPTS) return null
    // The server's own number wins where the two disagree -- it knows how loaded it is and this
    // client does not. The HTTP-date form is not parsed: parsing it needs a clock, for a header
    // Navidrome has never been observed to send, and falling through to the backoff is a correct
    // answer rather than an oversight.
    retryAfterHeader?.toLongOrNull()?.let { return (it * MILLIS_PER_SECOND).coerceIn(0L, MAX_BACKOFF_MS) }
    return (BASE_BACKOFF_MS shl (attempt - 1)).coerceIn(0L, MAX_BACKOFF_MS)
  }

  private const val MILLIS_PER_SECOND = 1_000L
}

/**
 * Where the proxy gets the bytes.
 *
 * An interface with exactly two operations, so the server's status and header logic is testable
 * without a network -- and so that the one implementation that *does* use the network is small
 * enough to read.
 */
interface ProxyUpstream {

  /**
   * The resource's total length, or `null` when the origin will not say.
   *
   * `null` is a real answer rather than a failure: a live Navidrome transcode declares no length
   * at all (spec section 4), and [MediaProxyServer] turns that into a 502 rather than into a 200
   * a renderer cannot tell the end of.
   *
   * @throws UpstreamThrottledException when the origin answered 429 until this client gave up.
   */
  fun totalLength(url: String): Long?

  /** Exactly the bytes in [range]. The caller closes the stream. */
  fun open(url: String, range: ByteRange): InputStream
}

/**
 * Navidrome, over **OkHttp**, and this is the one place in `:core:cast` where OkHttp belongs.
 *
 * The split is deliberate and the reasoning is on `LocalNetworkOnly`: traffic to a **renderer** is
 * plain HTTP on the LAN and goes through this module's own socket client, which enforces the
 * private-address rule in code; traffic to **Navidrome** is HTTPS to a real origin with redirects,
 * TLS and a 429 policy, and it goes through the library the rest of the project already uses --
 * still subject to the platform's network security policy, which is exactly where it should be.
 * `ConventionTest`'s `only the cast module's proxy package may reach for OkHttp` is what keeps
 * that split from being a comment.
 *
 * The length is learned with a **one-byte range probe** rather than a `HEAD`. Spec section 4
 * verified against a real container that `format=raw` honours RFC 7233 and always sends
 * `Content-Length`, so a `bytes=0-0` request is guaranteed to come back `206` with
 * `Content-Range: bytes 0-0/N`. Whether `/rest/stream` answers a `HEAD` is a separate question
 * `LiveNavidromeProxyTest` measures and pins -- and the answer, measured against
 * `deluan/navidrome:0.63.2`, is *yes, with an accurate `Content-Length`, for `format=raw`*. The
 * probe stays anyway: it costs one byte, it is right for both answers, and on a **live transcode**
 * -- where a `HEAD` returns 200 with no length at all -- it gives the same `null` by a route that
 * does not depend on Navidrome continuing to implement `HEAD`.
 *
 * @param sleep how the backoff waits. A seam, so `ProxyUpstreamTest` can observe the delays this
 *   class actually asks for -- four attempts of real backoff is eight seconds of a unit suite, and
 *   a test that sleeps them is a test nobody runs. Production passes `Thread::sleep`.
 */
class OkHttpProxyUpstream(
  private val client: OkHttpClient,
  private val sleep: (Long) -> Unit = Thread::sleep,
) : ProxyUpstream {

  override fun totalLength(url: String): Long? =
    fetch(url, "bytes=0-0").use { response ->
      // A live transcode answers 200 with no Content-Range at all, and `null` is the correct answer
      // there: it is not seekable, it is not length-declared, and this server must not pretend
      // otherwise. Written as an early return rather than as a second `?.` in the chain below,
      // because `substringAfterLast` cannot return null and a safe call on it is a branch no test
      // could ever reach -- the silent kind of unreachable arm a coverage floor then has to be
      // lowered for.
      val contentRange = response.header("Content-Range") ?: return@use null
      // `bytes 0-0/12345` -> 12345.
      contentRange.substringAfterLast('/').toLongOrNull()
    }

  override fun open(url: String, range: ByteRange): InputStream {
    val response = fetch(url, "bytes=${range.firstByte}-${range.lastByte}")
    // The response is NOT closed here: the caller streams the body and closes it. Closing the
    // stream closes the response, which is what returns the connection -- so the wrapper below is
    // the whole reason this returns a stream rather than a `Response` the caller must remember to
    // release as well.
    return object : FilterInputStream(response.body.byteStream()) {
      override fun close() {
        super.close()
        response.close()
      }
    }
  }

  /**
   * One ranged GET, retried while Navidrome says 429. The caller closes the response.
   *
   * The loop is here rather than in an OkHttp `Interceptor` because the decision to give up has to
   * become an [UpstreamThrottledException] carrying the server's own `Retry-After`, which the
   * proxy passes on to the renderer -- an interceptor would have to throw the same thing anyway,
   * from further away from the header it read.
   */
  private fun fetch(url: String, range: String): Response {
    var attempt = 1
    while (true) {
      val request = Request.Builder().url(url).header("Range", range).build()
      val response = client.newCall(request).execute()
      if (response.code != ProxyRetry.TOO_MANY_REQUESTS) return response
      val retryAfter = response.header("Retry-After")
      val delayMs = ProxyRetry.retryDelayMs(response.code, retryAfter, attempt)
      response.close()
      if (delayMs == null) throw UpstreamThrottledException(retryAfter?.toLongOrNull())
      sleep(delayMs)
      attempt += 1
    }
  }
}
