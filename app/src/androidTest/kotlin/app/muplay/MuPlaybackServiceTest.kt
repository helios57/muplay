package app.muplay

import android.Manifest
import android.app.NotificationManager
import android.content.Context
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
import app.muplay.media.PlaybackQueue
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
      connection = PlaybackConnection(context)
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

  private fun setQueueAndPlay(items: List<Song>): List<MediaItem> {
    val mediaItems = runBlocking { queueRepository().mediaItems(PlaybackQueue.of(items)) }
    onMain {
      controller.setMediaItems(mediaItems, 0, 0L)
      controller.prepare()
      controller.play()
    }
    return mediaItems
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
