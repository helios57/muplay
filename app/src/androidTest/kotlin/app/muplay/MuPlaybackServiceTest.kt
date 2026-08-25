package app.muplay

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.muplay.database.CredentialStoreEntryPoint
import app.muplay.media.PlaybackConnection
import app.muplay.media.PlaybackEntryPoint
import app.muplay.media.PlaybackNotification
import app.muplay.media.MuPlaybackService
import app.muplay.media.PlaybackQueue
import app.muplay.media.PlaybackState
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
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
      connection = PlaybackConnection(context, queueRepository())
    }
    controller = runBlocking { connection.controller() }
  }

  @After
  fun tearDown() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      controller.stop()
      controller.clearMediaItems()
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
    // Duration comes from the extractor, not from the mirror: proving it is a real number is
    // proving the container was actually parsed.
    assertThat(state.durationMs).isGreaterThan(0L)
    // Two items, positioned at the first: the only arrangement in which these two disagree.
    assertThat(state.hasNext).isTrue
    assertThat(state.hasPrevious).isFalse
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
   * **Two indices, so a hardcoded one satisfies neither**, and both ends of the queue are asserted
   * at each: a `mediaItems().drop(startIndex)` "fix" — the exact misreading `QueueRepositoryTest`
   * warns about — starts on the right *track* and fails on `mediaItemCount` and on `hasPrevious`.
   *
   * `songs` is sorted by title in [setUp], so `songs[1]` is genuinely the second track and the
   * mediaIds are three different server ids; a queue that ignored the index would have to coincide
   * with one of them to pass.
   */
  @Test
  fun aQueueStartsPlayingAtTheTrackItsStartIndexNames() {
    check(songs.size >= 3) { "this test needs three seeded tracks, found ${songs.size}" }

    setQueueAndPlay(songs.take(3), startIndex = 1)
    awaitState("mediaId == ${songs[1].id}") { connection.state.value.mediaId == songs[1].id }
    // Not merely the right id: a position past a second of real audio is what says the item the
    // index named is the one actually rendering.
    awaitPositionAtLeast(1_000L)

    assertThat(onMain { controller.mediaItemCount }).isEqualTo(3)
    assertThat(connection.state.value.hasPrevious).isTrue
    assertThat(connection.state.value.hasNext).isTrue

    // The second observation, at the far end of the same queue. `hasNext` flips, which is what
    // proves the player is genuinely positioned there rather than reporting a remembered index.
    setQueueAndPlay(songs.take(3), startIndex = 2)
    awaitState("mediaId == ${songs[2].id}") { connection.state.value.mediaId == songs[2].id }
    awaitPositionAtLeast(1_000L)

    assertThat(onMain { controller.mediaItemCount }).isEqualTo(3)
    assertThat(connection.state.value.hasPrevious).isTrue
    assertThat(connection.state.value.hasNext).isFalse
    assertThat(setOf(songs[0].id, songs[1].id, songs[2].id)).hasSize(3)
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
      fresh = PlaybackConnection(context, queueRepository()).also { it.release() }
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
    }
    context.stopService(Intent(context, MuPlaybackService::class.java))

    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      connection = PlaybackConnection(context, queueRepository())
    }
    controller = runBlocking { connection.controller() }
    setQueueAndPlay(listOf(songs[1]))

    // A position that moved, on the second instance of the service, is the observation a leaked or
    // half-released player could not produce.
    awaitPositionAtLeast(1_000L)
    assertThat(connection.state.value.mediaId).isEqualTo(songs[1].id)
  }

  /**
   * Starts [items] through the **production** path and hands back the `MediaItem`s built for them.
   *
   * `connection.play(queue)`, not a hand-rolled
   * `setMediaItems(items, 0, 0L); prepare(); play()` -- which is what stood here until Plan 3 Task
   * 6 and is precisely how `startIndex` came to be applied nowhere: a helper that passes a literal
   * `0` is a second copy of the production sequence that agrees with it on the only case it ever
   * exercises. The items are re-derived rather than captured from inside `play`, which costs one
   * more `mediaItems` call and keeps the connection's signature free of a test-shaped return value.
   */
  private fun setQueueAndPlay(items: List<Song>, startIndex: Int = 0): List<MediaItem> {
    val queue = PlaybackQueue.of(items, startIndex)
    runBlocking { connection.play(queue) }
    return runBlocking { queueRepository().mediaItems(queue) }
  }

  /** The scheme, host and path of a cover-art URL, with the authenticated query string removed. */
  private fun artworkEndpoint(uri: String?): String? = uri?.substringBefore("?")

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
   * own modules' `main` source sets, not here: Hilt aggregates `@InstallIn` from a variant's main
   * compilation only, so an `@EntryPoint` declared in this source set is not part of the running
   * application's generated `SingletonComponent` at all. Same reasoning as `SyncWatermarkEntryPoint`
   * and `LibraryRepositoryEntryPoint`, which this suite's neighbours already use.
   */
  private fun queueRepository() =
    EntryPointAccessors.fromApplication(context, PlaybackEntryPoint::class.java).queueRepository()

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
    const val POLL_MS = 50L
  }
}
