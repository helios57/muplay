package app.muplay.castpicker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The picker.
 *
 * Renders, in order: a title; the connected speaker with [CAST_DISCONNECT_LABEL] when there is one;
 * the other speakers, tappable; the remembered speakers that did not answer, named and **not**
 * tappable; a search-again action; and — only while something is connected — a volume slider and
 * [CAST_SPEED_LIMIT_NOTICE].
 *
 * Split into a stateful entry point and an `internal` stateless overload for the same reason
 * `PlayerScreen` is: the overload is what `CastPickerSheetTest` composes on a device against a
 * [CastUiState] built by hand, with no Hilt graph, no network and no speaker.
 *
 * **Nothing here renders a URL.** The only strings that reach a label are a device's friendly name,
 * its model name, and a sentence [castFailure] built from the device's name — see that function for
 * why the classified failures discard the protocol string they were classified from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastPickerSheet(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: CastViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
    CastPickerSheet(
      uiState = uiState,
      onSelect = viewModel::select,
      onDisconnect = viewModel::disconnect,
      onRefresh = viewModel::refresh,
      onVolume = viewModel::setVolume,
    )
  }
}

@Composable
internal fun CastPickerSheet(
  uiState: CastUiState,
  onSelect: (String) -> Unit,
  onDisconnect: () -> Unit,
  onRefresh: () -> Unit,
  onVolume: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(text = CAST_TITLE, style = MaterialTheme.typography.headlineSmall)

    when (uiState) {
      // The sheet is not shown at all in this state; the arm exists because `CastUiState` is a
      // sealed interface and a `when` over it that silently did nothing for one arm is how a screen
      // ends up blank with no error.
      CastUiState.Hidden -> Unit

      CastUiState.Searching -> {
        CircularProgressIndicator()
        Text(text = CAST_SEARCHING_LABEL, style = MaterialTheme.typography.bodyMedium)
      }

      is CastUiState.Failed -> {
        Text(text = uiState.message, style = MaterialTheme.typography.bodyMedium)
        // "Try again" and not "search again": the thing that failed was a cast, and a fresh search
        // is what puts the user back in front of a list they can choose from.
        TextButton(onClick = onRefresh) { Text(CAST_RETRY_LABEL) }
      }

      is CastUiState.Devices -> Devices(
        uiState = uiState,
        onSelect = onSelect,
        onDisconnect = onDisconnect,
        onRefresh = onRefresh,
        onVolume = onVolume,
      )
    }
  }
}

@Composable
private fun Devices(
  uiState: CastUiState.Devices,
  onSelect: (String) -> Unit,
  onDisconnect: () -> Unit,
  onRefresh: () -> Unit,
  onVolume: (Float) -> Unit,
) {
  val connected = uiState.devices.firstOrNull { it.isConnected }
  if (connected != null) {
    // Rendered here and excluded from the list below, rather than rendered twice with a tick: two
    // rows carrying one speaker's name is one a user can tap the wrong copy of.
    DeviceRow(row = connected, onClick = null)
    TextButton(onClick = onDisconnect) { Text(CAST_DISCONNECT_LABEL) }
  }

  uiState.devices
    .filterNot { it.isConnected }
    .forEach { row -> DeviceRow(row = row, onClick = { onSelect(row.udn) }) }

  if (uiState.devices.isEmpty()) {
    Text(text = CAST_NO_DEVICES_LABEL, style = MaterialTheme.typography.bodyMedium)
  }

  // Named, and **not** clickable: `RendererDirectory` surfaces a remembered speaker that did not
  // answer rather than dropping it, and this is where that becomes something a user can act on.
  // Tapping one would start a cast to an address nothing is listening at.
  uiState.unreachable.forEach { name ->
    Text(
      text = "$name — $CAST_UNREACHABLE_SUFFIX",
      style = MaterialTheme.typography.bodySmall,
    )
  }

  TextButton(onClick = onRefresh) { Text(CAST_REFRESH_LABEL) }

  // Absent, not inert, when nothing is connected -- there is no speaker whose volume this would be.
  if (uiState.volumePercent != null) {
    Text(text = CAST_VOLUME_LABEL, style = MaterialTheme.typography.bodyMedium)
    Slider(
      value = uiState.volumePercent / PERCENT,
      onValueChange = onVolume,
      modifier = Modifier.fillMaxWidth(),
    )
    // Task 5: a renderer accepts only `Speed = "1"`. Said out loud while casting, because a per-item
    // speed that is silently not applied is a setting the user believes is on.
    Text(text = CAST_SPEED_LIMIT_NOTICE, style = MaterialTheme.typography.bodySmall)
  }
}

/**
 * One speaker.
 *
 * [onClick] is `null` for the speaker already being cast to, which makes the row unclickable rather
 * than merely un-highlighted — re-casting to the current speaker would tear down and rebuild a
 * working session.
 */
@Composable
private fun DeviceRow(row: CastDeviceRow, onClick: (() -> Unit)?) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .let { if (onClick == null) it else it.clickable(onClick = onClick) }
      .padding(vertical = 4.dp),
  ) {
    Text(text = row.name, style = MaterialTheme.typography.bodyLarge)
    // The model when the device reported one, and "Sonos speaker" when it did not but is one.
    // "Sonos One" tells a user more than "Sonos" does, which is why the model wins.
    val supporting = row.subtitle ?: CAST_SONOS_FALLBACK_SUBTITLE.takeIf { row.isSonos }
    if (supporting != null) {
      Text(text = supporting, style = MaterialTheme.typography.bodySmall)
    }
  }
}

private const val PERCENT = 100f
