package app.muplay.cast.session

/** The four states a Media3 `Player` reports, named without depending on Media3. */
enum class CastPlaybackState { IDLE, BUFFERING, READY, ENDED }

/**
 * **Everything a `Player` needs to report, as one immutable value.**
 *
 * Task 9 wraps a [CastSession] in a `SimpleBasePlayer`, whose `getState()` must produce a complete
 * snapshot on demand and whose listener callbacks Media3 derives from the *diff* between two
 * snapshots. That is why this is a value and not a set of accessors: a `getState()` that read six
 * fields off a live object could observe half of one transition and half of the next, and Media3
 * would derive a callback nobody's state ever had.
 *
 * **It carries no URL of any kind.** The queue is present only as [queueSize], [currentIndex] and
 * [currentMediaId]; the resource URLs, the proxy tokens and above all the upstream Navidrome
 * stream URL (which holds the user's Subsonic credentials) stay inside [CastSession]. A snapshot
 * type is the one that ends up in a log line, a `toString()` in a crash report or an
 * `assertThat(...)` failure message, and this one cannot leak a credential because it never holds
 * one.
 *
 * @param positionAdvancing whether the **renderer** reported `PLAYING` at [positionMeasuredAtMs].
 *   Distinct from `playbackState == READY && playWhenReady`, which is Media3's `isPlaying` and is
 *   true during the optimistic window between `play()` and the poll that confirms it. Only the
 *   renderer's own word makes a clock run -- see [positionAtMs].
 */
data class CastPlayback(
  val playbackState: CastPlaybackState,
  val playWhenReady: Boolean,
  val positionAdvancing: Boolean,
  val queueSize: Int,
  val currentIndex: Int,
  val currentMediaId: String?,
  /** The last position the renderer reported, in milliseconds. */
  val positionMs: Long,
  /** The reading of [CastSession]'s clock at which [positionMs] was observed. */
  val positionMeasuredAtMs: Long,
  /** `0` when unknown, never a negative sentinel. */
  val durationMs: Long,
  val volumePercent: Int,
  /** Whether the device's own service description declares a **time** seek mode. */
  val canSeek: Boolean,
  val failure: CastFailure?,
) {

  /**
   * Where playback is *now*, extrapolated in real time from the last poll.
   *
   * `AVTransport` is polled at 1 Hz ([CastSession.POLL_INTERVAL_MS]) and there is no cheaper way to
   * get a position -- the `LastChange` event explicitly excludes `RelTime` and `AbsTime`. Reporting
   * [positionMs] unchanged between polls makes the seek bar tick once a second, and makes the
   * progress writer above it record a position up to a second stale. This advances it, and the next
   * poll resets it to the truth.
   *
   * It is Media3's `PositionSupplier.getExtrapolating` written where Tier 1 can hold it: Task 9
   * hands the same two numbers to that supplier, and this function is what says what the two numbers
   * mean.
   *
   * Three things it will not do:
   * - it does not advance unless the **renderer** said `PLAYING` ([positionAdvancing]), so the
   *   optimistic window after `play()` reports a still position rather than an invented one;
   * - it never runs backwards, however the clock behaves;
   * - it never runs past [durationMs] when one is known. A stalled poll must not report 4:31 of a
   *   3:00 track to a progress writer, because that becomes a resume position past the end of the
   *   chapter.
   */
  fun positionAtMs(nowMs: Long): Long {
    if (!positionAdvancing) return positionMs
    val extrapolated = positionMs + (nowMs - positionMeasuredAtMs).coerceAtLeast(0L)
    return if (durationMs > 0L) extrapolated.coerceAtMost(durationMs) else extrapolated
  }

  companion object {
    /**
     * Nothing cast, nothing queued.
     *
     * Every field written out rather than defaulted on the constructor: a defaulted parameter
     * compiles to a synthetic constructor only a caller omitting it can reach, and `CastRoute` in
     * this module already paid for that in permanently uncovered lines.
     */
    val IDLE: CastPlayback = CastPlayback(
      playbackState = CastPlaybackState.IDLE,
      playWhenReady = false,
      positionAdvancing = false,
      queueSize = 0,
      currentIndex = 0,
      currentMediaId = null,
      positionMs = 0L,
      positionMeasuredAtMs = 0L,
      durationMs = 0L,
      volumePercent = CastSession.DEFAULT_VOLUME_PERCENT,
      canSeek = false,
      failure = null,
    )
  }
}
