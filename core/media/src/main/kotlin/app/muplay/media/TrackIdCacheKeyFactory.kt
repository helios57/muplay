package app.muplay.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * Thrown when a [DataSpec] reaches the cache with no custom cache key.
 *
 * A distinct type rather than a bare `IllegalStateException` so a test can assert on it and a
 * reader can find every throw site by its name.
 *
 * [trackId] and **never a URL**. This exception is thrown inside `CacheDataSource.open`, which
 * runs inside `Loader$LoadTask.run`; that method logs it (`Log.e(TAG, "Unexpected exception
 * loading stream", e)`) and then wraps it into an `ExoPlaybackException` that
 * `ExoPlayerImplInternal` logs again. Message and cause chain therefore reach logcat, bug reports
 * and any crash reporter that is ever attached. This client's stream URLs carry `u`, `s` (the
 * salt) and `t` (`md5(password + salt)`), which Navidrome accepts forever and for any request, so
 * a URL in this message is a replayable password-equivalent in a log. `MediaModuleTest`'s
 * "nothing logs on the client that carries the credentials" exists to keep exactly that out of
 * logs, and an exception message is a way around it. The diagnostic value here is *which track*,
 * not *which salt* -- see [TrackIdCacheKeyFactory.trackIdIn].
 */
class MissingCacheKeyException(trackId: String) : IllegalStateException(
  "A media request reached the cache with no custom cache key (track $trackId). Every MediaItem " +
    "this app plays must set setCustomCacheKey(song.id) -- see MediaItems.kt. Media3's default " +
    "factory would fall back to the URI here, and this client's stream URLs carry a fresh auth " +
    "salt on every call, so a URL-derived key produces a cache that is written, never read, and " +
    "grows forever. That is the defect Tempo ships and spec section 4 names.",
)

/**
 * The cache key is the **track id**, and nothing else.
 *
 * Media3's `CacheKeyFactory.DEFAULT` returns `dataSpec.key` when present and the URI otherwise.
 * The fallback is removed here on purpose: a missing key is a programming error that this project
 * would otherwise discover as an unexplained 0% hit rate, months later, on a device.
 *
 * `androidx.annotation.OptIn`, not `kotlin.OptIn`, for the reason
 * [NavidromeLoadErrorHandlingPolicy] states at length: Media3's `@UnstableApi` is a Java
 * annotation marked with `androidx.annotation.RequiresOptIn`, which the Kotlin compiler does not
 * enforce at all. Android Lint's `UnsafeOptInUsageError` does, and `check` runs lint, so a file
 * missing this compiles cleanly and reddens the build a task later.
 */
@OptIn(UnstableApi::class)
object TrackIdCacheKeyFactory : CacheKeyFactory {
  override fun buildCacheKey(dataSpec: DataSpec): String =
    dataSpec.key ?: throw MissingCacheKeyException(trackIdIn(dataSpec.uri.toString()))

  /**
   * The `id` query parameter of [uri], or [UNKNOWN_TRACK] when there is none.
   *
   * The one place this module reduces a credential-bearing stream URL to something that may be
   * written down. It takes and returns a `String` and names no Android and no Media3 type, which
   * is deliberate and is the same split `StreamRetryPolicy` has against its Media3 adapter: the
   * decision "what of a stream URL is safe to say out loud" is then reachable from the fast JVM
   * tier and from `ci/mutation-probes.sh`, rather than only from a device.
   *
   * Hand-parsed rather than routed through `android.net.Uri.getQueryParameter` (Android, so
   * device-only) or OkHttp's `HttpUrl` (throws on a URL it cannot parse, which is the worst
   * possible behaviour for a diagnostic path that is already handling an error). The fragment is
   * dropped first so an `id` value can never carry one, and a parameter is matched on its whole
   * name, so `t=...` and a hypothetical `xid=...` cannot be mistaken for it.
   */
  internal fun trackIdIn(uri: String): String =
    uri.substringBefore('#')
      .substringAfter('?', "")
      .split('&')
      .firstOrNull { it.startsWith("$ID_PARAMETER=") }
      ?.substringAfter('=')
      ?.takeIf { it.isNotEmpty() }
      ?: UNKNOWN_TRACK

  /** Subsonic's track identifier parameter -- `/rest/stream?id=<song id>`. */
  private const val ID_PARAMETER = "id"

  /**
   * What [trackIdIn] says when a URL carries no `id`. A literal rather than the URL it could not
   * read: "the URL was shaped unexpectedly" is not a good enough reason to print a password
   * equivalent, and the exception's type and message already say what went wrong.
   */
  internal const val UNKNOWN_TRACK = "unknown"
}
