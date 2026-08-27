package app.muplay.media

import androidx.media3.common.Player
import app.muplay.model.SleepTimerRequest
import app.muplay.model.SleepTimerState
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The sleep timer: it fades, it pauses, and a shake brings it back.
 *
 * Spec section 5 asks for *"a sleep timer, with a shake-to-extend affordance"* and specifies
 * nothing else. Four decisions that sentence does not make:
 *
 * - **It fades, it does not cut.** Audio dropping to silence mid-word wakes people up, which is the
 *   opposite of the feature. The last [fadeMs] ramp the player's volume linearly to zero --
 *   [SleepTimerFade] owns the arithmetic.
 * - **It pauses, it does not stop.** Pausing runs through `ProgressWriter`'s persistence points
 *   (spec section 3, points 1 and 2), so the position is written exactly as if the listener had
 *   pressed pause. Stopping would drop the queue and take the position with it.
 * - **"End of chapter" is a position, not a duration.** [SleepTimerRequest.UntilPosition] carries a
 *   media id and a millisecond mark that the *caller* computed from `BookTimeline`; this class has
 *   one mechanism and no chapter knowledge.
 * - **A shake works for [graceMs] after the timer fired.** Waking up just after the audio stopped
 *   is the ordinary case rather than the exception, and a timer that only accepted a shake before
 *   its deadline would be useless exactly when it is wanted.
 *
 * ### The trap: a fade that never fades back
 *
 * `player.volume` is player *state*. Ramp it to zero, pause, and the next thing the listener plays
 * is silent -- no error, no indication, and the only recovery is reinstalling the app. So the
 * volume is restored at **every** exit: on expiry (after the pause), on [cancel], on [extend] and
 * on [detach]. `SleepTimerControllerTest` asserts the number came back *and* that audio does, and
 * `SleepTimerFadeAudioTest` asserts it on the samples.
 *
 * ### [attach]'s scope must dispatch on the player's own thread
 *
 * Not a style preference: `player.volume`, `player.pause()`, `player.play()` and
 * `player.currentPosition` all go through `ExoPlayerImpl.verifyApplicationThread()`, which throws
 * `IllegalStateException` from anywhere else -- so a ticker launched into a background dispatcher
 * would take the whole service down on its first tick rather than degrade. `MuPlaybackService`'s
 * `serviceScope` is built from `mainExecutor.asCoroutineDispatcher()` for exactly this reason and
 * is the scope this expects.
 *
 * [fadeMs] and [graceMs] are constructor arguments rather than constants so a test can run a real
 * countdown in seconds -- and so that neither is a value observed at exactly one value.
 */
@Singleton
class SleepTimerController internal constructor(
  private val clock: Clock,
  private val fadeMs: Long,
  private val graceMs: Long,
) {

  /**
   * The constructor Hilt uses.
   *
   * **Not** default parameter values on the primary constructor: Hilt ignores Kotlin defaults and
   * would look for a binding for `Long`, which fails at build time with a message about an unbound
   * `java.lang.Long` that reads like a Dagger bug rather than like this. A secondary `@Inject`
   * constructor is the shape that works, and it keeps the two timings injectable from a test --
   * which is what stops `fadeMs` and `graceMs` being values observed at exactly one value.
   */
  @Inject
  constructor(clock: Clock) : this(clock, SleepTimerFade.DEFAULT_FADE_MS, GRACE_MS)

  private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
  val state: StateFlow<SleepTimerState> = _state.asStateFlow()

  private var player: Player? = null
  private var scope: CoroutineScope? = null
  private var ticker: Job? = null

  private var deadlineEpochMs: Long? = null
  private var stopAtPositionMs: Long? = null
  private var stopForMediaId: String? = null
  private var firedAtEpochMs: Long? = null

  fun attach(player: Player, scope: CoroutineScope) {
    this.player = player
    this.scope = scope
  }

  fun detach() {
    cancel()
    player = null
    scope = null
  }

  fun start(request: SleepTimerRequest) {
    firedAtEpochMs = null
    when (request) {
      is SleepTimerRequest.Duration -> {
        deadlineEpochMs = clock.millis() + request.millis
        stopAtPositionMs = null
        stopForMediaId = null
      }
      is SleepTimerRequest.UntilPosition -> {
        deadlineEpochMs = null
        stopAtPositionMs = request.positionMs
        stopForMediaId = request.mediaId
      }
    }
    restoreVolume()
    publish()
    startTicking()
  }

  fun cancel() {
    ticker?.cancel()
    ticker = null
    deadlineEpochMs = null
    stopAtPositionMs = null
    stopForMediaId = null
    firedAtEpochMs = null
    restoreVolume()
    _state.value = SleepTimerState.Off
  }

  /**
   * Push the deadline out, restore the volume, and resume if the timer had already fired.
   *
   * An "until this position" timer extends by [byMs] of **media**, which is what "five more
   * minutes of the book" means; a duration timer extends by [byMs] of wall clock. After the timer
   * has fired there is no position left to extend -- [fire] cleared it -- so the extension lands on
   * a fresh duration deadline instead, and the shake that bought it turns an end-of-chapter timer
   * into a five-minute one. Stated because it is a behaviour, not an oversight: the chapter it was
   * counting to has already gone by.
   */
  fun extend(byMs: Long = EXTENSION_MS) {
    val resuming = firedAtEpochMs != null
    firedAtEpochMs = null
    when {
      stopAtPositionMs != null -> stopAtPositionMs = (stopAtPositionMs ?: 0L) + byMs
      // Extending after it fired counts from now, not from a deadline already in the past.
      else -> deadlineEpochMs = maxOf(deadlineEpochMs ?: 0L, clock.millis()) + byMs
    }
    restoreVolume()
    if (resuming) player?.play()
    publish()
    startTicking()
  }

  /**
   * The shake affordance. Ignored unless a timer is running, or fired within [graceMs].
   *
   * Two guards, not three: an earlier draft also refused a shake when `firedAtEpochMs != null &&
   * !recentlyFired`, which is **unreachable** -- [fire] clears both the deadline and the stop
   * position, so a fired timer is never `running`, and the first guard has already returned. It was
   * removed rather than left in as belt-and-braces: an unreachable branch is a line no test can
   * turn red, and this project counts those.
   */
  fun onShake() {
    val running = deadlineEpochMs != null || stopAtPositionMs != null
    val recentlyFired = firedAtEpochMs?.let { clock.millis() - it <= graceMs } == true
    if (!running && !recentlyFired) return
    extend()
  }

  private fun startTicking() {
    ticker?.cancel()
    val scope = scope ?: return
    ticker = scope.launch {
      while (true) {
        tick()
        delay(TICK_MS)
      }
    }
  }

  private fun tick() {
    val player = player ?: return
    val remaining = remainingMs(player) ?: return
    if (remaining <= 0L) {
      fire(player)
      return
    }
    player.volume = SleepTimerFade.volumeFor(remaining, fadeMs)
    publish(remaining)
  }

  private fun fire(player: Player) {
    ticker?.cancel()
    ticker = null
    // Pause first, restore second: the listener must never hear the volume jump back up on audio
    // that is still playing.
    player.pause()
    restoreVolume()
    firedAtEpochMs = clock.millis()
    deadlineEpochMs = null
    stopAtPositionMs = null
    stopForMediaId = null
    _state.value = SleepTimerState.Off
  }

  private fun remainingMs(player: Player): Long? {
    deadlineEpochMs?.let { return it - clock.millis() }
    val stopAt = stopAtPositionMs ?: return null
    // A transition to another file ends an "until this position" timer: the position it named
    // belongs to a file that is no longer playing.
    if (stopForMediaId != null && player.currentMediaItem?.mediaId != stopForMediaId) return 0L
    // Divided by the speed, because at 2x the remaining *media* is half the remaining wall clock,
    // and the fade is a wall-clock ramp. Note what this does and does not move: the timer still
    // fires at the same *position* either way, so a test that asserts only where playback stopped
    // cannot see this division at all. What it moves is how much media is left when the ramp
    // starts, and the number on screen --
    // `theRemainingTimeIsWallClockAndTheSpeedIsTheDivisor` reads that number at two speeds.
    val speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
    return ((stopAt - player.currentPosition) / speed).toLong()
  }

  private fun publish(remaining: Long? = null) {
    val player = player ?: return
    val left = remaining ?: remainingMs(player) ?: return
    _state.value = SleepTimerState.Running(
      remainingMs = left.coerceAtLeast(0L),
      untilEndOfChapter = stopAtPositionMs != null,
      isFading = left <= fadeMs,
    )
  }

  private fun restoreVolume() {
    player?.volume = FULL_VOLUME
  }

  companion object {
    const val TICK_MS = 250L

    /** One shake buys five more minutes. */
    const val EXTENSION_MS = 300_000L

    /** How long after firing a shake still counts as "no, keep going". */
    const val GRACE_MS = 60_000L

    /** What every exit restores the player to. `Player.volume` is a 0..1 linear scalar. */
    const val FULL_VOLUME = 1f

    /** What the UI offers, in milliseconds. */
    val PRESETS: List<Long> = listOf(5L, 10L, 15L, 30L, 45L, 60L).map { it * 60_000L }
  }
}
