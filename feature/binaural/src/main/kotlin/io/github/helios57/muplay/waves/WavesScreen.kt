package io.github.helios57.muplay.waves

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.helios57.muplay.designsystem.theme.MuPlayIcons
import io.github.helios57.muplay.designsystem.theme.MuPlaySpacing

/**
 * The binaural generator: what is playing, then every profile -- programs that move through the
 * bands over time, steady tones, and the listener's own. Tap a row to play it; the pencil opens it
 * for editing.
 */
@Composable
fun WavesScreen(
  onEdit: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: WavesViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  WavesContent(
    state = state,
    onPlay = viewModel::play,
    onStop = viewModel::stop,
    onEdit = onEdit,
    onAcknowledge = viewModel::acknowledgeSafety,
    onVolumeChange = viewModel::previewVolume,
    onVolumeSet = viewModel::setVolume,
    onCreate = { viewModel.create(onEdit) },
    modifier = modifier,
  )
}

@Composable
fun WavesContent(
  state: WavesUiState,
  onPlay: (String) -> Unit,
  onStop: () -> Unit,
  onEdit: (String) -> Unit,
  onAcknowledge: () -> Unit,
  onVolumeChange: (Double) -> Unit,
  onVolumeSet: (Double) -> Unit,
  onCreate: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  // The now-playing card is inserted above the row that was tapped, and a keyed list keeps its
  // place on that row -- so without this the card the tap produced opens off the top of the screen.
  val playingId = state.current?.id
  LaunchedEffect(playingId) { if (playingId != null) listState.animateScrollToItem(0) }
  LazyColumn(
    state = listState,
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(MuPlaySpacing.gutter),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
  ) {
    if (!state.safetyAcknowledged) {
      item { SafetyCard(onAcknowledge) }
    }
    state.current?.let { playing ->
      item(key = "now") { NowPlayingCard(playing, state.volume, onStop, onVolumeChange, onVolumeSet) }
    }
    section(PROGRAMS_HEADING, state.programs, onPlay, onEdit)
    section(TONES_HEADING, state.tones, onPlay, onEdit)
    section(YOURS_HEADING, state.yours, onPlay, onEdit)
    item {
      OutlinedButton(onClick = onCreate, modifier = Modifier.padding(top = MuPlaySpacing.sm)) {
        Text(NEW_PROFILE_LABEL)
      }
    }
    if (state.safetyAcknowledged) {
      item {
        Text(
          SAFETY_REMINDER,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = MuPlaySpacing.md),
        )
      }
    }
  }
}

private fun LazyListScope.section(
  heading: String,
  rows: List<ProfileRow>,
  onPlay: (String) -> Unit,
  onEdit: (String) -> Unit,
) {
  if (rows.isEmpty()) return
  item(key = "heading:$heading") {
    Text(
      heading,
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(top = MuPlaySpacing.lg, bottom = MuPlaySpacing.xs),
    )
  }
  items(rows, key = { it.id }) { row -> ProfileRowItem(row, onPlay, onEdit) }
}

@Composable
private fun ProfileRowItem(row: ProfileRow, onPlay: (String) -> Unit, onEdit: (String) -> Unit) {
  Card(
    colors = if (row.playing) {
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
      CardDefaults.cardColors()
    },
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = MuPlaySpacing.minTouchTarget)
        .clickable(onClickLabel = "$PLAY_LABEL ${row.name}") { onPlay(row.id) }
        .padding(start = MuPlaySpacing.lg, top = MuPlaySpacing.sm, bottom = MuPlaySpacing.sm)
        .semantics { if (row.playing) stateDescription = "Playing" },
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (row.edited) {
            Text(
              "  $EDITED_BADGE",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        Text(row.summary, style = MaterialTheme.typography.bodyMedium)
        if (row.detail.isNotEmpty()) {
          Text(
            row.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      IconButton(onClick = { onEdit(row.id) }, modifier = Modifier.semantics { contentDescription = "$EDIT_LABEL ${row.name}" }) {
        Icon(MuPlayIcons.Edit, contentDescription = null)
      }
    }
  }
}

@Composable
private fun NowPlayingCard(
  playing: NowPlaying,
  volume: Double,
  onStop: () -> Unit,
  onVolumeChange: (Double) -> Unit,
  onVolumeSet: (Double) -> Unit,
) {
  var dragged by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(MuPlaySpacing.lg), verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(playing.name, style = MaterialTheme.typography.titleLarge)
          Text(if (playing.stopping) "Stopping…" else playing.beat, style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(onClick = onStop, enabled = !playing.stopping) {
          Icon(MuPlayIcons.Stop, contentDescription = null)
          Text(" $STOP_LABEL")
        }
      }
      Text(playing.tones, style = MaterialTheme.typography.bodyMedium)
      ProgramCurve(playing.profile, progress = playing.progress)
      Text(playing.clock, style = MaterialTheme.typography.bodySmall)
      Text(TONE_VOLUME_LABEL, style = MaterialTheme.typography.labelMedium)
      Slider(
        value = dragged,
        onValueChange = {
          dragged = it
          onVolumeChange(it.toDouble())
        },
        onValueChangeFinished = { onVolumeSet(dragged.toDouble()) },
        modifier = Modifier.semantics { contentDescription = TONE_VOLUME_LABEL },
      )
    }
  }
}

@Composable
private fun SafetyCard(onAcknowledge: () -> Unit) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(MuPlaySpacing.lg), verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.md)) {
      Text("Before you start", style = MaterialTheme.typography.titleMedium)
      Text(SAFETY_TEXT, style = MaterialTheme.typography.bodyMedium)
      Button(onClick = onAcknowledge) { Text(ACKNOWLEDGE_LABEL) }
    }
  }
}

