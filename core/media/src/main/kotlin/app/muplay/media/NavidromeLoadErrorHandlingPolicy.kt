package app.muplay.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
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
 * **Where this has to be attached, or it does nothing.** On the `MediaSource.Factory`, inside
 * [MuPlayerFactory] -- the only place in this project that builds a player at all, and
 * `PlayerConstructionTest` is what keeps it the only place. Read that function rather than a
 * copy of it here: a snippet in a doc comment is a second version of the wiring that nothing
 * checks, and a wiring that nothing checks is this class's entire failure mode.
 *
 * `ExoPlayer.Builder` has no `setLoadErrorHandlingPolicy` of its own in Media3 1.11.0 — checked
 * against the resolved artifact — so there is no compile error waiting for anyone who forgets;
 * the player simply keeps `DefaultLoadErrorHandlingPolicy`'s three retries inside five seconds,
 * every test of *this class* stays green, and the 429 handling this module exists for is absent
 * from the running app. `ProgressiveMediaPeriod` reads the policy off the `MediaSource` that
 * created it, which is why the factory is the only place that counts.
 *
 * **The one test that goes red when it is not attached is
 * `MuPlayDataSourceFactoryTest.aRefusalBudgetThatRunsOutSurfacesAsAPlayerError`**, because it
 * counts requests, and the six it asserts (`MAX_RETRIES + 1`) is a number Media3's own
 * three-retry budget cannot reach at all. Do **not** reach for
 * `twoRefusalsWithHttp429DoNotKillThePlayback`, which is what this paragraph named until Task 2's
 * review measured it: it enqueues two refusals carrying `Retry-After: 0` and asserts three
 * requests, and Media3's *own* policy retries an `InvalidResponseCodeException` after
 * `min((errorCount - 1) * 1000, 5000)` ms -- 0, then 1000 -- so playback starts on the third
 * request and that test is green whether or not this class was ever wired in. A wiring test is a
 * wiring test only if the number it asserts is one `DefaultLoadErrorHandlingPolicy` cannot
 * produce on its own.
 *
 * `@Singleton` on the class with an `@Inject` constructor, rather than a `@Provides` in
 * `MediaModule`: a provider method whose whole body is a no-argument constructor call is the
 * boilerplate constructor injection exists to delete, and declaring both would leave one of the
 * two bindings dead in the graph with nothing to say which one Dagger actually used. The scope is
 * the part that matters and it is stated here — one policy instance per process, shared by every
 * player the media layer builds.
 */
@Singleton
// `androidx.annotation.OptIn`, not `kotlin.OptIn`: Media3's `@UnstableApi` is a *Java* annotation
// marked with `androidx.annotation.RequiresOptIn`, which the Kotlin compiler does not enforce at
// all -- Android Lint's `UnsafeOptInUsageError` does, and `check` runs lint, so this file compiled
// cleanly and failed the build one task later. Opting in here rather than marking this class
// `@UnstableApi` itself: that would propagate the requirement to every consumer, and the point of
// this module is that `androidx.media3.exoplayer` stops at its boundary.
//
// What is being opted into is real and worth stating: `DefaultLoadErrorHandlingPolicy`,
// `LoadErrorInfo` and `InvalidResponseCodeException` are all Media3 APIs that can change shape in
// a minor release. The mitigation is the module's own split -- `StreamRetryPolicy` holds the
// decision and names no Media3 type, so a signature change here is a repair to an adapter rather
// than to the 429 policy.
@OptIn(UnstableApi::class)
class NavidromeLoadErrorHandlingPolicy @Inject constructor() :
  DefaultLoadErrorHandlingPolicy(StreamRetryPolicy.MAX_RETRIES) {

  /**
   * How long Media3 should wait before trying this load again, or `C.TIME_UNSET` to give up now.
   *
   * `C.TIME_UNSET` is Media3's own "do not retry" sentinel, not a value invented here:
   * `ProgressiveMediaPeriod.onLoadError` compares what this returns against it and answers
   * `Loader.DONT_RETRY_FATAL` when they match -- read out of the resolved 1.11.0 bytecode rather
   * than assumed. `super` returns it for the cases this class does not touch.
   *
   * Two things happen below, and only the first is what this class exists for.
   *
   * **A 429 is answered by [StreamRetryPolicy].** Left to Media3 a refused track is retried three
   * times inside five seconds and then fails; here it is retried five times, and with no
   * `Retry-After` to obey that is 1 + 2 + 4 + 8 + 16 = 31 s of backoff over six requests before
   * the give-up path runs. That is the number
   * `MuPlayDataSourceFactoryTest.aRefusalBudgetThatRunsOutSurfacesAsAPlayerError` waits for.
   *
   * **Every other response code is handed back Media3's own budget.**
   * `getMinimumLoadableRetryCount` takes a data type and never sees the exception, so the five
   * retries the 429 path needs are necessarily raised for *every* retriable error. Nothing else
   * stops a 404: Media3 gives up early only on `ParserException`, `FileNotFoundException`,
   * `CleartextNotPermittedException`, `Loader.UnexpectedLoaderException` and
   * `DataSourceException.reason == 2008` (bytecode again), and an `InvalidResponseCodeException`
   * is none of them. Unchecked, a dead track costs 0 + 1 + 2 + 3 + 4 = 10 s across an attempt
   * budget of six, where an unmodified `DefaultLoadErrorHandlingPolicy` takes 3 s across four.
   * Giving up once `errorCount` passes `DEFAULT_MIN_LOADABLE_RETRY_COUNT` reproduces both of those
   * numbers exactly, which is why the threshold is Media3's own constant and not a literal `3`.
   *
   * That give-up is deliberately narrow -- an `InvalidResponseCodeException` [StreamRetryPolicy]
   * declined, and nothing else. A server that answered with a status will answer with the same
   * status again; a socket reset halfway through a track will not, so transport failures keep the
   * wider budget this policy asks for.
   */
  override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
    val exception = loadErrorInfo.exception
    if (exception is HttpDataSource.InvalidResponseCodeException) {
      val delay = StreamRetryPolicy.retryDelayMs(
        responseCode = exception.responseCode,
        retryAfterHeader = exception.headerFields.firstValueIgnoringCase(RETRY_AFTER),
        errorCount = loadErrorInfo.errorCount,
      )
      if (delay != null) return delay
      // Declined, so this is not a 429 and the raised budget above is not for it: give up where
      // an unmodified `DefaultLoadErrorHandlingPolicy` would have.
      val media3Budget = DefaultLoadErrorHandlingPolicy.DEFAULT_MIN_LOADABLE_RETRY_COUNT
      if (loadErrorInfo.errorCount > media3Budget) return C.TIME_UNSET
    }
    return super.getRetryDelayMsFor(loadErrorInfo)
  }

  /**
   * The first value held under [name], compared without regard to case.
   *
   * Deliberately not `get(name)`. `InvalidResponseCodeException.headerFields` is whatever the
   * `HttpDataSource` that threw handed it, and the two in Media3 hand over different shapes:
   * `OkHttpDataSource` -- the one this module uses -- passes `okhttp3.Headers.toMultimap()`, whose
   * keys are **lowercased**, and `DefaultHttpDataSource` passes
   * `HttpURLConnection.getHeaderFields()`, which additionally carries a `null` key for the status
   * line. An exact-case `get("Retry-After")` finds anything at all in the first of those only
   * because `toMultimap()` happens to build its `TreeMap` with `String.CASE_INSENSITIVE_ORDER` --
   * a detail of a third-party class, invisible from here, and the whole 429 policy silently
   * degrades to plain backoff on the day it changes. Field names are case-insensitive by RFC 9110
   * section 5.1, so this compares them that way itself. `String?.equals(other, ignoreCase = true)`
   * is the null-safe overload, which is what makes the `null` key above harmless.
   */
  private fun Map<String, List<String>>.firstValueIgnoringCase(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

  private companion object {
    /**
     * RFC 9110 section 10.2.3. Spelled in its canonical case for readers, matched in any case by
     * [firstValueIgnoringCase].
     */
    const val RETRY_AFTER = "Retry-After"
  }
}
