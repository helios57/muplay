package app.muplay.media

import android.app.PendingIntent
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import app.muplay.database.AudiobookRepository
import app.muplay.database.dao.MediaProgressDao
import app.muplay.media.browse.MuPlayLibraryCallback
import app.muplay.media.cast.CastSessionManager
import dagger.hilt.android.AndroidEntryPoint
import java.time.Clock
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The service that owns playback.
 *
 * A `MediaLibraryService`, per spec section 7, rather than a bare `MediaSessionService`: it is a
 * strict subclass, so the notification, media-button routing and foreground lifecycle are
 * identical, and Plan 5's Android Auto browse tree does not require changing a base class
 * underneath a live session, a live notification and a live `MediaController`.
 *
 * **The browse tree is [MuPlayLibraryCallback]'s**, injected below. Plan 3 Task 5 left
 * `MediaLibrarySession.Callback`'s browse methods at their *"not supported"* defaults and said why:
 * "not supported" was true then, and an empty root would have been a *claim*. Plan 5 Task 4 pays
 * that deferral off, and installing its callback is the only change this class needed.
 *
 * **Who may connect is [ControllerAccessPolicy]'s decision, applied in
 * [MuPlayLibraryCallback.onConnect].** The service is exported, and Media3's default callback
 * accepts every connection unconditionally; see that object for what a connected controller can
 * read, why the gate is the platform's own trust predicate rather than a package allow-list, and
 * why it costs Plan 5's Auto and Wear controllers nothing.
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
   * The in-memory `media_progress` view the resume policy answers from.
   *
   * Injected here for one reason: **somebody has to start its collector**, and a snapshot nobody
   * started answers `null` for everything -- which resumes nothing and is indistinguishable from
   * the defect this whole plan removes. It is a `@Singleton`, so this is the same object
   * `MediaModule.provideUndecoratedResumePolicy` reads through `AudiobookItemSource`.
   */
  @Inject lateinit var audiobookSnapshot: AudiobookSnapshot

  /**
   * The browse tree and the connection gate, in one object because Media3 takes one callback.
   *
   * Injected rather than constructed here: it reaches `BrowseTreeRepository`, which reaches Room
   * and the credential store, and a `MediaLibrarySession.Builder` argument is not a place to
   * assemble a graph.
   */
  @Inject lateinit var libraryCallback: MuPlayLibraryCallback

  /**
   * The project's first injected clock, and the only wall-clock read behind every
   * `media_progress.lastPlayedAtEpochMs` this app writes.
   */
  @Inject lateinit var clock: Clock

  /**
   * Which `Player` this session drives. **The seam the whole cast feature hangs from.**
   *
   * `MediaSession.setPlayer` is Media3's supported way to change what a session drives, and it is
   * what Google's own Cast integration does: the notification, the media buttons, the lock screen
   * and every `MediaController` follow without any of them being told.
   */
  @Inject lateinit var outputSwitch: PlaybackOutputSwitch

  /**
   * Injected here and nowhere else in this class, for one reason: it holds the single
   * [ProgressWriter] that has to follow the switch, and this is where that writer is built.
   */
  @Inject lateinit var castSessionManager: CastSessionManager

  /**
   * Which media ids are books, and what each book's speed and silence-skipping setting is.
   *
   * Injected rather than reached through [AudiobookRepository], because `BookSpeedController` is
   * asked this question on the player's application thread at every item transition and a Room
   * query there janks playback. See `MediaModule.provideAudiobookItemSource`.
   */
  @Inject lateinit var audiobookItems: AudiobookItemSource

  /**
   * Where a speed the listener chose is written. **Not read here** -- the read is
   * [audiobookItems]'s, for the threading reason above.
   */
  @Inject lateinit var audiobookRepository: AudiobookRepository

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

  private var speedController: BookSpeedController? = null

  override fun onCreate() {
    super.onCreate()

    // Built here, on the main thread, because an ExoPlayer binds to its creating thread's Looper --
    // and built through the factory, which is the only construction site in this project that
    // attaches the 429 retry policy. See `MuPlayerFactory` for why forgetting that is silent.
    //
    // A `MuPlayer`, not the raw `ExoPlayer`: this is the object the session hands every controller,
    // so it is the one place that can make "no code path sets a playback position" true of all of
    // them. See that class.
    // Both halves are kept, and that is the whole reason `wrap` exists. The session and every
    // `MediaController` see the seam; `BookSpeedController` needs the raw player, because
    // `setSkipSilenceEnabled` is on `ExoPlayer` and not on `Player`.
    val exoPlayer = playerFactory.createExoPlayer()
    val player: MuPlayer = playerFactory.wrap(exoPlayer)

    // Installed on the seam rather than on the raw player, so a position the writer records is the
    // position the session actually reports -- and so Plan 6 has exactly one writer to repoint.
    progressWriter =
      ProgressWriter(player, mediaProgressDao, clock, serviceScope).also { it.start() }

    // Without this the snapshot is empty for the life of the process and every book starts at zero
    // -- silently, and only on a device where nothing else warmed it. The collector's rebuild runs
    // off this scope's main dispatcher; see `AudiobookSnapshot.start`.
    audiobookSnapshot.start(serviceScope)
    // **One** writer, handed to the thing that moves it. A second writer built around the cast
    // player would race this one for the same `media_progress` row.
    castSessionManager.useProgressWriter(progressWriter!!)

    // Installed **before** any queue exists, so the first item of the session's first queue arrives
    // as an `onMediaItemTransition` and gets its book's speed like every item after it. A controller
    // attached after a queue was set would leave the first book of every session at 1.0x and every
    // book after it correct -- a defect that works the second time, which is the hardest kind to
    // see.
    speedController = BookSpeedController(exoPlayer, audiobookItems) { bookId, speed ->
      // Fire-and-forget into the service scope: this runs on the player's application thread, where
      // a Room write would jank playback. `onDestroy` cancels that scope after
      // `progressWriter.flushBlocking()`, so a speed change made in the last instant of the
      // service's life can be lost -- which costs a listener one tap, unlike a lost position, and
      // is why the position gets a blocking flush and this does not.
      serviceScope.launch { audiobookRepository.setSpeed(bookId, speed) }
    }.also { it.start() }

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

    session = MediaLibrarySession.Builder(this, player, libraryCallback)
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

    // Populated *after* the session exists, so the collector's first emission has something to
    // install onto. `MutableStateFlow` replays its current value to a late collector, which is why
    // this ordering is safe rather than lucky.
    outputSwitch.installLocal(player)
    // The session follows the switch. Everything player-bound that is broken by being left behind
    // is re-pointed here -- which today is the session itself. The `ProgressWriter` is deliberately
    // NOT on this list: it has to be attached *before* `setMediaItems` on the incoming player, and
    // this collector is a coroutine that would not have resumed by then, so `CastSessionManager`
    // moves it synchronously inside the handover. When the audiobook plan lands,
    // `SleepTimerController.attach(player, serviceScope)` goes here -- it binds to one player and
    // its fade drives that player's `volume`, so left behind it counts down against a paused,
    // silent phone while the speaker plays all night, and nothing throws and nothing logs.
    serviceScope.launch {
      outputSwitch.activePlayer.filterNotNull().collect { active ->
        session?.let { if (it.player !== active) it.player = active }
      }
    }
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
    session

  /**
   * Handles the Assistant's cold-start play intent, then hands the intent on to Media3 unchanged.
   *
   * *"Hey Google, play Second Book on MuPlay"* reaches a media app two different ways. With the app
   * already connected it arrives as a legacy `playFromSearch`, which Media3 turns into
   * `onSetMediaItems` with a query on the item's request metadata; with nothing connected it
   * arrives **here**, as a plain `Intent` at the exported service. Both funnel into
   * [MuPlayLibraryCallback.spokenQueue], so there is one answer to "which one thing does that
   * play" rather than two that drift.
   *
   * **`super.onStartCommand` still runs for every intent, including this one.**
   * `MediaSessionService` uses it for media-button routing and for its own foreground bookkeeping,
   * and short-circuiting it for one action is how a service ends up alive but deaf to a headset.
   *
   * The action is matched through the platform constant rather than a literal; the manifest carries
   * the literal, and `ConventionTest`'s *a declared play-from-search filter must have a handler and
   * a gate entry* is what stops the filter, this branch and the merged-manifest requirement from
   * landing without one another.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
      playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
    }
    return super.onStartCommand(intent, flags, startId)
  }

  /**
   * Starts whatever [query] should start, on the session's **current** player.
   *
   * `session?.player` is read at the moment it is used and never cached, for the reason
   * [MuPlayLibraryCallback]'s own header gives: Plan 6 swaps the session's player when audio moves
   * to a speaker.
   *
   * `C.TIME_UNSET`, like everywhere else: `MuPlayer` discards it and asks the resume policy, so a
   * book asked for out loud resumes where it was left.
   */
  private fun playFromSearch(query: String) {
    serviceScope.launch {
      val queue = libraryCallback.spokenQueue(query) ?: return@launch
      val player = session?.player ?: return@launch
      player.setMediaItems(queue.mediaItems, queue.startIndex, C.TIME_UNSET)
      player.prepare()
      player.play()
    }
  }

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
    libraryCallback.release()
    progressWriter?.flushBlocking()
    progressWriter?.stop()
    progressWriter = null
    // No flush of its own: a speed change is already written the moment it happens, and the only
    // thing left to undo is the listener registration on a player that is about to be released.
    speedController?.stop()
    speedController = null
    // Before the scope is cancelled, for the same reason the flush is: cancelling the scope stops
    // the collector anyway, and stopping it here is what lets a recreated service start a fresh one
    // -- `start` is idempotent on a non-null job, so a snapshot never stopped would refuse to
    // collect again for the rest of the process.
    audiobookSnapshot.stop()
    serviceScope.cancel()
    session?.run {
      val active = player
      release()
      active.release()
      // The local player is **paused, not released** while casting, so the session's own player is
      // not necessarily it. Releasing only what the session held would leak a real `ExoPlayer` on a
      // real path: swiping the app away mid-cast.
      outputSwitch.localPlayer()?.takeIf { it !== active }?.release()
    }
    session = null
    super.onDestroy()
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
