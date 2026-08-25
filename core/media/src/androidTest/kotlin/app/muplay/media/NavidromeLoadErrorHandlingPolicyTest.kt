package app.muplay.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The adapter half, held to the same standard as the decision half.
 *
 * `StreamRetryPolicyTest` proves what the *decision* is. It cannot prove that
 * [NavidromeLoadErrorHandlingPolicy] actually reaches the response code, the `Retry-After` header
 * and the attempt number out of Media3's own error object and hands over **those** values — a
 * delegating method that forwards a constant, or that reads `errorCount` from the wrong place,
 * passes every assertion in that file. That is this project's recorded "argument passthrough on a
 * delegating method" defect, one layer further out, and this class is where it is closed.
 *
 * Instrumented rather than JVM because every input type here is an Android or Media3 type:
 * `DataSpec` holds an `android.net.Uri`, which is a stub that throws off-device. There is no
 * Robolectric in this project by constraint, so the device is the only honest place for this.
 *
 * Every expectation below is chosen where this policy and `DefaultLoadErrorHandlingPolicy`
 * **disagree**, because in the direction where they agree the assertion would pass with the whole
 * override deleted. Media3's own formula, read out of the resolved 1.11.0 bytecode rather than
 * assumed, is `min((errorCount - 1) * 1000, 5000)` for any retriable cause.
 *
 * One test breaks that rule on purpose and says so where it does:
 * [aResponseCodeThisPolicyDoesNotOwnGivesUpOnMedia3sOwnBudgetNotThisOnes] asserts *agreement* on
 * either side of a threshold. Those two are still discriminating -- what they discriminate against
 * is a give-up that fires too early rather than a missing override -- and without them the
 * threshold itself would be unpinned in the only direction that can break seeking.
 */
@RunWith(AndroidJUnit4::class)
class NavidromeLoadErrorHandlingPolicyTest {

  private val policy = NavidromeLoadErrorHandlingPolicy()

  @Test
  fun theResponseCodeIsReadOutOfTheExceptionAndNotAssumed() {
    // 429 is this policy's; 503 is not. One value each side of the only status it has an opinion
    // about, at the same errorCount, so a passthrough that ignored the code could not satisfy
    // both: ours says 4_000 at attempt 3, Media3's says 2_000.
    //
    // Attempt 3 rather than 4 because a non-429 response code past attempt 3 now gives up rather
    // than backing off at all -- see
    // [aResponseCodeThisPolicyDoesNotOwnGivesUpOnMedia3sOwnBudgetNotThisOnes]. Both numbers here
    // are still ones only a policy that read the code could produce.
    assertThat(policy.getRetryDelayMsFor(loadError(responseCode = 429, errorCount = 3)))
      .isEqualTo(4_000L)
    assertThat(policy.getRetryDelayMsFor(loadError(responseCode = 503, errorCount = 3)))
      .isEqualTo(2_000L)
  }

  @Test
  fun theRetryAfterHeaderIsReadOutOfTheExceptionAndNotAssumed() {
    // Two different header values at the same attempt count, and neither is a number Media3's own
    // formula or this policy's backoff would ever produce at errorCount 1 (1_000). A forwarder
    // that passed a constant, or `null`, fails both.
    assertThat(policy.getRetryDelayMsFor(loadError(429, errorCount = 1, retryAfter = "3")))
      .isEqualTo(3_000L)
    assertThat(policy.getRetryDelayMsFor(loadError(429, errorCount = 1, retryAfter = "17")))
      .isEqualTo(17_000L)
    // And the header is looked up by its own name: a policy reading some other header, or reading
    // the whole map's first entry, would pick this up as a `Retry-After` it never was.
    assertThat(policy.getRetryDelayMsFor(loadError(429, errorCount = 1, headers = mapOf("X-Not-Retry-After" to listOf("9")))))
      .isEqualTo(1_000L)
  }

  @Test
  fun theRetryAfterHeaderIsFoundInEveryKeyShapeItCanArriveIn() {
    // What production actually hands the adapter, asserted as a fact about okhttp rather than
    // trusted: `OkHttpDataSource` passes `okhttp3.Headers.toMultimap()` straight into
    // `InvalidResponseCodeException`, and that map's keys are lowercased.
    val asOkHttpBuildsIt = Headers.headersOf("Retry-After", "3").toMultimap()
    assertThat(asOkHttpBuildsIt.keys).containsExactly("retry-after")
    assertThat(policy.getRetryDelayMsFor(loadError(429, 1, headers = asOkHttpBuildsIt)))
      .isEqualTo(3_000L)

    // The same lowercase key in a plain map, where nothing can rescue an exact-case lookup.
    // `toMultimap()` returns a `TreeMap` built with `String.CASE_INSENSITIVE_ORDER`, so the
    // assertion above passes even against an adapter that looks up `"Retry-After"` exactly -- it
    // is carried by a comparator inside a third-party class, not by anything this module owns.
    // This is the case that fails there: 1_000, the attempt-1 backoff, from a header it never saw.
    assertThat(
      policy.getRetryDelayMsFor(
        loadError(429, errorCount = 1, headers = mapOf("retry-after" to listOf("3"))),
      ),
    ).isEqualTo(3_000L)

    // Canonical case in a plain map, and an all-caps spelling RFC 9110 section 5.1 permits and
    // nobody sends. With the two above, these pin the comparison as case-insensitive rather than
    // merely lowercase-tolerant: an adapter that normalised one side only -- `key == name`
    // against a lowercased name, say -- answers the first two and fails both of these.
    assertThat(
      policy.getRetryDelayMsFor(
        loadError(429, errorCount = 1, headers = mapOf("Retry-After" to listOf("11"))),
      ),
    ).isEqualTo(11_000L)
    assertThat(
      policy.getRetryDelayMsFor(
        loadError(429, errorCount = 1, headers = mapOf("RETRY-AFTER" to listOf("7"))),
      ),
    ).isEqualTo(7_000L)

    // Media3's *other* HTTP data source, `DefaultHttpDataSource`, passes
    // `HttpURLConnection.getHeaderFields()`, which carries a `null` key holding the status line.
    // This module does not build that data source today; the lookup is written to survive it
    // anyway, and an unasserted claim of null-safety is one that stops being true quietly. The
    // null goes in first because a `HashMap` puts it in bucket 0, so it is what the scan meets
    // before it ever reaches `Retry-After`.
    val withAStatusLineUnderANullKey = HashMap<String?, List<String>>()
    withAStatusLineUnderANullKey[null] = listOf("HTTP/1.1 429 Too Many Requests")
    withAStatusLineUnderANullKey["Retry-After"] = listOf("5")
    @Suppress("UNCHECKED_CAST")
    val headerFields = withAStatusLineUnderANullKey as Map<String, List<String>>
    assertThat(policy.getRetryDelayMsFor(loadError(429, errorCount = 1, headers = headerFields)))
      .isEqualTo(5_000L)
  }

  @Test
  fun theAttemptCountIsMedia3sOwnErrorCountAndNotAnInternalTally() {
    // Three attempts, three different delays, all off the same policy instance. A field this
    // class incremented itself would drift from these the moment Media3 reused the instance --
    // which it does, because the policy is a `@Singleton`.
    assertThat(policy.getRetryDelayMsFor(loadError(429, errorCount = 1))).isEqualTo(1_000L)
    assertThat(policy.getRetryDelayMsFor(loadError(429, errorCount = 3))).isEqualTo(4_000L)
    assertThat(policy.getRetryDelayMsFor(loadError(429, errorCount = 2))).isEqualTo(2_000L)
  }

  @Test
  fun everythingThatIsNotA429KeepsMedia3sOwnBehaviourIncludingItsGiveUpPath() {
    // The half of the contract that is about *not* overriding. `ParserException` is Media3's
    // "this will never succeed" case and must stay unretried; a policy that answered every error
    // with a backoff would turn a malformed container into six pointless refetches.
    val parserError = loadError(
      exception = ParserException.createForMalformedContainer("malformed", null),
      errorCount = 1,
    )
    assertThat(policy.getRetryDelayMsFor(parserError)).isEqualTo(C.TIME_UNSET)

    // A plain IOException that is not an InvalidResponseCodeException at all: no response code to
    // read, so the whole 429 branch must be skipped rather than defaulted into.
    assertThat(policy.getRetryDelayMsFor(loadError(exception = IOException("reset"), errorCount = 5)))
      .isEqualTo(4_000L)
  }

  @Test
  fun aResponseCodeThisPolicyDoesNotOwnGivesUpOnMedia3sOwnBudgetNotThisOnes() {
    // The bill for [theRetryBudgetMedia3EnforcesIsThisPolicysMaxRetries]. `MAX_RETRIES` is raised
    // through the constructor, and `getMinimumLoadableRetryCount` takes a data type and never sees
    // the exception -- so the five retries the 429 path needs are raised for *every* retriable
    // error. A 404 is retriable as far as Media3 is concerned: `isNonRetriableException` covers
    // `ParserException`, `FileNotFoundException`, `CleartextNotPermittedException`,
    // `Loader$UnexpectedLoaderException` and `DataSourceException.reason == 2008`, and an
    // `InvalidResponseCodeException` is none of them. Unchecked, a dead track costs 0+1+2+3+4 =
    // 10 s over six requests where Media3 alone takes 3 s over four.
    //
    // Below the threshold, agreement with Media3 -- deliberately, and it is the only place in this
    // file that asserts agreement. Media3's own `min((errorCount - 1) * 1000, 5000)`, unchanged.
    // A give-up that crept earlier would take the 404 retry that makes a failed range request
    // recoverable with it, and only these two assertions would notice.
    assertThat(policy.getRetryDelayMsFor(loadError(404, errorCount = 1))).isEqualTo(0L)
    assertThat(policy.getRetryDelayMsFor(loadError(404, errorCount = 3))).isEqualTo(2_000L)

    // Past it, `C.TIME_UNSET`, which `ProgressiveMediaPeriod.onLoadError` turns into
    // `Loader.DONT_RETRY_FATAL` -- four requests in total, which is Media3's own budget handed
    // back. Two statuses, because one of them could be a special case somewhere upstream and both
    // being 404 would not show it.
    assertThat(policy.getRetryDelayMsFor(loadError(404, errorCount = 4))).isEqualTo(C.TIME_UNSET)
    assertThat(policy.getRetryDelayMsFor(loadError(500, errorCount = 4))).isEqualTo(C.TIME_UNSET)

    // And the status this policy *does* own keeps the wider budget, at the same attempt numbers
    // the two above give up at. A give-up written one branch too high -- before the 429 check
    // rather than after it -- passes everything above and fails here.
    assertThat(policy.getRetryDelayMsFor(loadError(429, errorCount = 4))).isEqualTo(8_000L)
    assertThat(policy.getRetryDelayMsFor(loadError(429, errorCount = 5))).isEqualTo(16_000L)
  }

  @Test
  fun theRetryBudgetMedia3EnforcesIsThisPolicysMaxRetries() {
    // `MAX_RETRIES` being 5 is asserted in the JVM tier; that it is *applied* -- that Media3's own
    // `Loader` gives up after five rather than after its default three -- is a different
    // statement, at a different layer, and only this one can fail when the constructor argument
    // is dropped. Asserted against the default it must not equal, not just against 5.
    assertThat(DefaultLoadErrorHandlingPolicy().getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
      .describedAs("Media3's own default, the value this policy must not be left at")
      .isEqualTo(3)
    assertThat(policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
      .isEqualTo(StreamRetryPolicy.MAX_RETRIES)
    assertThat(policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
      .isEqualTo(StreamRetryPolicy.MAX_RETRIES)
  }

  private fun loadError(
    responseCode: Int,
    errorCount: Int,
    retryAfter: String? = null,
    // Through okhttp's own `toMultimap()`, not `mapOf("Retry-After" to ...)`. That hand-built map
    // is a `LinkedHashMap` keyed exactly as written, and production never sees one:
    // `OkHttpDataSource` fills `headerFields` from `Headers.toMultimap()`, whose keys are
    // lowercased. Every header assertion in this file ran against the hand-built shape for a whole
    // task and so was structurally incapable of observing the shape the adapter actually meets --
    // which is how an exact-case `get("Retry-After")` in the adapter went unnoticed. Overridable
    // per test, and [theRetryAfterHeaderIsFoundInEveryKeyShapeItCanArriveIn] overrides it.
    headers: Map<String, List<String>> =
      retryAfter?.let { Headers.headersOf("Retry-After", it).toMultimap() }.orEmpty(),
  ): LoadErrorHandlingPolicy.LoadErrorInfo = loadError(
    exception = HttpDataSource.InvalidResponseCodeException(
      responseCode,
      /* responseMessage = */ null,
      /* cause = */ null,
      headers,
      dataSpec(),
      ByteArray(0),
    ),
    errorCount = errorCount,
  )

  private fun loadError(exception: IOException, errorCount: Int): LoadErrorHandlingPolicy.LoadErrorInfo =
    LoadErrorHandlingPolicy.LoadErrorInfo(
      LoadEventInfo(LoadEventInfo.getNewId(), dataSpec(), /* elapsedRealtimeMs = */ 0L),
      MediaLoadData(C.DATA_TYPE_MEDIA),
      exception,
      errorCount,
    )

  private fun dataSpec() = DataSpec(Uri.parse("http://localhost:4533/rest/stream"))
}
