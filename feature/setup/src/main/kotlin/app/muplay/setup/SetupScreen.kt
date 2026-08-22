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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The first-run setup screen: a server URL, username and password, and a Connect button that
 * reports [SetupUiState] back to the user. Deliberately thin — no branching this file owns is
 * unit-tested; [SetupViewModel] carries all of it, and Task 8's emulator journey covers this
 * screen end to end. `viewModel()` uses the platform's default factory, which is able to
 * construct [SetupViewModel] because every one of its constructor parameters is defaulted.
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
  var serverUrl by rememberSaveable { mutableStateOf("") }
  var username by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
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

private fun SetupFailureReason.toMessage(): String = when (this) {
  SetupFailureReason.InvalidUrl -> "Enter a valid server URL, e.g. https://music.example.com."
  is SetupFailureReason.Rejected -> "Could not sign in" + (detail?.let { ": $it" } ?: " (server error $code).")
  SetupFailureReason.Unreachable -> "Could not reach the server. Check the URL and your connection."
}
