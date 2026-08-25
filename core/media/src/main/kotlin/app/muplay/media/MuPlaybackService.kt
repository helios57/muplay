package app.muplay.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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
 * **Nothing here logs.** A media service is the easiest place in an app to leak a stream URL: every
 * `MediaItem` it holds carries one, complete with an auth token and salt, and the reflex when a
 * track will not play is to log the item. It has not happened yet because there is no logging in
 * this class at all, which is the state to keep it in.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn` -- see `MuPlayerFactory` for the full argument.
// `MediaLibraryService`, `MediaLibrarySession` and `DefaultMediaNotificationProvider` are all
// `@UnstableApi`, and the Kotlin compiler cannot see that annotation at all; `check` fails at
// `lintDebug` with `UnsafeOptInUsageError` instead, long after the code compiled clean.
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MuPlaybackService : MediaLibraryService() {

  @Inject lateinit var playerFactory: MuPlayerFactory

  private var session: MediaLibrarySession? = null

  override fun onCreate() {
    super.onCreate()

    // Built here, on the main thread, because an ExoPlayer binds to its creating thread's Looper --
    // and built through the factory, which is the only construction site in this project that
    // attaches the 429 retry policy. See `MuPlayerFactory` for why forgetting that is silent.
    val player: ExoPlayer = playerFactory.create()

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
   * Both halves matter. Stopping unconditionally kills music the user is listening to while they
   * clear their recents list; never stopping leaves an idle foreground service and a stale
   * notification the user cannot get rid of.
   */
  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = session?.player
    if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    session?.run {
      player.release()
      release()
    }
    session = null
    super.onDestroy()
  }

  /**
   * No browse overrides, on purpose -- see this class's own documentation. The type exists so Plan 5
   * has one place to add them, and so the "not supported" answer is a decision with a name rather
   * than an omission.
   */
  private class LibraryCallback : MediaLibrarySession.Callback

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
