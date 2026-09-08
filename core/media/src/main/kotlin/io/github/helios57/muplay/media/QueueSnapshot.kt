package io.github.helios57.muplay.media

/**
 * One row of the queue, as the UI needs it.
 *
 * Three strings and nothing else -- deliberately not a `Song` and deliberately not a `MediaItem`.
 * A `MediaItem` carries the stream URI, and in this app a stream URI carries `u`, `t` and `s`,
 * which are password equivalents; [PlaybackState]'s own header states the rule this follows, that
 * the surest way never to leak a stream URL is not to carry it to a layer whose job is to display
 * things. A `Song` would be the mirror's row, which the player's timeline is not: what is queued
 * is whatever was queued, including items a later sync has removed from the mirror entirely.
 */
data class QueueItem(val mediaId: String, val title: String, val artist: String?)

/**
 * What is queued and where playback is in it.
 *
 * Published by [PlaybackConnection] from the player's own timeline rather than kept alongside it.
 * That is the only version that can be right: a car, a watch, a headset button and the Assistant
 * all edit the same queue through the same media session, and a copy this process maintained would
 * be wrong the first time any of them touched it.
 *
 * ### Every index in this class is a guard, and they exist because the screen is always stale
 *
 * The queue screen renders a snapshot; the user then taps a row. In between, a track can have
 * ended, a car can have skipped, or a "play next" from a browse screen can have inserted an item.
 * `Player.removeMediaItem`, `moveMediaItem` and `seekTo(index, ..)` all take an index into the
 * *current* timeline and Media3 throws `IllegalSeekPositionException` out of the controller for one
 * that has gone out of range -- it does not ignore it. So the caller checks the index against the
 * snapshot it was looking at, which is what [canRemove], [canMove] and [playNextIndex] are for, and
 * a check that fails is a tap that does nothing rather than a crash.
 */
data class QueueSnapshot(val items: List<QueueItem>, val currentIndex: Int) {

  /** What is playing, or `null` -- including when [currentIndex] has outlived the item it named. */
  val currentItem: QueueItem? get() = items.getOrNull(currentIndex)

  /**
   * Where an "play next" insertion goes: directly after whatever is playing.
   *
   * `items.size` is a legal argument to `Player.addMediaItems(index, ..)` and means "append", which
   * is why the clamp's upper bound is the size and not the last index. An empty queue answers 0
   * rather than 1, because "after what is playing" has no answer when nothing is.
   *
   * MEASURED: there was an `if (items.isEmpty()) 0 else ..` in front of this clamp and it was a
   * branch nothing could reach. On an empty queue `items.size` is 0, so `coerceIn(0, 0)` already
   * answers 0 whatever `currentIndex` holds -- deleting the arm changed the answer at no input and
   * no assertion in `QueueSnapshotTest` noticed. It is gone rather than kept as documentation,
   * because a guard that cannot fire reads as a case somebody thought about and is really a case
   * nothing tests. The empty queue is still asserted; the clamp is what makes it true.
   */
  val playNextIndex: Int get() = playNextIndexIn(currentIndex, items.size)

  fun canRemove(index: Int): Boolean = canRemoveFrom(index, items.size)

  /**
   * A move needs two real rows and two *different* ones. The top row's "up" and the bottom row's
   * "down" produce the equal case on every queue there is; sending it to the controller would not
   * crash, but it would republish the whole timeline for no change.
   */
  fun canMove(from: Int, to: Int): Boolean = canMoveWithin(from, to, items.size)

  companion object {
    /** Nothing queued -- what [PlaybackConnection] publishes before and after a connection. */
    val EMPTY = QueueSnapshot(items = emptyList(), currentIndex = 0)
  }
}

// ---- the same three rules, over a live timeline -----------------------------------------------
//
// [QueueEditor] cannot ask a [QueueSnapshot], and that is the point rather than an inconvenience. A
// snapshot is what a screen was *looking at*; the controller is what the timeline *is*, and between
// the two the user's tap crosses a thread hop and a media session. So the screen decides whether to
// draw a control from the snapshot, the editor decides whether to send it from the controller, and
// both ask the same three functions -- which is what stops the enabled state and the guard drifting
// into disagreeing about which taps do something.

/**
 * Where a "play next" insertion goes in a timeline of [itemCount] items whose current item is
 * [currentIndex].
 *
 * The upper bound is [itemCount] and not the last index: `Player.addMediaItems(index, ..)` accepts
 * `size` and that is what "append" means to it. The lower bound answers an empty timeline, whose
 * `currentMediaItemIndex` Media3 reports as 0 -- so `0 + 1` clamps back to 0, which is the only
 * legal index there is into a timeline with nothing in it.
 */
internal fun playNextIndexIn(currentIndex: Int, itemCount: Int): Int =
  (currentIndex + 1).coerceIn(0, itemCount)

internal fun canRemoveFrom(index: Int, itemCount: Int): Boolean = index in 0 until itemCount

internal fun canMoveWithin(from: Int, to: Int, itemCount: Int): Boolean =
  from != to && canRemoveFrom(from, itemCount) && canRemoveFrom(to, itemCount)
