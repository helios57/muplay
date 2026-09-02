package app.muplay.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.SleepTimerRequest
import app.muplay.model.SleepTimerState
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.io.File
import java.time.Clock
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The countdown, against a real player playing real audio off the real container.
 *
 * Short durations and a short fade, both passed in: [SleepTimerController] takes `fadeMs` and
 * `graceMs` as constructor arguments precisely so a test can use one second rather than twenty, and
 * so that neither is a value observed at exactly one value.
 *
 * **The assertion this class exists for is the volume restore.** Ramping to zero and forgetting to
 * ramp back leaves the app permanently silent -- no error, no indication, and the only recovery a
 * listener has is reinstalling it. `SleepTimerFadeAudioTest` is the other half: it measures the
 * ramp and the restore on the **samples**, which nothing here can see.
 *
 * ### Everything is called on the player's own thread
 *
 * `start`, `cancel`, `extend`, `onShake`, `attach` and `detach` all reach the player -- to restore
 * the volume, to read the position, to pause. `ExoPlayerImpl.verifyApplicationThread()` throws from
 * anywhere else, so every call below goes through `harness.onMain`, exactly as a `MediaController`
 * command or a UI tap would arrive in production.
 *
 * ### The fixture is 21 seconds, and that is why these tests can discriminate
 *
 * On the five-second music fixtures, "wait for playback to pause" is satisfied by the track simply
 * ending. `Second Book` runs 21 s, so every pause asserted here is a pause the timer caused --
 * every wait ends well inside the file, and the two tests that could still be satisfied by an
 * ending track ([theTimerPausesPlaybackWhenItRunsOut] and
 * [cancellingStopsTheCountdownAndRestoresTheVolume]) assert playback is *still running* first.
 *
 * No stream URL is asserted on, logged, or put in a description: they carry `u`, `s` and `t`.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerControllerTest {

  /**
   * The **real** clock, unlike most of this plan's device suites: this is a real countdown, and a
   * fixed clock would mean it never counts down.
   */
  private val clock: Clock = Clock.systemUTC()

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var book: Song
  private lateinit var parts: List<Song>
  private lateinit var scope: CoroutineScope
  private val harnesses = mutableListOf<PlayerHarness>()
  private val cleanups = mutableListOf<() -> Unit>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "sleep-timer-${System.nanoTime()}")
    val files = runBlocking { RealTrackBytes.audiobookFiles() }
    // Looked up by the titles `ci/seed-fixtures.sh` writes, never by index and never by count:
    // the corpus is shared with every other lane and grows without warning.
    book = files.first { it.title == BOOK_TITLE }
    parts = PART_TITLES.map { title -> files.first { it.title == title } }
    scope = CoroutineScope(SupervisorJob() + MAIN_EXECUTOR.asCoroutineDispatcher())
  }

  @After
  fun tearDown() {
    // Guarded rather than assumed initialised: a failure inside `setUp` before these are assigned
    // would otherwise replace the real cause with an `UninitializedPropertyAccessException` from
    // here, which is the only message the report would carry.
    if (::scope.isInitialized) scope.cancel()
    harnesses.forEach { it.release() }
    harnesses.clear()
    cleanups.forEach { it() }
    cleanups.clear()
    if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
  }

  @Test
  fun theTimerPausesPlaybackWhenItRunsOut() {
    val harness = startBook()
    val subject = timer(harness)

    harness.onMain { subject.start(SleepTimerRequest.Duration(COUNTDOWN_MS)) }

    // Two observations, before and after. "It is paused" alone is satisfied by a timer that fired
    // immediately, which is a real bug and a very annoying one.
    Thread.sleep(COUNTDOWN_MS / 2)
    assertThat(harness.onMain { harness.player.isPlaying })
      .describedAs("still playing halfway through a %d ms timer", COUNTDOWN_MS).isTrue
    harness.await("playback to pause", timeoutMs = FIRE_TIMEOUT_MS) { !harness.player.isPlaying }
  }

  @Test
  fun theVolumeComesBackAfterTheTimerFires() {
    // The trap. One forgotten line here leaves every later playback silent, with no error anywhere.
    val harness = startBook()
    val subject = timer(harness)

    harness.onMain { subject.start(SleepTimerRequest.Duration(COUNTDOWN_MS)) }
    harness.await("playback to pause", timeoutMs = FIRE_TIMEOUT_MS) { !harness.player.isPlaying }

    assertThat(harness.onMain { harness.player.volume }).isEqualTo(FULL_VOLUME)
    // ...and it is not merely the number: audio must actually come back.
    val before = harness.onMain { harness.player.currentPosition }
    harness.onMain { harness.player.play() }
    harness.awaitPositionAtLeast(before + RESUME_MS, timeoutMs = PLAYBACK_TIMEOUT_MS)
  }

  @Test
  fun theVolumeActuallyGoesDownBeforeItFires() {
    // The control for the test above. Without it, "volume is 1.0 at the end" is satisfied by a
    // timer that never faded at all, and the fade would be untested here.
    val harness = startBook()
    val subject = timer(harness, fadeMs = 2_000L)

    harness.onMain { subject.start(SleepTimerRequest.Duration(2_500L)) }
    harness.await("the fade to start", timeoutMs = FIRE_TIMEOUT_MS) { harness.player.volume < 0.9f }

    assertThat(harness.onMain { harness.player.volume }).isBetween(0.0f, 0.9f)
    // ...and the state says so too, so the UI can show it.
    assertThat((subject.state.value as SleepTimerState.Running).isFading).isTrue
  }

  @Test
  fun cancellingStopsTheCountdownAndRestoresTheVolume() {
    val harness = startBook()
    val subject = timer(harness)

    harness.onMain { subject.start(SleepTimerRequest.Duration(COUNTDOWN_MS)) }
    // Inside the fade, so the cancel has a volume to restore rather than an untouched one.
    Thread.sleep(COUNTDOWN_MS - 800L)
    assertThat(harness.onMain { harness.player.volume })
      .describedAs("the fade must have started, or this test's premise is empty")
      .isLessThan(FULL_VOLUME)
    harness.onMain { subject.cancel() }
    Thread.sleep(COUNTDOWN_MS)

    assertThat(harness.onMain { harness.player.isPlaying })
      .describedAs("a cancelled timer must not fire").isTrue
    assertThat(harness.onMain { harness.player.volume }).isEqualTo(FULL_VOLUME)
    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
  }

  @Test
  fun extendingPushesTheDeadlineOut() {
    val harness = startBook()
    // A two-second fade, not the default one: with a 1 s fade the tick grid puts the reading below
    // at exactly `remaining == fadeMs`, where `volumeFor` answers 1.0 -- so the restore assertion
    // would be satisfied whether or not the restore happened. Measured at **0.978** on the run that
    // found it.
    val subject = timer(harness, fadeMs = 2_000L)

    harness.onMain { subject.start(SleepTimerRequest.Duration(COUNTDOWN_MS)) }
    Thread.sleep(COUNTDOWN_MS - 800L)
    // Both readings happen **inside the same main-thread turn as the extend**, and that is what
    // makes the second one discriminate: `extend` restarts the ticker with `launch`, whose first
    // tick is dispatched rather than immediate, so a separate `onMain` read lands after that tick
    // has already written the volume for the new deadline. Measured -- with the restore deleted
    // from `begin`, the two-turn version of this assertion stayed green.
    val (faded, restored) = harness.onMain {
      val faded = harness.player.volume
      subject.extend(byMs = 6_000L)
      faded to harness.player.volume
    }
    Thread.sleep(2_000L)

    // Past the original deadline, still playing. And the volume came back, because the extend
    // happened during the fade -- which the first reading is what proves.
    assertThat(faded)
      .describedAs("mid-fade when the extend arrived, or the restore has nothing to restore")
      .isLessThan(FULL_VOLUME)
    assertThat(restored)
      .describedAs("the volume the listener hears between the extend and the next tick")
      .isEqualTo(FULL_VOLUME)
    assertThat(harness.onMain { harness.player.isPlaying }).isTrue
    assertThat(harness.onMain { harness.player.volume }).isEqualTo(FULL_VOLUME)
    assertThat(subject.state.value).isInstanceOf(SleepTimerState.Running::class.java)
  }

  @Test
  fun aShakeJustAfterItFiredResumesPlayback() {
    // Waking up a moment after the audio stopped is the ordinary case. A timer that only accepted
    // a shake *before* the deadline would be useless exactly when it is wanted.
    val harness = startBook()
    val subject = timer(harness)

    harness.onMain { subject.start(SleepTimerRequest.Duration(2_000L)) }
    harness.await("playback to pause", timeoutMs = FIRE_TIMEOUT_MS) { !harness.player.isPlaying }

    harness.onMain { subject.onShake() }

    harness.await("playback to resume", timeoutMs = FIRE_TIMEOUT_MS) { harness.player.isPlaying }
    assertThat(subject.state.value).isInstanceOf(SleepTimerState.Running::class.java)
  }

  @Test
  fun aShakeLongAfterItFiredDoesNothing() {
    // The control for the grace period. Without it, "a shake resumes" is true forever, and picking
    // the phone up the next morning restarts the audiobook.
    val harness = startBook()
    val subject = timer(harness, graceMs = 500L)

    harness.onMain { subject.start(SleepTimerRequest.Duration(2_000L)) }
    harness.await("playback to pause", timeoutMs = FIRE_TIMEOUT_MS) { !harness.player.isPlaying }
    Thread.sleep(1_500L)

    harness.onMain { subject.onShake() }

    Thread.sleep(1_000L)
    assertThat(harness.onMain { harness.player.isPlaying }).isFalse
    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
  }

  @Test
  fun aShakeBeforeTheDeadlinePushesItOutWithoutPausing() {
    // The primary use of the affordance, and the one every other shake test here misses: the
    // listener is still awake, hears the fade start, and shakes the phone *before* the timer fires.
    val harness = startBook()
    // Two seconds of fade, for the reason `extendingPushesTheDeadlineOut` records.
    val subject = timer(harness, fadeMs = 2_000L)

    harness.onMain { subject.start(SleepTimerRequest.Duration(COUNTDOWN_MS)) }
    Thread.sleep(COUNTDOWN_MS - 800L)
    val remainingBeforeTheShake = (subject.state.value as SleepTimerState.Running).remainingMs
    // Same turn as the shake, for the reason `extendingPushesTheDeadlineOut` records.
    val (faded, restored) = harness.onMain {
      val faded = harness.player.volume
      subject.onShake()
      faded to harness.player.volume
    }
    val remainingAfterTheShake = (subject.state.value as SleepTimerState.Running).remainingMs

    // Read with no wait: `extend` republishes synchronously, so this cannot be satisfied by
    // playback reaching anything on its own.
    assertThat(remainingAfterTheShake - remainingBeforeTheShake)
      .describedAs("one shake buys EXTENSION_MS more, from %d ms remaining", remainingBeforeTheShake)
      .isBetween(SleepTimerController.EXTENSION_MS - 500L, SleepTimerController.EXTENSION_MS + 500L)
    assertThat(faded)
      .describedAs("mid-fade when the shake arrived, or the restore has nothing to restore")
      .isLessThan(FULL_VOLUME)
    assertThat(restored)
      .describedAs("the volume the listener hears between the shake and the next tick")
      .isEqualTo(FULL_VOLUME)
    Thread.sleep(1_500L)
    assertThat(harness.onMain { harness.player.isPlaying })
      .describedAs("past the original deadline, still playing").isTrue
  }

  @Test
  fun aShakeExtendsAnEndOfChapterTimerByMediaRatherThanByWallClock() {
    // The other arm of `extend`'s countdown, and it is a different unit: "five more minutes" of a
    // chapter timer is five more minutes of the **book**, so the stop position moves, not a clock.
    val harness = startBook()
    val subject = timer(harness)

    val (before, after) = harness.onMain {
      val stopAt = harness.player.currentPosition + AHEAD_MS
      subject.start(SleepTimerRequest.UntilPosition(book.id, stopAt))
      val before = (subject.state.value as SleepTimerState.Running).remainingMs
      subject.onShake()
      before to (subject.state.value as SleepTimerState.Running).remainingMs
    }
    harness.onMain { subject.cancel() }

    assertThat(after - before)
      .describedAs("media milliseconds added to an end-of-chapter timer by one shake")
      .isBetween(SleepTimerController.EXTENSION_MS - 500L, SleepTimerController.EXTENSION_MS + 500L)
  }

  @Test
  fun aTimerWhosePositionHasAlreadyGoneByReportsZeroAndFires() {
    // Reachable from a caller that computed a chapter end and then took a moment: the mark is
    // behind the playhead by the time the timer starts. The countdown on screen must be zero rather
    // than a negative number, and it must fire rather than count backwards forever.
    val harness = startBook()
    val subject = timer(harness)

    val published = harness.onMain {
      subject.start(SleepTimerRequest.UntilPosition(book.id, positionMs = 0L))
      subject.state.value
    }

    assertThat((published as SleepTimerState.Running).remainingMs).isZero
    harness.await("playback to pause", timeoutMs = FIRE_TIMEOUT_MS) { !harness.player.isPlaying }
    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
  }

  @Test
  fun anEndOfChapterTimerOnAnEmptyQueueFiresRatherThanHanging() {
    // `currentMediaItem` is null with nothing queued, so the file the request named is not the file
    // playing -- which is the same answer as "the queue moved on", and must not be a countdown that
    // never ends.
    val harness = startQueue(emptyList())
    val subject = timer(harness)

    harness.onMain { subject.start(SleepTimerRequest.UntilPosition(book.id, 15_000L)) }

    harness.await("the timer to give up", timeoutMs = FIRE_TIMEOUT_MS) {
      subject.state.value == SleepTimerState.Off
    }
  }

  @Test
  fun theConstructorHiltUsesCarriesTheShippedFadeLength() {
    // The `@Inject` constructor is the one production takes and no other test here calls it. What
    // it is observed *doing* is choosing `SleepTimerFade.DEFAULT_FADE_MS`: 25 s left is not fading,
    // 15 s left is, and only a 20 s fade puts the boundary between them.
    val harness = startBook()
    val subject = SleepTimerController(clock)
      .also { controller -> harness.onMain { controller.attach(harness.player, scope) } }

    val outside = harness.onMain {
      subject.start(SleepTimerRequest.Duration(25_000L))
      subject.state.value as SleepTimerState.Running
    }
    val inside = harness.onMain {
      subject.start(SleepTimerRequest.Duration(15_000L))
      subject.state.value as SleepTimerState.Running
    }
    harness.onMain { subject.cancel() }

    assertThat(outside.isFading).describedAs("25 s left against the shipped 20 s fade").isFalse
    assertThat(inside.isFading).describedAs("15 s left against the shipped 20 s fade").isTrue
  }

  @Test
  fun aShakeWithNoTimerRunningIsIgnored() {
    // Nobody asked for a sleep timer, so the accelerometer must not be able to start one -- and a
    // shake must certainly not pause or resume anything. The third arm of `onShake`'s guard.
    val harness = startBook()
    val subject = timer(harness)

    harness.onMain { subject.onShake() }

    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
    assertThat(harness.onMain { harness.player.isPlaying }).isTrue
  }

  @Test
  fun endOfChapterPausesAtThePositionRatherThanAtAWallClockTime() {
    // Second Book's third chapter ends at 15 000 ms. Started from 12 000, that is three seconds of
    // media -- and the assertion is on the POSITION, so a timer that happened to fire after three
    // seconds of wall clock for the wrong reason would still have to land in the right place.
    val harness = startBook()
    harness.onMain { harness.player.seekTo(12_000L) }
    harness.awaitPositionAtLeast(12_100L, timeoutMs = PLAYBACK_TIMEOUT_MS)
    val subject = timer(harness)

    harness.onMain {
      subject.start(SleepTimerRequest.UntilPosition(book.id, positionMs = 15_000L))
    }

    harness.await("playback to pause at the chapter end", timeoutMs = FIRE_TIMEOUT_MS) {
      !harness.player.isPlaying
    }
    assertThat(harness.onMain { harness.player.currentPosition }).isBetween(14_500L, 16_000L)
    assertThat(harness.onMain { harness.player.volume }).isEqualTo(FULL_VOLUME)
  }

  @Test
  fun anEndOfChapterTimerEndsWhenTheQueueMovesOnToAnotherFile() {
    // A ripped book is many files, and "until the end of this chapter" names a position *in one of
    // them*. Without the media-id guard the timer keeps counting toward that millisecond mark in
    // whatever file is playing next -- so a four-second Part One would hand a 60-second deadline to
    // Part Two and the timer would never fire at all.
    val harness = startQueue(parts)
    val subject = timer(harness)

    harness.onMain {
      subject.start(SleepTimerRequest.UntilPosition(parts[0].id, positionMs = 60_000L))
    }
    // Still playing while the named file is: the position it was given is past this file's end.
    Thread.sleep(1_000L)
    assertThat(harness.onMain { harness.player.isPlaying })
      .describedAs("a position past the end of the current file must not fire early").isTrue

    harness.await("playback to pause once the queue has moved on", timeoutMs = FIRE_TIMEOUT_MS) {
      !harness.player.isPlaying
    }
    val (state, position, mediaId) = harness.onMain {
      Triple(
        harness.player.playbackState,
        harness.player.currentPosition,
        harness.player.currentMediaItem?.mediaId,
      )
    }

    // **These three are what make the wait above non-vacuous, and that is measured rather than
    // argued.** Part One and Part Two together run ten seconds, so "playback stopped" is satisfied
    // by the queue simply *ending* -- and it was: with the media-id guard removed, this test stayed
    // green while `anEndOfChapterTimerOnAnEmptyQueueFiresRatherThanHanging` went red. A timer that
    // kept counting into the next file reaches `STATE_ENDED` at Part Two's own end, six seconds in;
    // the guard pauses on the first tick after the transition, still `STATE_READY`, a fraction of a
    // second in.
    assertThat(state)
      .describedAs("STATE_READY (%d), not STATE_ENDED (%d)", Player.STATE_READY, Player.STATE_ENDED)
      .isEqualTo(Player.STATE_READY)
    assertThat(position)
      .describedAs("how far into Part Two the timer let playback get")
      .isLessThan(PART_TRANSITION_MS)
    assertThat(mediaId)
      .describedAs("the queue really did transition, so this is the guard and not an early fire")
      .isEqualTo(parts[1].id)
    assertThat(harness.onMain { harness.player.volume }).isEqualTo(FULL_VOLUME)
  }

  @Test
  fun theRemainingTimeIsWallClockAndTheSpeedIsTheDivisor() {
    // The one assertion that can see `remainingMs`'s `/ speed`. Note what that division does NOT
    // move: an "until this position" timer fires at the same *position* whatever the speed, so a
    // test asserting only where playback stopped is green against its removal. What it moves is the
    // countdown the UI shows and when the ramp starts -- four seconds of media at 2x is two
    // seconds of wall clock, and the fade is a wall-clock ramp.
    //
    // Read with no wait at all: `start` publishes synchronously, so this is an observation that is
    // true immediately rather than one playback could satisfy on its own.
    val harness = startBook()
    val subject = timer(harness)

    val atNormalSpeed = harness.onMain {
      harness.player.playbackParameters = PlaybackParameters(1.0f)
      subject.start(
        SleepTimerRequest.UntilPosition(book.id, harness.player.currentPosition + AHEAD_MS),
      )
      (subject.state.value as SleepTimerState.Running).remainingMs
    }
    val atDoubleSpeed = harness.onMain {
      harness.player.playbackParameters = PlaybackParameters(2.0f)
      subject.start(
        SleepTimerRequest.UntilPosition(book.id, harness.player.currentPosition + AHEAD_MS),
      )
      (subject.state.value as SleepTimerState.Running).remainingMs
    }
    harness.onMain { subject.cancel() }

    // Two speeds, one distance, two answers -- so the divisor is observed at more than one value.
    assertThat(atNormalSpeed)
      .describedAs("wall-clock milliseconds to %d ms of media at 1.0x", AHEAD_MS)
      .isBetween(AHEAD_MS - SPEED_TOLERANCE_MS, AHEAD_MS + SPEED_TOLERANCE_MS)
    assertThat(atDoubleSpeed)
      .describedAs("wall-clock milliseconds to %d ms of media at 2.0x", AHEAD_MS)
      .isBetween(AHEAD_MS / 2 - SPEED_TOLERANCE_MS, AHEAD_MS / 2 + SPEED_TOLERANCE_MS)
  }

  @Test
  fun theStateCountsDownAndThenGoesOff() {
    val harness = startBook()
    val subject = timer(harness)

    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
    harness.onMain { subject.start(SleepTimerRequest.Duration(COUNTDOWN_MS)) }

    val first = (subject.state.value as SleepTimerState.Running).remainingMs
    Thread.sleep(1_200L)
    val second = (subject.state.value as SleepTimerState.Running).remainingMs

    // Two readings, strictly decreasing. A state holding a constant "remaining" satisfies a single
    // reading, and the countdown on screen would never move.
    assertThat(second).isLessThan(first)
    harness.await("the timer to go off", timeoutMs = FIRE_TIMEOUT_MS) {
      subject.state.value == SleepTimerState.Off
    }
  }

  @Test
  fun theEndOfChapterFlagIsCarriedSoTheUiCanSayWhichKindItIs() {
    val harness = startBook()
    val subject = timer(harness)

    harness.onMain { subject.start(SleepTimerRequest.Duration(60_000L)) }
    val duration = subject.state.value as SleepTimerState.Running
    assertThat(duration.untilEndOfChapter).isFalse
    // 60 s left against a 1 s fade: not fading, which is the other value of that field.
    assertThat(duration.isFading).isFalse

    harness.onMain { subject.start(SleepTimerRequest.UntilPosition(book.id, 20_000L)) }
    assertThat((subject.state.value as SleepTimerState.Running).untilEndOfChapter).isTrue
    harness.onMain { subject.cancel() }
  }

  @Test
  fun detachingStopsTheCountdownAndRestoresTheVolume() {
    // What `MuPlaybackService.onDestroy` owes the next process: a player released mid-fade is a
    // player whose volume was never put back, and `player.volume` outlives the timer object.
    val harness = startBook()
    val subject = timer(harness, fadeMs = 2_000L)

    harness.onMain { subject.start(SleepTimerRequest.Duration(2_500L)) }
    harness.await("the fade to start", timeoutMs = FIRE_TIMEOUT_MS) { harness.player.volume < 0.9f }
    harness.onMain { subject.detach() }
    Thread.sleep(2_000L)

    assertThat(harness.onMain { harness.player.volume }).isEqualTo(FULL_VOLUME)
    assertThat(harness.onMain { harness.player.isPlaying })
      .describedAs("a detached timer must not still be able to pause the player").isTrue
    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
  }

  @Test
  fun aCountdownInFlightFollowsTheHandoverToTheNewActivePlayer() {
    // `MuPlaybackService`'s `activePlayer` collector calls `attach` again when audio moves to a
    // speaker, and this is why it has to: a countdown left bound to the outgoing player ramps a
    // phone nobody is listening to down to zero and pauses something already paused, while the
    // speaker plays all night. Nothing throws and nothing logs, which is why it needs a test rather
    // than a comment.
    //
    // Two real Media3 players, the second standing in for the cast one. Audio focus is
    // `MuPlayerFactory`'s and is not this test's subject: whichever of them holds it, the two
    // assertions below are about `volume`, which focus does not touch.
    val outgoing = startBook()
    val incoming = startQueue(listOf(book))
    val subject = timer(outgoing, fadeMs = 2_000L)

    outgoing.onMain { subject.start(SleepTimerRequest.Duration(2_500L)) }
    outgoing.await("the fade to start", timeoutMs = FIRE_TIMEOUT_MS) {
      outgoing.player.volume < 0.9f
    }
    // Read in the **same main-thread turn** as the handover, for the reason
    // `extendingPushesTheDeadlineOut` records: `begin` restarts the ticker with `launch`, so a
    // separate turn would land after a tick had already written a volume.
    val (fadedBefore, restoredOnOutgoing) = incoming.onMain {
      val faded = outgoing.player.volume
      subject.attach(incoming.player, scope)
      faded to outgoing.player.volume
    }

    assertThat(fadedBefore)
      .describedAs("mid-fade when the handover arrived, or the restore has nothing to restore")
      .isLessThan(FULL_VOLUME)
    assertThat(restoredOnOutgoing)
      .describedAs("the player the listener is no longer hearing must not be left part-faded")
      .isEqualTo(FULL_VOLUME)
    // The ramp moved: it is the **incoming** player being faded now.
    incoming.await("the fade to reach the incoming player", timeoutMs = FIRE_TIMEOUT_MS) {
      incoming.player.volume < 0.9f
    }
    // ...and it is the incoming player the timer pauses.
    incoming.await("the incoming player to pause", timeoutMs = FIRE_TIMEOUT_MS) {
      !incoming.player.playWhenReady
    }
    assertThat(outgoing.onMain { outgoing.player.volume })
      .describedAs("a timer that has moved on must never touch the outgoing player again")
      .isEqualTo(FULL_VOLUME)
  }

  @Test
  fun aTimerWithNoPlayerAttachedIsInertRatherThanACrash() {
    // `MuPlaybackService` builds this before it builds the player, and a `@Singleton` outlives the
    // session it was attached to. Every entry point has to survive being called with nothing there.
    val subject = SleepTimerController(clock, fadeMs = 1_000L, graceMs = 500L)

    subject.start(SleepTimerRequest.Duration(1_000L))
    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
    subject.onShake()
    subject.extend()
    subject.cancel()
    subject.detach()

    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
  }

  @Test
  fun thePresetsTheUiOffersAreMinutesRatherThanSeconds() {
    // What is actually gated here is the **unit**. `5 * 1_000` and `5 * 60_000` are one character
    // apart, both compile, and a five-second sleep timer offered as "5 minutes" is a defect no
    // other test in this plan can see -- Task 9 reads this list straight onto a screen.
    assertThat(SleepTimerController.PRESETS.map { it / 60_000L })
      .describedAs("the presets, in whole minutes")
      .containsExactly(5L, 10L, 15L, 30L, 45L, 60L)
    assertThat(SleepTimerController.PRESETS.first()).isEqualTo(300_000L)
    // ...and the two timings a caller inherits when it does not choose, in the same unit.
    assertThat(SleepTimerController.EXTENSION_MS).isEqualTo(5L * 60_000L)
    assertThat(SleepTimerController.GRACE_MS).isEqualTo(60_000L)
  }

  // ---- apparatus ------------------------------------------------------------------------------

  private fun timer(
    harness: PlayerHarness,
    fadeMs: Long = 1_000L,
    graceMs: Long = SleepTimerController.GRACE_MS,
  ): SleepTimerController =
    SleepTimerController(clock, fadeMs, graceMs)
      .also { controller -> harness.onMain { controller.attach(harness.player, scope) } }

  /** The book, playing, with at least half a second of real audio already behind it. */
  private fun startBook(): PlayerHarness = startQueue(listOf(book)).also {
    it.awaitPositionAtLeast(500L, timeoutMs = PLAYBACK_TIMEOUT_MS)
  }

  /** [songs] as a queue on the **shipping** player, playing, from the top. */
  @OptIn(UnstableApi::class)
  private fun startQueue(songs: List<Song>): PlayerHarness {
    val cache = MediaCache.create(context, File(cacheDir, "run-${System.nanoTime()}"))
    cleanups += { cache.release() }
    lateinit var harness: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness = PlayerHarness(
        MuPlayerFactory(
          context = context,
          dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), cache),
          loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
          resumePolicy = NeverResume,
        ).createExoPlayer(),
      )
      harnesses += harness
      harness.player.setMediaItems(songs.map(::itemFor))
      harness.player.prepare()
      harness.player.play()
    }
    return harness
  }

  private fun itemFor(song: Song): MediaItem =
    MediaItems.of(
      song = song,
      streamUri = RealTrackBytes.rawStreamUrl(song),
      artworkId = null,
      isAudiobook = true,
      format = StreamFormat.Raw,
    )

  private companion object {
    /** `ci/seed-fixtures.sh`'s longest book: 21 s, four chapters of unequal length. */
    const val BOOK_TITLE = "Second Book"

    /** The ripped, multi-file book: three mp3 parts of 4 s, 6 s and 5 s. */
    val PART_TITLES = listOf("Part One", "Part Two")

    const val COUNTDOWN_MS = 3_000L
    const val FIRE_TIMEOUT_MS = 15_000L
    const val PLAYBACK_TIMEOUT_MS = 30_000L
    const val RESUME_MS = 1_000L
    const val FULL_VOLUME = 1.0f

    /**
     * How far into the second file the multi-file timer may let playback get before it pauses.
     * The tick is 250 ms and Part Two runs six seconds, so this separates "paused at the
     * transition" from "played the whole next file".
     */
    const val PART_TRANSITION_MS = 2_000L

    /** How far ahead of the playhead the speed test puts its stop position. */
    const val AHEAD_MS = 8_000L

    /**
     * Room for the milliseconds that elapse between reading the position and publishing the state,
     * and for the fact that `currentPosition` advances while both happen. Far tighter than the
     * 4 000 ms that separates the two answers being compared.
     */
    const val SPEED_TOLERANCE_MS = 700L

    val MAIN_EXECUTOR = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
  }
}
