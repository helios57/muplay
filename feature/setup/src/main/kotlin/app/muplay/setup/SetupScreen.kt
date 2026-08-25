package app.muplay.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.model.LibraryRole

/**
 * The first-run setup screen: a server URL, username and password, and a Connect button that
 * reports [SetupUiState] back to the user — including, once connected, every library the server
 * returned, for the user to tag as Music or Audiobooks. Deliberately thin — the only branching
 * this file owns is which `Text`/`Composable` to render for a given [SetupUiState], which
 * genuinely needs Compose to exercise and is covered by `FirstRunJourneyTest` on a real emulator
 * (Tier 2); [SetupViewModel] carries the state-machine logic, and the failure-to-message mapping
 * lives in `SetupFailureReason.toMessage` precisely so it does *not* need Compose or an emulator
 * to test.
 * `hiltViewModel()` resolves [SetupViewModel] from the Hilt graph, which is what lets it reach
 * `LibraryRepository` and `CredentialStore` through ordinary constructor injection.
 */
@Composable
fun SetupScreen(
  onSetupComplete: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SetupViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // Observed, as of Task 9's review round 1 (N-1). This effect was flagged by Task 8's own review
  // (N-5, task-8-review.md) as unobserved by any test: `MuPlayApp.kt` wired `onSetupComplete = {}`
  // then, so deleting the whole effect left both tiers green. Task 9 supplied the real callback
  // (`backStack.clear(); backStack.add(LibraryRoute)`), and
  // `FirstRunJourneyTest.completingEveryTagPersistsBothRolesAndLandsOnTheLibraryScreen` now walks
  // the flow to Ready and asserts the *browse* screen's own controls are on screen afterwards --
  // so deleting this effect, or reverting the callback to a no-op, strands that test on the setup
  // screen. Note what this costs and why it is right: reaching Ready no longer renders
  // `Text("Setup complete")` to anybody, because the host navigates away in the same frame, so the
  // journey's old assertion on that string was not weakened but replaced -- see that test's doc.
  LaunchedEffect(uiState) {
    if (uiState is SetupUiState.Ready) onSetupComplete()
  }
  SetupScreen(
    uiState = uiState,
    onConnect = viewModel::connect,
    onRoleChosen = viewModel::setRole,
    onContinue = viewModel::continueToLibrary,
    modifier = modifier,
  )
}

@Composable
private fun SetupScreen(
  uiState: SetupUiState,
  onConnect: (serverUrl: String, username: String, password: String) -> Unit,
  onRoleChosen: (Int, LibraryRole) -> Unit,
  onContinue: () -> Unit,
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
      is SetupUiState.Tagging -> {
        Text(
          text = "Connected to ${uiState.serverInfo.type} ${uiState.serverInfo.serverVersion}",
          color = MaterialTheme.colorScheme.primary,
        )
        // The server cannot tell us what a library holds -- Navidrome reports every file as
        // `type: "music"` -- so the user decides, once, here. No name is inspected: "Hörbücher"
        // is not "Audiobooks", and a wrong guess silently poisons shuffle scope.
        Text(text = "What is each library for?", style = MaterialTheme.typography.titleMedium)
        // Labelled "Tag as Music"/"Tag as Audiobooks", not the bare "Music"/"Audiobooks" a first
        // draft used: every row also renders the library's own name, so a bare-word chip label is
        // indistinguishable, to both a screen reader and a black-box UI test, from the name of a
        // library that happens to be called "Music" or "Audiobooks". A distinct label is what lets
        // `FirstRunJourneyTest` assert on the library *name* -- its actual documented contract on
        // server state -- without accidentally matching a chip instead (see that test's own doc).
        uiState.libraries.forEach { library ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(text = library.name, modifier = Modifier.weight(1f))
            FilterChip(
              selected = library.role == LibraryRole.MUSIC,
              onClick = { onRoleChosen(library.id, LibraryRole.MUSIC) },
              label = { Text("Tag as Music") },
            )
            FilterChip(
              selected = library.role == LibraryRole.AUDIOBOOKS,
              onClick = { onRoleChosen(library.id, LibraryRole.AUDIOBOOKS) },
              label = { Text("Tag as Audiobooks") },
            )
          }
        }
        Button(
          onClick = onContinue,
          enabled = uiState.canContinue,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Continue")
        }
      }
      is SetupUiState.Ready -> Text(text = "Setup complete")
      is SetupUiState.Failure ->
        Text(
          text = uiState.reason.toMessage(),
          color = MaterialTheme.colorScheme.error,
        )
    }
  }
}
