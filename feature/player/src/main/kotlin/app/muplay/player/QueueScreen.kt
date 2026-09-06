package app.muplay.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.component.Message
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing

/**
 * What plays after this — visible, and editable in place.
 *
 * **It renders the media session's timeline and nothing else.** There is no queue object in this
 * app; the timeline in the session *is* the queue, which is what makes this screen agree with the
 * lock screen, the car and the watch without anything having to keep them in step. Every edit here
 * goes to the player, and the list redraws from what the player reports afterwards -- so a row that
 * refuses to move is telling the truth about a queue that changed underneath the tap.
 *
 * Split into a stateful entry point and a **stateless** overload, the shape every screen in this
 * codebase uses and, here as in `PlayerScreen`, the thing that makes it testable at all: the
 * stateless one takes a [QueueUiState] and four lambdas, so a device test composes it against a
 * state built by hand with no media session and no Hilt graph.
 */
@Composable
fun QueueScreen(modifier: Modifier = Modifier, viewModel: QueueViewModel = hiltViewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  QueueScreen(
    uiState = uiState,
    onPlay = viewModel::jumpTo,
    onRemove = viewModel::remove,
    onMoveUp = viewModel::moveUp,
    onMoveDown = viewModel::moveDown,
    modifier = modifier,
  )
}

@Composable
internal fun QueueScreen(
  uiState: QueueUiState,
  onPlay: (Int) -> Unit,
  onRemove: (Int) -> Unit,
  onMoveUp: (Int) -> Unit,
  onMoveDown: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  // **Once per visit, and only once.** The queue arrives after the screen does -- `Empty` first,
  // then the timeline -- so there is nothing to scroll to at composition and this cannot be a
  // `LaunchedEffect(Unit)`. Scrolling on every change instead would be worse than not scrolling at
  // all: a track ending while the list is open would yank it out from under a reading finger.
  // `rememberSaveable`, so a rotation is not a second visit either.
  var hasScrolledToCurrent by rememberSaveable { mutableStateOf(false) }
  val currentPosition = (uiState as? QueueUiState.Content)?.currentPosition
  LaunchedEffect(currentPosition) {
    if (!hasScrolledToCurrent && currentPosition != null) {
      hasScrolledToCurrent = true
      listState.scrollToItem(currentPosition)
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = MuPlaySpacing.gutter),
  ) {
    Text(
      text = QUEUE_TITLE,
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier
        .padding(top = MuPlaySpacing.lg)
        .semantics { heading() },
    )
    // "2 of 3" under the title. It is drawn from the same `currentPosition` the list scrolls to,
    // and it is absent rather than blank on an empty queue: there is no position and no count worth
    // reading when the next thing on the screen already says "Nothing is queued yet."
    if (uiState is QueueUiState.Content) {
      Text(
        text = uiState.summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = MuPlaySpacing.xs),
      )
    }
    Spacer(Modifier.height(MuPlaySpacing.lg))
    when (uiState) {
      // The app's one way of saying "nothing here"; see `PlayerScreen`'s own empty state.
      QueueUiState.Empty -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
      ) {
        Message(text = EMPTY_QUEUE_LABEL)
      }

      is QueueUiState.Content -> LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs),
      ) {
        // Keyed by `row.key` and not by `mediaId`: the same track can legitimately be in a queue
        // twice, and a `LazyColumn` throws on a duplicate key. See `QueueUiState`'s own test.
        items(items = uiState.rows, key = { it.key }) { row ->
          QueueRowItem(
            row = row,
            onPlay = { onPlay(row.index) },
            onRemove = { onRemove(row.index) },
            onMoveUp = { onMoveUp(row.index) },
            onMoveDown = { onMoveDown(row.index) },
          )
        }
      }
    }
  }
}

/**
 * One queued track.
 *
 * The **row** plays it and the three trailing buttons edit it. Each button is a Material
 * `IconButton`, which is 48dp square, so the four targets tile the row edge to edge without any of
 * them having to be grown into a neighbour -- the collision `TapTargets`' sweep exists to catch.
 *
 * A disabled arrow at the ends of the list rather than a hidden one: a row whose controls move
 * about as it is dragged up the queue is a row you cannot aim at.
 */
@Composable
private fun QueueRowItem(
  row: QueueRow,
  onPlay: () -> Unit,
  onRemove: () -> Unit,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(MaterialTheme.shapes.medium)
      // The row that is playing is marked by a filled background rather than by coloured text: it
      // has to be findable in a list being scrolled fast, and a tint reads at arm's length where a
      // colour change on one line of type does not.
      .background(
        if (row.isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
      )
      .heightIn(min = MuPlaySpacing.minTouchTarget),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // **The tap target is this column, not the whole row.** `Modifier.clickable` merges its
    // descendants' semantics, and a merging node has no children in the merged tree -- so three
    // buttons nested inside a clickable row would be tappable by a finger and invisible to
    // TalkBack's swipe navigation and to every merged-tree matcher. Four sibling controls keep all
    // four reachable. The same fix `AlbumScreen`'s `TrackRow` carries.
    Column(
      modifier = Modifier
        .weight(1f)
        .clickable(onClick = onPlay)
        .heightIn(min = MuPlaySpacing.minTouchTarget)
        .padding(start = MuPlaySpacing.md, top = MuPlaySpacing.sm, bottom = MuPlaySpacing.sm),
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = row.title,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (row.artist != null) {
        Text(
          text = row.artist,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    QueueRowAction(MuPlayIcons.ArrowUp, MOVE_UP_LABEL, row.canMoveUp, onMoveUp)
    QueueRowAction(MuPlayIcons.ArrowDown, MOVE_DOWN_LABEL, row.canMoveDown, onMoveDown)
    QueueRowAction(MuPlayIcons.Close, REMOVE_LABEL, enabled = true, onClick = onRemove)
  }
}

@Composable
private fun QueueRowAction(
  icon: ImageVector,
  label: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  IconButton(onClick = onClick, enabled = enabled) {
    Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(MuPlaySpacing.xl))
  }
}

/**
 * The strings this screen renders.
 *
 * Public because `:app`'s journeys and this module's own device tests find these controls by their
 * accessible names, and a retyped copy is a test that goes on passing after the screen stops saying
 * it -- the mechanism `AlbumScreen`'s `NOT_FOUND_LABEL` records at length.
 */
const val QUEUE_TITLE: String = "Play queue"

const val EMPTY_QUEUE_LABEL: String = "Nothing is queued yet."

const val MOVE_UP_LABEL: String = "Move up"

const val MOVE_DOWN_LABEL: String = "Move down"

const val REMOVE_LABEL: String = "Remove from queue"
