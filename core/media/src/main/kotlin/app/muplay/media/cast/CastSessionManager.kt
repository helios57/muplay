package app.muplay.media.cast

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.muplay.cast.control.UpnpRenderer
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.route.CastRouter
import app.muplay.cast.session.CastSession
import app.muplay.cast.session.CastSessionState
import app.muplay.cast.soap.SoapClient
import app.muplay.media.ArtworkUrls
import app.muplay.media.MuPlayer
import app.muplay.media.PlaybackOutputSwitch
import app.muplay.media.ProgressWriter
import app.muplay.media.ResumeTarget
import app.muplay.media.di.CastCommands
import java.time.Clock
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Starts and ends a cast session, and moves playback between the phone and a speaker **without
 * losing the listener's place**.
 *
 * The handover is four steps and their **order is load-bearing**:
 *
 * 1. write the outgoing player's position to `media_progress`;
 * 2. arm a one-shot [ResumeTarget] for that media id;
 * 3. `setMediaItems` on the incoming player -- through `MuPlayer`, like every other caller;
 * 4. the policy answers the armed target once, then delegates forever after.
 *
 * Step 1 comes before step 2 so that a process death mid-handover leaves the position **durable**:
 * the ordinary resume path finds it. Arming first and writing second loses it on exactly the crash
 * a handover is most likely to provoke. No in-process test can observe that ordering -- swapping
 * the two lines fails nothing, which is why it is written here as well as asserted in the half that
 * is observable (that the row is written at all).
 *
 * This class defines **no** resume mechanism and **no** progress path of its own. Both are the
 * playback core's, and this decorates them: [OneShotResumePolicy] wraps whatever `ResumePolicy` is
 * bound, and the write goes through [ProgressWriter.write], which is a read-modify-write that
 * preserves the columns it does not own. That is why a listener's per-book speed survives a cast
 * and why a column added later is preserved for free.
 *
 * ### Threading
 *
 * [castTo] and [stopCasting] read and write `Player`s, so they run on the **player's application
 * thread** -- the main looper. Everything that talks to a speaker is suspending socket work and
 * runs on [scope], which is not that thread. A session that fails or vanishes reports from a
 * background coroutine, so [handleSessionState] posts the hand-back onto the main looper rather
 * than touching a player from wherever the poll happened to be.
 */
@Singleton
class CastSessionManager @Inject constructor(
  private val switch: PlaybackOutputSwitch,
  private val oneShot: OneShotResumePolicy,
  /**
   * A [Provider], not the router itself, because building a [CastRouter] builds a
   * [app.muplay.cast.proxy.MediaProxyServer], and building one **binds a listening socket**. This
   * class is injected by the playback service, which runs whenever anything plays; a socket open
   * from the first note of local playback until the app dies is a capability nobody asked for. The
   * first `get()` is the first cast.
   */
  private val router: Provider<CastRouter>,
  private val soap: SoapClient,
  private val http: CastHttpClient,
  private val clock: Clock,
  /**
   * Resolves an item's credential-free `muplay-art:` URI into the URL this phone fetches a cover
   * from. See [app.muplay.media.ArtworkUri] for why the item does not carry one.
   */
  private val artworkUrls: ArtworkUrls,
  @CastCommands private val scope: CoroutineScope,
) {

  private val _state = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
  val state: StateFlow<CastSessionState> = _state.asStateFlow()

  /**
   * The remote player, while one is installed.
   *
   * Held here as well as in [PlaybackOutputSwitch] because the switch's type is `Player` by design
   * -- that is what lets the service know nothing about UPnP -- and the picker needs the speaker's
   * name and its volume, which are this type's.
   */
  var castPlayer: UpnpPlayer? = null
    private set

  /**
   * The one writer that follows the switch.
   *
   * Handed over rather than injected because `ProgressWriter` is constructed by the playback
   * service, around the player the service just built, and is not in the object graph. One writer
   * and not two: a second writer on a second player races the first for the same `media_progress`
   * row, and the loser's value is whatever it read before the winner wrote.
   */
  private var progressWriter: ProgressWriter? = null

  /**
   * True once a [CastSessionState.Failed] or [CastSessionState.Lost] has been reported.
   *
   * Its whole job is to stop **the consequences of a failure erasing its diagnosis**: handing back
   * releases the session, releasing it reports `Idle`, and `Idle` overwriting `Failed("… refused:
   * 714 …")` would leave the user with a picker that says nothing happened. Cleared by the next
   * [castTo] or [stopCasting], both of which are the user saying something new.
   */
  private var terminal = false

  private val mainScope = CoroutineScope(
    SupervisorJob() +
      Executor { command -> Handler(Looper.getMainLooper()).post(command) }.asCoroutineDispatcher(),
  )

  /** Called once, by the playback service, with the writer it built around the local player. */
  fun useProgressWriter(writer: ProgressWriter) {
    progressWriter = writer
  }

  /**
   * Move playback to [device].
   *
   * Call on the player's application thread. Returns as soon as the incoming player has been told
   * what to play; the speaker's own answer arrives through [state] and through the player's
   * listeners.
   */
  // `incoming` is a [MuPlayer], and `MuPlayer` is a `ForwardingPlayer`, which is `@UnstableApi` --
  // so `prepare()` and `play()` resolve to `ForwardingPlayer`'s members rather than `Player`'s and
  // are opt-in. `local.prepare()` in `handBackTo` is not flagged, because that receiver is typed
  // `Player`: which declaration a call resolves to is what decides this, not the method name.
  //
  // `androidx.annotation.OptIn`, not `kotlin.OptIn`: Media3's marker is an
  // `androidx.annotation.RequiresOptIn`, invisible to the Kotlin compiler, so the omission compiles
  // clean and fails much later at `lintDebug`. This file arrived on master having done exactly
  // that -- green in its own worktree, red on the first `--no-build-cache check` after the merge.
  @androidx.annotation.OptIn(UnstableApi::class)
  suspend fun castTo(device: CastDevice) {
    val outgoing = switch.current() ?: return
    terminal = false
    _state.value = CastSessionState.Connecting(device.friendlyName)
    val handover = snapshot(outgoing)

    val remote = UpnpPlayer(
      looper = Looper.getMainLooper(),
      scope = scope,
      nowMs = clock::millis,
      // A `MediaItem` carries `muplay-art:<coverArtId>` so that nothing on the platform media
      // session carries a credential (see `ArtworkUri`). The cast proxy still has to fetch the real
      // bytes from Navidrome, so this is where the credential goes back on -- in this process, for
      // a URL that reaches `ProxyRegistry.publishArtwork` and stops there.
      artworkUrl = artworkUrls::urlFor,
    ) { onPlaybackChanged ->
      CastSession(
        device = device,
        renderer = UpnpRenderer(device, soap, http),
        router = router.get(),
        scope = scope,
        nowMs = clock::millis,
        onPlaybackChanged = onPlaybackChanged,
        onSessionStateChanged = ::handleSessionState,
      )
    }
    castPlayer = remote
    // Wrapped in the same seam the local player wears, and that is not symmetry for its own sake:
    // an unwrapped remote would be the one code path in this application that can set a playback
    // position, sitting inside the object a `MediaController` in a car reaches.
    val incoming = MuPlayer(remote, oneShot)

    // 1 then 2 -- see this class's KDoc.
    handover?.let {
      // `gainDb = null` is "this handover has nothing to say about the gain", which the writer
      // reads as *preserve what is stored*. The alternative -- re-reading the item's ReplayGain
      // extras here -- would be a second place that decides what a row's `gainDb` means.
      progressWriter?.write(it.mediaId, it.positionMs, finished = false, gainDb = null)
      oneShot.armFor(it.mediaId, ResumeTarget(it.index, it.positionMs))
    }

    switch.installRemote(incoming)
    // Before `setMediaItems`, and synchronously: a writer still pointed at the paused local player
    // records nothing at all while a book plays on a speaker, and nobody finds out until they lose
    // their place.
    progressWriter?.attach(incoming)
    // 3: an ordinary `setMediaItems`, with a zero position. The seam is fed, not bypassed -- the
    // position comes from the policy, which is what step 2 armed.
    incoming.setMediaItems(handover?.items.orEmpty().toMutableList(), handover?.index ?: 0, 0L)
    incoming.prepare()
    if (handover?.wasPlaying == true) incoming.play()
  }

  /** Back to the phone, at the second the speaker had reached. Call on the application thread. */
  suspend fun stopCasting() {
    if (castPlayer == null) return
    handBackTo(forcePaused = false)
    terminal = false
    _state.value = CastSessionState.Idle
  }

  /**
   * Every state the session reports, and the two that end it.
   *
   * A speaker that failed or vanished hands playback back **paused**. The spec says playback
   * stopping when the phone leaves the network is intended behaviour; losing the listener's place
   * is not, and neither is starting audio out of a phone's own loudspeaker in somebody's pocket.
   */
  internal fun handleSessionState(state: CastSessionState) {
    when {
      state is CastSessionState.Failed || state is CastSessionState.Lost -> {
        terminal = true
        _state.value = state
        mainScope.launch { handBackTo(forcePaused = true) }
      }
      // The `Idle` that the hand-back's own `release()` reports. See [terminal].
      terminal -> Unit
      else -> _state.value = state
    }
  }

  // `remote.release()`: the receiver is the `UpnpPlayer` built above, a `SimpleBasePlayer`, and
  // `release()` is that class's `@UnstableApi` member. See `castTo`'s note for why the compiler
  // cannot see this.
  @androidx.annotation.OptIn(UnstableApi::class)
  private suspend fun handBackTo(forcePaused: Boolean) {
    val remote = castPlayer ?: return
    val local = switch.localPlayer() ?: return
    val active = switch.current() ?: return
    castPlayer = null

    val handover = snapshot(active)
    handover?.let {
      // The same two steps in the same order, in the other direction.
      progressWriter?.write(it.mediaId, it.positionMs, finished = false, gainDb = null)
      oneShot.armFor(it.mediaId, ResumeTarget(it.index, it.positionMs))
    }

    switch.returnToLocal()
    // Re-pointed **before** the remote is released, so the writer detaches its listener from a
    // player that still answers. A released `SimpleBasePlayer` is not a good place to be tidying up
    // from, and the failure would be a stack trace during a speaker outage.
    progressWriter?.attach(local)
    runCatching { remote.release() }
    // The session's own `release()` revokes too; this is here because the commonest reason to hand
    // back is that the speaker stopped answering, and that path has already thrown once. A proxy
    // still serving after its session has ended is a capability lying on the LAN with nobody
    // watching it.
    runCatching { router.get().revokeAll() }

    local.setMediaItems(handover?.items.orEmpty().toMutableList(), handover?.index ?: 0, 0L)
    local.prepare()
    if (!forcePaused && handover?.wasPlaying == true) local.play()
  }

  private data class Handover(
    val mediaId: String,
    val positionMs: Long,
    val index: Int,
    val items: List<MediaItem>,
    val wasPlaying: Boolean,
  )

  private fun snapshot(player: Player): Handover? {
    val mediaId = player.currentMediaItem?.mediaId ?: return null
    return Handover(
      mediaId = mediaId,
      // A player with no timeline reports `C.TIME_UNSET`, which is negative; a negative position in
      // `media_progress` would sort ahead of every real one.
      positionMs = player.currentPosition.coerceAtLeast(0L),
      index = player.currentMediaItemIndex,
      items = (0 until player.mediaItemCount).map(player::getMediaItemAt),
      wasPlaying = player.isPlaying,
    )
  }
}
