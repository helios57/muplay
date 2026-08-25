package app.muplay.media

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

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
class MuPlayer(player: Player, private val resumePolicy: ResumePolicy) : ForwardingPlayer(player) {

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
}
