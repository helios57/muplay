package app.muplay.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import app.muplay.database.dao.MediaProgressDao
import dagger.hilt.android.AndroidEntryPoint
import java.time.Clock
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel

/**
 * The service that owns playback.
 *
 * A `MediaLibraryService`, per spec section 7, rather than a bare `MediaSessionService`: it is a
 * strict subclass, so the notification, media-button routing and foreground lifecycle are
 * identical, and Plan 5's Android Auto browse tree does not require changing a base class
 * underneath a live session, a live notification and a live `MediaController`.
 *
 * **The browse tree is deliberately not implemented here.** `MediaLibrarySession.Callback`'s
 * defaults answer "not supported" for `onGetLibraryRoot` and `onGetChildren`, and that is the
 * truthful answer today. Overriding them to return an empty root would be a *claim* -- a car head
 * unit renders that as a library containing nothing, which is a wrong answer rather than an absent
 * one.
 *
 * **Who may connect is [ControllerAccessPolicy]'s decision, applied in [LibraryCallback.onConnect].**
 * The service is exported, and Media3's default callback accepts every connection unconditionally;
 * see that object for what a connected controller can read, why the gate is the platform's own
 * trust predicate rather than a package allow-list, and why it costs Plan 5's Auto and Wear
 * controllers nothing.
 *
 * **Nothing in this class logs, and that is narrower than it sounds.** A media service is the
 * easiest place in an app to leak a stream URL: every `MediaItem` it holds carries one, complete
 * with an auth token and salt, and the reflex when a track will not play is to log the item. No
 * source file under `core/media/src/main` names `android.util.Log` or `println`, and
 * `ConventionTest`'s `nothing in the media module logs` is what keeps it that way rather than this
 * paragraph.
 *
 * It does **not** follow that no URL can reach `logcat`. Two of this module's own dependencies log
 * on paths this project drives: Media3's `MediaSessionLegacyStub` logs a warning naming the artwork
 * `Uri` when the session bitmap loader fails to load it, and `DefaultHttpDataSource` embeds the
 * request URL in the message of the `HttpDataSourceException` it throws on a cross-protocol
 * redirect. "This code does not log" is a claim about this code; it is not a claim about the
 * process.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn` -- see `MuPlayerFactory` for the full argument.
// `MediaLibraryService`, `MediaLibrarySession` and `DefaultMediaNotificationProvider` are all
// `@UnstableApi`, and the Kotlin compiler cannot see that annotation at all; `check` fails at
// `lintDebug` with `UnsafeOptInUsageError` instead, long after the code compiled clean.
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MuPlaybackService : MediaLibraryService() {

  @Inject lateinit var playerFactory: MuPlayerFactory

  @Inject lateinit var mediaProgressDao: MediaProgressDao

  /**
   * The project's first injected clock, and the only wall-clock read behind every
   * `media_progress.lastPlayedAtEpochMs` this app writes.
   */
  @Inject lateinit var clock: Clock

  private var session: MediaLibrarySession? = null

  /**
   * Main-thread-confined, and built from a `Handler` rather than from `Dispatchers.Main`.
   *
   * [ProgressWriter]'s ticker reads the player, and a `Player` may only be read from the thread it
   * was built on. `Dispatchers.Main` would supply that too, but it lives in
   * `kotlinx-coroutines-android`, which this module deliberately does not declare -- the same
   * construction, for the same stated reason, as [PlaybackConnection] and [PlaybackLauncher].
   */
  private val mainExecutor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
  private val serviceScope =
    CoroutineScope(SupervisorJob() + mainExecutor.asCoroutineDispatcher())

  private var progressWriter: ProgressWriter? = null

  override fun onCreate() {
    super.onCreate()

    // Built here, on the main thread, because an ExoPlayer binds to its creating thread's Looper --
    // and built through the factory, which is the only construction site in this project that
    // attaches the 429 retry policy. See `MuPlayerFactory` for why forgetting that is silent.
    //
    // A `MuPlayer`, not the raw `ExoPlayer`: this is the object the session hands every controller,
    // so it is the one place that can make "no code path sets a playback position" true of all of
    // them. See that class.
    val player: MuPlayer = playerFactory.create()

    // Installed on the seam rather than on the raw player, so a position the writer records is the
    // position the session actually reports -- and so Plan 6 has exactly one writer to repoint.
    progressWriter =
      ProgressWriter(player, mediaProgressDao, clock, serviceScope).also { it.start() }

    // Media3 posts its own notification through this provider; what this call changes is its
    // *identity*. Without it the notification lands on Media3's default channel under Media3's
    // default id, which is a channel a user cannot find under this app's name in system settings.
    setMediaNotificationProvider(
      DefaultMediaNotificationProvider.Builder(this)
        .setChannelId(PlaybackNotification.CHANNEL_ID)
        .setChannelName(R.string.playback_notification_channel_name)
        .setNotificationId(PlaybackNotification.NOTIFICATION_ID)
        .build(),
    )

    session = MediaLibrarySession.Builder(this, player, LibraryCallback())
      // Tapping the notification opens the app. Resolved through the package manager rather than
      // by referencing MainActivity: :core:media must not depend on :app, and a launch intent is
      // exactly what "open the app" means.
      .setSessionActivity(
        PendingIntent.getActivity(
          this,
          0,
          checkNotNull(packageManager.getLaunchIntentForPackage(packageName)) {
            "no launcher activity for $packageName; the notification would do nothing when tapped"
          },
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
      )
      .build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
    session

  /**
   * Stops the service when the user swipes the app away **and nothing is playing**.
   *
   * The rule is [TaskRemovalPolicy]'s, not this method's, and the split is not decoration: this
   * override is called by the system and by nothing else, so it is unreachable from any test this
   * project can run, and a rule that lives inside it is a rule protected by a comment. See that
   * object for why both halves of the condition matter and which defect each one prevents.
   */
  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = session?.player
    if (TaskRemovalPolicy.stopsService(player?.playWhenReady, player?.mediaItemCount)) stopSelf()
  }

  /**
   * Persistence point 7, and the order in it is load-bearing.
   *
   * [ProgressWriter.flushBlocking] blocks on purpose: a coroutine launched into [serviceScope]
   * would be cancelled two lines later and write nothing at all, and this is the last chance to
   * record where the listener was. It runs *before* the scope is cancelled and *before* the player
   * is released -- a released player reports no current item, so a flush after it would be a
   * silent no-op rather than a failure.
   */
  override fun onDestroy() {
    progressWriter?.flushBlocking()
    progressWriter?.stop()
    progressWriter = null
    serviceScope.cancel()
    session?.run {
      player.release()
      release()
    }
    session = null
    super.onDestroy()
  }

  /**
   * The connection gate, and no browse overrides -- see this class's own documentation. The type
   * also exists so Plan 5 has one place to add the browse answers, and so the "not supported" answer
   * is a decision with a name rather than an omission.
   *
   * `internal` rather than `private`, so `ControllerAccessGateTest` can drive [onConnect] with a
   * real `ControllerInfo` for a package that is not this one. Nothing else in this project
   * constructs it: the session is built with `LibraryCallback()` eleven lines above.
   */
  internal class LibraryCallback : MediaLibrarySession.Callback {

    /**
     * Refuses a controller [ControllerAccessPolicy] does not accept, and otherwise leaves the
     * decision exactly where Media3 had it.
     *
     * **`onConnect` and not `onConnectAsync`, and that is a measurement.**
     * `MediaSessionImpl.onConnectOnHandler` calls `Callback.onConnect` *first* and only falls
     * through to `onConnectAsync` when the returned result is accepted **and** carries Media3's
     * `androidx.media3.session.CALLBACK_NOT_IMPLEMENTED` sentinel in its `sessionExtras` -- which is
     * precisely what the interface's own default `onConnect` returns. Overriding the async half
     * alone would leave the sync half answering first; overriding this one gates both, including a
     * future Plan 5 `onConnectAsync` for the browse tree.
     *
     * The accepted arm is `super.onConnect(...)`, i.e. that sentinel, rather than a hand-built
     * `AcceptedResultBuilder(session, controller).build()`. The two are the same thing today, and
     * delegating means a legitimate controller keeps whatever Media3's default is -- including the
     * narrowing it already applies to an untrusted-but-accepted caller -- instead of this file
     * freezing a copy of it.
     */
    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult =
      if (ControllerAccessPolicy.accepts(controller.packageName, controller.isTrusted)) {
        super.onConnect(session, controller)
      } else {
        MediaSession.ConnectionResult.reject()
      }
  }

  companion object {
    /**
     * The token every `MediaController` in this process connects through.
     *
     * On the service's own companion rather than on [PlaybackConnection]'s, because the token names
     * *this class* and nothing else: `ComponentName(context, MuPlaybackService::class.java)` is a
     * fact about this service, and a caller that wants to reach it should not have to know which
     * client type happens to hold the constructor for it. Plan 5's Android Auto wiring and Plan 3
     * Task 10 both ask for it here.
     *
     * There is exactly one of these. [PlaybackConnection] calls this function rather than building
     * its own `SessionToken`, so a change to how this service is addressed cannot leave a second
     * copy behind pointing at the old answer.
     */
    fun sessionToken(context: Context): SessionToken =
      SessionToken(context, ComponentName(context, MuPlaybackService::class.java))
  }
}
