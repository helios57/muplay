package app.muplay.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing

/**
 * The one control that puts a track somewhere in the queue without taking over what is playing.
 *
 * **Here rather than in each screen**, because three screens list tracks -- an album, a folder and
 * a playlist -- and a control repeated three times becomes three controls. The queue is one idea
 * and it gets one affordance.
 *
 * **A menu rather than two buttons in the row**, and that is a tap-target decision measured
 * elsewhere in this repository rather than a taste one: a track row is already tappable, and
 * `TapTargets`' sweep records what happens when small controls are packed into one -- adjacent
 * targets grow to Material's 48dp minimum and then fight over the same pixels. One button opens a
 * menu whose two items are full-width rows, which is the layout that cannot collide with anything.
 *
 * The row's own tap still plays immediately; nothing here changes that. That is the distinction the
 * user asked for -- "enqueue a song instead of playing it directly" -- and it stays a distinction
 * only while the direct path is left alone.
 */
@Composable
fun AddToQueueButton(
  onPlayNext: () -> Unit,
  onAddToQueue: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var open by remember { mutableStateOf(false) }
  IconButton(onClick = { open = true }, modifier = modifier) {
    Icon(
      imageVector = MuPlayIcons.QueueAdd,
      contentDescription = QUEUE_MENU_LABEL,
      modifier = Modifier.size(MuPlaySpacing.xl),
    )
  }
  DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
    DropdownMenuItem(
      text = { Text(PLAY_NEXT_LABEL) },
      onClick = {
        open = false
        onPlayNext()
      },
    )
    DropdownMenuItem(
      text = { Text(ADD_TO_QUEUE_LABEL) },
      onClick = {
        open = false
        onAddToQueue()
      },
    )
  }
}

/**
 * The button's accessible name, and the menu's two items.
 *
 * Public because three feature modules render this control and their journeys find it by these
 * exact strings; a retyped copy per screen is three chances for one of them to say something
 * different from what the button does.
 */
const val QUEUE_MENU_LABEL: String = "Queue options"

const val PLAY_NEXT_LABEL: String = "Play next"

const val ADD_TO_QUEUE_LABEL: String = "Add to queue"
