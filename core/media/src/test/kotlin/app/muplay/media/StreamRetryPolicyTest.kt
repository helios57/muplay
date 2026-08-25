package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A plain JVM test, on purpose. [StreamRetryPolicy] takes an HTTP status code, a header string and
 * an attempt count, and returns a delay — no `android.net.Uri`, no `DataSpec`, no Media3 type at
 * all — which is what lets Tier 1 gate the branch that decides whether a 429 kills playback. The
 * Media3 adapter around it (`NavidromeLoadErrorHandlingPolicy`) is three lines with no logic, and
 * is covered on the device.
 *
 * This is the same shape as `KeystoreCipher` taking a `SecretKey` rather than reaching into
 * AndroidKeyStore, and `SyncDecision.decide` being a pure ruling: put the decision where the fast
 * tier can hold it to a floor.
 */
class StreamRetryPolicyTest {

  @Test
  fun `a status that is not 429 is not this policy's business`() {
    // `null` means "defer to Media3's own DefaultLoadErrorHandlingPolicy". Overriding every status
    // would be re-implementing the library, and getting 404 or 416 wrong breaks seeking.
    assertThat(StreamRetryPolicy.retryDelayMs(404, retryAfterHeader = null, errorCount = 1)).isNull()
    assertThat(StreamRetryPolicy.retryDelayMs(416, retryAfterHeader = null, errorCount = 1)).isNull()
    assertThat(StreamRetryPolicy.retryDelayMs(500, retryAfterHeader = "1", errorCount = 1)).isNull()
    assertThat(StreamRetryPolicy.retryDelayMs(200, retryAfterHeader = null, errorCount = 1)).isNull()
  }

  @Test
  fun `a 429 with no retry-after backs off exponentially from the base delay`() {
    // Four observations of a value one constant could satisfy. This is the field-level rule
    // applied to a computed number: a `return 1_000L` passes the first of these and fails three.
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 1)).isEqualTo(1_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 2)).isEqualTo(2_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 3)).isEqualTo(4_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 4)).isEqualTo(8_000L)
  }

  @Test
  fun `the backoff is capped rather than doubling forever`() {
    // Without a ceiling, attempt 10 waits over eight minutes and the user believes the app hung.
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 6)).isEqualTo(30_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 50)).isEqualTo(30_000L)
    assertThat(StreamRetryPolicy.MAX_BACKOFF_MS).isEqualTo(30_000L)
  }

  @Test
  fun `a retry-after in seconds is honoured and is the value the server sent`() {
    // Two values, so "honoured" cannot be satisfied by returning a constant that happens to match
    // one of them.
    assertThat(StreamRetryPolicy.retryDelayMs(429, "3", errorCount = 1)).isEqualTo(3_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, "17", errorCount = 1)).isEqualTo(17_000L)
  }

  @Test
  fun `a retry-after the server sent beats the backoff the client would have chosen`() {
    // errorCount 4 would back off 8s on its own; the server said 2, and the server wins. Asserted
    // in the direction where the two disagree, because in the direction where they agree the test
    // would pass with the header ignored.
    assertThat(StreamRetryPolicy.retryDelayMs(429, "2", errorCount = 4)).isEqualTo(2_000L)
  }

  @Test
  fun `an oversized retry-after is clamped to the same ceiling as the backoff`() {
    assertThat(StreamRetryPolicy.retryDelayMs(429, "600", errorCount = 1)).isEqualTo(30_000L)
  }

  @Test
  fun `a retry-after large enough to overflow milliseconds is still clamped, not immediate`() {
    // Not hypothetical arithmetic: `seconds * 1000L` overflows `Long` above ~9.2e15 and the
    // product comes out *negative*, which a trailing `coerceIn(0L, MAX)` turns into 0 -- an
    // immediate retry, the exact behaviour this policy exists to prevent, produced by the branch
    // meant to slow things down the most. Both values below overflow the multiply; both must
    // still land on the ceiling.
    assertThat(StreamRetryPolicy.retryDelayMs(429, "10000000000000000", errorCount = 1))
      .isEqualTo(30_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, "9223372036854775807", errorCount = 3))
      .isEqualTo(30_000L)
  }

  @Test
  fun `a retry-after this policy cannot parse falls through to the backoff`() {
    // The HTTP-date form is deliberately not parsed -- parsing it needs a clock, for a header
    // Navidrome has never been observed to send. Falling through is a correct answer, and these
    // assertions are what stop that from being mistaken for an oversight.
    assertThat(StreamRetryPolicy.retryDelayMs(429, "Wed, 21 Oct 2026 07:28:00 GMT", 1)).isEqualTo(1_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, "soon", 2)).isEqualTo(2_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, "", 3)).isEqualTo(4_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, "-5", 1)).isEqualTo(1_000L)
  }

  @Test
  fun `a retry-after of zero means retry now and is not mistaken for absent`() {
    // 0 is a legal delta-seconds value and it is *not* the same as "no header": the header form
    // returns 0, the absent form returns the base backoff. A parser using `toLongOrNull() ?: base`
    // would get this right; one using `takeIf { it > 0 }` would silently turn it into 1000.
    assertThat(StreamRetryPolicy.retryDelayMs(429, "0", errorCount = 1)).isEqualTo(0L)
  }

  @Test
  fun `five attempts is the retry budget`() {
    assertThat(StreamRetryPolicy.MAX_RETRIES).isEqualTo(5)
    assertThat(StreamRetryPolicy.BASE_BACKOFF_MS).isEqualTo(1_000L)
    assertThat(StreamRetryPolicy.TOO_MANY_REQUESTS).isEqualTo(429)
  }
}
