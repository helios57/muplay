package app.muplay.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
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
   */
  fun create(): DataSource.Factory =
    CacheDataSource.Factory()
      .setCache(cache)
      .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(callFactory).setUserAgent(USER_AGENT))
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
