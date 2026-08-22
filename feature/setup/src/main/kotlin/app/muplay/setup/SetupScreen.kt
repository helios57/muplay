package app.muplay.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The first-run setup screen: a server URL, username and password, and a Connect button that
 * reports [SetupUiState] back to the user. Deliberately thin — the only branching this file owns
 * is which `Text`/`Composable` to render for a given [SetupUiState], which genuinely needs
 * Compose to exercise and is left for Task 8's emulator journey; [SetupViewModel] carries the
 * state-machine logic, and the failure-to-message mapping lives in `SetupFailureReason.toMessage`
 * precisely so it does *not* need Compose or an emulator to test. `viewModel()` uses the
 * platform's default factory, which is able to construct [SetupViewModel] because every one of
 * its constructor parameters is defaulted.
 */
@Composable
fun SetupScreen(modifier: Modifier = Modifier, viewModel: SetupViewModel = viewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  SetupScreen(uiState = uiState, onConnect = viewModel::connect, modifier = modifier)
}

@Composable
private fun SetupScreen(
  uiState: SetupUiState,
  onConnect: (serverUrl: String, username: String, password: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Server URL and username are not sensitive, so losing them on rotation would be a pure
  // usability regression — rememberSaveable is right for both. The password is different:
  // rememberSaveable writes into the Activity's saved-instance-state Bundle, which survives not
  // just configuration change but process death, meaning a plaintext credential would sit in
  // system-managed state. Plain `remember` only survives recomposition, never process death, so
  // the password is deliberately lost on rotation rather than persisted anywhere. Do not "fix"
  // this inconsistency by making all three uniform.
  var serverUrl by rememberSaveable { mutableStateOf("") }
  var username by rememberSaveable { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  val isConnecting = uiState is SetupUiState.Connecting

  Column(
    modifier = modifier.padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(text = "Connect to your server", style = MaterialTheme.typography.headlineSmall)

    OutlinedTextField(
      value = serverUrl,
      onValueChange = { serverUrl = it },
      label = { Text("Server URL") },
      singleLine = true,
      enabled = !isConnecting,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = username,
      onValueChange = { username = it },
      label = { Text("Username") },
      singleLine = true,
      enabled = !isConnecting,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = password,
      onValueChange = { password = it },
      label = { Text("Password") },
      singleLine = true,
      enabled = !isConnecting,
      visualTransformation = PasswordVisualTransformation(),
      modifier = Modifier.fillMaxWidth(),
    )

    Button(
      onClick = { onConnect(serverUrl, username, password) },
      enabled = !isConnecting,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(if (isConnecting) "Connecting…" else "Connect")
    }

    when (uiState) {
      is SetupUiState.Idle, is SetupUiState.Connecting -> Unit
      is SetupUiState.Success ->
        Text(
          text = "Connected to ${uiState.serverInfo.type} ${uiState.serverInfo.serverVersion}",
          color = MaterialTheme.colorScheme.primary,
        )
      is SetupUiState.Failure ->
        Text(
          text = uiState.reason.toMessage(),
          color = MaterialTheme.colorScheme.error,
        )
    }
  }
}
