package app.muplay.castpicker

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The control that opens the picker, and says where audio is going.
 *
 * Split into a stateful entry point and an `internal` stateless overload, the shape `PlayerScreen`
 * and `LibraryScreen` already use here — and the reason is the same: the overload is what
 * `CastButtonTest` composes on a device against a name built by hand, with no Hilt graph and no
 * speaker.
 *
 * A **text** button rather than an icon, matching `PlayerScreen`'s own recorded decision (this
 * project has no icon set and no icon-content-description convention). Its content description
 * carries the connected speaker's name, so a journey can assert *which* state the button is in and
 * a user with TalkBack hears where their audio is going.
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
  TextButton(
    onClick = onClick,
    // On the button, not on the `Text`: the merged semantics node a test and a screen reader both
    // see is the clickable one, and a description on the child would be replaced by the parent's
    // merge rather than added to it.
    modifier = modifier.semantics { contentDescription = description },
  ) {
    Text(CAST_BUTTON_LABEL)
  }
}
