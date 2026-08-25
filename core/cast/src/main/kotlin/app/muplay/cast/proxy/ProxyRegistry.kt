package app.muplay.cast.proxy

import app.muplay.cast.didl.ServedMedia
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * One item the proxy is currently willing to serve.
 *
 * [upstreamUrl] is a Navidrome stream URL and therefore **carries credentials**: it holds the
 * Subsonic `u`, `t` and `s` parameters. It must never reach a log line, a failure message or a
 * fixture -- which is why [ProxyRequest] records the [token] and never this, and why nothing in
 * this package puts a URL into an exception message.
 */
data class PublishedMedia(
  val token: String,
  val path: String,
  val upstreamUrl: String,
  val served: ServedMedia,
)

/**
 * What the proxy will serve, and under what path.
 *
 * The path is `/media/<token>.<extension>`, where the token is 128 random bits and **not** the
 * track id. Two reasons, both real:
 *
 * - The proxy binds on the LAN. A path containing a track id makes it an **open relay for the
 *   whole library** to anything on that network, and Navidrome ids are guessable. A token
 *   published only for the current session, and revoked with it, means the proxy serves exactly
 *   what the user chose to cast.
 * - A stable id in a path lets anything watching the LAN correlate what is being listened to
 *   across sessions.
 *
 * The extension is **not** decoration: spec section 6 records that Sonos infers MIME from the URL,
 * and Task 3's renderer answers `714 Illegal MIME-type` to a path without one. It is minted
 * through [ServedMedia.fileName] rather than by appending a suffix of this class's own, so the
 * extension the URL carries and the extension `protocolInfo`'s MIME implies are one expression
 * evaluated once.
 */
class ProxyRegistry(private val random: SecureRandom = SecureRandom()) {

  private val published = ConcurrentHashMap<String, PublishedMedia>()

  fun publish(upstreamUrl: String, served: ServedMedia): PublishedMedia {
    val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
      .joinToString("") { "%02x".format(it) }
    return PublishedMedia(
      token = token,
      path = PATH_PREFIX + served.fileName(token),
      upstreamUrl = upstreamUrl,
      served = served,
    ).also { published[token] = it }
  }

  /**
   * The item a request path names, or `null`.
   *
   * The path is matched **whole** rather than by extracting a token from it: a
   * `substringAfterLast('/')` would happily pull a token out of `/etc/../media/<token>.mp3` and
   * out of anything else that ended the same way. Comparing against the exact published path makes
   * traversal, case games and trailing segments all resolve to nothing without a separate check
   * for each.
   *
   * A linear scan, and deliberately so at this size: a cast session publishes a queue, not a
   * library, and an index keyed by path would still have to compare the whole path at the end to
   * keep the property above. The comparison is the security control; the lookup is not the cost.
   */
  fun resolve(path: String): PublishedMedia? =
    published.values.firstOrNull { it.path == path }

  fun revoke(token: String) {
    published.remove(token)
  }

  fun revokeAll() = published.clear()

  companion object {
    /** 128 bits. Not an identity; a capability. */
    const val TOKEN_BYTES: Int = 16
    const val PATH_PREFIX: String = "/media/"
  }
}
