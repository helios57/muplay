package app.muplay.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.muplay.media.di.MediaHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Call

/**
 * Every byte this app plays, and where it comes from when it is not already on disk.
 *
 * OkHttp rather than `DefaultHttpDataSource`, for two concrete reasons. A Navidrome behind a
 * reverse proxy commonly redirects `http` to `https`, and `DefaultHttpDataSource` refuses a
 * cross-protocol redirect unless told otherwise — a refusal that presents as a dead track.
 * And this project already has exactly one HTTP implementation; a second would mean two TLS
 * configurations and two proxy behaviours to reason about.
 *
 * The `Call.Factory` is injected rather than built here so that the client's timeout policy is
 * declared in one place (`MediaModule`) and so an instrumented test can point the same factory at
 * a `MockWebServer`. The [Cache] is injected for a stronger reason still: `SimpleCache` throws if
 * a second live instance is built on a directory another instance holds, so the one instance has
 * to come from the graph — see [MediaCache].
 *
 * `androidx.annotation.OptIn`, not `kotlin.OptIn` — see [TrackIdCacheKeyFactory] and
 * [NavidromeLoadErrorHandlingPolicy] for why the Kotlin compiler never enforces Media3's
 * `@UnstableApi` and Android Lint does.
 */
@Singleton
@OptIn(UnstableApi::class)
class MuPlayDataSourceFactory @Inject constructor(
  @MediaHttpClient private val callFactory: Call.Factory,
  private val cache: Cache,
) {

  /**
   * A cache-backed `DataSource.Factory`: read from [cache] where possible, fall through to HTTP
   * otherwise, and write what HTTP returns back into the cache.
   *
   * A **fresh** factory every call, deliberately not memoised. Media3 factories are cheap, a
   * `DataSource.Factory` is consumed by exactly one `MediaSource` graph, and the shared, expensive
   * state — the [cache] itself and the `Call.Factory`'s connection pool — is shared by being
   * injected rather than by caching the wrapper around it.
   *
   * `FLAG_IGNORE_CACHE_ON_ERROR` is set so that a corrupt or unreadable cache entry degrades to a
   * network read rather than failing the track. The failure mode being avoided is a cache that,
   * once damaged, permanently breaks one specific song with no way for a user to tell why.
   *
   * The upstream source is wrapped in [RequestedUriDataSource], which is a **security** measure
   * and not a tidy-up — see that class.
   */
  fun create(): DataSource.Factory =
    CacheDataSource.Factory()
      .setCache(cache)
      .setUpstreamDataSourceFactory(
        RequestedUriDataSource.Factory(
          OkHttpDataSource.Factory(callFactory).setUserAgent(USER_AGENT),
        ),
      )
      .setCacheKeyFactory(TrackIdCacheKeyFactory)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

  companion object {
    /**
     * Sent as `User-Agent`. Navidrome identifies clients by the `c` query parameter, not by this,
     * so nothing behavioural hangs on it — but a server log that says which client issued a
     * request is worth the one line, and an absent `User-Agent` is the kind of thing a proxy
     * decides to reject.
     */
    const val USER_AGENT: String = "MuPlay"
  }
}

/**
 * An upstream source that reports the URI it was **asked** for, never the one a redirect landed on.
 *
 * This is a security measure, and the chain it breaks is short. `CacheDataSource.openNextSource`,
 * having opened the upstream, reads `upstream.getUri()` into `actualUri`; if that differs from the
 * `DataSpec`'s own URI it calls `ContentMetadataMutations.setRedirectedUri(..)` and hands the
 * result to `cache.applyContentMetadataMutations(key, ..)`, which persists it under `exo_redir`.
 * `OkHttpDataSource.getUri()` returns `response.request().url()` -- the **final** URL, after
 * redirects. `MediaCache` builds its `SimpleCache` with a `StandaloneDatabaseProvider`, so that
 * index is `exoplayer_internal.db` in `/data/data/app.muplay/databases/` -- plaintext SQLite,
 * **outside `cacheDir`**, surviving OS cache reclamation, a cache clear and a logout alike.
 *
 * The URL that would land there carries `u`, `s` (the salt) and `t` (`md5(password + salt)`).
 * Navidrome tracks no salt nonce, so that triple is a replayable, non-expiring password
 * equivalent. `CredentialStore` goes to the trouble of sealing the password with an
 * AndroidKeystore AES-GCM key so that only ciphertext ever reaches DataStore; writing a password
 * equivalent to plaintext SQLite in the same sandbox undoes that.
 *
 * No second bug is needed to trigger it: a Navidrome behind a reverse proxy that redirects `http`
 * to `https` is the exact deployment [MuPlayDataSourceFactory]'s own note gives as the reason for
 * choosing OkHttp, and `MediaModuleTest`'s "redirects are followed, including across protocols"
 * pins as supported.
 *
 * Nothing is lost by suppressing it. `exo_redir` is read back by exactly one method,
 * `CacheDataSource.getRedirectedUriOrDefault`, whose result is assigned to `actualUri` and used
 * only to answer `CacheDataSource.getUri()` -- read out of the 1.11.0 bytecode, not assumed. Every
 * upstream request is built from the caller's own `DataSpec.uri`, redirect or no redirect. If
 * anything, dropping it is the safer behaviour twice over: a remembered redirect target would
 * carry a *stale* salt and token into a later session's request.
 *
 * `HttpDataSource` rather than the plain `DataSource` this needs, so that the wrapper is a
 * substitute for what it wraps rather than a narrowing of it -- Media3 ships no
 * `ForwardingDataSource` (checked in 1.11.0), and an upstream that quietly stopped being an
 * `HttpDataSource` is the kind of thing that surfaces as a `ClassCastException` in a Media3
 * component nobody was thinking about.
 */
@OptIn(UnstableApi::class)
class RequestedUriDataSource(private val delegate: HttpDataSource) : HttpDataSource {

  /**
   * Set **before** the delegate is opened, deliberately. If `open` throws after OkHttp has already
   * followed a redirect, the delegate's own `getUri()` already answers with the redirect target;
   * setting this first means there is no window in which this source reports one.
   */
  private var requestedUri: Uri? = null

  override fun addTransferListener(transferListener: TransferListener) =
    delegate.addTransferListener(transferListener)

  override fun open(dataSpec: DataSpec): Long {
    requestedUri = dataSpec.uri
    return delegate.open(dataSpec)
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
    delegate.read(buffer, offset, length)

  /**
   * The requested URI, full stop — never the delegate's, not even as a fallback. `DataSource`
   * specifies this as null while the source is not open, which is exactly the answer wanted before
   * [open] and after [close]; delegating in those windows would be a leak with no live request
   * behind it, and it would make the guarantee conditional on state rather than absolute.
   */
  override fun getUri(): Uri? = requestedUri

  override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

  override fun getResponseCode(): Int = delegate.responseCode

  override fun setRequestProperty(name: String, value: String) =
    delegate.setRequestProperty(name, value)

  override fun clearRequestProperty(name: String) = delegate.clearRequestProperty(name)

  override fun clearAllRequestProperties() = delegate.clearAllRequestProperties()

  override fun close() {
    try {
      delegate.close()
    } finally {
      // `DataSource.getUri()` is specified to answer null while the source is not open, and a
      // closed source that still names a URI is exactly the kind of leftover a later read of
      // `getUri()` would report as fact.
      requestedUri = null
    }
  }

  /** Wraps every source [delegate] builds. */
  class Factory(private val delegate: HttpDataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource =
      RequestedUriDataSource(delegate.createDataSource())
  }
}
