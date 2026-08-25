package app.muplay.media

/**
 * What to do about an HTTP status Media3 hit while loading a stream.
 *
 * Deliberately free of every Media3 and Android type: it takes a status code, a header value and
 * an attempt number, and returns a delay in milliseconds — or `null`, meaning *"not my business,
 * use Media3's own policy"*. That shape is what lets the fast tier gate this branch, and it is the
 * same trade `KeystoreCipher` makes by taking a `SecretKey` instead of opening the Android
 * Keystore itself.
 *
 * Only **429** is this policy's business. Navidrome 0.62.0 added `Transcoding.MaxConcurrent`, and
 * a 429 from it means "a transcoding slot is busy, ask again". Media3's own
 * `DefaultLoadErrorHandlingPolicy` treats it like any other response-code error: three retries,
 * the first immediate, all of them inside five seconds — which fails the track *and* adds to the
 * contention on the way out. Spec section 4 records the symptom: unhandled, this looks like random
 * playback failure.
 */
object StreamRetryPolicy {

  /** The only status this policy has an opinion about. */
  const val TOO_MANY_REQUESTS: Int = 429

  /** Attempts before giving up. Five, over a backoff that reaches the ceiling at attempt six. */
  const val MAX_RETRIES: Int = 5

  /** The first wait. One second, not zero: an immediate retry is what makes contention worse. */
  const val BASE_BACKOFF_MS: Long = 1_000L

  /**
   * The longest this will ever wait. Doubling without a ceiling reaches eight minutes by attempt
   * ten, which a listener experiences as the app having hung.
   */
  const val MAX_BACKOFF_MS: Long = 30_000L

  /**
   * The delay before Media3 should retry, or `null` to defer to Media3's own policy.
   *
   * [retryAfterHeader] is honoured only in its **delta-seconds** form (RFC 9110 §10.2.3's first
   * alternative). The HTTP-date form is not parsed: doing so needs a wall clock — and therefore an
   * injected `Clock`, per this project's constraints — for a header Navidrome has never been
   * observed to send. An unparseable value falls through to the backoff, which is a correct
   * answer rather than a wrong one.
   *
   * A `Retry-After` of `"0"` returns `0`, not the base backoff: zero is a legal value that means
   * "now", and collapsing it into "absent" would be a client silently disagreeing with a server
   * that answered the question.
   */
  fun retryDelayMs(responseCode: Int, retryAfterHeader: String?, errorCount: Int): Long? {
    if (responseCode != TOO_MANY_REQUESTS) return null
    val fromHeader = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
      // Clamped in *seconds*, before the multiply, not only after it. `seconds * 1000L` overflows
      // `Long` for anything above ~9.2e15, and an overflowed product is negative -- which the
      // trailing `coerceIn(0L, ..)` then reads as "retry immediately", the single behaviour this
      // whole policy exists to prevent. Clamping first makes the multiply unable to overflow at
      // all, and changes nothing for any value a real server sends: 600 still lands on the same
      // 30_000 ceiling either way. `StreamRetryPolicyTest`'s
      // `a retry-after large enough to overflow milliseconds is still clamped, not immediate` is
      // the test that goes red if this `coerceAtMost` is removed.
      ?.coerceAtMost(MAX_BACKOFF_MS / 1000L)
    val delay = fromHeader?.times(1000L) ?: backoffMs(errorCount)
    return delay.coerceIn(0L, MAX_BACKOFF_MS)
  }

  /** `BASE_BACKOFF_MS * 2^(errorCount - 1)`, computed by shifting so it cannot overflow. */
  private fun backoffMs(errorCount: Int): Long {
    val doublings = (errorCount - 1).coerceIn(0, 30)
    return BASE_BACKOFF_MS shl doublings
  }
}
