package app.muplay.media

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media3's own load-error policy, with one exception carved out of it: HTTP 429.
 *
 * Everything this class does that is interesting lives in [StreamRetryPolicy], which is a plain
 * object with no Media3 or Android types in its signature. This is only the adapter that reaches
 * a response code and a header out of an [HttpDataSource.InvalidResponseCodeException] and hands
 * them over — the split exists so the decision is gated by the fast tier rather than only by an
 * emulator.
 *
 * Extending `DefaultLoadErrorHandlingPolicy` rather than implementing
 * [LoadErrorHandlingPolicy] from scratch: everything that is not a 429 must keep Media3's own
 * behaviour, and 404/416/`ParserException` handling in particular is what makes seeking work.
 *
 * **Where this has to be attached, or it does nothing.** On the `MediaSource.Factory`:
 *
 * ```
 * ExoPlayer.Builder(context)
 *   .setMediaSourceFactory(
 *     DefaultMediaSourceFactory(dataSourceFactory).setLoadErrorHandlingPolicy(policy),
 *   )
 * ```
 *
 * `ExoPlayer.Builder` has no `setLoadErrorHandlingPolicy` of its own in Media3 1.11.0 — checked
 * against the resolved artifact — so there is no compile error waiting for anyone who forgets;
 * the player simply keeps `DefaultLoadErrorHandlingPolicy`'s three retries inside five seconds,
 * every test of *this class* stays green, and the 429 handling this module exists for is absent
 * from the running app. `ProgressiveMediaPeriod` reads the policy off the `MediaSource` that
 * created it, which is why the factory is the only place that counts.
 * `MuPlayDataSourceFactoryTest.twoRefusalsWithHttp429DoNotKillThePlayback` is the test that fails
 * when the wiring is wrong rather than the logic.
 *
 * `@Singleton` on the class with an `@Inject` constructor, rather than a `@Provides` in
 * `MediaModule`: a provider method whose whole body is a no-argument constructor call is the
 * boilerplate constructor injection exists to delete, and declaring both would leave one of the
 * two bindings dead in the graph with nothing to say which one Dagger actually used. The scope is
 * the part that matters and it is stated here — one policy instance per process, shared by every
 * player the media layer builds.
 */
@Singleton
class NavidromeLoadErrorHandlingPolicy @Inject constructor() :
  DefaultLoadErrorHandlingPolicy(StreamRetryPolicy.MAX_RETRIES) {

  override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
    val exception = loadErrorInfo.exception
    if (exception is HttpDataSource.InvalidResponseCodeException) {
      val retryAfter = exception.headerFields["Retry-After"]?.firstOrNull()
      val delay = StreamRetryPolicy.retryDelayMs(
        responseCode = exception.responseCode,
        retryAfterHeader = retryAfter,
        errorCount = loadErrorInfo.errorCount,
      )
      if (delay != null) return delay
    }
    return super.getRetryDelayMsFor(loadErrorInfo)
  }

  /**
   * `C.TIME_UNSET` is Media3's own "do not retry" sentinel and is referenced here only so the
   * import documents the contract this override lives inside: a non-negative return retries after
   * that many milliseconds, `C.TIME_UNSET` gives up. `super` returns it for the cases this class
   * does not touch.
   */
  private companion object {
    @Suppress("unused")
    const val DO_NOT_RETRY: Long = C.TIME_UNSET
  }
}
