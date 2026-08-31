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
 *   [SleepTimerFade] owns the arithmetic, and `SleepTimerFadeAudioTest` measures it on the samples.
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
 * `SleepTimerFadeAudioTest` asserts it on the PCM the decoder produced.
 *
 * ### [attach]'s scope must dispatch on the player's own thread
 *
 * Not a style preference: `player.volume`, `player.pause()`, `player.play()` and
 * `player.currentPosition` all go through `ExoPlayerImpl.verifyApplicationThread()`, which throws
 * `IllegalStateException` from anywhere else -- so a ticker launched into a background dispatcher
 * would take the whole service down on its first tick rather than degrade. `MuPlaybackService`'s
 * `serviceScope` is built from a main-`Handler` executor for exactly this reason and is the scope
 * this expects. The same applies to every entry point below: they all reach the player.
 *
 * ### Why a [Countdown] rather than three fields
 *
 * "A wall-clock deadline" and "a position in one file" are two arms of one choice, and holding them
 * as three nullable fields made every reader of them ask a question that could not be answered
 * wrong -- `deadlineEpochMs ?: 0L` inside a branch that had just proved it non-null, a `stopAt`
 * elvis that no input could take. Those are branches no test can turn red, which this project
 * counts. A sealed interface is also what the global constraints ask for.
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

  /** The player and the scope arrive together and leave together, so they are held together. */
  private class Attachment(val player: Player, val scope: CoroutineScope)

  /** What the timer is counting to. */
  private sealed interface Countdown {
    /** A wall-clock instant. */
    data class Until(val epochMs: Long) : Countdown

    /** A millisecond mark inside one file, named by its media id. */
    data class AtPosition(val mediaId: String, val positionMs: Long) : Countdown
  }

  private var attachment: Attachment? = null
  private var ticker: Job? = null
  private var countdown: Countdown? = null
  private var firedAtEpochMs: Long? = null

  /**
   * Bind to [player], and bring any countdown already in flight with it.
   *
   * **Called on every emission of `PlaybackOutputSwitch.activePlayer`**, which is the same
   * collector that re-points the media session when audio moves to a speaker. Attaching once at
   * startup is not enough and the failure is silent: this class's whole mechanism is
   * `player.volume` and `player.pause()` on *one* player, so a timer left behind ramps the phone
   * it is no longer driving down to zero and pauses something that is already paused, while the
   * speaker plays all night. Nothing throws and nothing logs.
   *
   * Two things therefore happen here rather than one field assignment:
   *
   * - **the outgoing player's volume goes back to [FULL_VOLUME]**, because a handover that lands
   *   mid-fade would otherwise leave it at whatever the ramp had reached -- the "fade that never
   *   fades back" this class's header is built around, arriving by a route that has nothing to do
   *   with the timer ending;
   * - **a running countdown is restarted against the incoming player**, so the deadline the
   *   listener set survives the handover and the ramp lands on the thing making the sound.
   *
   * Unlike `ProgressWriter`, this has **no ordering constraint against `setMediaItems`**: it reads
   * no history off the player and writes nothing that a later item transition would invalidate, so
   * arriving a dispatch after the handover costs at most one tick against the outgoing player --
   * whose volume the line above then puts back anyway. That is why the service can move it from a
   * coroutine and has to move the writer synchronously.
   */
  fun attach(player: Player, scope: CoroutineScope) {
    attachment?.let { it.player.volume = FULL_VOLUME }
    val attached = Attachment(player, scope)
    attachment = attached
    countdown?.let { begin(attached, it) }
  }

  fun detach() {
    cancel()
    attachment = null
  }

  fun start(request: SleepTimerRequest) {
    val attached = attachment ?: return
    firedAtEpochMs = null
    begin(
      attached,
      when (request) {
        is SleepTimerRequest.Duration -> Countdown.Until(clock.millis() + request.millis)
        is SleepTimerRequest.UntilPosition ->
          Countdown.AtPosition(request.mediaId, request.positionMs)
      },
    )
  }

  fun cancel() {
    ticker?.cancel()
    ticker = null
    countdown = null
    firedAtEpochMs = null
    attachment?.let { it.player.volume = FULL_VOLUME }
    _state.value = SleepTimerState.Off
  }

  /**
   * Push the deadline out, restore the volume, and resume if the timer had already fired.
   *
   * An "until this position" timer extends by [byMs] of **media**, which is what "five more minutes
   * of the book" means; a wall-clock timer extends by [byMs] of wall clock. After the timer has
   * fired there is no countdown left to extend -- [fire] cleared it -- so the extension lands on a
   * fresh wall-clock deadline instead, and the shake that bought it turns an end-of-chapter timer
   * into a five-minute one. Stated because it is a decision, not an oversight: the chapter it was
   * counting to has already gone by.
   */
  fun extend(byMs: Long = EXTENSION_MS) {
    val attached = attachment ?: return
    val resuming = firedAtEpochMs != null
    firedAtEpochMs = null
    val extended = when (val current = countdown) {
      is Countdown.AtPosition -> current.copy(positionMs = current.positionMs + byMs)
      // A running wall-clock timer's deadline is always in the future, so this adds to what is
      // left rather than restarting from now: one shake on a timer with twenty minutes on it
      // leaves twenty-five, not five.
      is Countdown.Until -> Countdown.Until(current.epochMs + byMs)
      // ...and after it fired there is no deadline at all, so the extension counts from now.
      null -> Countdown.Until(clock.millis() + byMs)
    }
    if (resuming) attached.player.play()
    begin(attached, extended)
  }

  /**
   * The shake affordance. Ignored unless a timer is running, or one fired within [graceMs].
   *
   * Two conditions, not three: an earlier draft also refused a shake when a timer had fired but was
   * outside the grace window *and* nothing was running, which is **unreachable** -- [fire] clears
   * the countdown, so a fired timer is never running and the guard below has already returned. It
   * was deleted rather than kept as belt-and-braces: an unreachable branch is a line no test can
   * turn red.
   */
  fun onShake() {
    val recentlyFired = firedAtEpochMs?.let { clock.millis() - it <= graceMs } == true
    if (countdown == null && !recentlyFired) return
    extend()
  }

  /**
   * Adopt [countdown], put the volume back, publish, and restart the ticker against it.
   *
   * The countdown is passed to the ticker rather than read from the field on every tick, so a
   * running ticker can never observe a half-applied change: [extend] builds the new one, this
   * cancels the old job, and the new job counts to the new value from its first tick.
   */
  private fun begin(attached: Attachment, countdown: Countdown) {
    this.countdown = countdown
    attached.player.volume = FULL_VOLUME
    publish(remainingMs(attached.player, countdown), countdown)
    ticker?.cancel()
    ticker = attached.scope.launch {
      while (tick(attached, countdown)) delay(TICK_MS)
    }
  }

  /** One step of the ramp. Returns whether the countdown is still running. */
  private fun tick(attached: Attachment, countdown: Countdown): Boolean {
    val remaining = remainingMs(attached.player, countdown)
    if (remaining <= 0L) {
      fire(attached.player)
      return false
    }
    attached.player.volume = SleepTimerFade.volumeFor(remaining, fadeMs)
    publish(remaining, countdown)
    return true
  }

  private fun fire(player: Player) {
    // Pause first, restore second: the listener must never hear the volume jump back up on audio
    // that is still playing.
    player.pause()
    player.volume = FULL_VOLUME
    firedAtEpochMs = clock.millis()
    countdown = null
    _state.value = SleepTimerState.Off
  }

  private fun remainingMs(player: Player, countdown: Countdown): Long = when (countdown) {
    is Countdown.Until -> countdown.epochMs - clock.millis()
    is Countdown.AtPosition ->
      // A transition to another file ends an "until this position" timer, and so does an empty
      // queue: the position it named belongs to a file that is not playing.
      if (player.currentMediaItem?.mediaId != countdown.mediaId) {
        0L
      } else {
        // Divided by the speed, because at 2x the remaining *media* is half the remaining wall
        // clock, and the fade is a wall-clock ramp. Note what this does NOT move: the timer still
        // fires at the same *position* whatever the speed, so a test asserting only where playback
        // stopped is green against its removal. What it moves is the countdown the UI shows and
        // when the ramp starts -- `theRemainingTimeIsWallClockAndTheSpeedIsTheDivisor` reads the
        // number at two speeds.
        //
        // No guard against a zero or negative speed: `PlaybackParameters`'s own constructor
        // requires `speed > 0`, so the arm a guard would add is one no caller can reach.
        ((countdown.positionMs - player.currentPosition) / player.playbackParameters.speed).toLong()
      }
  }

  private fun publish(remainingMs: Long, countdown: Countdown) {
    _state.value = SleepTimerState.Running(
      // A countdown started past the mark it names is zero on screen, never negative.
      remainingMs = remainingMs.coerceAtLeast(0L),
      untilEndOfChapter = countdown is Countdown.AtPosition,
      isFading = remainingMs <= fadeMs,
    )
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
