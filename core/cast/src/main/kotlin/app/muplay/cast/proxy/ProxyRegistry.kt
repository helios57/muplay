package app.muplay.cast.proxy

import app.muplay.cast.didl.ServedMedia
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * One thing the proxy is currently willing to serve, whatever kind of thing it is.
 *
 * [upstreamUrl] is a Navidrome URL and therefore **carries credentials**: it holds the Subsonic
 * `u`, `t` and `s` parameters. It must never reach a log line, a failure message or a fixture --
 * which is why [ProxyRequest] records the [token] and never this, and why nothing in this package
 * puts a URL into an exception message.
 *
 * Two implementations, and the difference between them is not cosmetic -- it decides how the bytes
 * are fetched. See [PublishedArtwork].
 */
sealed interface Published {
  val token: String
  val path: String
  val upstreamUrl: String
}

/** One **track** the proxy is willing to serve: length-declared, seekable, streamed. */
data class PublishedMedia(
  override val token: String,
  override val path: String,
  override val upstreamUrl: String,
  val served: ServedMedia,
) : Published

/**
 * One **cover image** the proxy is willing to serve.
 *
 * It exists as its own type because Navidrome's `/rest/getCoverArt` behaves nothing like
 * `/rest/stream`, and that was measured rather than assumed. Against `deluan/navidrome:0.63.2`,
 * `getCoverArt` answers a plain `200` with **`Transfer-Encoding: chunked` and no
 * `Content-Length`**, and it **ignores `Range` entirely** -- `bytes=0-0` and `bytes=10-20` both
 * return the whole image with a `200`, not a `206`.
 *
 * So the one-byte range probe [ProxyUpstream.totalLength] uses to learn a track's length finds no
 * `Content-Range` here and correctly answers `null`, which [MediaProxyServer] turns into a
 * **502** -- the right answer for a track nobody can tell the end of, and the wrong answer for a
 * cover image. Routing artwork down the streaming path would therefore have produced a renderer
 * that is handed a URL and gets a gateway error from it, every time, silently.
 *
 * The image is read whole into memory instead ([ProxyUpstream.readFully]) and served with an
 * accurate `Content-Length`. That is affordable precisely because it is artwork:
 * `QueueRepository.ARTWORK_SIZE_PX` asks for a 512 px edge and the seeded fixtures come back around
 * 70 KB, against 30-40 MB for a FLAC track -- which is why the same treatment would be wrong one
 * type up. [MediaProxyServer.MAX_ARTWORK_BYTES] is the bound that keeps "affordable" true against
 * an origin that disagrees.
 *
 * No [ServedMedia]: that type is the three-way agreement between a `<res>` URL's extension, a DIDL
 * `protocolInfo` and a `Content-Type`, and **artwork has no `protocolInfo` leg** --
 * `<upnp:albumArtURI>` is a bare URL. Its content type is whatever the origin says it is, taken
 * from the response at the moment the bytes are fetched, which is one statement of the format
 * rather than a second guess at it. (Navidrome answers `image/webp` for a sized request, whatever
 * the source file was; a fixed `.jpg` in the path would have been a lie a renderer might act on.)
 */
data class PublishedArtwork(
  override val token: String,
  override val path: String,
  override val upstreamUrl: String,
) : Published

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

  private val published = ConcurrentHashMap<String, Published>()

  fun publish(upstreamUrl: String, served: ServedMedia): PublishedMedia {
    val token = mintToken()
    return PublishedMedia(
      token = token,
      path = PATH_PREFIX + served.fileName(token),
      upstreamUrl = upstreamUrl,
      served = served,
    ).also { published[token] = it }
  }

  /**
   * Publishes one **cover image**, so a renderer can be shown artwork without being shown a
   * credential.
   *
   * A separate token from the track's, deliberately: the two are fetched independently, a renderer
   * that fetches only the artwork has proved nothing about its ability to play (see
   * [MediaProxyServer.awaitRequest], which counts a fetch of *this route's own token*), and the
   * artwork has to be revocable on its own when a route falls back to renderer-direct.
   *
   * The path carries **no extension**, and that is the difference from [publish] rather than an
   * omission. `ServedMedia`'s extension exists because spec section 6 records that Sonos infers a
   * *media* MIME type from the `<res>` URL; `<upnp:albumArtURI>` carries no `protocolInfo` for an
   * extension to have to agree with, and Navidrome re-encodes a sized cover to WebP whatever the
   * source was -- so any extension minted here would be a guess this app cannot make, written into
   * the one place a renderer might trust it.
   */
  fun publishArtwork(upstreamUrl: String): PublishedArtwork {
    val token = mintToken()
    return PublishedArtwork(
      token = token,
      path = ART_PATH_PREFIX + token,
      upstreamUrl = upstreamUrl,
    ).also { published[token] = it }
  }

  private fun mintToken(): String =
    ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

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
  fun resolve(path: String): Published? =
    published.values.firstOrNull { it.path == path }

  fun revoke(token: String) {
    published.remove(token)
  }

  fun revokeAll() = published.clear()

  companion object {
    /** 128 bits. Not an identity; a capability. */
    const val TOKEN_BYTES: Int = 16
    const val PATH_PREFIX: String = "/media/"

    /**
     * Where a published cover image lives. Distinct from [PATH_PREFIX] so a request's *shape*
     * already says which kind of thing it is asking for, and so the two namespaces cannot collide
     * however the token minting later changes.
     */
    const val ART_PATH_PREFIX: String = "/art/"
  }
}
