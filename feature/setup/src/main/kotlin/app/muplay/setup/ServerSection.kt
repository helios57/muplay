package app.muplay.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.settings.SettingsSection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asserted by `ServerSectionTest`, and the string a user looks for to change servers. */
const val SERVER_TITLE: String = "Server"

/** The sign-out control. Its label is the only place the app says the word. */
const val SIGN_OUT_LABEL: String = "Sign out"

/** The confirmation's title. */
const val SIGN_OUT_CONFIRM_TITLE: String = "Sign out of this server?"

/**
 * What signing out actually costs, said plainly, because the honest answer is "not much" and a
 * user who does not know that will not use the control that gets them out of a mistyped server.
 */
const val SIGN_OUT_CONFIRM_BODY: String =
  "MuPlay forgets your server address, username and password, and asks for them again. Your " +
    "audiobook positions stay on this phone."

/** Shown while nothing is stored, which is a state the settings screen can genuinely be reached in. */
const val SERVER_NOT_CONNECTED: String = "Not connected to a server."

/**
 * The connected server, and the way back to the setup screen.
 *
 * **This section is the fix for a defect, not a convenience.** `SetupRoute` was `MuPlayApp`'s start
 * destination and nothing else, so setup could be reached exactly once, on first run. A user who
 * mistyped their server URL, changed their password, moved to a different Navidrome, or simply
 * wanted their password off a phone they were selling had no route to any of it -- and
 * `CredentialStore.clear()`, which exists and destroys the Keystore key, had no caller anywhere in
 * the app. Reinstalling was the only way to change a stored credential.
 *
 * **An injected scope, not `rememberCoroutineScope()`**, for the reason `RendererDirectSection`
 * gives and more sharply: a scope remembered by the composable dies when the composable leaves
 * composition, and this composable navigates away in the same gesture that writes. A lost write
 * here leaves the password on the device after the user has been told it is gone.
 */
@Singleton
class ServerSection @Inject constructor(
  private val account: ServerAccount,
  @SetupScope private val scope: CoroutineScope,
) : SettingsSection {

  /**
   * First, before casting's 200 and integrations' 300. Which server the app is talking to is the
   * setting every other setting is about, and it is the one a user arrives on this screen looking
   * for when something is wrong.
   */
  override val order: Int = 100

  @Composable
  override fun Content(onNavigate: (NavKey) -> Unit) {
    // `initialValue = null` renders "Not connected" for one frame before the store answers. That
    // is the right way round: the alternative would flash a server address that may not be there.
    val identity by account.identity.collectAsStateWithLifecycle(initialValue = null)

    ServerSummary(
      identity = identity,
      onSignOut = {
        // Ordered: forget first, then navigate. `:app` turns `SetupRoute` into a stack reset, so
        // the reverse order would leave the library screen composed over a store that is being
        // cleared underneath it.
        //
        // **`withContext(Dispatchers.Main)` around the navigation**, because [SetupScope] is
        // `Dispatchers.Default` and `onNavigate` mutates a `NavBackStack`. A snapshot state list
        // tolerates a write from any thread -- the snapshot system is thread-safe -- so this would
        // have *appeared* to work; what it would not have is a defined moment, and a back stack
        // that changes between a frame's composition and its layout is the kind of defect that
        // reproduces once a week on somebody else's phone.
        scope.launch {
          account.signOut()
          withContext(Dispatchers.Main) { onNavigate(SetupRoute) }
        }
      },
    )
  }
}

/**
 * The stateless half, so the section can be composed with no Hilt graph and no Keystore -- the
 * split every other section and screen in this project uses.
 */
@Composable
internal fun ServerSummary(
  identity: ServerIdentity?,
  onSignOut: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // `rememberSaveable`, not `remember`: a rotation with the dialog open must not silently answer
  // "no" on the user's behalf. Same reason `MuPlayApp` saves `pickerOpen`. A `Boolean` is
  // saveable as-is and carries nothing sensitive.
  var confirming by rememberSaveable { mutableStateOf(false) }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
  ) {
    Text(text = SERVER_TITLE, style = MaterialTheme.typography.titleMedium)

    if (identity == null) {
      Text(
        text = SERVER_NOT_CONNECTED,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      // The URL at body size and the username under it, because the URL is what a user checks when
      // they suspect they typed it wrong -- which is the single most likely reason to be here.
      Text(text = identity.baseUrl, style = MaterialTheme.typography.bodyMedium)
      Text(
        text = "Signed in as ${identity.username}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // Offered even when nothing is stored: reaching setup is the point, and a user whose
    // credentials failed to open (a restored backup, a reset Keystore) sees exactly that state and
    // needs the way out more than anyone.
    OutlinedButton(onClick = { confirming = true }) { Text(SIGN_OUT_LABEL) }
  }

  // **A confirmation, for one destructive control.** Re-entering a password on a phone keyboard is
  // the cost of a mis-tap, and this button necessarily sits next to switches where a mis-tap costs
  // nothing. Nothing else in the app asks twice, and nothing else in the app destroys a key.
  if (confirming) {
    AlertDialog(
      onDismissRequest = { confirming = false },
      title = { Text(SIGN_OUT_CONFIRM_TITLE) },
      text = { Text(SIGN_OUT_CONFIRM_BODY) },
      confirmButton = {
        TextButton(
          onClick = {
            confirming = false
            onSignOut()
          },
        ) { Text(SIGN_OUT_LABEL) }
      },
      dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
    )
  }
}
