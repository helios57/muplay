package io.github.helios57.muplay.waves

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.helios57.muplay.binaural.Band
import io.github.helios57.muplay.binaural.CurveShape
import io.github.helios57.muplay.binaural.EndBehaviour
import io.github.helios57.muplay.binaural.Limits
import io.github.helios57.muplay.binaural.NoiseType
import io.github.helios57.muplay.binaural.ProfileOrigin
import io.github.helios57.muplay.binaural.leftHz
import io.github.helios57.muplay.binaural.rightHz
import io.github.helios57.muplay.designsystem.component.Message
import io.github.helios57.muplay.designsystem.theme.MuPlayIcons
import io.github.helios57.muplay.designsystem.theme.MuPlaySpacing

/**
 * Edits one profile: its steps (each a glide of the beat and the carrier over some minutes), its
 * sound, and its fades. The curve at the top redraws as the steps change; the left and right ear
 * tones are shown under every step, because that is how the owner thinks of a profile.
 */
@Composable
fun WaveEditorScreen(
  profileId: String,
  onDone: () -> Unit,
  onOpen: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: WaveEditorViewModel = hiltViewModel(),
) {
  LaunchedEffect(profileId) { viewModel.open(profileId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  // Every way out goes through `close`, system back included: the view model outlives this screen.
  val leave = {
    viewModel.close()
    onDone()
  }
  BackHandler(onBack = leave)
  when (val s = state) {
    EditorUiState.Loading -> Message(text = "Loading…", loading = true, modifier = modifier)
    EditorUiState.Gone -> LaunchedEffect(Unit) { leave() }
    is EditorUiState.Editing -> EditorContent(
      state = s,
      onEdit = viewModel::edit,
      onSave = { viewModel.save(leave) },
      onTry = viewModel::tryIt,
      onReset = viewModel::reset,
      onDuplicate = { viewModel.duplicate { id -> viewModel.close(); onOpen(id) } },
      onDelete = { viewModel.delete(leave) },
      onBack = leave,
      modifier = modifier,
    )
  }
}

@Composable
fun EditorContent(
  state: EditorUiState.Editing,
  onEdit: ((ProfileDraft) -> ProfileDraft) -> Unit,
  onSave: () -> Unit,
  onTry: () -> Unit,
  onReset: () -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val draft = state.draft
  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(MuPlaySpacing.gutter),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
  ) {
    item {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
          Icon(MuPlayIcons.ArrowBack, contentDescription = BACK_LABEL)
        }
        Text(EDITOR_TITLE, style = MaterialTheme.typography.titleLarge)
      }
    }
    item {
      OutlinedTextField(
        value = draft.name,
        onValueChange = { name -> onEdit { it.copy(name = name.take(Limits.MAX_NAME_LENGTH)) } },
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item {
      draft.toProfile()?.takeIf { state.canSave }?.let { ProgramCurve(it) }
        ?: Text("The curve appears once every step has valid numbers.", style = MaterialTheme.typography.bodySmall)
    }
    itemsIndexed(draft.segments) { index, segment ->
      StepCard(
        index = index,
        segment = segment,
        removable = draft.segments.size > 1,
        onChange = { change -> onEdit { it.withSegment(index, change) } },
        onRemove = { onEdit { it.withStepRemoved(index) } },
      )
    }
    item {
      OutlinedButton(onClick = { onEdit { it.withStepAdded() } }, enabled = draft.segments.size < Limits.MAX_SEGMENTS) {
        Text(ADD_STEP_LABEL)
      }
    }
    item { SoundCard(draft, onEdit) }
    if (state.problems.isNotEmpty()) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs)) {
          state.problems.forEach { problem ->
            Text(
              problem.message,
              color = if (problem.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }
    }
    item {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
        Button(onClick = onSave, enabled = state.canSave) { Text(SAVE_LABEL) }
        OutlinedButton(onClick = onTry, enabled = state.canSave) { Text(TRY_LABEL) }
        TextButton(onClick = onDuplicate) { Text(DUPLICATE_LABEL) }
        if (state.origin == ProfileOrigin.EDITED) TextButton(onClick = onReset) { Text(RESET_LABEL) }
        if (state.origin == ProfileOrigin.CUSTOM) TextButton(onClick = onDelete) { Text(DELETE_LABEL) }
      }
    }
  }
}

@Composable
private fun StepCard(
  index: Int,
  segment: SegmentDraft,
  removable: Boolean,
  onChange: ((SegmentDraft) -> SegmentDraft) -> Unit,
  onRemove: () -> Unit,
) {
  val parsed = segment.toSegment()
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(MuPlaySpacing.lg), verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        val bands = parsed?.let {
          val from = Band.of(it.startBeatHz).label
          val to = Band.of(it.endBeatHz).label
          if (from == to) " · $from" else " · $from → $to"
        }.orEmpty()
        Text("Step ${index + 1}$bands", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (removable) {
          TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "$REMOVE_STEP_LABEL ${index + 1}" }) {
            Text(REMOVE_STEP_LABEL)
          }
        }
      }
      PairRow(
        label = "Beat (Hz)", step = index + 1,
        from = segment.startBeat, to = segment.endBeat,
        onFrom = { v -> onChange { it.copy(startBeat = v) } },
        onTo = { v -> onChange { it.copy(endBeat = v) } },
      )
      PairRow(
        label = "Carrier (Hz)", step = index + 1,
        from = segment.startCarrier, to = segment.endCarrier,
        onFrom = { v -> onChange { it.copy(startCarrier = v) } },
        onTo = { v -> onChange { it.copy(endCarrier = v) } },
      )
      NumberField(
        label = "Minutes", value = segment.minutes,
        description = "Step ${index + 1} minutes",
        onValue = { v -> onChange { it.copy(minutes = v) } },
        modifier = Modifier.width(MuPlaySpacing.numberField),
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
        CurveShape.entries.forEach { shape ->
          FilterChip(
            selected = segment.curve == shape,
            onClick = { onChange { it.copy(curve = shape) } },
            label = { Text(shape.label) },
          )
        }
      }
      parsed?.let {
        val left = "${formatHz(leftHz(it.startCarrierHz, it.startBeatHz))} → ${formatHz(leftHz(it.endCarrierHz, it.endBeatHz))}"
        val right = "${formatHz(rightHz(it.startCarrierHz, it.startBeatHz))} → ${formatHz(rightHz(it.endCarrierHz, it.endBeatHz))}"
        val text = if (it.startBeatHz == it.endBeatHz && it.startCarrierHz == it.endCarrierHz) {
          leftRightText(it.startCarrierHz, it.startBeatHz)
        } else {
          "Left $left Hz · right $right Hz"
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun PairRow(label: String, step: Int, from: String, to: String, onFrom: (String) -> Unit, onTo: (String) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
    NumberField("$label from", from, "Step $step $label from", onFrom, Modifier.weight(1f))
    Text("→")
    NumberField("to", to, "Step $step $label to", onTo, Modifier.weight(1f))
  }
}

@Composable
private fun NumberField(label: String, value: String, description: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
  OutlinedTextField(
    value = value,
    onValueChange = onValue,
    label = { Text(label) },
    singleLine = true,
    isError = parseNumber(value) == null,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    modifier = modifier.semantics { contentDescription = description },
  )
}

@Composable
private fun SoundCard(draft: ProfileDraft, onEdit: ((ProfileDraft) -> ProfileDraft) -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(MuPlaySpacing.lg), verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
      Text("Sound", style = MaterialTheme.typography.titleSmall)
      Text("Tone level ${"%.0f".format(draft.amplitude * 100)} %", style = MaterialTheme.typography.labelMedium)
      Slider(
        value = draft.amplitude.toFloat(),
        onValueChange = { v -> onEdit { it.copy(amplitude = v.toDouble()) } },
        valueRange = 0.01f..Limits.MAX_AMPLITUDE.toFloat(),
        modifier = Modifier.semantics { contentDescription = "Tone level" },
      )
      Text("Background noise", style = MaterialTheme.typography.labelMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
        NoiseType.entries.forEach { type ->
          FilterChip(
            selected = draft.noiseType == type,
            onClick = {
              onEdit { it.copy(noiseType = type, noiseLevel = if (type != NoiseType.NONE && it.noiseLevel == 0.0) 0.03 else it.noiseLevel) }
            },
            label = { Text(type.label) },
          )
        }
      }
      if (draft.noiseType != NoiseType.NONE) {
        Text("Noise level ${"%.0f".format(draft.noiseLevel * 100)} %", style = MaterialTheme.typography.labelMedium)
        Slider(
          value = draft.noiseLevel.toFloat(),
          onValueChange = { v -> onEdit { it.copy(noiseLevel = v.toDouble()) } },
          valueRange = 0f..Limits.MAX_NOISE_LEVEL.toFloat(),
          modifier = Modifier.semantics { contentDescription = "Noise level" },
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
        NumberField("Fade in (s)", draft.fadeInSeconds, "Fade in seconds", { v -> onEdit { it.copy(fadeInSeconds = v) } }, Modifier.weight(1f))
        NumberField("Fade out (s)", draft.fadeOutSeconds, "Fade out seconds", { v -> onEdit { it.copy(fadeOutSeconds = v) } }, Modifier.weight(1f))
      }
      Text("At the end", style = MaterialTheme.typography.labelMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
        EndBehaviour.entries.forEach { end ->
          FilterChip(
            selected = draft.endBehaviour == end,
            onClick = { onEdit { it.copy(endBehaviour = end) } },
            label = { Text(end.label) },
          )
        }
      }
    }
  }
}

