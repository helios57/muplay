package app.muplay.media

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives a real [ExoPlayer] from an instrumented test thread.
 *
 * Three jobs, and the third is the one worth reading:
 *
 * 1. Every touch of the player happens on the main `Looper`, which Media3 requires.
 * 2. Waiting is done by polling a condition with a deadline — never a fixed sleep. A fixed sleep
 *    is either flaky or slow, and on a CI emulator it is reliably both.
 * 3. **A playback error fails the test as that error.** Without this, a 404, a codec that would
 *    not initialise and a URL that was never fetched all present identically: a `waitUntil` that
 *    timed out. The captured [PlaybackException] is rethrown as the assertion failure's cause,
 *    so the message names the real problem.
 */
class PlayerHarness(val player: ExoPlayer) {

  private val error = AtomicReference<PlaybackException?>(null)

  init {
    onMain {
      player.addListener(object : Player.Listener {
        override fun onPlayerError(e: PlaybackException) {
          error.set(e)
        }
      })
    }
  }

  /**
   * Runs [block] on the main thread and returns its result, propagating any exception.
   *
   * The already-on-main short-circuit is not an optimisation. `Instrumentation.runOnMainSync`
   * throws `RuntimeException: This method can not be called from the main application thread`
   * when it is already there, and the harness is *constructed* inside a `runOnMainSync` (an
   * `ExoPlayer` has to be built on a `Looper` thread), so its own `init` block reaches this
   * method from the main thread every single time. Without this branch every test in this module
   * died in `PlayerHarness.<init>` -- observed, not anticipated.
   */
  fun <T> onMain(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val result = AtomicReference<Any?>(null)
    val thrown = AtomicReference<Throwable?>(null)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result.set(it) }.onFailure { thrown.set(it) }
    }
    thrown.get()?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result.get() as T
  }

  /** Polls [condition] on the main thread until it is true or [timeoutMs] elapses. */
  fun await(description: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      error.get()?.let { throw AssertionError("playback failed while waiting for $description", it) }
      if (onMain(condition)) return
      Thread.sleep(POLL_INTERVAL_MS)
    }
    error.get()?.let { throw AssertionError("playback failed while waiting for $description", it) }
    throw AssertionError(
      "timed out after ${timeoutMs}ms waiting for $description; " +
        "state=${onMain { player.playbackState }} playWhenReady=${onMain { player.playWhenReady }} " +
        "position=${onMain { player.currentPosition }}",
    )
  }

  fun awaitState(state: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS) =
    await("playbackState == $state", timeoutMs) { player.playbackState == state }

  /**
   * Waits until the player's own position passes [positionMs].
   *
   * **This is the assertion that distinguishes "playing" from "was asked to play".** `play()`
   * returning, `playWhenReady == true` and `STATE_READY` are all satisfied by a player that never
   * produced a sample. A position that has genuinely moved past a second of media is not.
   */
  fun awaitPositionAtLeast(positionMs: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS) =
    await("currentPosition >= $positionMs", timeoutMs) { player.currentPosition >= positionMs }

  fun awaitEnded(timeoutMs: Long = DEFAULT_TIMEOUT_MS) = awaitState(Player.STATE_ENDED, timeoutMs)

  /**
   * Waits for the player to report an error, and returns it.
   *
   * The mirror image of [await]'s error handling, and it has to be a separate method rather than a
   * flag on that one: [await] treats a captured [PlaybackException] as a reason to abandon the
   * wait immediately, which is right for every wait where an error means the test's premise broke,
   * and exactly wrong for the give-up path, where the error **is** the assertion. Without this,
   * "the retry budget ran out as designed" and "the retry budget never ran out" both arrive as a
   * thrown `AssertionError` from [await] and nothing tells them apart.
   */
  fun awaitPlaybackError(timeoutMs: Long = DEFAULT_TIMEOUT_MS): PlaybackException {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      error.get()?.let { return it }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    throw AssertionError(
      "timed out after ${timeoutMs}ms waiting for a playback error; " +
        "state=${onMain { player.playbackState }} position=${onMain { player.currentPosition }} " +
        "playerError=${onMain { player.playerError }}",
    )
  }

  /** Rethrows any error the player reported, whether or not a wait was in progress. */
  fun assertNoPlaybackError() {
    error.get()?.let { throw AssertionError("player reported an error", it) }
  }

  fun release() = onMain { player.release() }

  companion object {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val POLL_INTERVAL_MS = 50L
  }
}
