package app.muplay

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.muplay.database.CredentialStoreEntryPoint
import app.muplay.media.PlaybackConnection
import app.muplay.media.PlaybackEntryPoint
import app.muplay.media.PlaybackLauncher
import app.muplay.media.PlaybackNotification
import app.muplay.media.MuPlaybackService
import app.muplay.media.PlaybackQueue
import app.muplay.media.PlaybackState
import app.muplay.model.SleepTimerRequest
import app.muplay.model.SleepTimerState
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real service, the real session, a real `MediaController` over real IPC, and the real
 * notification the system is holding.
 *
 * Every assertion here is about something *observable from outside the app's own code*: a position
 * that moved, a notification the `NotificationManager` is actually holding, a title that changed
 * when the track changed. Asserting that a provider was configured, or that `play()` was called,
 * would pass against a service that renders silence.
 *
 * **In `:app`, not `:core:media`.** `MuPlaybackService` is `@AndroidEntryPoint`, which requires the
 * hosting `Application` to be `@HiltAndroidApp`. A library module's instrumented tests run in a
 * self-instrumenting APK whose application is the plain `android.app.Application`, so starting this
 * service from `:core:media`'s own `androidTest` fails at runtime with Hilt's *"must be attached to
 * an @HiltAndroidApp Application"*. The usual fix -- `HiltTestApplication` plus a custom
 * `testInstrumentationRunner` -- is unavailable: `configureKotlinAndroid` sets that runner for every
 * Android module in build-logic and `ConventionTest` forbids a module overriding it. Coverage still
 * lands on `:core:media`, because `Jacoco.kt`'s `mergedExecutionData` globs every project's `.ec`.
 *
 * **No stream URL and no cover-art URL is ever asserted whole.** Both carry a fresh auth token and
 * salt, and an AssertJ failure message prints the actual value it saw. [artworkEndpoint] strips the
 * query string before the value can reach any message; the stream URL is never read here at all.
 */
@RunWith(AndroidJUnit4::class)
class MuPlaybackServiceTest {

  /**
   * `POST_NOTIFICATIONS` is `dangerous` from API 33. Without the grant, the notification is
   * silently not posted and `getActiveNotifications()` returns an empty array -- a green-looking
   * nothing. Granted by a rule rather than by an `adb` step so it cannot be reordered away from
   * the test that needs it.
   *
   * **This rule cannot be shown to fire on the path CI uses, and saying so is the point.** Deleting
   * it and re-running left all seven tests green; measured on `muplay37`, AGP's own installer passes
   * `-g` (a plain `adb install -r` of the same APK leaves the permission `granted=false`, `-g`
   * leaves it `granted=true`), so every runtime permission in the merged manifest is already held by
   * the time the suite starts. The rule earns its line for the *other* way this suite gets run -- a
   * hand `adb install` plus `am instrument`, which is how it gets debugged -- and for the day AGP
   * stops doing that.
   *
   * What actually keeps a missing grant from reading as a product defect is [awaitNotification],
   * which asserts the permission is held before it waits, so the failure names the grant instead of
   * the service.
   */
  @get:Rule
  val notificationPermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

  private lateinit var context: Context
  private lateinit var connection: PlaybackConnection
  private lateinit var controller: MediaController
  private lateinit var songs: List<Song>

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()

    runBlocking {
      // Seeded here rather than inherited from whichever journey ran first: a test that depends on
      // another test having run is a test that fails alone, and this suite must not have a hidden
      // ordering. These are ci/navidrome.compose.yml's credentials.
      //
      // Written through the **real** singleton `CredentialStore` -- reached from the production
      // graph, not rebuilt over the same DataStore file, which would throw "there are multiple
      // DataStores active for the same file" in this very process.
      credentialStore().save(SubsonicCredentials(NAVIDROME_URL, USERNAME, PASSWORD))

      songs = SubsonicClient(SubsonicCredentials(NAVIDROME_URL, USERNAME, PASSWORD))
        .getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500)
        // mp3 only. Plan 3 Task 12 seeded a fourth music track, `Offset Track` -- thirty seconds of
        // Opus whose first ten are silent -- and sorted by title it lands at index 0, which is the
        // song most of this suite plays. Every queue assertion below is written against the
        // five-second CBR fixtures; a thirty-second one would turn a transition wait into a
        // thirty-second wait and a "did it play" check into a check against silence.
        // `TranscodeSeekJourneyTest` is where that file is played deliberately.
        .filter { it.suffix.equals("mp3", ignoreCase = true) }
        .sortedBy { it.title }
    }
    check(songs.size >= 2) { "the seeded music library must hold at least two tracks" }
    // The three metadata fields this suite reads must be pairwise different on the fixture, or a
    // `title`/`artist`/`albumTitle` swap in the state mapping would be invisible to every
    // assertion below. ci/seed-fixtures.sh writes "Track N" / "Test Artist" / "Test Album".
    check(setOf(songs[0].title, songs[0].artistName, songs[0].albumName).size == 3) {
      "the fixture's title, artist and album must differ, or a field swap cannot be detected"
    }

    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      connection = PlaybackConnection(context)
    }
    controller = runBlocking { connection.controller() }
  }

  /**
   * Guarded on `isInitialized`, because an un-guarded `@After` **replaces the real failure with its
   * own**. Both properties are assigned on the last two lines of [setUp], and `connection.controller()`
   * is a bind to a service another class may just have destroyed -- so a failure there leaves
   * `controller` unset and this method throwing
   * `UninitializedPropertyAccessException: lateinit property controller has not been initialized`,
   * which is then the **only** message in the report.
   *
   * Measured here on 2026-09-01, in a full ten-module emulator run:
   * `theSleepTimerTurnsOnTheShakeSensorThatMakesTheGestureReachable` reported exactly that and
   * nothing else, and the class was 18/18 when re-run alone. So the flake is order-dependent and
   * survives -- but whatever really went wrong in `setUp` was unreportable, which is the part that
   * cost time. `GaplessTest.tearDown` is the run that first put this rule in `CLAUDE.md`; this is
   * the same defect in `:app`.
   *
   * `connection.release()` still runs whenever there is a connection to release: a leaked binding is
   * what poisons the *next* class, so the guard must not skip it just because `controller` is unset.
   */
  @After
  fun tearDown() {
    if (!::connection.isInitialized) return
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      if (::controller.isInitialized) {
        controller.stop()
        controller.clearMediaItems()
      }
      connection.release()
    }
  }

  @Test
  fun aControllerCanConnectToTheServiceAndPlayRealAudio() {
    setQueueAndPlay(songs.take(1))

    // Not "playWhenReady is true": a position past a second of a five-second track is the only
    // observation here that a player rendering silence could not produce.
    awaitPositionAtLeast(1_000L)
    assertThat(onMain { controller.isPlaying }).isTrue
  }

  @Test
  fun theSystemHoldsAMediaNotificationWhosePropertiesFollowTheTrack() {
    setQueueAndPlay(listOf(songs[0]))
    awaitPositionAtLeast(1_000L)

    assertThat(awaitNotificationTitle(songs[0].title)).isEqualTo(songs[0].title)

    // The discriminating half: change the track and require the notification to follow. A title
    // that is a constant, or a notification posted once at startup and never updated, fails here
    // and passes every single-track assertion.
    setQueueAndPlay(listOf(songs[1]))
    awaitPositionAtLeast(1_000L)

    assertThat(awaitNotificationTitle(songs[1].title)).isEqualTo(songs[1].title)
    assertThat(songs[0].title).isNotEqualTo(songs[1].title)
  }

  @Test
  fun theNotificationIsOnThisAppsOwnChannelUnderThisAppsOwnId() {
    setQueueAndPlay(listOf(songs[0]))
    awaitPositionAtLeast(1_000L)

    val notification = awaitNotification()
    assertThat(notification.notification.channelId).isEqualTo(PlaybackNotification.CHANNEL_ID)
    // The other half of the identity `PlaybackNotification` declares. Media3's own default id is
    // not 1001, so this fails if the provider is left unconfigured -- and it is read off the
    // notification the system is holding, not off the constant that produced it.
    assertThat(notification.id).isEqualTo(PlaybackNotification.NOTIFICATION_ID)
    // A media notification with no actions is one a user cannot control from the lock screen.
    assertThat(notification.notification.actions).isNotEmpty
  }

  /**
   * The five commands a lock screen and a car head unit bind their buttons to, read off the session
   * over real IPC.
   *
   * **From the middle of a three-item queue, and that is a measurement rather than a preference.**
   * `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` and `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` are *position*
   * dependent: Media3 withdraws each one at the end of the timeline it points past. Asserted at
   * index 0, as this test was first written, `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` comes back
   * `false` and the failure looks like a session that narrowed its command set -- observed on
   * `muplay37`, and the reason the seek below exists. The middle of a queue is the one position
   * where all five must be available at once, so it is the position that asks the real question.
   */
  @Test
  fun theSessionOffersTheTransportCommandsALockScreenNeeds() {
    setQueueAndPlay(songs.take(3))
    awaitPositionAtLeast(500L)
    onMain { controller.seekToNextMediaItem() }
    awaitState("mediaId == ${songs[1].id}") { connection.state.value.mediaId == songs[1].id }

    val commands = onMain { controller.availableCommands }
    // The exact list, not `anyMatch`: an empty command set would make an `anyMatch` check
    // vacuously false and a badly-written `allMatch` vacuously true.
    assertThat(
      listOf(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
      ).map { commands.contains(it) },
    ).containsExactly(true, true, true, true, true)
  }

  @Test
  fun tappingTheNotificationHasSomewhereToGo() {
    setQueueAndPlay(listOf(songs[0]))
    awaitPositionAtLeast(500L)

    // A media notification with no content intent is one that does nothing when tapped -- a
    // defect a user reports as "the notification is broken" and a developer cannot reproduce from
    // a log.
    assertThat(awaitNotification().notification.contentIntent).isNotNull
  }

  /**
   * Every field of [app.muplay.media.PlaybackState] that carries information, observed at a value
   * that no *other* field of the same state could supply.
   *
   * That is the whole point of the `check` in [setUp] and of the two-item queue here: title, artist
   * and album are three different strings on the fixture, and a queue positioned at its first of
   * two items makes `hasNext` and `hasPrevious` differ. A mapping that read `artist` into `title`,
   * or `hasPreviousMediaItem()` into `hasNext`, passes a suite that only asserts each field is
   * non-null and fails here.
   */
  @Test
  fun everyPlaybackStateFieldReachesTheUiSideOfTheConnection() {
    val items = setQueueAndPlay(songs.take(2))
    awaitPositionAtLeast(1_000L)

    val state = connection.state.value
    assertThat(state.isPlaying).isTrue
    assertThat(state.isBuffering).isFalse
    assertThat(state.mediaId).isEqualTo(songs[0].id)
    assertThat(state.title).isEqualTo(songs[0].title)
    assertThat(state.artist).isEqualTo(songs[0].artistName)
    assertThat(state.albumTitle).isEqualTo(songs[0].albumName)
    // Redacted before it is asserted: a cover-art URL carries the same auth token and salt a
    // stream URL does, and an AssertJ failure message prints the value it saw.
    assertThat(artworkEndpoint(state.artworkUri))
      .isEqualTo(artworkEndpoint(items[0].mediaMetadata.artworkUri?.toString()))
    assertThat(state.positionMs).isGreaterThan(0L)
    // A real number, which is all this assertion can claim: on this fixture the extractor and the
    // mirror agree to within a few milliseconds, so it does not say *which* source supplied it.
    // [theDurationTheUiRendersIsTheExtractorsAndNotTheMetadatas] is the one that does.
    assertThat(state.durationMs).isGreaterThan(0L)
    // Two items, positioned at the first: the only arrangement in which these two disagree.
    assertThat(state.hasNext).isTrue
    assertThat(state.hasPrevious).isFalse
  }

  /**
   * The two fields Plan 4 Task 7 added, each observed at a value nothing else in the state could
   * have supplied -- the rule the test above is built on, applied to the two newcomers.
   *
   * `mediaType` is checked against `MEDIA_TYPE_MUSIC` while
   * [app.muplay.media.PlaybackState.NOTHING_PLAYING] carries `MEDIA_TYPE_MIXED`, so a connection
   * that published nothing at all cannot satisfy it -- which "is not an audiobook" alone would.
   *
   * `speed` is the more interesting half, and it is why this test exists rather than one more line
   * in the test above. The listener's speed control reaches the player through a `MediaController`
   * -- and, later, through a car and a watch -- so the value has to survive a real IPC round trip
   * in **both** directions: the command out, and the `EVENT_PLAYBACK_PARAMETERS_CHANGED` back.
   * `PlaybackConnection` refreshes on every `onEvents` and so needs no event list of its own; this
   * is what says so. Two readings, before and after, because 1.0 is also the value of a state that
   * was never populated.
   */
  @Test
  fun theMediaTypeAndTheChosenSpeedReachTheUiSideOfTheConnection() {
    setQueueAndPlay(songs.take(1))
    awaitPositionAtLeast(500L)

    assertThat(connection.state.value.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
    assertThat(connection.state.value.isAudiobook)
      .describedAs("a song is not an audiobook").isFalse

    val before = connection.state.value.speed
    onMain { controller.setPlaybackSpeed(FASTER) }
    try {
      awaitState("the chosen speed to reach the state") { connection.state.value.speed == FASTER }
      assertThat(listOf(before, connection.state.value.speed)).containsExactly(1.0f, FASTER)
    } finally {
      // Playback parameters are a property of the **player**, and this suite shares one service
      // across every test in it. Left at 1.5x, every later position assertion here would be
      // measuring a player running half again as fast -- which is the very leak
      // `BookSpeedController` exists to close, arriving inside the test suite instead of inside the
      // app. The controller resets it at the next item transition anyway; this makes the reset
      // this test's own responsibility rather than the next test's luck.
      onMain { controller.setPlaybackSpeed(1.0f) }
      awaitState("the speed to be back to normal") { connection.state.value.speed == 1.0f }
    }
  }

  /**
   * The mirror image of the pair above, after a real transport command over real IPC.
   *
   * `hasNext`/`hasPrevious` swapping orientation is what proves they are read from the player's
   * live timeline position rather than from anything fixed at the time the queue was set -- and it
   * proves the ticker keeps publishing after the connection's first `publish`.
   *
   * It deliberately does **not** also assert the title here, and the reason is a measured property
   * of `MediaController` worth writing down: `currentMediaItem` and `mediaMetadata` do not update
   * in the same instant. After `seekToNextMediaItem` there is a window -- observed on `muplay37`,
   * this test failed in it with *expected "Track 2" but was "Track 1"* -- in which the controller
   * has the new item but still the old combined metadata, so a `PlaybackState` sampled by the
   * ticker inside that window carries the new `mediaId` beside the previous `title`. That is
   * Media3's own behaviour and not something this connection can paper over without giving up
   * `mediaMetadata`, which is the field Media3's notification renders from and the field that
   * merges the stream's own tags. The window is shorter than one tick and a UI renders it as
   * nothing. That the title follows the track *at all* is asserted, discriminately, by
   * [theSystemHoldsAMediaNotificationWhosePropertiesFollowTheTrack] -- which polls for the change
   * rather than sampling once, and requires the two tracks' titles to differ.
   */
  @Test
  fun steppingToTheNextTrackFlipsBothEndsOfTheQueueState() {
    setQueueAndPlay(songs.take(2))
    awaitPositionAtLeast(500L)

    onMain { controller.seekToNextMediaItem() }
    awaitState("mediaId == ${songs[1].id}") { connection.state.value.mediaId == songs[1].id }

    // Read from the same snapshot as that `mediaId`: `publish` builds all three from one player
    // read, and all three come from the timeline position, so they cannot disagree with each other
    // the way `mediaMetadata` can disagree with `currentMediaItem`.
    val state = connection.state.value
    assertThat(state.mediaId).isEqualTo(songs[1].id)
    assertThat(state.hasNext).isFalse
    assertThat(state.hasPrevious).isTrue
  }

  /**
   * **A queue starts at the track its `startIndex` names, and the whole queue is still there.**
   *
   * The one defect in this plan that no JVM test can see, and it was live until this test existed:
   * `QueueRepository.mediaItems` returns the whole queue by contract, `startIndex` is an argument
   * to `setMediaItems(items, startIndex, positionMs)`, and until `PlaybackConnection.play` existed
   * nothing joined the two. Every queue silently started at track 1 — "play track 7 of this album"
   * played track 1, for every album, with a green repository suite. That is this project's recorded
   * "verified at a different layer than applied" class, and the layer that applies it is a real
   * player behind a real session, which is why this lives here and not in `:core:media`.
   *
   * ### The index is asserted before anything is waited for, and that is the whole test
   *
   * Written as *await until `state.mediaId == songs[1].id`*, this test was **green with
   * `setMediaItems(items, 0, 0L)`** — measured, not feared. The fixture tracks are five seconds
   * long and the wait allows thirty, so a queue that started at track 1 simply *played into* track
   * 2 and satisfied the assertion; the second half, at `startIndex = 2`, was satisfied ten seconds
   * in for the same reason. A wait is exactly the wrong instrument for an assertion about where
   * playback *began*.
   *
   * `setMediaItems` applies the index synchronously through `MediaController`'s own masking, so the
   * index is read back with no wait at all — drift cannot manufacture it — and only then is a
   * second of real audio awaited, after which the index must **still** be the one that was named.
   * That pair is what separates "started there" from "arrived there".
   *
   * **Two indices, so a hardcoded one satisfies neither.** Both mutations were measured, in
   * `PlaybackLauncher.play`: `setMediaItems(items, 0, 0L)` fails on the first index read
   * (`expected:<1> but was:<0>`), and
   * `mediaItems()` returning `queue.songs.drop(queue.startIndex)` — the exact misreading
   * `QueueRepositoryTest` warns about — lands on index 1 of a two-item queue and fails on the id
   * there (`expected:<"lVRD…"> but was:<"nMjR…">`), with `mediaItemCount` a second, independent
   * observation behind it.
   */
  @Test
  fun aQueueStartsPlayingAtTheTrackItsStartIndexNames() {
    check(songs.size >= 3) { "this test needs three seeded tracks, found ${songs.size}" }
    // Three distinct server ids, or an index assertion could coincide with the wrong item.
    assertThat(setOf(songs[0].id, songs[1].id, songs[2].id)).hasSize(3)

    setQueueAndPlay(songs.take(3), startIndex = 1)

    // No wait: this is where playback *began*.
    assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(1)
    assertThat(onMain { controller.currentMediaItem?.mediaId }).isEqualTo(songs[1].id)
    assertThat(onMain { controller.mediaItemCount }).isEqualTo(3)

    // ...and a second of real audio later it is still that item, now genuinely rendering.
    awaitPositionAtLeast(1_000L)
    assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(1)
    assertThat(connection.state.value.mediaId).isEqualTo(songs[1].id)
    assertThat(connection.state.value.hasPrevious).isTrue
    assertThat(connection.state.value.hasNext).isTrue

    // The second observation, at the far end of the same queue. `hasNext` flips, which is what
    // proves the player is genuinely positioned there rather than reporting a remembered index.
    setQueueAndPlay(songs.take(3), startIndex = 2)

    assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(2)
    assertThat(onMain { controller.currentMediaItem?.mediaId }).isEqualTo(songs[2].id)
    assertThat(onMain { controller.mediaItemCount }).isEqualTo(3)

    awaitPositionAtLeast(1_000L)
    assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(2)
    assertThat(connection.state.value.mediaId).isEqualTo(songs[2].id)
    assertThat(connection.state.value.hasPrevious).isTrue
    assertThat(connection.state.value.hasNext).isFalse
  }

  /**
   * Asking to play **nothing** leaves what is already playing alone.
   *
   * `launchQueue`'s empty arm, driven where it is applied. `PlaybackLauncherTest` (JVM) pins the
   * decision — an empty list yields `null` rather than `PlaybackQueue`'s own
   * `IllegalArgumentException`, because "play this album" against songs the mirror has not
   * delivered yet is an ordinary race and not a programming error — but the decision and its
   * consequence are two different claims, and this is the one a user feels: the tap does nothing
   * instead of stopping the music that was playing. Nothing else in the project reaches that arm
   * with a real session behind it, which is why `PlaybackLauncher` measured 1/2 BRANCH until this.
   */
  @Test
  fun askingToPlayNoSongsAtAllLeavesTheCurrentQueueAlone() {
    setQueueAndPlay(songs.take(1))
    awaitPositionAtLeast(500L)

    runBlocking { PlaybackLauncher(queueRepository(), connection).play(emptyList(), 0) }

    // Both halves matter: a launcher that called `setMediaItems(emptyList(), ..)` would report 0
    // here, and one that threw would never reach either assertion.
    assertThat(onMain { controller.mediaItemCount }).isEqualTo(1)
    assertThat(connection.state.value.mediaId).isEqualTo(songs[0].id)
    assertThat(onMain { controller.isPlaying }).isTrue
  }

  /**
   * One controller per connection, however many times it is asked for.
   *
   * Not a triviality: `controller()` is what every screen in `:feature:player` will call, and a
   * connection that built a second `MediaController` per caller would leave the first one bound,
   * its listener still publishing into the same `StateFlow`, and the service unable to stop because
   * something is still connected to it. Asserted by identity, not by equality.
   */
  @Test
  fun askingTheConnectionForItsControllerTwiceReturnsTheSameOne() {
    val again = runBlocking { connection.controller() }

    assertThat(again).isSameAs(controller)
  }

  /**
   * Releasing a connection that never connected must be a no-op, not a crash.
   *
   * This is the shape a UI produces on its own: a screen is created, its connection starts, the
   * user leaves before the service binds, and the screen releases. Nothing here can be reached by
   * any test that connects first.
   */
  @Test
  fun releasingAConnectionThatNeverConnectedIsSafeAndLeavesNothingPlaying() {
    var fresh: PlaybackConnection? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      fresh = PlaybackConnection(context).also { it.release() }
    }

    assertThat(checkNotNull(fresh).state.value).isEqualTo(PlaybackState.NOTHING_PLAYING)
  }

  /**
   * The service can be stopped and comes back with a working session.
   *
   * `onDestroy` releases the player and the session, and it is the one place in this class where a
   * leak would be silent: an unreleased `ExoPlayer` keeps its audio focus, its codecs and its
   * loading thread, and the symptom is a second player fighting the first one over the output --
   * which is only visible once a session has been created twice in one process. That is what this
   * does.
   */
  @Test
  fun theServiceCanBeStoppedAndComesBackWithAWorkingSession() {
    setQueueAndPlay(listOf(songs[0]))
    awaitPositionAtLeast(1_000L)

    onMain {
      controller.stop()
      controller.clearMediaItems()
      connection.release()
      // The application's **own** singleton connection, not this test's. `:feature:player`'s
      // `PlayerViewModel` binds it behind the mini player and never releases it, so from the first
      // journey that composes a screen it holds a `MediaController` bound to this service for the
      // rest of the process -- and a bound service cannot be destroyed, which makes `stopService`
      // below a no-op and this test's premise false. Measured: with `BrowseJourneyTest` ahead of it
      // in the same run, `onDestroy` was never reached and `MuPlaybackService` LINE fell from 29/31
      // to 22/31, failing its floor, while this test stayed green.
      appPlaybackConnection().release()
    }
    context.stopService(Intent(context, MuPlaybackService::class.java))

    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      connection = PlaybackConnection(context)
    }
    controller = runBlocking { connection.controller() }
    setQueueAndPlay(listOf(songs[1]))

    // A position that moved, on the second instance of the service, is the observation a leaked or
    // half-released player could not produce.
    awaitPositionAtLeast(1_000L)
    assertThat(connection.state.value.mediaId).isEqualTo(songs[1].id)
  }

  /**
   * Two callers asking for the controller **at the same time** get the same one.
   *
   * [askingTheConnectionForItsControllerTwiceReturnsTheSameOne] asks twice in sequence, and that is
   * structurally unable to see this: `connect()` suspends, which releases the main thread, so the
   * defect only exists in the window between one caller starting a connection and that connection
   * completing. `PlaybackConnection` is a `@Singleton`, and a mini-player and a now-playing screen
   * starting together is the ordinary case that opens it.
   *
   * What went wrong before the fix was not "two objects are returned". It was that the second one
   * overwrote the field, so the first stayed bound for the life of the process: the service could
   * not stop, the orphan's `Player.Listener` kept publishing into the same `StateFlow`, and
   * `release()` could only ever release one of them.
   *
   * **The interleaving is forced, not hoped for.** Both coroutines are started from the main thread
   * with `CoroutineStart.UNDISPATCHED`, so each runs up to its first suspension immediately -- which
   * is `controller()`'s own `withContext(mainDispatcher)` posting to the main looper. The two bodies
   * are therefore queued in order, and the second runs while the first is suspended inside
   * `MediaController.Builder.buildAsync()`, which needs a real service bind and cannot complete
   * within one main-loop task.
   */
  @Test
  fun twoConcurrentCallersShareOneController() {
    val fresh = newConnection()
    try {
      lateinit var first: Deferred<MediaController>
      lateinit var second: Deferred<MediaController>
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        first = mainScope.async(start = CoroutineStart.UNDISPATCHED) { fresh.controller() }
        second = mainScope.async(start = CoroutineStart.UNDISPATCHED) { fresh.controller() }
      }

      val (a, b) = runBlocking { withTimeout(TIMEOUT_MS) { first.await() to second.await() } }
      // By identity. Two `MediaController`s over the same session are equal by nothing this
      // assertion could use, and the defect is precisely that there are two objects.
      assertThat(a).isSameAs(b)
    } finally {
      InstrumentationRegistry.getInstrumentation().runOnMainSync { fresh.release() }
    }
  }

  /**
   * Releasing while a connection is still being built leaves nothing behind that a later caller can
   * be handed.
   *
   * The same root cause as the test above, on the other side of the suspension point: a
   * `controller()` suspended inside `connect()` when `release()` runs used to resume afterwards,
   * assign the field, and start a fresh ticker -- on a `MediaController` that `release()` had
   * already handed to `MediaController.releaseFuture`. The visible result is the assertion here: the
   * *next* caller gets that dead controller, because the field had been re-populated behind the
   * release.
   *
   * The ordering is forced the same way the test above forces its own. After `runOnMainSync` has
   * started the connect, a second, empty `runOnMainSync` cannot run until the connect's body has run
   * and suspended, so the `release()` in the third one is guaranteed to land mid-connection.
   */
  @Test
  fun releasingDuringAnInFlightConnectLeavesNoDeadControllerBehind() {
    val fresh = newConnection()
    try {
      lateinit var pending: Deferred<MediaController>
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        pending = mainScope.async(start = CoroutineStart.UNDISPATCHED) { fresh.controller() }
      }
      // Runs after the coroutine's own posted body, which is where `buildAsync()` is called.
      InstrumentationRegistry.getInstrumentation().runOnMainSync { }
      InstrumentationRegistry.getInstrumentation().runOnMainSync { fresh.release() }

      // Let the released attempt settle whichever way it settles: cancelled (correct) or completed
      // with a controller (the defect). `runCatching`, because the correct answer is a thrown
      // `CancellationException` and this is not the assertion.
      runCatching { runBlocking { withTimeout(TIMEOUT_MS) { pending.await() } } }

      val after = runBlocking { withTimeout(TIMEOUT_MS) { fresh.controller() } }
      assertThat(onMain { after.isConnected })
        .describedAs("a connection asked for after a release must hand back a live controller")
        .isTrue
    } finally {
      InstrumentationRegistry.getInstrumentation().runOnMainSync { fresh.release() }
    }
  }

  /**
   * The duration the UI renders is the **player's**, not the mirror's, when the two disagree.
   *
   * `PlaybackState.durationMsOf` takes `playerDurationMs` and `metadataDurationMs` -- two `Long?`s,
   * so swapping them at the call site in `PlaybackConnection.publish` compiles, and
   * `PlaybackStateTest` cannot see it: it drives the function, not the call. Every other assertion
   * in this suite is blind to it too, because on this fixture the extractor and the mirror agree to
   * within a few milliseconds and both are "greater than zero".
   *
   * So the fixture is made to disagree. The item is rebuilt with a metadata duration no five-second
   * track could have, and the state has to carry the extractor's answer instead. With the two
   * arguments swapped this reports [WRONG_METADATA_DURATION_MS].
   *
   * Both premises are asserted rather than assumed: that the tampered value really did reach the
   * controller's combined metadata, and that the extractor really did produce a duration of its own
   * (if it had not, `player.duration` would be `C.TIME_UNSET`, the metadata would legitimately win,
   * and this test would be discriminating nothing).
   */
  @Test
  fun theDurationTheUiRendersIsTheExtractorsAndNotTheMetadatas() {
    val real = runBlocking { queueRepository().mediaItems(PlaybackQueue.of(listOf(songs[0]))) }
    val tampered = real[0].buildUpon()
      .setMediaMetadata(
        real[0].mediaMetadata.buildUpon().setDurationMs(WRONG_METADATA_DURATION_MS).build(),
      )
      .build()

    // The one place in this file that still drives the controller by hand rather than through
    // [setQueueAndPlay], and it has to: the whole test is about an item whose metadata does not
    // match what `QueueRepository` would build, so it cannot come from the production path that
    // builds items. Nothing about `startIndex` is being exercised here -- that is
    // [setQueueAndPlay]'s subject and its own tests'.
    onMain {
      controller.setMediaItems(listOf(tampered), 0, 0L)
      controller.prepare()
      controller.play()
    }
    awaitPositionAtLeast(1_000L)

    // Premise 1: the tamper took. `Player.getMediaMetadata()` is the *combined* metadata, and the
    // item's own values win over the stream's -- so if this were not the case the test below would
    // be comparing the extractor's answer with itself.
    assertThat(onMain { controller.mediaMetadata.durationMs })
      .describedAs("the tampered metadata duration must be what the session reports")
      .isEqualTo(WRONG_METADATA_DURATION_MS)

    // Premise 2: the extractor has an answer at all.
    val playerDuration = onMain { controller.duration }
    assertThat(playerDuration)
      .describedAs("without an extractor duration the metadata legitimately wins and this proves nothing")
      .isNotEqualTo(C.TIME_UNSET)
      .isGreaterThan(0L)

    assertThat(connection.state.value.durationMs)
      .describedAs("the player measured what is playing; the metadata is what the server said")
      .isEqualTo(playerDuration)
  }

  /**
   * The sleep timer the **app** injects, against the player the **service** built.
   *
   * This is the only shape of test that could have caught what it was written for.
   * `SleepTimerController` shipped with a full device suite of its own, a countdown, a fade, a
   * grace window and thirteen green tests -- every one of which handed it a player itself. Nothing
   * in any `src/main` ever called `attach`, so `attachment` was null in the running app and both
   * `start` and `extend` opened with `val attached = attachment ?: return`. The listener set a
   * timer, the screen acknowledged it, and playback never stopped. A test that supplies the
   * dependency production forgets to supply cannot see that; this one asks the real graph for the
   * real singleton and calls the method the book screen's view model calls.
   *
   * **`playWhenReady == false`, not `!isPlaying`, and that is the whole discrimination.** The music
   * fixtures are five seconds long, so "playback stopped" is something a queue reaches on its own
   * -- and CLAUDE.md's own rule about five-second fixtures says to prefer an observation the
   * fixture cannot produce. A queue that simply runs out ends at `STATE_ENDED` with `playWhenReady`
   * still `true`. Only something calling `pause()` puts it to `false`, and the only thing here that
   * could is the timer. The `STATE_READY` assertion afterwards closes the other half: the pause
   * landed inside the queue rather than at the end of it.
   *
   * The state read is taken **in the same main-thread turn as `start`** and asserted with no wait,
   * for the reason the same file's other timing tests record: `start` publishes synchronously when
   * it has a player and returns without touching `_state` when it does not, so `Running` here is
   * true immediately or never.
   */
  @Test
  fun theSleepTimerTheAppInjectsStopsTheServicesPlayback() {
    // Three tracks, so the queue outlives the timer by a comfortable margin and the pause below
    // cannot be the fixture running out.
    setQueueAndPlay(songs.take(3))
    awaitPositionAtLeast(500L)
    val timer = sleepTimerController()

    val published = onMain {
      timer.start(SleepTimerRequest.Duration(SLEEP_TIMER_MS))
      timer.state.value
    }

    try {
      assertThat(published)
        .describedAs(
          "`start` on the singleton the book screen holds, with the service's own player attached",
        )
        .isInstanceOf(SleepTimerState.Running::class.java)
      // The control: a timer that fired the instant it was set would satisfy everything below.
      Thread.sleep(SLEEP_TIMER_MS / 2)
      assertThat(onMain { controller.playWhenReady })
        .describedAs("still asked to play halfway through a %d ms timer", SLEEP_TIMER_MS)
        .isTrue
      awaitState("playWhenReady == false") { !onMain { controller.playWhenReady } }
      assertThat(onMain { controller.playbackState })
        .describedAs(
          "STATE_READY (%d), not STATE_ENDED (%d): the timer paused a queue that had not run out",
          Player.STATE_READY,
          Player.STATE_ENDED,
        )
        .isEqualTo(Player.STATE_READY)
      assertThat(timer.state.value).isEqualTo(SleepTimerState.Off)
    } finally {
      // The controller is a process-wide `@Singleton`: a countdown left running here would pause
      // whichever test runs next. `cancel` also puts the faded volume back.
      onMain { timer.cancel() }
    }
  }

  /**
   * Starts [items] through **`PlaybackLauncher`, the production entry point**, and hands back the
   * `MediaItem`s built for them.
   *
   * Not a hand-rolled `setMediaItems(items, 0, 0L); prepare(); play()` -- which is what stood here
   * until Plan 3 Task 6, and is precisely how `startIndex` came to be applied nowhere: a helper
   * that passes a literal `0` is a second copy of the production sequence that agrees with it on
   * the only case it ever exercises, so no assertion in this file could see the real one being
   * wrong. `PlaybackLauncher` is documented as *"the one way anything in this app starts playing
   * something"*, and this is the only suite in the project that can run it: every line of it needs
   * a `MediaController` bound to a real `MuPlaybackService`, which only an `@HiltAndroidApp`
   * application can start.
   *
   * Constructed over **this test's own** [connection] rather than reached through Hilt, for the
   * reason the connection itself is built by hand here: the singleton is shared with the running
   * app and cannot be released between tests.
   *
   * The items are re-derived rather than captured from inside `play`, which costs one more
   * `mediaItems` call and keeps the launcher's signature free of a test-shaped return value. Both
   * derivations produce the same ids and the same endpoints; only the auth salt differs, and
   * nothing here asserts on one.
   */
  /**
   * The shake gesture is **reachable at all**, which until Plan 8 it was not.
   *
   * `ShakeSensor` and `ShakeDetector` shipped complete: a threshold in g, a peak window, an
   * idempotent `start`, seventeen tests between them, all green. And `ShakeSensor` was injected by
   * nothing -- `start` had no caller in any `src/main` -- so `SleepTimerController.onShake` had no
   * route to a real jolt and the affordance did not exist for a user. That is the same defect, one
   * seam further out, as `theSleepTimerTheAppInjectsStopsTheServicesPlayback` above was written
   * for, and it is invisible to every test that turns the sensor on itself.
   *
   * So this asks the real graph for the real singleton and never touches `start`: the only thing
   * that may register the listener is `MuPlaybackService`'s own collector. Delete that collector
   * and this fails at the `awaitState` below.
   *
   * **What it deliberately does not assert is the sensor going off again**, and that is a property
   * of the design rather than a gap in the test. The service keeps listening for
   * `SleepTimerController.GRACE_MS` -- a minute -- after a timer ends, because waking up *just
   * after* the audio stopped is the ordinary case the grace window exists for. Waiting that out on
   * a shared emulator would cost a minute of the device lock to observe a `delay`.
   *
   * The same minute is why the sensor is stopped **before** the precondition is read rather than
   * asserted to be off: an earlier class in this process may have left a timer's grace tail
   * running, and "it happens to be off right now" is exactly the order-dependent premise this
   * suite's own notes warn about. The transition is what this test is about.
   */
  @Test
  fun theSleepTimerTurnsOnTheShakeSensorThatMakesTheGestureReachable() {
    setQueueAndPlay(songs.take(3))
    awaitPositionAtLeast(500L)
    val timer = sleepTimerController()
    val sensor = shakeSensor()

    // Every read and write on the main thread, which is where the service's collector runs: the
    // sensor's listener field is a plain `var`, and the main looper is what orders this against
    // it.
    onMain { sensor.stop() }
    assertThat(onMain { sensor.isListening })
      .describedAs("with no timer set and the sensor just stopped")
      .isFalse

    try {
      onMain { timer.start(SleepTimerRequest.Duration(SLEEP_TIMER_MS)) }
      awaitState("the shake sensor to be listening while a timer runs") {
        onMain { sensor.isListening }
      }
    } finally {
      // The controller and the sensor are both process-wide `@Singleton`s: a countdown left
      // running would pause whichever test runs next, and a registered accelerometer would wake
      // the CPU for the rest of the run.
      onMain { timer.cancel() }
      onMain { sensor.stop() }
    }
  }

  private fun setQueueAndPlay(items: List<Song>, startIndex: Int = 0): List<MediaItem> {
    runBlocking { PlaybackLauncher(queueRepository(), connection).play(items, startIndex) }
    return runBlocking { queueRepository().mediaItems(PlaybackQueue.of(items, startIndex)) }
  }

  /** The scheme, host and path of a cover-art URL, with the authenticated query string removed. */
  private fun artworkEndpoint(uri: String?): String? = uri?.substringBefore("?")

  /**
   * A `PlaybackConnection` of its own, built on the main thread because it captures a `Looper`.
   *
   * The three tests above cannot use [connection]: it is already connected by [setUp], and every one
   * of them is about what happens *while* a connection is being made.
   */
  private fun newConnection(): PlaybackConnection {
    lateinit var fresh: PlaybackConnection
    InstrumentationRegistry.getInstrumentation().runOnMainSync { fresh = PlaybackConnection(context) }
    return fresh
  }

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

  private fun awaitPositionAtLeast(positionMs: Long) {
    val deadline = System.currentTimeMillis() + TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      if (onMain { controller.currentPosition } >= positionMs) return
      Thread.sleep(POLL_MS)
    }
    throw AssertionError(
      "position never reached ${positionMs}ms; " +
        "state=${onMain { controller.playbackState }} isPlaying=${onMain { controller.isPlaying }} " +
        "error=${onMain { controller.playerError }}",
    )
  }

  private fun awaitState(description: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(POLL_MS)
    }
    throw AssertionError("the published state never satisfied $description")
  }

  private fun awaitNotification(): StatusBarNotification {
    // Checked, not assumed, and it can fail: a plain `adb install -r` of this APK leaves
    // POST_NOTIFICATIONS denied (measured), and every notification assertion below would then be
    // waiting on an array the system keeps empty for a reason that has nothing to do with this
    // service. Assert the precondition where it is cheap to name.
    assertThat(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
      .describedAs("POST_NOTIFICATIONS must be held or activeNotifications is empty regardless")
      .isEqualTo(PackageManager.PERMISSION_GRANTED)
    val manager = context.getSystemService(NotificationManager::class.java)
    val deadline = System.currentTimeMillis() + TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      manager.activeNotifications.firstOrNull { it.packageName == context.packageName }
        ?.let { return it }
      Thread.sleep(POLL_MS)
    }
    // Thrown, never `null`: without the `GrantPermissionRule` above, `activeNotifications` is an
    // empty array and a helper that returned null would let every notification assertion pass on
    // nothing. This message is what that failure has to look like.
    throw AssertionError("no notification was ever posted by ${context.packageName}")
  }

  /**
   * The notification's title once it reaches [expected], or the last title actually seen.
   *
   * Returning the last observed value rather than throwing on timeout is deliberate: the caller
   * asserts equality, so a notification whose title never followed the track fails as
   * *"expected Track 2 but was Track 1"* -- which names the defect -- rather than as a timeout,
   * which names nothing.
   */
  private fun awaitNotificationTitle(expected: String): String? {
    val deadline = System.currentTimeMillis() + TIMEOUT_MS
    var last: String? = null
    while (System.currentTimeMillis() < deadline) {
      last = awaitNotification().notification.extras
        .getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
      if (last == expected) return last
      Thread.sleep(POLL_MS)
    }
    return last
  }

  /**
   * The **production** singleton `QueueRepository`, reached without `@HiltAndroidTest`.
   *
   * `MuPlayApplication` is `@HiltAndroidApp`, so `EntryPointAccessors.fromApplication` hands back
   * the real object the service and the UI use. The entry point interfaces are declared in their
   * own modules' **`src/debug/`** source sets, not here: Hilt aggregates `@InstallIn` from a
   * variant's main *compilation*, so an `@EntryPoint` declared in this `androidTest` source set is
   * not part of the running application's generated `SingletonComponent` at all -- but a build-type
   * source set is part of that compilation, and the instrumented tests run the debug variant. Same
   * placement as `SyncWatermarkEntryPoint` and `LibraryRepositoryEntryPoint`, which this suite's
   * neighbours use, and as `app/src/debug/kotlin/app/muplay/di/CleartextPolicyModule.kt`.
   * `ConventionTest`'s `every Hilt entry point is declared in a debug source set` keeps all four
   * out of `src/main/`, where each was public API of its own module.
   */
  private fun queueRepository() =
    EntryPointAccessors.fromApplication(context, PlaybackEntryPoint::class.java).queueRepository()

  private fun appPlaybackConnection() =
    EntryPointAccessors.fromApplication(context, PlaybackEntryPoint::class.java).playbackConnection()

  private fun sleepTimerController() =
    EntryPointAccessors.fromApplication(context, PlaybackEntryPoint::class.java)
      .sleepTimerController()

  private fun shakeSensor() =
    EntryPointAccessors.fromApplication(context, PlaybackEntryPoint::class.java).shakeSensor()

  private fun credentialStore() =
    EntryPointAccessors.fromApplication(context, CredentialStoreEntryPoint::class.java)
      .credentialStore()

  private companion object {
    /** Reached from inside the emulator via `adb reverse tcp:4533 tcp:4533` -- ci/prepare-emulator.sh. */
    const val NAVIDROME_URL = "http://localhost:4533"
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"
    const val MUSIC_LIBRARY_ID = 1
    const val TIMEOUT_MS = 30_000L

    /**
     * The speed the listener chooses. Not 1.0, and not a value any other field of the state
     * carries, so a connection publishing a default cannot satisfy the assertion that reads it.
     */
    const val FASTER = 1.5f
    const val POLL_MS = 50L

    /**
     * The countdown the wiring test sets. Long enough that the halfway control is a real reading
     * on a loaded emulator, short enough to sit well inside a three-track queue of five-second
     * fixtures.
     */
    const val SLEEP_TIMER_MS = 4_000L

    /**
     * Sixteen and a half minutes, on a five-second fixture track.
     *
     * Far enough from any real duration that a failure reads as "the metadata won" rather than as a
     * rounding difference, and it is the value
     * [theDurationTheUiRendersIsTheExtractorsAndNotTheMetadatas] would report if
     * `PlaybackConnection.publish` passed `durationMsOf`'s two arguments the wrong way round.
     */
    const val WRONG_METADATA_DURATION_MS = 999_000L
  }

  /**
   * A scope on the main `Looper`, for the two tests that have to start a coroutine *from* the main
   * thread so that its first suspension lands on the main queue in a known order.
   *
   * Built from a `Handler` rather than from `Dispatchers.Main` for the reason `PlaybackConnection`
   * itself gives: `Dispatchers.Main` lives in `kotlinx-coroutines-android`, which nothing in this
   * build declares.
   */
  private val mainScope = CoroutineScope(
    SupervisorJob() +
      Executor { command -> Handler(Looper.getMainLooper()).post(command) }.asCoroutineDispatcher(),
  )
}
