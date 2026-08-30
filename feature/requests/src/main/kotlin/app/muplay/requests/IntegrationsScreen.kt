package app.muplay.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.integrations.IntegrationService

/** The heading. A screen, not a dialog: setting up a server is a task, not a confirmation. */
const val INTEGRATIONS_SCREEN_TITLE: String = "Integrations"

/**
 * Setting up, and forgetting, the optional services.
 *
 * **Always reachable, whatever is configured**, which is what makes the feature possible to turn on
 * at all -- see `IntegrationsSection`. Everything about *what it shows* is in
 * [IntegrationsViewModel] and the pure functions beside it; this file arranges it.
 */
@Composable
fun IntegrationsScreen(
  modifier: Modifier = Modifier,
  viewModel: IntegrationsViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  IntegrationsScreen(
    uiState = uiState,
    onEdit = viewModel::edit,
    onCancel = viewModel::cancel,
    onUrlChange = viewModel::setUrl,
    onKeyChange = viewModel::setKey,
    onTest = viewModel::test,
    onSave = viewModel::save,
    onForget = viewModel::forget,
    modifier = modifier,
  )
}

/** The stateless half, so the screen can be composed with no Hilt graph at all. */
@Composable
internal fun IntegrationsScreen(
  uiState: IntegrationsUiState,
  onEdit: (IntegrationService) -> Unit,
  onCancel: () -> Unit,
  onUrlChange: (String) -> Unit,
  onKeyChange: (String) -> Unit,
  onTest: () -> Unit,
  onSave: () -> Unit,
  onForget: (IntegrationService) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .testTag("integrations:root")
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(text = INTEGRATIONS_SCREEN_TITLE, style = MaterialTheme.typography.headlineSmall)

    uiState.services.forEachIndexed { index, service ->
      if (index > 0) HorizontalDivider()
      ServiceBlock(
        service = service,
        configured = service in uiState.configured,
        form = uiState.editing?.takeIf { it.service == service },
        onEdit = { onEdit(service) },
        onCancel = onCancel,
        onUrlChange = onUrlChange,
        onKeyChange = onKeyChange,
        onTest = onTest,
        onSave = onSave,
        onForget = { onForget(service) },
      )
    }
  }
}

@Composable
private fun ServiceBlock(
  service: IntegrationService,
  configured: Boolean,
  form: IntegrationSetupUiState?,
  onEdit: () -> Unit,
  onCancel: () -> Unit,
  onUrlChange: (String) -> Unit,
  onKeyChange: (String) -> Unit,
  onTest: () -> Unit,
  onSave: () -> Unit,
  onForget: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().testTag("integrations:service:${service.name}"),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(text = service.displayName, style = MaterialTheme.typography.titleMedium)
    Text(
      text = if (configured) "Set up." else "Not set up.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (form == null) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onEdit, modifier = Modifier.testTag("integrations:setup:${service.name}")) {
          Text(if (configured) "Replace" else "Set up")
        }
        if (configured) {
          TextButton(onClick = onForget, modifier = Modifier.testTag("integrations:forget:${service.name}")) {
            Text("Forget")
          }
        }
      }
    } else {
      SetupForm(
        form = form,
        onCancel = onCancel,
        onUrlChange = onUrlChange,
        onKeyChange = onKeyChange,
        onTest = onTest,
        onSave = onSave,
      )
    }
  }
}

@Composable
private fun SetupForm(
  form: IntegrationSetupUiState,
  onCancel: () -> Unit,
  onUrlChange: (String) -> Unit,
  onKeyChange: (String) -> Unit,
  onTest: () -> Unit,
  onSave: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
      value = form.urlText,
      onValueChange = onUrlChange,
      singleLine = true,
      isError = form.urlText.isNotEmpty() && form.urlError != null,
      label = { Text("${form.service.displayName} address") },
      modifier = Modifier.fillMaxWidth().testTag("setup:url"),
    )
    form.urlError?.let { error ->
      Text(
        text = error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("setup:error"),
      )
    }

    OutlinedTextField(
      value = form.keyText,
      onValueChange = onKeyChange,
      singleLine = true,
      // Masked. An API key here is instance-wide and admin-equivalent on both services; it does not
      // belong on a screen somebody is holding up in a room.
      visualTransformation = PasswordVisualTransformation(),
      label = { Text("API key") },
      modifier = Modifier.fillMaxWidth().testTag("setup:key"),
    )

    form.check?.let { check ->
      Text(
        text = check.message(form.service),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("setup:check"),
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(onClick = onTest, enabled = form.canTest, modifier = Modifier.testTag("setup:test")) {
        Text("Test connection")
      }
      // Enabled only once a check has come back `Ok`: storing a credential that has never been
      // shown to work turns every later failure into a service outage nobody can diagnose.
      Button(onClick = onSave, enabled = form.canSave, modifier = Modifier.testTag("setup:save")) {
        Text("Save")
      }
      TextButton(onClick = onCancel) { Text("Cancel") }
    }
  }
}
