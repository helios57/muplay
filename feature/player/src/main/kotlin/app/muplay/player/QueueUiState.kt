package app.muplay.player

import app.muplay.media.QueueSnapshot

/**
 * What the queue screen renders.
 *
 * A sealed interface for the same reason [PlayerUiState] is one: a `when` over it is exhaustive at
 * every call site, so a state added later cannot be silently left un-drawn.
 */
sealed interface QueueUiState {

  /** Nothing is queued. The screen says so rather than drawing an empty list. */
  data object Empty : QueueUiState

  data class Content(val rows: List<QueueRow>) : QueueUiState
}

/**
 * One line of the queue.
 *
 * @property index what the **player** calls this row, which is what every edit is addressed by.
 *   Kept as a field rather than left to the `itemsIndexed` the screen happens to use, so that the
 *   number sent to [app.muplay.media.QueueEditor] comes from the snapshot the row was built from
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
