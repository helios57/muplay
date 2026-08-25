package app.muplay.media

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Call

/**
 * The HTTP half of every byte this app plays.
 *
 * OkHttp rather than `DefaultHttpDataSource`, for two concrete reasons. A Navidrome behind a
 * reverse proxy commonly redirects `http` to `https`, and `DefaultHttpDataSource` refuses a
 * cross-protocol redirect unless told otherwise — a refusal that presents as a dead track.
 * And this project already has exactly one HTTP implementation; a second would mean two TLS
 * configurations and two proxy behaviours to reason about.
 *
 * The `Call.Factory` is injected rather than built here so that the client's timeout policy is
 * declared in one place (`MediaModule`) and so an instrumented test can point the same factory at
 * a `MockWebServer`.
 */
@Singleton
class MuPlayDataSourceFactory @Inject constructor(private val callFactory: Call.Factory) {

  /**
   * A fresh `DataSource.Factory`. Not cached: Media3 factories are cheap, and Task 3 wraps this
   * one in a cache-backed factory whose lifetime is different from this object's.
   */
  fun create(): DataSource.Factory =
    OkHttpDataSource.Factory(callFactory).setUserAgent(USER_AGENT)

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
