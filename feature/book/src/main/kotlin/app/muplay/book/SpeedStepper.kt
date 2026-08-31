package app.muplay.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.model.BookSettings

/**
 * `−  Speed 1.4x  +`, as one control rather than three.
 *
 * **Shared by the book screen and the book player**, which is why it is a file of its own rather
 * than a private function in either: both set the same per-book setting, they meet at the same
 * Room row (`BookViewModel` writes it before playback, `BookPlayerViewModel` during), and two
 * screens that set one setting should not offer it two different ways. It is `internal` for the
 * same reason `BookContent` is -- the device suites compose it through those two screens.
 *
 * The division of labour between glyph and word is forced rather than chosen: **a number cannot be
 * an icon**, so the readout stays text (`formatSpeed`, still `Speed 1.4x`, still asserted verbatim
 * by three suites), and the two steppers become icons carrying [SLOWER_LABEL] and [FASTER_LABEL]
 * as their `contentDescription`s. Those two words either side of `Speed 1.4x` used to read as a
 * three-word sentence rather than as a stepper.
 *
 * The raw sum is passed up and the clamp is `setSpeed`'s: two places that both clamp is two places
 * that are each half right.
 */
@Composable
internal fun SpeedStepper(speed: Float, onSpeed: (Float) -> Unit, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = MuPlaySpacing.sm),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = { onSpeed(speed - BookSettings.SPEED_STEP) }) {
        Icon(MuPlayIcons.Minus, contentDescription = SLOWER_LABEL)
      }
      Text(
        text = formatSpeed(speed),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      IconButton(onClick = { onSpeed(speed + BookSettings.SPEED_STEP) }) {
        Icon(MuPlayIcons.Plus, contentDescription = FASTER_LABEL)
      }
    }
  }
}
