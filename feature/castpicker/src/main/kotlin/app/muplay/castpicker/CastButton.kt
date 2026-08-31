package app.muplay.castpicker

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.theme.MuPlayIcons

/**
 * The control that opens the picker, and says where audio is going.
 *
 * Split into a stateful entry point and an `internal` stateless overload, the shape `PlayerScreen`
 * and `LibraryScreen` already use here — and the reason is the same: the overload is what
 * `CastButtonTest` composes on a device against a name built by hand, with no Hilt graph and no
 * speaker.
 *
 * **An icon button.** It was a text button reading `Cast`, matching `PlayerScreen`'s own recorded
 * decision at the time ("this project has no icon set and no icon-content-description
 * convention"). Both halves of that are now false: `:core:designsystem` draws the icon set, and
 * the transport row this button sits in is icons -- one word among four glyphs read as a control
 * somebody forgot.
 *
 * Nothing about its semantics changed, and that is why the swap was cheap: the description was
 * always on the button rather than on the `Text`, and it always carried the connected speaker's
 * name, so a journey can assert *which* state the button is in and a user with TalkBack hears
 * where their audio is going. `CastButtonTest` already found it with
 * `onNodeWithContentDescription` and needed no edit at all.
 */
@Composable
fun CastButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: CastViewModel = hiltViewModel(),
) {
  val connected by viewModel.connectedDeviceName.collectAsStateWithLifecycle()
  CastButton(connectedDeviceName = connected, onClick = onClick, modifier = modifier)
}

@Composable
internal fun CastButton(
  connectedDeviceName: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val description = castButtonDescription(connectedDeviceName)
  IconButton(
    onClick = onClick,
    // On the button, not on the `Icon`: the merged semantics node a test and a screen reader both
    // see is the clickable one, and a description on the child would be replaced by the parent's
    // merge rather than added to it. That also keeps the speaker's name in the description --
    // `Icon`'s own `contentDescription` could only carry the bare label.
    modifier = modifier.semantics { contentDescription = description },
  ) {
    Icon(MuPlayIcons.Cast, contentDescription = null)
  }
}
