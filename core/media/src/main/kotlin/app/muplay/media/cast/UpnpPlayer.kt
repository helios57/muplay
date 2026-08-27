package app.muplay.media.cast

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import app.muplay.cast.session.CastFailure
import app.muplay.cast.session.CastFailureKind
import app.muplay.cast.session.CastPlayback
import app.muplay.cast.session.CastPlaybackState
import app.muplay.cast.session.CastSession
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * **A UPnP renderer, seen by Media3 as an ordinary `Player`.**
 *
 * The whole reason casting is cheap in this app: because the cast output *is* a `Player`,
 * `MuPlayer`'s `ForwardingPlayer` seam, `ProgressWriter`, the notification, the `MediaSession`,
 * Android Auto and the Compose player all keep working with no change at all.
 *
 * ### What is here, and what deliberately is not
 *
 * Every **decision** -- the queue, the transport commands, the poll, when a speaker counts as lost,
 * what a position means between polls -- is `CastSession`'s, in `:core:cast`, which is pure JVM and
 * is therefore gated against a **real UPnP renderer over real sockets** without an emulator. What
 * is left here is translation, and only translation: `MediaItem` to `CastSource`, [CastPlayback] to
 * [SimpleBasePlayer.State], [CastFailure] to [PlaybackException], and the `handle*` calls to the
 * session's suspending commands. There is no rule in this file that a test would want to drive from
 * both directions, and that is on purpose.
 *
 * ### Speed is absent, and that is a statement rather than an omission
 *
 * `AVTransport::Play` takes `Speed="1"`, and `TransportPlaySpeed` values other than `1` are not
 * implemented by Sonos or by any renderer this app targets. So [PlaybackParameters.DEFAULT] is
 * reported unconditionally and `COMMAND_SET_SPEED_AND_PITCH` is **withheld** from the available
 * commands -- a controller that asks is refused rather than told "yes" and then given 1.0x. A
 * book's per-item speed is real and is honoured locally; while cast it cannot be, and the picker
 * says so in words.
 *
 * ### Threading
 *
 * A `SimpleBasePlayer` is confined to the `Looper` it is built on and every `handle*` runs there.
 * The session's commands are suspending socket work, so each handler launches into [scope] -- a
 * **background** scope, never the looper's -- and completes the future it returned when the command
 * has actually been sent. Media3 masks the state in the meantime, which is exactly the behaviour a
 * remote player wants: the seek bar moves when the user drags it, and the renderer's own answer
 * replaces the guess a poll later.
 *
 * The traffic in the other direction is [CastSession]'s `onPlaybackChanged`, which fires on
 * whatever coroutine published the snapshot. It is posted back to the looper before
 * `invalidateState()` is touched, because that method verifies the application thread.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn`: `SimpleBasePlayer` and `UnstableApi` are an
// `androidx.annotation.RequiresOptIn`, which the Kotlin compiler cannot see at all -- without this
// the file compiles clean and `check` fails one task later at `lintDebug` with
// `UnsafeOptInUsageError`.
@OptIn(UnstableApi::class)
class UpnpPlayer(
  looper: Looper,
  private val scope: CoroutineScope,
  private val nowMs: () -> Long,
  newSession: (onPlaybackChanged: () -> Unit) -> CastSession,
) : SimpleBasePlayer(looper) {

  private val handler = Handler(looper)

  /**
   * Built by the caller so that the session's `onPlaybackChanged` can name this object.
   *
   * A lambda rather than a constructor parameter because the two are mutually recursive: the
   * session must call `invalidateState()` on the player, and the player must hold the session. A
   * `lateinit var` would leave a window in which a snapshot published during construction reached a
   * player that did not exist yet.
   */
  private val session: CastSession = newSession(::onPlaybackChanged)

  /**
   * The queue **as Media3 gave it**, because `CastSource` is a lossy read of a `MediaItem` and the
   * session's copy could not be turned back into one.
   *
   * Written on the looper in `handleSetMediaItems`, read on the looper in [getState]. No lock: a
   * `SimpleBasePlayer` is single-threaded by contract and adding one here would suggest otherwise.
   */
  private var items: List<MediaItem> = emptyList()

  /** The speaker's name, for the picker and for anything that reports where audio is going. */
  val deviceName: String get() = session.deviceName

  /** The session's own view, for a caller that needs the failure or the last known position. */
  val playback: CastPlayback get() = session.playback

  override fun getState(): State {
    val snapshot = session.playback
    val playlist = items.mapIndexed { index, item ->
      MediaItemData.Builder(index)
        .setMediaItem(item)
        .setMediaMetadata(item.mediaMetadata)
        // From the library's own duration, not from `GetPositionInfo`: a renderer reports
        // `TrackDuration` only once it has read the container, and a seek bar with no length until
        // then is a visible regression against local playback.
        .setDurationUs(durationUs(item))
        // The device's own service description decides this -- `CastSession` reads the SCPD rather
        // than guessing. A device that cannot seek by time shows no seek bar at all, instead of one
        // that answers 710 to every drag.
        .setIsSeekable(snapshot.canSeek)
        .setIsDynamic(false)
        .build()
    }
    val playbackState = if (playlist.isEmpty()) STATE_IDLE else playbackState(snapshot.playbackState)
    val builder = State.Builder()
      .setAvailableCommands(commands(snapshot))
      .setPlaybackState(playbackState)
      .setPlayWhenReady(snapshot.playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaylist(playlist)
      // Always 1.0. See this class's own note: UPnP has no other speed to offer.
      .setPlaybackParameters(PlaybackParameters.DEFAULT)
      .setDeviceInfo(REMOTE_DEVICE)
      .setDeviceVolume(snapshot.volumePercent)
    if (playlist.isNotEmpty()) {
      builder.setCurrentMediaItemIndex(snapshot.currentIndex.coerceIn(0, playlist.size - 1))
      // `PositionSupplier`, not a fixed number, and it reads the session **again** on every call:
      // this is `getExtrapolating` written where Tier 1 can hold it. `CastPlayback.positionAtMs` is
      // the one place that knows the position only advances while the renderer says it is playing,
      // and that it never runs past a known duration.
      builder.setContentPositionMs(PositionSupplier { session.playback.positionAtMs(nowMs()) })
    }
    // "Player error only allowed in STATE_IDLE" is a `SimpleBasePlayer` invariant, and
    // `CastSession.playbackState()` reports IDLE whenever there is a failure -- so this is a guard
    // against the two disagreeing rather than a second decision about when a session has failed.
    if (playbackState == STATE_IDLE) {
      snapshot.failure?.let { builder.setPlayerError(playbackException(it)) }
    }
    return builder.build()
  }

  override fun handleSetMediaItems(
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    // Set on the looper, before anything is launched: `getState()` can run at any time from here on
    // and a playlist that lagged the session's queue would report the wrong current item.
    items = mediaItems.toList()
    val sources = CastSources.of(items)
    // `C.INDEX_UNSET` / `C.TIME_UNSET` are what Media3 passes for "the default position", which is
    // the start. `MuPlayer` always names both, so these arms are for the overloads that do not --
    // `setMediaItems(items, resetPosition)` and everything a `MediaController` can send.
    val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
    val position = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
    return command { session.setQueue(sources, startIndex = index, startPositionMs = position) }
  }

  /**
   * Nothing to do: `setQueue` is this session's `prepare()`.
   *
   * It already issued `SetAVTransportURI` and, if the queue was set while playing, `Play`. A second
   * load here would re-mint the capability token and re-fetch the media for no reason.
   */
  override fun handlePrepare(): ListenableFuture<*> = command { }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
    command { session.setPlayWhenReady(playWhenReady) }

  override fun handleStop(): ListenableFuture<*> = command { session.stop() }

  override fun handleRelease(): ListenableFuture<*> = command { session.release() }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: Int,
  ): ListenableFuture<*> =
    command {
      session.seekTo(mediaItemIndex, if (positionMs == C.TIME_UNSET) 0L else positionMs)
    }

  override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> =
    command { session.setVolumePercent(deviceVolume) }

  /**
   * Runs one session command off the looper and reports when it is done.
   *
   * The future is what makes Media3's masking correct rather than merely optimistic: the state a
   * caller sees between the request and the renderer's answer is the *requested* one, and it is
   * replaced by the real one when this completes. Returning an immediate future instead would snap
   * a dragged seek bar back to where the renderer last said it was.
   *
   * A failure is **not** put on the future. `CastSession.guard` has already turned every way a cast
   * operation can fail into a reported [CastFailure], which reaches Media3 as a `playerError`
   * through [getState]; failing the future as well would raise the same event twice, once as a
   * state and once as an exception with no context.
   */
  private fun command(block: suspend () -> Unit): ListenableFuture<*> {
    val future = SettableFuture.create<Unit>()
    scope.launch {
      try {
        block()
      } finally {
        future.set(Unit)
      }
    }
    return future
  }

  private fun onPlaybackChanged() {
    // Posted, because `invalidateState()` verifies the application thread and this arrives on
    // whichever coroutine published the snapshot. `invalidateState` is itself a no-op once the
    // player has been released, so a snapshot in flight during teardown needs no guard here.
    handler.post { invalidateState() }
  }

  private fun playbackState(state: CastPlaybackState): Int = when (state) {
    CastPlaybackState.IDLE -> STATE_IDLE
    CastPlaybackState.BUFFERING -> STATE_BUFFERING
    CastPlaybackState.READY -> STATE_READY
    CastPlaybackState.ENDED -> STATE_ENDED
  }

  /**
   * What this player can be asked to do.
   *
   * Two of these are decisions rather than a list. `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` is present
   * only when the device's own SCPD declares a time seek mode, so a renderer that cannot seek shows
   * no seek bar rather than one that answers 710. `COMMAND_SET_SPEED_AND_PITCH` is absent always --
   * see this class's own note.
   */
  private fun commands(snapshot: CastPlayback): Player.Commands =
    Player.Commands.Builder()
      .addAll(
        COMMAND_PLAY_PAUSE,
        COMMAND_PREPARE,
        COMMAND_STOP,
        COMMAND_SET_MEDIA_ITEM,
        COMMAND_CHANGE_MEDIA_ITEMS,
        COMMAND_GET_TIMELINE,
        COMMAND_GET_CURRENT_MEDIA_ITEM,
        COMMAND_GET_METADATA,
        COMMAND_SEEK_TO_MEDIA_ITEM,
        COMMAND_SEEK_TO_NEXT,
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        COMMAND_SEEK_TO_PREVIOUS,
        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        COMMAND_GET_DEVICE_VOLUME,
        COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        COMMAND_RELEASE,
      )
      .addIf(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, snapshot.canSeek)
      .build()

  private fun durationUs(item: MediaItem): Long {
    val durationMs = item.mediaMetadata.durationMs ?: return C.TIME_UNSET
    return if (durationMs <= 0L) C.TIME_UNSET else durationMs * 1_000L
  }

  /**
   * A cast failure, as the error a `MediaController` already knows how to render.
   *
   * The message is [CastFailure]'s, which `:core:cast` guarantees is user-facing and **URL-free** --
   * it is the string a snackbar shows, and a renderer-direct URL is a Navidrome stream URL carrying
   * the user's `u`, `t` and `s`.
   */
  private fun playbackException(failure: CastFailure): PlaybackException = PlaybackException(
    failure.message,
    null,
    when (failure.kind) {
      CastFailureKind.RENDERER_REFUSED -> PlaybackException.ERROR_CODE_REMOTE_ERROR
      CastFailureKind.RENDERER_UNREACHABLE ->
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
      CastFailureKind.UNROUTABLE, CastFailureKind.UNEXPECTED ->
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED
    },
  )

  private companion object {
    /**
     * `PLAYBACK_TYPE_REMOTE` is what makes the volume keys and the notification's volume row drive
     * the *speaker* rather than the phone's own stream, and `0..100` is UPnP `RenderingControl`'s
     * own range.
     */
    val REMOTE_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
      .setMinVolume(0)
      .setMaxVolume(100)
      .build()
  }
}
