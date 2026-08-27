package app.muplay.media

import androidx.media3.common.Player
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which `Player` the media session is currently driving.
 *
 * The seam that lets the cast layer exist without this module's *service* knowing anything about
 * UPnP: [MuPlaybackService] observes [activePlayer] and calls `MediaSession.setPlayer`, and
 * `CastSessionManager` installs a remote player through here. `:core:media` depends on `:core:cast`
 * for the protocol; the service depends only on `Player`.
 *
 * ### The local player is paused, not released
 *
 * Releasing it would mean rebuilding it -- and rebinding audio focus, the cache-backed data source
 * and the becoming-noisy receiver -- on every handover back, and `MuPlayerFactory.create()` is the
 * only construction site that attaches the 429 retry policy, so a rebuild is also a chance to
 * forget it. Paused, it holds no audio focus and decodes nothing. `handBackTo` then finds a player
 * that still answers `playbackState`, which a released `ExoPlayer` does not.
 *
 * ### Everything here runs on the player's application thread
 *
 * [installRemote] calls `pause()` on the outgoing player, and a `Player` may only be touched from
 * the thread it was built on. Every caller in this module is already on the main looper for that
 * reason; this class adds no thread-hopping of its own, deliberately, because a `post` here would
 * make the handover's *ordering* -- write, arm, `setMediaItems` -- depend on when a message loop
 * got round to it.
 */
@Singleton
class PlaybackOutputSwitch @Inject constructor() {

  private val _activePlayer = MutableStateFlow<Player?>(null)

  /**
   * The player the session should be driving, or `null` before [installLocal] has run.
   *
   * A `StateFlow` rather than a callback because the collector in [MuPlaybackService] has to be
   * able to join late: the service builds its session after the switch is populated, and a
   * callback registered afterwards would miss the local player entirely and leave the session
   * driving nothing.
   */
  val activePlayer: StateFlow<Player?> = _activePlayer.asStateFlow()

  private var local: Player? = null

  /** The phone's own player. Called once, from the service's `onCreate`. */
  fun installLocal(player: Player) {
    local = player
    _activePlayer.value = player
  }

  /**
   * A speaker takes over.
   *
   * The local player is **paused** and kept. `runCatching` because pausing is a courtesy, not the
   * point: a local player that refuses to pause must not stop a handover that has already written
   * the listener's position down.
   */
  fun installRemote(player: Player) {
    local?.let { runCatching { it.pause() } }
    _activePlayer.value = player
  }

  /** Back to the phone. The local player is still there; it was only paused. */
  fun returnToLocal() {
    local?.let { _activePlayer.value = it }
  }

  fun current(): Player? = _activePlayer.value

  /**
   * The phone's own player, whether or not it is the active one.
   *
   * Exists for exactly one caller: `MuPlaybackService.onDestroy`, which releases what the session
   * is driving and would otherwise leak the paused local `ExoPlayer` whenever the service is
   * destroyed while casting. That is a real leak on a real path -- swiping the app away mid-cast --
   * and it is invisible from [current].
   */
  fun localPlayer(): Player? = local
}
