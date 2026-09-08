package io.github.helios57.muplay

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.service.notification.StatusBarNotification
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.helios57.muplay.media.PlaybackConnection
import io.github.helios57.muplay.media.PlaybackEntryPoint
import io.github.helios57.muplay.media.PlaybackNotification
import io.github.helios57.muplay.media.PlaybackQueue
import io.github.helios57.muplay.model.SubsonicCredentials
import io.github.helios57.muplay.network.SubsonicClient
import dagger.hilt.android.EntryPointAccessors
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2: **audio actually plays**, on a real emulator, out of a real Navidrome.
 *
 * The distinction this whole class is built around: `play()` returning, `playWhenReady == true`,
 * `STATE_READY` and a session reporting `isPlaying` are all satisfied by a player that renders
 * silence, by a URL that 404s into a swallowed error, and by a decoder that never produced a
 * sample. Three observations are used instead, and none of them is a flag this app sets:
 *
 *  1. **The position readout on the real screen reaches a later time, and then a later one still.**
 *     While a track is playing ExoPlayer's position comes from `AudioTrack`'s own playback head —
 *     frames the audio sink has actually consumed — not from a wall clock started on `play()`. A
 *     readout that shows `0:01` and then `0:03` is three seconds of decoded PCM that reached the
 *     sink. A single "it is displayed" assertion cannot say that, and a constant, or a readout
 *     accidentally wired to the track's own duration, satisfies one value but never two in order.
 *  2. **The wall-clock interval between those two values is at least a second.** A counter
 *     free-running faster than real time, or a value that jumped, fails this and passes every
 *     "did the UI change" test.
 *  3. **The platform's own audio service says a music stream is active** — `AudioManager
 *     .isMusicActive()`, which reads AudioFlinger's record of started tracks rather than anything
 *     this app publishes. Asserted in **both** directions, true while playing and false once
 *     paused, so neither half can pass on a device that always answers the same way.
 *
 * Every test here starts real audio and waits real seconds for it. That is irreducible: there is
 * no way to prove three seconds of audio came out in less than three seconds.
 *
 * The seeded music tracks are **five seconds** each (`ci/seed-fixtures.sh`), so a queue left
 * playing rolls on to the next track every five seconds. Every wait below is written to be
 * reachable inside one such cycle, and [tearDown] stops playback so no test can measure the
 * previous one's audio.
 *
 * Preconditions this test cannot establish for itself — the container being up,
 * `adb reverse tcp:4533 tcp:4533`, and the emulator's `-feature Minigbm -prop
 * qemu.hardware.gralloc=minigbm` boot flags — are all handled by `ci/prepare-emulator.sh`, which
 * `.github/workflows/e2e.yml` runs and which a local run must run too. A missing `adb reverse`
 * does **not** fail loudly: the app's connect attempt times out silently, and this suite then dies
 * in [reachLibraryScreen]'s first `waitUntil`, naming nothing.
 *
 * **No stream URL and no cover-art URL is ever read or asserted here.** Both carry `u`, `s=salt`
 * and `t=md5(password+salt)`, and an AssertJ failure message prints the value it saw.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackJourneyTest {

  /**
   * Ordered before the activity launches. Without the grant the media notification is silently not
   * posted — `activeNotifications` is an empty array, not an error.
   *
   * As `MuPlaybackServiceTest` records, this rule cannot be shown to fire on the path CI uses
   * (AGP's installer passes `adb install -g`), so [awaitNotification] asserts the permission is
   * held before it waits: a missing grant then names itself instead of arriving as "the service
   * posted nothing".
   */
  @get:Rule(order = 0)
  val notificationPermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

  @get:Rule(order = 1)
  val composeRule = createAndroidComposeRule<MainActivity>()

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private val audioManager: AudioManager
    get() = context.getSystemService(AudioManager::class.java)

  private var connection: PlaybackConnection? = null

  /**
   * Leaves nothing playing.
   *
   * Not tidiness: these tests run in one instrumentation process, and a suite that measured "the
   * position advanced" against audio the *previous* test started would be the exact vacuous gate
   * this class exists to avoid.
   */
  @After
  fun tearDown() {
    val open = connection ?: onMain { PlaybackConnection(context, appArtworkUrls()) }
    connection = null
    // `controller()` hops to the main thread itself, so it must be called from *this* thread: a
    // `runBlocking` on the main thread would block the very Looper it is waiting on.
    val controller = runBlocking { open.controller() }
    onMain {
      controller.stop()
      controller.clearMediaItems()
      open.release()
    }
  }

  /**
   * The plan's headline claim, end to end: browse to a track, tap it, hear it.
   *
   * **Track 2, not Track 1**, and that is the whole point of the choice: `PlaybackLauncher` is
   * handed the tapped row's index and has to carry it through `PlaybackQueue.startIndex` into
   * `setMediaItems(items, startIndex, …)`. Any link in that chain replaced by a constant `0`
   * starts Track 1 — audibly wrong, and invisible to a journey that taps the first row.
   */
  @Test
  fun tappingATrackPlaysThatTrackAndTheAudioAdvances() {
    openTheMusicAlbum()
    playTrackNamed(MUSIC_TRACKS[1])

    // The tapped track, on the player screen. Not "something is playing".
    composeRule.onNodeWithText(MUSIC_TRACKS[1]).assertIsDisplayed()
    composeRule.onNodeWithText(ALBUM_ARTIST).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(ARTWORK_DESCRIPTION).assertIsDisplayed()

    // Observations 1 and 2: two different times, in order, no faster than the wall clock.
    awaitElapsedAtLeast(1)
    val atOneSecond = System.currentTimeMillis()
    awaitElapsedAtLeast(3)
    assertThat(System.currentTimeMillis() - atOneSecond)
      .describedAs("wall-clock milliseconds between the readout showing 0:01 and showing 0:03")
      .isGreaterThanOrEqualTo(ONE_SECOND_OF_AUDIO_MILLIS)

    // Observation 3: the platform's own audio service, not this app.
    assertThat(awaitMusicActive(true))
      .describedAs("AudioManager.isMusicActive() while a track is playing")
      .isTrue

    // The elapsed readout is the *left* one and it is not the total: a screen that rendered the
    // duration in both slots would satisfy every assertion above.
    val (elapsed, total) = timeReadouts()
    assertThat(secondsOf(elapsed))
      .describedAs("elapsed '$elapsed' against total '$total'")
      .isLessThan(secondsOf(total))
  }

  /**
   * The notification the system is holding while that audio plays — the surface a user reaches for
   * from the lock screen, and what keeps the service alive at all.
   */
  @Test
  fun theNotificationNamesTheTrackThatIsPlaying() {
    openTheMusicAlbum()
    playTrackNamed(MUSIC_TRACKS[1])
    awaitElapsedAtLeast(1)

    val notification = awaitNotification()
    assertThat(notification.notification.channelId).isEqualTo(PlaybackNotification.CHANNEL_ID)
    assertThat(notification.id).isEqualTo(PlaybackNotification.NOTIFICATION_ID)
    // The exact track that was tapped, not "one of the seeded titles": the three differ, so a
    // notification wired to the first item of the queue fails here.
    assertThat(awaitNotificationTitle(MUSIC_TRACKS[1])).isEqualTo(MUSIC_TRACKS[1])
    // A media notification with no actions is one a user cannot control from the lock screen; one
    // with no content intent does nothing when tapped.
    assertThat(notification.notification.actions).isNotEmpty
    assertThat(notification.notification.contentIntent).isNotNull
  }

  /**
   * The app's own controls, driving the real session — the hop from a `PlayerViewModel` built by
   * Hilt, through its `PlaybackControls` adapter, into a bound `MediaController`. Nothing below
   * `:feature:player`'s own device suite reaches it, because that suite deliberately composes over
   * a hand-written seam.
   *
   * Pause is asserted as **the clock stopping**, not as a label flipping: the readout is sampled,
   * left alone for longer than its own one-second granularity, and required to be unchanged — a
   * still-running player cannot produce that.
   *
   * The seek is performed **while paused**, and that is what makes it discriminate: against a
   * playing player the readout advances on its own, so "the number went up after a scrub" would
   * pass with the scrub wired to nothing.
   *
   * `Next` and `Previous` are asserted by the title changing in the right direction, with the
   * previous title required to be *gone* in between, so neither can be satisfied by a screen that
   * never changed.
   */
  @Test
  fun theOnScreenControlsDriveTheRealSession() {
    openTheMusicAlbum()
    playTrackNamed(MUSIC_TRACKS[1])
    awaitElapsedAtLeast(2)

    composeRule.onNodeWithContentDescription(PAUSE_LABEL).performClick()
    awaitControl(PLAY_LABEL)
    val whenPaused = awaitSettledElapsed()
    Thread.sleep(PAUSE_OBSERVATION_MILLIS)
    assertThat(timeReadouts().first)
      .describedAs("the elapsed readout ${PAUSE_OBSERVATION_MILLIS}ms after pausing")
      .isEqualTo(whenPaused)
    assertThat(awaitMusicActive(false))
      .describedAs("AudioManager.isMusicActive() once playback is paused")
      .isFalse
    assertThat(secondsOf(whenPaused))
      .describedAs("the paused position, which the seek below has to move away from")
      .isGreaterThan(0)

    // Back to the start of the track. `down(center)` rather than the left edge: a touch that
    // *starts* at the exact left edge of the slider's semantics bounds reaches none of its gesture
    // handlers, which `PlayerScreenTest` measured and records.
    composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
      .performTouchInput {
        down(center)
        moveTo(Offset(LEFT_EDGE_INSET_PX, center.y))
        up()
      }
    composeRule.waitUntil("the seek to move the position back to zero", TIMEOUT_MILLIS) {
      secondsOf(timeReadouts().first) == 0
    }

    composeRule.onNodeWithContentDescription(PLAY_LABEL).performClick()
    awaitControl(PAUSE_LABEL)
    // From a position this test put at zero, so reaching a second is the resume and nothing else.
    awaitElapsedAtLeast(1)
    assertThat(awaitMusicActive(true))
      .describedAs("AudioManager.isMusicActive() after resuming")
      .isTrue

    // Paused again **before** stepping, and that is a measurement rather than tidiness. The seeded
    // tracks are five seconds long, so a *playing* queue walks itself from Track 2 to Track 3 on
    // its own: with `next()` mutated to `seekToPreviousMediaItem()` this test was green, because
    // the wait for Track 3 was satisfied by the auto-advance a few seconds later. With the queue
    // frozen, the only thing that can change the track is the button.
    composeRule.onNodeWithContentDescription(PAUSE_LABEL).performClick()
    awaitControl(PLAY_LABEL)
    val frozenAt = timeReadouts().first

    // The middle of a three-track album is the one position where both ends are live.
    composeRule.onNodeWithContentDescription(NEXT_LABEL).assertIsEnabled().performClick()
    awaitLabel(MUSIC_TRACKS[2])
    composeRule.onNodeWithText(MUSIC_TRACKS[1]).assertDoesNotExist()
    composeRule.onNodeWithContentDescription(PREVIOUS_LABEL).assertIsEnabled().performClick()
    awaitLabel(MUSIC_TRACKS[1])
    composeRule.onNodeWithText(MUSIC_TRACKS[2]).assertDoesNotExist()
    // Still paused throughout, so neither step above can have been the queue advancing itself.
    composeRule.onNodeWithContentDescription(PLAY_LABEL).assertIsDisplayed()
    assertThat(secondsOf(timeReadouts().first))
      .describedAs("the position after two steps, from a queue frozen at '$frozenAt'")
      .isLessThanOrEqualTo(secondsOf(frozenAt))
  }

  /**
   * **"Try again" restarts a player Media3 has already given up on.**
   *
   * The defect this exists against is recorded in `PlayerScreen`'s own comment: a failed track used
   * to publish `isPlaying = false` and nothing else, so it was indistinguishable from a pause, and
   * the button a user then pressed was the ordinary play button. `PlaybackControls.retry` is a
   * member of its own so that the failure state gets an action that means "start this again".
   *
   * **What makes it discriminating is the third step, and it is deliberate.** A real, playable item
   * is put on the session *without* preparing it. A player in `STATE_IDLE` stays there until
   * something prepares it, so at that moment the queue is good, the screen still shows the failure,
   * and nothing is playing -- the only thing on the device that can turn that into audio is the
   * button. The assertions either side of the tap say both halves out loud: idle and silent before,
   * decoding real PCM after. **Falsified:** with `retry()`'s body emptied, this fails at
   * `ComposeTimeoutException: Condition ('Pause' to appear on screen) still not satisfied after
   * 30000 ms`.
   *
   * **What it does *not* pin is the `prepare()` call inside `retry()`, and that was measured rather
   * than assumed.** Deleting it -- leaving `retry()` as a bare `play()` -- keeps this test green.
   * `connection.controller()` hands back a `MediaController`, and a controller's `play()` reaches
   * the player through `MediaSessionImpl.handleMediaControllerPlayRequest`, which calls
   * `Util.handlePlayButtonAction`, which prepares an `STATE_IDLE` player itself before playing
   * (verified in `media3-common-1.11.0`'s bytecode, not inferred). So no journey through a session
   * can separate the two, here or anywhere else; see the note at the `prepare()` call for why the
   * line stays.
   *
   * The `PlaybackControls` implementation the `@Inject` constructor builds is reachable from this
   * class and nowhere else -- `:feature:player`'s own suite composes over a hand-built one on
   * purpose, so that adapter exists only inside Hilt's graph, and
   * [theOnScreenControlsDriveTheRealSession] above is what drives the rest of it. `retry` was the
   * one member of it nothing drove: when it was added, its five lines were the only ones in
   * `PlayerViewModel$1` no test executed, and the floor at `io.github.helios57.muplay.player.PlayerViewModel.1`
   * went from 10/10 to 10/15 and stayed red, unseen, because only a full instrumented coverage run
   * can measure it.
   *
   * The unreachable item's cache key is unique per run. `TrackIdCacheKeyFactory` throws on a
   * `DataSpec` with no key, so one has to be set; making it constant would let a previous run's
   * cached bytes satisfy the request this test needs to fail.
   */
  @Test
  fun tryAgainRestartsASessionMedia3HasMovedToIdle() {
    openTheMusicAlbum()
    playTrackNamed(MUSIC_TRACKS[1])
    awaitElapsedAtLeast(1)

    // Built before the session is broken: it reaches the network, and a failure there should read
    // as a fixture problem rather than as this test's subject.
    val playable = aRealPlayableItem()
    val controller = connectController()

    onMain {
      controller.setMediaItem(anUnreachableItem())
      controller.prepare()
    }
    awaitLabel(RETRY_LABEL)
    assertThat(awaitMusicActive(false))
      .describedAs("AudioManager.isMusicActive() once the item failed to load")
      .isFalse

    // The queue is good again and the player is not. No `prepare()` here, and that absence is the
    // whole assertion below it.
    onMain { controller.setMediaItem(playable) }
    awaitOnMain("the player to still be idle after the queue was replaced") {
      controller.playbackState == Player.STATE_IDLE
    }
    assertThat(awaitMusicActive(false))
      .describedAs("AudioManager.isMusicActive() with a good queue on an idle player")
      .isFalse
    composeRule.onAllNodesWithText(RETRY_LABEL).notTheMiniPlayer()
      .assertCountEquals(1)

    composeRule.onAllNodesWithText(RETRY_LABEL).notTheMiniPlayer()[0].performClick()

    awaitControl(PAUSE_LABEL)
    // From an item this test put on the session at position zero, so reaching a second is the
    // retry and nothing else.
    awaitElapsedAtLeast(1)
    assertThat(awaitMusicActive(true))
      .describedAs("AudioManager.isMusicActive() after 'Try again'")
      .isTrue
    composeRule.onAllNodesWithText(RETRY_LABEL).notTheMiniPlayer()
      .assertCountEquals(0)
  }

  /**
   * The lock screen, the headset button and Android Auto's play/pause all arrive the same way: as
   * a media button event the system routes to the active session. Driving a real
   * `KEYCODE_MEDIA_PLAY_PAUSE` through the shell exercises that whole path, and needs no UI
   * automation dependency.
   */
  @Test
  fun aMediaButtonFromTheSystemPausesAndResumesPlayback() {
    openTheMusicAlbum()
    playTrackNamed(MUSIC_TRACKS[1])
    awaitElapsedAtLeast(1)
    val controller = connectController()

    shell("input keyevent 85") // KEYCODE_MEDIA_PLAY_PAUSE
    awaitOnMain("playback to pause") { !controller.isPlaying }
    // Settled, for the reason `awaitSettledElapsed` records: `isPlaying` goes false a fraction of
    // a second before the position stops moving. Reading it raw here has never failed, and it is
    // the same race one layer down from the one that did.
    val pausedAt = awaitSettled("the paused position") { onMain { controller.currentPosition } }
    Thread.sleep(PAUSE_OBSERVATION_MILLIS)
    // Paused means the clock stopped, not merely that a flag flipped.
    assertThat(onMain { controller.currentPosition }).isEqualTo(pausedAt)
    assertThat(awaitMusicActive(false))
      .describedAs("AudioManager.isMusicActive() after a system media button paused playback")
      .isFalse
    // The app agrees with the system about what happened; otherwise the screen would go on showing
    // Pause over a stopped player.
    awaitControl(PLAY_LABEL)

    shell("input keyevent 85")
    awaitOnMain("playback to resume") { controller.isPlaying }
    awaitOnMain("the position to move past where it paused") {
      controller.currentPosition > pausedAt
    }
    assertThat(awaitMusicActive(true)).isTrue
    awaitControl(PAUSE_LABEL)
  }

  /**
   * Backgrounding. The app goes off screen and audio has to keep going — which is the entire
   * reason a `MediaLibraryService` with `foregroundServiceType="mediaPlayback"` and
   * `FOREGROUND_SERVICE_MEDIA_PLAYBACK` exists. A missing permission there fails no build and no
   * install; it throws `SecurityException` from `startForeground`.
   *
   * The position is read through a `MediaController` rather than off the screen, because there is
   * no screen — that is the point of the test. **The activity really having left the foreground is
   * asserted**, because without that this test would pass just as well against a `keyevent 3` that
   * did nothing at all.
   *
   * The track is rewound to zero first: the fixtures are five seconds long, so a three-second
   * observation started near the end would roll on to the next track and read as a position that
   * went *backwards*.
   */
  @Test
  fun playbackSurvivesTheAppGoingToTheBackground() {
    openTheMusicAlbum()
    playTrackNamed(MUSIC_TRACKS[1])
    awaitElapsedAtLeast(1)
    val controller = connectController()

    onMain { controller.seekTo(0L) }
    awaitOnMain("the track to rewind") { controller.currentPosition < REWOUND_MILLIS }
    shell("input keyevent 3") // KEYCODE_HOME
    awaitActivityBackgrounded()
    Thread.sleep(BACKGROUND_OBSERVATION_MILLIS)

    assertThat(onMain { controller.currentPosition })
      .describedAs("position after ${BACKGROUND_OBSERVATION_MILLIS}ms on the home screen")
      .isGreaterThan(BACKGROUND_OBSERVATION_MILLIS - ONE_SECOND_OF_AUDIO_MILLIS)
    assertThat(onMain { controller.isPlaying }).isTrue
    assertThat(awaitMusicActive(true))
      .describedAs("AudioManager.isMusicActive() with the app off screen")
      .isTrue
    // The foreground service is still up, which is what the notification the system holds means.
    assertThat(awaitNotification().notification.channelId).isEqualTo(PlaybackNotification.CHANNEL_ID)

    // Back to the foreground, so the rule tears the activity down from a resumed state and the
    // next test does not start behind the launcher.
    shell("am start -n $LAUNCHER_COMPONENT")
    awaitControl(PAUSE_LABEL)
  }

  /**
   * The headline feature, now that it can actually be *played*. Plan 2 proved a music shuffle's
   * result never contains the audiobook; this proves the thing coming out of the speaker never is
   * it either — and that the row the user tapped is the row that plays.
   *
   * Five attempts, not Plan 2's ten: each one starts real audio and costs real seconds.
   *
   * Playback is stopped at the top of every attempt. Without that, "the session reports a title"
   * is satisfied instantly by the *previous* attempt's track and the whole loop is one attempt
   * observed five times — the same shape Plan 2's journey recorded for its own shuffle heading.
   */
  @Test
  fun shufflingMusicAndPlayingItPlaysTheTappedRowAndNeverTheAudiobook() {
    composeRule.reachLibraryScreen()
    val controller = connectController()

    repeat(SHUFFLE_ATTEMPTS) { attempt ->
      onMain {
        controller.stop()
        controller.clearMediaItems()
      }
      awaitOnMain("the session to report nothing playing") { controller.mediaMetadata.title == null }

      // Re-selecting the library clears the previous shuffle; waiting for the heading to be *gone*
      // is what stops the next wait succeeding on its first poll against the previous draw.
      composeRule.onAllNodesWithText(MUSIC_LIBRARY)[LIBRARY_CHIP].performClick()
      composeRule.waitUntil("the previous shuffle to be cleared", TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isEmpty()
      }
      composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
      composeRule.waitUntil("a fresh shuffle to be drawn", TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isNotEmpty()
      }

      val (rowY, tapped) = shuffledRowToTap()
      clickRow(tapped, rowY)
      awaitControl(PAUSE_LABEL)
      awaitOnMain("the session to report what it is playing") {
        controller.mediaMetadata.title != null
      }

      // Not `assertDoesNotExist(AUDIOBOOK_TITLE)`: an empty or null title satisfies that
      // vacuously. What is playing must be one of the three music tracks **by name**, and it must
      // be the row that was tapped — which is also what proves the shuffle's start index is
      // carried through rather than replaced by a constant 0.
      assertThat(onMain { controller.mediaMetadata.title?.toString() })
        .describedAs("what is actually coming out of the speaker on attempt $attempt")
        .isIn(MUSIC_TRACKS)
        .isEqualTo(tapped)
      // ...and it really is coming out: a title on a session that decoded nothing is not playback.
      awaitElapsedAtLeast(1)

      Espresso.pressBack()
      awaitLabel(SHUFFLE_LABEL)
      // Back on the library screen the mini player names what is playing, which is the one control
      // that gets a user back to it from anywhere.
      composeRule.onNodeWithContentDescription(MINI_PLAYER_LABEL).assertIsDisplayed()
      composeRule.onAllNodesWithText(tapped).fetchSemanticsNodes().also {
        check(it.isNotEmpty()) { "the mini player did not name the track it is playing" }
      }
    }

    // The bar is a handle, not decoration: tapping it opens the player.
    composeRule.onNodeWithContentDescription(MINI_PLAYER_LABEL).performClick()
    composeRule.waitUntil("the mini player to open the full player", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithContentDescription(ARTWORK_DESCRIPTION).fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeRule.onNodeWithContentDescription(MINI_PLAYER_LABEL).assertDoesNotExist()
  }

  // ---- the walk -------------------------------------------------------------------------------

  /**
   * Opens the album named [MUSIC_ALBUM], found by **name**, never by list position.
   *
   * `onAllNodesWithText("Open")[0]` names whichever album the mirror happens to sort first, which
   * is a fixture-coupled way of saying "the one with Track 1 in it". Every album row carries an
   * identical `Open` button, so the row is identified by pairing that button with the album name
   * beside it: same `Row`, so the two are vertically centred on each other. The pairing is
   * `check`ed, which is what stops this silently opening the wrong album when the seeded library
   * grows.
   */
  private fun openTheMusicAlbum() {
    composeRule.reachLibraryScreen()
    // Explicit rather than relying on the default selection, so this walk does not depend on which
    // library a previously-run test left chosen.
    composeRule.onAllNodesWithText(MUSIC_LIBRARY)[LIBRARY_CHIP].performClick()
    composeRule.waitUntil("the album list to arrive from the mirror", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_ALBUM).notTheMiniPlayer().fetchSemanticsNodes()
        .isNotEmpty() &&
        composeRule.onAllNodesWithText(OPEN_LABEL).fetchSemanticsNodes().isNotEmpty()
    }

    val albumCentre = composeRule.onAllNodesWithText(MUSIC_ALBUM).notTheMiniPlayer()
      .fetchSemanticsNodes().first().boundsInRoot.center.y
    val distances = composeRule.onAllNodesWithText(OPEN_LABEL).fetchSemanticsNodes()
      .map { abs(it.boundsInRoot.center.y - albumCentre) }
    val onTheSameRow = distances.indices.minByOrNull { distances[it] }!!
    // Unambiguous, rather than merely nearest: the runner-up has to be a whole row further away.
    // A pixel tolerance was tried first and is the wrong tool -- it has to be re-guessed for every
    // density and every future row layout, and it was wrong on `muplay37` at the first attempt.
    val runnerUp = distances.filterIndexed { i, _ -> i != onTheSameRow }.minOrNull()
    check(runnerUp == null || runnerUp > distances[onTheSameRow] * ROW_SEPARATION_FACTOR) {
      "the Open button next to '$MUSIC_ALBUM' cannot be told from another album's " +
        "(distances $distances); this walk would open the wrong album"
    }
    composeRule.onAllNodesWithText(OPEN_LABEL)[onTheSameRow].performClick()

    composeRule.waitUntil("the album's tracks to be listed", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_TRACKS[0]).notTheMiniPlayer().fetchSemanticsNodes()
        .isNotEmpty()
    }
  }

  /**
   * Taps a track row on the album screen, which plays it and opens the player.
   *
   * It waits for the player *screen*, not for the `Pause` label, and that is a diagnosability
   * decision paid for by a measurement: with the stream URL mutated to a 404 this helper's
   * original wait-for-`Pause` was what timed out, so a run in which **no audio was decoded at all**
   * failed as a bare `ComposeTimeoutException` naming nothing. Waiting only for the transport row
   * to exist lets [awaitElapsedAtLeast] be the assertion that fails, and its message says exactly
   * which of the two things went wrong.
   */
  private fun playTrackNamed(title: String) {
    composeRule.onAllNodesWithText(title).notTheMiniPlayer()[0].performClick()
    composeRule.waitUntil("the player screen to open", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithContentDescription(PLAY_LABEL).fetchSemanticsNodes().isNotEmpty() ||
        composeRule.onAllNodesWithContentDescription(PAUSE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /** Blocks until [text] is on screen, naming what it was waiting for if it never arrives. */
  private fun awaitLabel(text: String) {
    composeRule.waitUntil("'$text' to appear on screen", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /**
   * [awaitLabel], for a control named by its `contentDescription`.
   *
   * The transport controls became icons in the design pass and their label constants did not move:
   * `Play`, `Pause`, `Next` and `Previous` are each control's `contentDescription` now rather than
   * its text, which is the same accessible name reached through the property a screen reader
   * actually reads for a graphic. Only the finder changed.
   */
  private fun awaitControl(description: String) {
    composeRule.waitUntil("'$description' to appear on screen", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /**
   * Every row of the shuffle result, top to bottom, as (y, title).
   *
   * **Structural, not a list of titles this test expects.** An earlier version gathered the nodes
   * matching the three seeded music titles, which silently renumbered the rows the moment anything
   * else could appear among them — so a scope leak would have moved the row this test taps rather
   * than failing the assertion that exists to catch it, and any change to the seeded library would
   * have broken the indexing rather than the claim. A row here is *whatever the screen put between
   * the `Shuffled` heading and the album list*: a clickable text node, which the search field, the
   * library chips and the three action buttons are not (they are all above the heading), and which
   * the out-of-scope warning is not (it has no click action).
   *
   * The mini player is excluded by name — its `Modifier.clickable` merges descendants, so the
   * playing track's title resolves to the bar node itself, and the bar is a clickable text node
   * below the heading like any row.
   */
  private fun shuffledRows(): List<Pair<Float, String>> {
    val headingY = composeRule.onNodeWithText(SHUFFLE_HEADING).fetchSemanticsNode().positionInRoot.y
    val albumListY = composeRule.onAllNodesWithText(OPEN_LABEL).fetchSemanticsNodes()
      .minOfOrNull { it.positionInRoot.y } ?: Float.MAX_VALUE
    return composeRule
      .onAllNodes(
        SemanticsMatcher("is a clickable text row that is not the mini player") { node ->
          node.config.contains(SemanticsActions.OnClick) &&
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty() &&
            node.config.getOrNull(SemanticsProperties.ContentDescription)
              ?.contains(MINI_PLAYER_LABEL) != true
        },
      )
      .fetchSemanticsNodes()
      .filter { it.positionInRoot.y > headingY && it.positionInRoot.y < albumListY }
      .map { it.positionInRoot.y to it.config[SemanticsProperties.Text].first().text }
      .sortedBy { it.first }
  }

  /**
   * The row at [SHUFFLED_ROW_TO_TAP], and its y so the click can find it again unambiguously.
   *
   * **Not the topmost row, and that is a measurement.** With `PlaybackLauncher`'s
   * `setMediaItems(items, queue.startIndex, 0L)` mutated to a constant `0`, a version of this test
   * that tapped the first row stayed green — the tapped index *was* zero, so the constant happened
   * to be right. Tapping the second row is what makes this journey able to fail on a start index
   * that never left the ViewModel: the same mutation now fails it with *expected "Track 3" but was
   * "Track 2"*.
   */
  private fun shuffledRowToTap(): Pair<Float, String> {
    val rows = shuffledRows()
    check(rows.size > SHUFFLED_ROW_TO_TAP) {
      "a music shuffle put ${rows.size} row(s) on screen; this test taps row $SHUFFLED_ROW_TO_TAP"
    }
    return rows[SHUFFLED_ROW_TO_TAP]
  }

  /**
   * Clicks the node carrying [text] that sits at [y], so two rows sharing a title cannot confuse
   * it. [y] came from [shuffledRows] a moment earlier and nothing has re-laid the screen out, so
   * the match is on the same coordinate rather than on a tolerance.
   */
  private fun clickRow(text: String, y: Float) {
    val matches = composeRule.onAllNodesWithText(text).notTheMiniPlayer()
    val index = matches.fetchSemanticsNodes().indexOfFirst { it.positionInRoot.y == y }
    check(index >= 0) { "the row '$text' at y=$y is no longer on screen" }
    matches[index].performClick()
  }

  // ---- observations ---------------------------------------------------------------------------

  /**
   * The player screen's two `m:ss` readouts, **left first**: elapsed, then total.
   *
   * By x coordinate rather than by finder order, so a screen that swapped them fails. Checked at
   * exactly two, so a change that removed one is a loud failure here rather than a silent
   * misreading downstream.
   */
  private fun timeReadouts(): Pair<String, String> {
    val readouts = composeRule
      .onAllNodes(
        SemanticsMatcher("is an m:ss readout") { node ->
          node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            .any { TIME_READOUT.matches(it.text) }
        },
        useUnmergedTree = true,
      )
      .fetchSemanticsNodes()
      .map { node ->
        node.positionInRoot.x to
          node.config[SemanticsProperties.Text].first { TIME_READOUT.matches(it.text) }.text
      }
      .sortedBy { it.first }
    check(readouts.size == 2) {
      "expected exactly two m:ss readouts on the player screen, found ${readouts.map { it.second }}"
    }
    return readouts[0].second to readouts[1].second
  }

  /**
   * The elapsed readout, once it has stopped moving.
   *
   * **Pausing does not stop the clock instantly, and the readout can tick once more afterwards.**
   * Measured here: a pause taken at about 2.9s published `0:02`, and 1.5s later the screen read
   * `0:03` on a player that had been genuinely stopped throughout. Two things add up to it --
   * `PlaybackConnection`'s ticker samples every 250ms, and the audio sink drains for up to a couple
   * of hundred milliseconds after `isPlaying` goes false (CLAUDE.md records the same delay from the
   * other side, as a `media_progress` row rewritten one tick after a pause). Neither is a defect;
   * together they move the position by a fraction of a second, which changes the *readout* only
   * when that fraction happens to cross a second boundary. So it fails about one run in five, and
   * the run it fails is the one where the pause landed near a whole second.
   *
   * Settling costs the caller nothing it needs: a **playing** player holds each readout for a full
   * second, so this returns just as promptly against one, and the freeze assertion after it is
   * exactly as sharp as it was.
   */
  private fun awaitSettledElapsed(): String =
    awaitSettled("the elapsed readout") { timeReadouts().first }

  /**
   * Polls [sample] until the same value comes back for [SETTLE_MILLIS], and returns it.
   *
   * Bounded, and loud when it runs out: a value that never settles is a finding, not a reason to
   * carry on with whatever the last sample happened to be.
   */
  private fun <T> awaitSettled(description: String, sample: () -> T): T {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    var last = sample()
    var stableSince = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(POLL_MILLIS)
      val now = sample()
      if (now != last) {
        last = now
        stableSince = System.currentTimeMillis()
      } else if (System.currentTimeMillis() - stableSince >= SETTLE_MILLIS) {
        return now
      }
    }
    throw AssertionError("$description never held still for ${SETTLE_MILLIS}ms; last saw '$last'")
  }

  private fun secondsOf(readout: String): Int =
    readout.split(":").map { it.toInt() }.fold(0) { total, part -> total * 60 + part }

  /**
   * Blocks until the elapsed readout on screen shows at least [seconds].
   *
   * This is the observation the whole plan rests on, and it is deliberately not `isPlaying`: the
   * value being waited for came off `AudioTrack`'s playback head, so reaching it means that many
   * seconds of decoded audio were consumed by the sink and then rendered on a real screen.
   */
  private fun awaitElapsedAtLeast(seconds: Int) {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    var last = ""
    while (System.currentTimeMillis() < deadline) {
      last = timeReadouts().first
      if (secondsOf(last) >= seconds) return
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
      "the elapsed readout never reached ${seconds}s; last saw '$last'. Either nothing was " +
        "decoded, or the position never reached the screen.",
    )
  }

  /**
   * The platform's own answer to "is a music stream playing", polled until it agrees with
   * [expected] or the deadline passes, then returned as it last was.
   *
   * Returned rather than asserted here so the caller's own `describedAs` names which direction
   * failed. Polled rather than sampled because `AudioTrack` does not stop the instant `pause()`
   * returns.
   */
  private fun awaitMusicActive(expected: Boolean): Boolean {
    val deadline = System.currentTimeMillis() + AUDIO_STATE_TIMEOUT_MILLIS
    var last = audioManager.isMusicActive
    while (System.currentTimeMillis() < deadline) {
      last = audioManager.isMusicActive
      if (last == expected) return last
      Thread.sleep(POLL_MILLIS)
    }
    return last
  }

  private fun awaitActivityBackgrounded() {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      if (composeRule.activityRule.scenario.state != Lifecycle.State.RESUMED) return
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("the activity never left the foreground, so nothing was backgrounded")
  }

  private fun awaitNotification(): StatusBarNotification {
    // Checked, not assumed, and it can fail: a plain `adb install -r` of this APK leaves
    // POST_NOTIFICATIONS denied (measured -- see `MuPlaybackServiceTest`), and every notification
    // assertion would then be waiting on an array the system keeps empty for a reason that has
    // nothing to do with this service.
    assertThat(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
      .describedAs("POST_NOTIFICATIONS must be held or activeNotifications is empty regardless")
      .isEqualTo(PackageManager.PERMISSION_GRANTED)
    val manager = context.getSystemService(NotificationManager::class.java)
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      manager.activeNotifications.firstOrNull { it.packageName == context.packageName }
        ?.let { return it }
      Thread.sleep(POLL_MILLIS)
    }
    // Thrown, never `null`: a helper that returned null would let every notification assertion
    // pass on nothing.
    throw AssertionError("no notification was ever posted by ${context.packageName}")
  }

  /**
   * The notification's title once it reaches [expected], or the last title actually seen — so a
   * notification whose title never followed the track fails as *"expected Track 2 but was
   * Track 1"*, which names the defect, rather than as a timeout, which names nothing.
   */
  private fun awaitNotificationTitle(expected: String): String? {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    var last: String? = null
    while (System.currentTimeMillis() < deadline) {
      last = awaitNotification().notification.extras
        .getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
      if (last == expected) return last
      Thread.sleep(POLL_MILLIS)
    }
    return last
  }

  // ---- plumbing -------------------------------------------------------------------------------

  /**
   * A `MediaController` bound to the app's own live session, for what a screen cannot be asked:
   * a position while the app is backgrounded, and the metadata the session is publishing.
   *
   * A second controller on the same session, exactly as `MuPlaybackServiceTest` does — Media3
   * supports many, and the app's own `PlaybackConnection` is a `@Singleton` this test has no entry
   * point to. Released in [tearDown].
   */
  private fun connectController(): MediaController {
    val open = connection ?: onMain { PlaybackConnection(context, appArtworkUrls()) }.also { connection = it }
    // From the test thread, never from inside `runOnMainSync`: `controller()` hops to the main
    // Looper itself and a `runBlocking` there would deadlock against it.
    return runBlocking { open.controller() }
  }

  /**
   * A `MediaItem` the app can really play, built through the **application's own**
   * [io.github.helios57.muplay.media.QueueRepository] -- stored credentials, one `SubsonicSource` per queue, the
   * real stream URL and the `muplay-art:` artwork URI.
   *
   * Not hand-assembled here, for the reason `PlaybackEntryPoint`'s own doc gives: an item this test
   * built would exercise `MediaItems.of` and not the chain that feeds the service in production.
   */
  private fun aRealPlayableItem(): MediaItem {
    val songs = runBlocking {
      SubsonicClient(SubsonicCredentials(NAVIDROME_URL, USERNAME, PASSWORD))
        .getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = RANDOM_SONGS_PAGE)
        // The five-second CBR fixtures only. `Offset Track` is thirty seconds of Opus whose first
        // ten are silent, so a queue starting there would reach neither the elapsed readout nor
        // `isMusicActive` inside this test's patience.
        .filter { it.suffix.equals("mp3", ignoreCase = true) }
        .sortedBy { it.title }
    }
    check(songs.isNotEmpty()) { "the seeded music library returned no mp3 fixture to retry onto" }
    val items = runBlocking { queueRepository().mediaItems(PlaybackQueue.of(listOf(songs[0]))) }
    return items.single()
  }

  /**
   * An item whose host answers nothing, so preparing it fails rather than hanging.
   *
   * Port 1 on the device's own loopback: privileged, unbound, and refused immediately by the
   * kernel. A URL on the real server that merely 404s would take the same path through
   * `StreamRetryPolicy` and cost the test its retries in wall clock.
   */
  private fun anUnreachableItem(): MediaItem =
    MediaItem.Builder()
      .setUri(UNREACHABLE_URI)
      // Required, not decorative: `TrackIdCacheKeyFactory.buildCacheKey` throws
      // `MissingCacheKeyException` on a `DataSpec` with no key rather than falling back to the URI,
      // so an item with none fails for a reason this test is not about.
      .setCustomCacheKey("p8-retry-${System.nanoTime()}")
      .build()

  private fun queueRepository() =
    EntryPointAccessors.fromApplication(context, PlaybackEntryPoint::class.java).queueRepository()

  private fun <T> onMain(block: () -> T): T {
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private fun awaitOnMain(description: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      if (onMain(condition)) return
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("timed out waiting for $description")
  }

  private fun shell(command: String) {
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
  }

  private companion object {
    // The literal strings the real screens render, duplicated from the production code rather than
    // shared with it: a journey is a black-box walk through what a user sees, and a shared
    // constant would let a wording change pass unnoticed. Same stance as Plan 2's journeys, and
    // `PlayerScreen.kt`'s own doc says so from the other side.
    const val SHUFFLE_LABEL = "Shuffle this library"
    const val SHUFFLE_HEADING = "Shuffled"
    const val OPEN_LABEL = "Open"
    const val PLAY_LABEL = "Play"
    const val PAUSE_LABEL = "Pause"
    const val NEXT_LABEL = "Next"
    const val PREVIOUS_LABEL = "Previous"
    const val ARTWORK_DESCRIPTION = "Cover art"
    const val MINI_PLAYER_LABEL = "Now playing"
    const val MUSIC_LIBRARY = "Music"

    /**
     * `Message`'s action label on the player screen, retyped rather than imported.
     *
     * `:feature:player` declares it `internal`, which is module-scoped in Kotlin and cannot cross a
     * Gradle module boundary at all -- and the retyping is the convention here anyway, for the
     * reason the block above gives. This one is anchored: the journey that names it asserts it is
     * **displayed** before it taps it, so a wording change goes red rather than quietly passing an
     * absence check.
     */
    const val RETRY_LABEL = "Try again"

    /** The seeded content, per `ci/seed-fixtures.sh` and `ci/configure-libraries.sh`. */
    /**
     * Every title the seeded **music** library can return, which is what a shuffle draws from.
     *
     * `Offset Track` is Plan 3 Task 12's Opus fixture and is deliberately last: the three indexed
     * reads below (`MUSIC_TRACKS[0]`, `[1]`, `[2]`) name tracks inside [MUSIC_ALBUM], and appending
     * rather than inserting keeps them naming the same three. What the fourth entry is for is
     * `aShuffleStartsOnTheRowThatWasTapped`, whose `isIn(MUSIC_TRACKS)` is the assertion that goes
     * red if the shuffle ever surfaces something this list does not know about.
     */
    val MUSIC_TRACKS = listOf("Track 1", "Track 2", "Track 3", "Offset Track")
    const val ALBUM_ARTIST = "Test Artist"
    const val MUSIC_ALBUM = "Test Album"

    /**
     * How much further away the runner-up has to be before a nearest-node pairing is trusted.
     * A ratio, not a pixel count, so it needs no re-measuring at another screen density.
     */
    const val ROW_SEPARATION_FACTOR = 2f

    const val LAUNCHER_COMPONENT = "io.github.helios57.muplay/io.github.helios57.muplay.MainActivity"

    const val LIBRARY_CHIP = 0

    /** Five, not Plan 2's ten: each attempt here starts real audio and costs real seconds. */
    const val SHUFFLE_ATTEMPTS = 5

    /**
     * The **second** row of the shuffle result, not the first. See [shuffledRowToTap]: with a
     * mutated launcher passing a constant start index of 0, tapping row 0 is green by coincidence.
     * The seeded music library holds three tracks, so this row always exists.
     */
    const val SHUFFLED_ROW_TO_TAP = 1

    /** `m:ss` or `h:mm:ss`, which is every shape `formatDuration` produces. */
    val TIME_READOUT = Regex("""\d+:\d\d(:\d\d)?""")

    /**
     * Longer than the readout's own one-second granularity, so a *running* clock is guaranteed to
     * have changed the string. Equality after this interval therefore means stopped, rather than
     * "sampled twice inside the same second".
     */
    const val PAUSE_OBSERVATION_MILLIS = 1_500L

    /**
     * How long a value has to hold still to count as settled.
     *
     * Comfortably past both of the delays `awaitSettledElapsed` names -- one 250ms ticker interval
     * and the couple of hundred milliseconds the sink takes to drain -- and comfortably short of
     * the one second a playing readout holds each value for, so it cannot be reached by a player
     * that is still running.
     */
    const val SETTLE_MILLIS = 500L

    /** Long enough that a service the system killed at HOME cannot fake the advance. */
    const val BACKGROUND_OBSERVATION_MILLIS = 3_000L

    /**
     * The floor on the wall-clock gap between the readout showing `0:01` and showing `0:03`.
     *
     * Exactly one second, and the arithmetic is why: `0:01` is shown for a true position in
     * [1s, 2s) and `0:03` for [3s, 4s), so the true interval is strictly greater than one second
     * however unluckily the two samples land. Anything advancing faster than real time — a
     * free-running counter, a value that jumped — fails.
     *
     * Reused as the slack on the backgrounded measurement, where it is one tick of the same clock.
     */
    const val ONE_SECOND_OF_AUDIO_MILLIS = 1_000L

    /** A rewind has landed once the position is inside the first quarter-second of the track. */
    const val REWOUND_MILLIS = 250L

    /** Not zero: `moveTo` at x = 0 is the edge case that reaches no gesture handler at all. */
    const val LEFT_EDGE_INSET_PX = 1f

    const val TIMEOUT_MILLIS = 30_000L

    /** `AudioTrack` does not stop the instant `pause()` returns, but it does not take seconds. */
    const val AUDIO_STATE_TIMEOUT_MILLIS = 5_000L
    const val POLL_MILLIS = 100L

    /** ci/navidrome.compose.yml's credentials, reached via `adb reverse tcp:4533 tcp:4533`. */
    const val NAVIDROME_URL = "http://localhost:4533"
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"
    const val MUSIC_LIBRARY_ID = 1

    /** Navidrome caps `getRandomSongs` at 500; asking for the cap returns the whole fixture set. */
    const val RANDOM_SONGS_PAGE = 500

    /**
     * A port on the device's own loopback that nothing binds. Privileged, so nothing in this
     * process could take it either.
     */
    const val UNREACHABLE_URI = "http://localhost:1/p8-retry.mp3"
  }
}
