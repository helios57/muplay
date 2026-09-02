package app.muplay.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary

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
    // **Scrollable, and it was not before.** Every other state fits, but `Tagging` grows by one
    // block per library the server returns and the form above it never goes away (see below for
    // why it must not) -- so on a server with four libraries the `Continue` button used to be laid
    // out past the bottom of the screen with no way to reach it. Nothing in the fixture set has
    // more than two, which is exactly the shape of defect a two-library emulator cannot see.
    modifier = modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = MuPlaySpacing.gutter, vertical = MuPlaySpacing.xl),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.lg),
  ) {
    Text(
      text = "Connect to your server",
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier.semantics { heading() },
    )

    // The product's whole thesis, said once, on the only screen where the user is being asked to
    // hand over a server address and a password -- which is the moment the question "where does
    // this go?" is actually being asked. Hidden once connected: by then it has been answered, and
    // the tagging step needs the height (this column scrolls, but a `Continue` button below the
    // fold is still a worse screen than one above it).
    if (uiState !is SetupUiState.Tagging) {
      Text(
        text = "The only computer MuPlay talks to is the one whose address you type here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

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
      modifier = Modifier.fillMaxWidth().heightIn(min = MuPlaySpacing.minTouchTarget),
    ) {
      Text(if (isConnecting) "Connecting…" else "Connect")
    }

    when (uiState) {
      is SetupUiState.Idle, is SetupUiState.Connecting -> Unit
      is SetupUiState.Tagging -> {
        Text(
          text = "Connected to ${uiState.serverInfo.type} ${uiState.serverInfo.serverVersion}",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        // The server cannot tell us what a library holds -- Navidrome reports every file as
        // `type: "music"` -- so the user decides, once, here. No name is inspected: "Hörbücher"
        // is not "Audiobooks", and a wrong guess silently poisons shuffle scope.
        // The empty-library case says something instead of asking about libraries that are not
        // there -- see SetupUiState.Tagging.prompt for what a server actually returns then.
        Text(
          text = uiState.prompt,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.semantics { heading() },
        )
        uiState.libraries.forEach { library ->
          LibraryTagCard(library = library, onRoleChosen = onRoleChosen)
        }
        Button(
          onClick = onContinue,
          enabled = uiState.canContinue,
          modifier = Modifier.fillMaxWidth().heightIn(min = MuPlaySpacing.minTouchTarget),
        ) {
          Text("Continue")
        }
      }
      is SetupUiState.Ready -> Text(text = "Setup complete")
      is SetupUiState.Failure ->
        // A tonal card in `errorContainer` rather than a line of red text under the button. Red
        // body text on the app's own background is the one colour combination this palette does
        // not check for contrast (see `Color.kt`'s table, which pairs `error` with
        // `onErrorContainer`), and a container is also what makes the message read as the answer
        // to the button that was just pressed rather than as more of the form.
        Surface(
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = uiState.reason.toMessage(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(MuPlaySpacing.lg),
          )
        }
    }
  }
}

/**
 * One library, and the single most under-used moment in this app.
 *
 * **The two chips are where the palette is taught.** `Color.kt` spends itself on one idea -- cold
 * teal is the music voice, warm amber is the audiobook voice, and the chassis is shared -- and
 * every screen that acts on it (`PlayerScreen`, `BookshelfScreen`, `BookPlayerScreen`) is one a
 * user reaches minutes later, by which time the two colours are decoration they have to work out.
 * Here they are the *answer to the question on screen*: selecting `Tag as Audiobooks` turns that
 * chip amber, and the shelf it produces is amber for the same reason. It costs two colour
 * arguments and it is the only place in the flow where the language can be shown rather than
 * relied on.
 *
 * **The labels and the node structure are contractual.** `FirstRunJourneyTest` finds these chips
 * with `onAllNodesWithText("Tag as Music")[n]`, where `n` is a *row* index -- so each library must
 * render exactly one chip per role, and the rows must stay in `uiState.libraries` order. A card
 * per library keeps both true while fixing what a `Row` could not: a `FlowRow` **wraps**, so a
 * library called "Hörbücher und Podcasts" pushes the second chip onto a second line instead of
 * off the right edge. `SleepTimerRow` in `:feature:book` records the same fix for the same reason.
 */
@Composable
private fun LibraryTagCard(library: MusicLibrary, onRoleChosen: (Int, LibraryRole) -> Unit) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(MuPlaySpacing.lg),
      verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
    ) {
      Text(text = library.name, style = MaterialTheme.typography.titleMedium)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
        modifier = Modifier.fillMaxWidth(),
      ) {
        // Labelled "Tag as Music"/"Tag as Audiobooks", not the bare "Music"/"Audiobooks" a first
        // draft used: every card also renders the library's own name, so a bare-word chip label is
        // indistinguishable, to both a screen reader and a black-box UI test, from the name of a
        // library that happens to be called "Music" or "Audiobooks". A distinct label is what lets
        // `FirstRunJourneyTest` assert on the library *name* -- its actual documented contract on
        // server state -- without accidentally matching a chip instead (see that test's own doc).
        FilterChip(
          selected = library.role == LibraryRole.MUSIC,
          onClick = { onRoleChosen(library.id, LibraryRole.MUSIC) },
          label = { Text("Tag as Music") },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        )
        FilterChip(
          selected = library.role == LibraryRole.AUDIOBOOKS,
          onClick = { onRoleChosen(library.id, LibraryRole.AUDIOBOOKS) },
          label = { Text("Tag as Audiobooks") },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
          ),
        )
      }
    }
  }
}
