package io.github.helios57.muplay.player

import io.github.helios57.muplay.media.QueueSnapshot

/**
 * What the queue screen renders.
 *
 * A sealed interface for the same reason [PlayerUiState] is one: a `when` over it is exhaustive at
 * every call site, so a state added later cannot be silently left un-drawn.
 */
sealed interface QueueUiState {

  /** Nothing is queued. The screen says so rather than drawing an empty list. */
  data object Empty : QueueUiState

  data class Content(val rows: List<QueueRow>) : QueueUiState {

    /**
     * Where the playing row sits in [rows], or `null` when none of them is playing.
     *
     * Computed once here rather than searched for twice, because two callers want it and they must
     * agree: the header counts from it and the list scrolls to it. A header reading "2 of 3" over a
     * list opened on row 5 is a worse screen than either mistake on its own.
     *
     * `null` is a real answer and not a missing one. [QueueSnapshot.currentIndex] can name an item
     * the timeline no longer has -- that class's own header says why -- and the honest thing to
     * draw then is a count, not a position.
     */
    val currentPosition: Int? get() = rows.indexOfFirst { it.isCurrent }.takeIf { it >= 0 }

    /**
     * The one line under the title: where playback is, or how much is waiting.
     *
     * "2 of 3" is what a listener wants nine times in ten -- how far through this queue am I -- and
     * it is only answerable while something in the queue is playing. Otherwise the queue is a pile
     * of tracks and its size is the only true thing to say about it.
     */
    val summary: String
      get() {
        val position = currentPosition ?: return if (rows.size == 1) {
          "1 track"
        } else {
          "${rows.size} tracks"
        }
        return "${position + 1} of ${rows.size}"
      }
  }
}

/**
 * One line of the queue.
 *
 * @property index what the **player** calls this row, which is what every edit is addressed by.
 *   Kept as a field rather than left to the `itemsIndexed` the screen happens to use, so that the
 *   number sent to [io.github.helios57.muplay.media.QueueEditor] comes from the snapshot the row was built from
 *   and not from a position in whatever list the screen drew.
 * @property key what Compose identifies this row by across recompositions. **Not [mediaId]**: see
 *   `the same song queued twice gets two distinct keys`.
 */
data class QueueRow(
  val index: Int,
  val mediaId: String,
  val title: String,
  val artist: String?,
  val isCurrent: Boolean,
  val canMoveUp: Boolean,
  val canMoveDown: Boolean,
) {
  val key: String get() = "$index:$mediaId"
}

/**
 * Pure mapping, on the fast tier, for the reason `playerUiState` is a function and not a
 * `ViewModel` method.
 *
 * The two arrow guards go through [QueueSnapshot.canMove] rather than comparing `index` against
 * `rows.lastIndex` here, so that "which indices exist" has exactly one definition and the screen
 * cannot offer a move the editor will then refuse.
 */
internal fun queueUiState(snapshot: QueueSnapshot): QueueUiState =
  if (snapshot.items.isEmpty()) {
    QueueUiState.Empty
  } else {
    QueueUiState.Content(
      rows = snapshot.items.mapIndexed { index, item ->
        QueueRow(
          index = index,
          mediaId = item.mediaId,
          title = item.title,
          artist = item.artist,
          isCurrent = index == snapshot.currentIndex,
          canMoveUp = snapshot.canMove(from = index, to = index - 1),
          canMoveDown = snapshot.canMove(from = index, to = index + 1),
        )
      },
    )
  }
