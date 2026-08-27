package app.muplay.media

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The structural enforcement of spec §3.
 *
 * **All six** `setMediaItem(s)` overloads are overridden and funnelled into one place, so no code
 * path -- not a `MediaController` in a car, not a headset button, not a feature written next year --
 * can set a playback position. Only [resumePolicy] can. Miss one overload and the guarantee is gone
 * entirely, which is why `MuPlayerTest` counts the policy's calls rather than merely checking that
 * one of them happened, and why it drives each overload through a policy answering a **non-zero**
 * position: an overload that was left un-overridden still lands the player at 0, which is
 * indistinguishable from a correctly-resolved `NeverResume`.
 *
 * (The idea is Voice's. Voice is GPL and none of it was read: this is written from spec §3's
 * description, which is what the licence constraint requires.)
 *
 * ### What is discarded, and what is emphatically not
 *
 * The caller's **position** is discarded. The caller's **index** is not: it is handed to
 * [ResumePolicy.resolve] as `requestedIndex` and [NeverResume] returns it unchanged, so
 * `setMediaItems(album, 3, 0L)` still starts on track 3. That is the whole reason the policy's
 * signature takes an index at all -- an index is queue membership, a position is progress -- and it
 * is what keeps this seam compatible with Task 6's fix to `PlaybackLauncher`, which exists precisely
 * so that "play track 3 of this album" starts on track 3. `MuPlayerTest` asserts both halves of that
 * sentence in one observation: 30 seconds asked for, zero delivered; index 1 asked for, index 1
 * delivered.
 *
 * The overloads that take a `resetPosition` flag ignore it, deliberately: `resetPosition = false`
 * means "keep the current position", which is precisely the caller-chosen position this class exists
 * to remove. The policy is asked instead, every time. Those overloads name no index either, so the
 * requested index they report is 0 -- Media3's own default for them.
 *
 * ### Threading
 *
 * Every override runs on the player's application thread, because `ForwardingPlayer` does no
 * thread-hopping and Media3 requires it. [ResumePolicy]'s own documentation therefore forbids a
 * blocking implementation, and this is the call site that makes that a hard requirement rather
 * than a preference.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn` -- see `MuPlayerFactory` for the full argument.
// `ForwardingPlayer` is `@UnstableApi`, which the Kotlin compiler cannot see at all; without this
// the file compiles clean and `check` fails much later at `lintDebug` with `UnsafeOptInUsageError`.
@OptIn(UnstableApi::class)
class MuPlayer(
  player: Player,
  private val resumePolicy: ResumePolicy,
  private val transcodeSeek: TranscodeSeekSupport = TranscodeSeekSupport.None,
) : ForwardingPlayer(player) {

  /** Every listener added through this class, so its own command changes can reach them. */
  private val listeners = CopyOnWriteArrayList<Player.Listener>()

  /** On the player's own application thread, which is where Media3 requires a callback to arrive. */
  private val announcements = Handler(player.applicationLooper)

  private var announcedCommands: Player.Commands? = null

  private var released = false

  init {
    // On the WRAPPED player, so this class hears every event without competing with the listeners
    // its own callers registered. `onEvents` rather than the individual callbacks: the command set
    // here depends on the current item, and every event that can change which item is current --
    // a timeline change, a transition, a `replaceMediaItem` -- arrives inside one.
    player.addListener(
      object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = scheduleCommandAnnouncement()
      },
    )
  }

  override fun setMediaItem(mediaItem: MediaItem) = setResolved(listOf(mediaItem), 0)

  override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
    setResolved(listOf(mediaItem), 0)

  override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
    setResolved(listOf(mediaItem), 0)

  override fun setMediaItems(mediaItems: MutableList<MediaItem>) = setResolved(mediaItems, 0)

  override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) =
    setResolved(mediaItems, 0)

  override fun setMediaItems(
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) = setResolved(mediaItems, startIndex)

  /**
   * The one place a queue is actually set. Note what is *not* a parameter: the caller's position.
   *
   * A `private` funnel rather than six bodies, so "the position comes from the policy" is one line
   * that six overloads share instead of six lines that have to agree.
   */
  private fun setResolved(mediaItems: List<MediaItem>, requestedIndex: Int) {
    val target = resumePolicy.resolve(mediaItems.map { it.mediaId }, requestedIndex)
    super.setMediaItems(mediaItems.toMutableList(), target.startIndex, target.startPositionMs)
  }

  // ---- Transcoded seek (Task 12) -------------------------------------------------------------
  //
  // Everything below is about ONE thing: a stream the server is transcoding right now has no
  // `Content-Length` and answers `Accept-Ranges: none`, so `super.seekTo` on it either does
  // nothing or resolves against a length the player does not have. Nothing throws. See
  // `TranscodeSeek` for the decision and `TranscodeOffsetSupport` for the capability gate.
  //
  // **This does not weaken the seam above.** A re-issued item is prepared at position `0` -- the
  // offset lives in the URI, not in a position argument -- so it is still true that no
  // caller-supplied position reaches the wrapped player. What reaches it is a different URI.

  override fun seekTo(positionMs: Long) = seekCurrentTo(positionMs)

  /**
   * A seek that names an item.
   *
   * An index other than the current one is queue navigation, and goes straight through: the target
   * item's format is not this item's, and `MuPlayer` has never guarded a position on
   * `seekTo(index, position)` -- it guards `setMediaItem(s)`, which is where a queue is chosen.
   * Widening it here would be a behaviour change wearing a transcode's clothes.
   */
  override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
    if (mediaItemIndex != currentMediaItemIndex) {
      super.seekTo(mediaItemIndex, positionMs)
      return
    }
    seekCurrentTo(positionMs)
  }

  private fun seekCurrentTo(positionMs: Long) {
    val item = currentMediaItem
    if (item == null) {
      super.seekTo(positionMs)
      return
    }
    when (val method = transcodeSeek.methodFor(item, positionMs)) {
      SeekMethod.InPlace -> super.seekTo(positionMs)
      // Withdrawn, not swallowed: `isCommandAvailable` already said no, so nothing in a
      // well-behaved controller can reach this. Reaching it anyway must not move the bar.
      SeekMethod.NotOffered -> Unit
      is SeekMethod.ReissueWithOffset -> {
        val index = currentMediaItemIndex
        val wasPlaying = playWhenReady
        // `replaceMediaItem`, not `setMediaItem`: the rest of the queue is untouched, so a seek
        // inside track 4 of a shuffle does not discard tracks 5 onwards. And it goes to the
        // WRAPPED player rather than through this class's own overrides because it is not a new
        // user request -- nothing is being decided here that the seam has not already decided,
        // and routing it through `setMediaItem` would hand the whole queue back to the resume
        // policy in the middle of a seek.
        wrappedPlayer.replaceMediaItem(index, transcodeSeek.reissue(item, method.timeOffsetSeconds))
        wrappedPlayer.seekTo(index, 0L)
        wrappedPlayer.prepare()
        wrappedPlayer.playWhenReady = wasPlaying
      }
    }
  }

  /**
   * How far into the real track the wrapped player's zero is, read off the **current item** rather
   * than held in a field here.
   *
   * A field would have to be reset on every item transition and by every `setMediaItem(s)`
   * overload, and one that is a single transition stale reports a position from the previous track.
   * `MediaItems.KEY_TIME_OFFSET_MS` travels with the item that has the offset in its URI, so there
   * is nothing to reset and no way to forget.
   */
  private fun offsetBaseMs(): Long = currentMediaItem?.let { MediaItems.timeOffsetMsOf(it) } ?: 0L

  // Every position this player reports is real-track time. A UI showing the re-issued stream's own
  // clock would jump to 0:00 on every seek and count up from there -- a seek that worked,
  // displayed as one that did not.
  override fun getCurrentPosition(): Long = offsetBaseMs() + super.getCurrentPosition()

  override fun getContentPosition(): Long = offsetBaseMs() + super.getContentPosition()

  override fun getBufferedPosition(): Long = offsetBaseMs() + super.getBufferedPosition()

  /**
   * The whole track's duration, not what is left of it.
   *
   * Observable only when the offset stream came back with a length, which for Navidrome means it
   * was served out of its **transcoding cache** rather than produced live (measured: a warm
   * `timeOffset=20` entry answers `Content-Length: 100449` and `Accept-Ranges: bytes`, a cold one
   * answers chunked with neither). On a live transcode `super.getDuration()` is `C.TIME_UNSET` and
   * this override provably changes nothing -- which is why the device test that gates it warms the
   * entry first rather than hoping.
   */
  override fun getDuration(): Long = withOffsetBase(super.getDuration())

  override fun getContentDuration(): Long = withOffsetBase(super.getContentDuration())

  private fun withOffsetBase(durationMs: Long): Long =
    if (durationMs == C.TIME_UNSET) C.TIME_UNSET else offsetBaseMs() + durationMs

  /**
   * Seeking backward and forward go through the same decision as any other seek.
   *
   * Overridden because `ForwardingPlayer` delegates them to the **wrapped** player, which resolves
   * them against its own position and its own timeline -- so on a re-issued transcode they would
   * jump relative to the re-issued stream's zero and, on a live one, do nothing at all. Advertising
   * [Player.COMMAND_SEEK_BACK] and [Player.COMMAND_SEEK_FORWARD] on a transcode (see
   * [getAvailableCommands]) is only honest if they actually work, and this is what makes them.
   *
   * `currentPosition` here is this class's own, so the arithmetic is in real-track time.
   */
  override fun seekBack() = seekCurrentTo(currentPosition - seekBackIncrement)

  override fun seekForward() = seekCurrentTo(currentPosition + seekForwardIncrement)

  /**
   * The honest form of spec section 4's *"unsupported features are silent no-ops, not errors"* --
   * and, in the other direction, the reason a transcode can be seeked at all.
   *
   * Both this and [getAvailableCommands] are overridden, and both are needed: Media3's transport
   * controls and **`MediaSession`'s own permission check** read the command *set*, while
   * application code asks the single-command question. Answering them differently is a UI that
   * disables a button the code still honours, or the reverse.
   *
   * ### Why the answer is *added*, not only removed
   *
   * Measured on the device: an `ExoPlayer` playing a live transcode does not offer
   * `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` at all, because `ProgressiveMediaSource` reports a stream
   * with no `Content-Length` as an unseekable timeline window -- correctly, for a byte-range seek.
   * So leaving `super`'s answer alone would have made the whole feature unreachable from the app:
   * the session would refuse a controller's `seekTo` before `MuPlayer` ever saw it, and the only
   * tests that passed would be the ones that call this class directly.
   *
   * This player *can* seek that stream, by re-issuing its URI. Saying so is the point.
   */
  override fun isCommandAvailable(command: Int): Boolean =
    if (command in SEEK_COMMANDS) {
      when (seekMethodForCurrentItem()) {
        SeekMethod.NotOffered -> false
        is SeekMethod.ReissueWithOffset -> true
        else -> super.isCommandAvailable(command)
      }
    } else {
      super.isCommandAvailable(command)
    }

  override fun getAvailableCommands(): Player.Commands =
    when (seekMethodForCurrentItem()) {
      SeekMethod.NotOffered ->
        Player.Commands.Builder().addAll(super.getAvailableCommands())
          .removeAll(*SEEK_COMMANDS).build()
      is SeekMethod.ReissueWithOffset ->
        Player.Commands.Builder().addAll(super.getAvailableCommands())
          .addAll(*SEEK_COMMANDS).build()
      else -> super.getAvailableCommands()
    }

  /**
   * How the current item would be seeked, asked at position zero because the *method* does not
   * depend on the target -- only the offset inside [SeekMethod.ReissueWithOffset] does.
   *
   * [SeekMethod.InPlace] for no item at all, which is `super`'s answer and therefore no change.
   */
  private fun seekMethodForCurrentItem(): SeekMethod =
    currentMediaItem?.let { transcodeSeek.methodFor(it, 0L) } ?: SeekMethod.InPlace

  // ---- announcing a command set the wrapped player does not know it has ----------------------
  //
  // MEASURED, and the whole feature was unreachable from the app without it.
  //
  // `MediaControllerImplBase.seekTo(long)` starts with `if (!isPlayerCommandAvailable(
  // COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return;` -- read off the 1.11.0 bytecode -- and the set it
  // checks is the one the **session pushed to it**, not one it re-reads from the player. The
  // session pushes on `Player.Listener.onAvailableCommandsChanged`, passing the *argument* through
  // (`MediaSessionImpl$PlayerListener`, same bytecode), and `ForwardingPlayer.ForwardingListener`
  // forwards the WRAPPED player's commands verbatim. An `ExoPlayer` playing a live transcode never
  // fires that callback with the seek command in it, because the stream is not seekable to it -- so
  // overriding [getAvailableCommands] alone changed the answer for anything that asked this object
  // directly, and changed nothing at all for the seek bar. Measured on the device: the bar moved,
  // the readout stayed at 0:01, and no seek ever reached this class.
  //
  // So this class announces its own answer. Three details are load-bearing:
  //
  //  * The announcement is **posted**, not sent inline. `ForwardingListener` will forward the
  //    wrapped player's own (unmodified) commands to the same listeners during the current event
  //    batch, and the last write wins; a post lands after that batch.
  //  * Listeners are tracked here as well as forwarded to `super`, because `ForwardingPlayer`
  //    exposes no way to reach them and gives each one a private wrapper.
  //  * Only a **change** is announced. Without the comparison this posts a callback on every
  //    player event, for a value that almost never moves.

  override fun addListener(listener: Player.Listener) {
    listeners.addIfAbsent(listener)
    super.addListener(listener)
  }

  override fun removeListener(listener: Player.Listener) {
    listeners.remove(listener)
    super.removeListener(listener)
  }

  /**
   * Releases the wrapped player and stops announcing.
   *
   * The order matters: a posted announcement that ran after the release would ask a released player
   * for its commands, which is an `IllegalStateException` on a background of nothing being wrong.
   */
  override fun release() {
    released = true
    announcements.removeCallbacksAndMessages(null)
    super.release()
  }

  private fun scheduleCommandAnnouncement() {
    announcements.post {
      if (released) return@post
      val commands = availableCommands
      if (commands == announcedCommands) return@post
      announcedCommands = commands
      listeners.forEach { it.onAvailableCommandsChanged(commands) }
    }
  }

  private companion object {
    /**
     * The commands this class answers for on a transcode -- withdrawn when the server cannot start
     * one part-way through, and **granted** when it can, because the wrapped player does not offer
     * them for an unseekable stream and would otherwise veto the feature.
     *
     * `COMMAND_SEEK_TO_DEFAULT_POSITION` is deliberately **not** among them: restarting an item
     * from the top needs no offset and works on a live transcode exactly as it always did.
     */
    val SEEK_COMMANDS = intArrayOf(
      Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
      Player.COMMAND_SEEK_BACK,
      Player.COMMAND_SEEK_FORWARD,
    )
  }
}

/**
 * What [MuPlayer] needs to know to seek a transcode: which of the three [SeekMethod]s applies to an
 * item, and how to rebuild that item at an offset.
 *
 * An interface with an inert [None] default rather than a constructor parameter every caller must
 * supply, so that every test written before this task compiles and keeps meaning what it meant --
 * `MuPlayerTest`'s assertions about the six `setMediaItem(s)` overloads stay assertions about those
 * overloads. [None] answers [SeekMethod.InPlace] for everything, which is precisely what a
 * `ForwardingPlayer` did before Task 12 existed.
 *
 * `TranscodeOffsetSupport` is the production implementation.
 */
interface TranscodeSeekSupport {

  fun methodFor(mediaItem: MediaItem, targetPositionMs: Long): SeekMethod

  fun reissue(mediaItem: MediaItem, timeOffsetSeconds: Int): MediaItem

  /**
   * Makes sure this server's capabilities have been negotiated, if they have not been already.
   *
   * `suspend`, and therefore **not** something [MuPlayer] can call: a seek runs on the player's
   * application thread and cannot wait for a network round trip. `PlaybackLauncher` calls it, which
   * is both the one entry point to playback in this app and the earliest moment at which the answer
   * can be obtained at all -- credentials have to exist before a stream URL can be built, and a
   * capability query made before the user has signed in cannot succeed.
   *
   * That ordering is the whole reason this is on the interface rather than on the implementation.
   * It was tried in `MuPlaybackService.onCreate` first, which is *earlier* than signing in: the
   * negotiation could fail, nothing retried it, and the seek bar was then withdrawn for the rest of
   * the session -- correct behaviour for a server that cannot seek a transcode, and a silent
   * removal of the feature for one that can.
   */
  suspend fun refreshIfUnknown()

  /** Seek everything in place and rebuild nothing -- the behaviour before this task. */
  object None : TranscodeSeekSupport {
    override fun methodFor(mediaItem: MediaItem, targetPositionMs: Long) = SeekMethod.InPlace

    override fun reissue(mediaItem: MediaItem, timeOffsetSeconds: Int) = mediaItem

    override suspend fun refreshIfUnknown() = Unit
  }
}
