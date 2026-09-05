package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What is queued, where playback is in it, and every index the queue screen is allowed to hand a
 * `MediaController`.
 *
 * The guards are the point of this file. `Player.removeMediaItem`, `moveMediaItem` and
 * `seekTo(index, ..)` all take an index into a timeline the caller does not own: the queue screen
 * renders a snapshot, the user taps a row, and in between a track can have ended, a car can have
 * skipped, or a `playNext` from a browse screen can have inserted something. An index that was
 * valid when it was drawn is an `IllegalSeekPositionException` a moment later, and Media3 throws
 * that out of the controller rather than ignoring it. So every index this app sends is checked
 * against the snapshot the *caller* was looking at, here, on the fast tier -- there is no
 * `MediaController` in this file and there does not need to be.
 */
class QueueSnapshotTest {

  private fun queue(size: Int, currentIndex: Int = 0) = QueueSnapshot(
    items = List(size) { QueueItem(mediaId = "track-$it", title = "Track $it", artist = "Artist") },
    currentIndex = currentIndex,
  )

  @Test
  fun `an empty queue has nothing playing rather than an item at index zero`() {
    assertThat(QueueSnapshot.EMPTY.items).isEmpty()
    assertThat(QueueSnapshot.EMPTY.currentItem).isNull()
  }

  @Test
  fun `the current item is the one the current index names`() {
    // Two items and a non-zero index: with one item, or with index 0, a `currentItem` that always
    // answered `items.first()` would pass.
    assertThat(queue(size = 3, currentIndex = 2).currentItem?.mediaId).isEqualTo("track-2")
  }

  @Test
  fun `a current index the queue no longer has answers nothing rather than throwing`() {
    // The queue shrinks under the screen -- a `remove` from a car, a track dropped by a rescan --
    // and one frame later something reads `currentItem`. `getOrNull`, not `get`.
    assertThat(queue(size = 2, currentIndex = 5).currentItem).isNull()
  }

  // ---- where "play next" inserts ----------------------------------------------------------------

  @Test
  fun `play next inserts directly after whatever is playing`() {
    assertThat(queue(size = 4, currentIndex = 1).playNextIndex).isEqualTo(2)
  }

  @Test
  fun `play next on the last item inserts at the end rather than past it`() {
    // `addMediaItems(index, ..)` accepts `size` -- that is "append" -- and rejects `size + 1`. The
    // last item is where the difference between those two shows up.
    assertThat(queue(size = 3, currentIndex = 2).playNextIndex).isEqualTo(3)
  }

  @Test
  fun `play next on an empty queue inserts at the front`() {
    // Nothing is playing, so "after what is playing" has no answer and 0 is the only valid index
    // into an empty timeline. `currentIndex + 1` would be 1 and would throw.
    assertThat(QueueSnapshot.EMPTY.playNextIndex).isZero
  }

  @Test
  fun `play next with a stale current index still names a real place in the queue`() {
    assertThat(queue(size = 2, currentIndex = 9).playNextIndex).isEqualTo(2)
    assertThat(queue(size = 2, currentIndex = -3).playNextIndex).isZero
  }

  // ---- which edits the screen is allowed to ask for ---------------------------------------------

  @Test
  fun `a row that is on the queue can be removed and one past the end cannot`() {
    val queue = queue(size = 3)

    assertThat(queue.canRemove(0)).isTrue
    assertThat(queue.canRemove(2)).isTrue
    assertThat(queue.canRemove(3)).isFalse
    assertThat(queue.canRemove(-1)).isFalse
  }

  @Test
  fun `nothing can be removed from an empty queue`() {
    assertThat(QueueSnapshot.EMPTY.canRemove(0)).isFalse
  }

  @Test
  fun `a move needs two real rows`() {
    val queue = queue(size = 3)

    assertThat(queue.canMove(from = 0, to = 2)).isTrue
    assertThat(queue.canMove(from = 2, to = 0)).isTrue
    assertThat(queue.canMove(from = 0, to = 3)).isFalse
    assertThat(queue.canMove(from = 3, to = 0)).isFalse
  }

  @Test
  fun `moving a row to where it already is is not a move`() {
    // The top row's "up" and the bottom row's "down" both produce this, on every queue. Sending it
    // to the controller is not a crash, it is a redundant timeline change that republishes the
    // whole queue and scrolls the list -- so it is refused here rather than absorbed there.
    assertThat(queue(size = 3).canMove(from = 1, to = 1)).isFalse
  }
}
