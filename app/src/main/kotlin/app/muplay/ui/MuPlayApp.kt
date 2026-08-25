package app.muplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.muplay.setup.SetupScreen
import app.muplay.ui.navigation.SetupRoute

/**
 * The app's root composable: a Navigation 3 back stack (`androidx.navigation3`, not Navigation
 * Compose) that today holds a single destination, the first-run setup screen. `onBack` pops the
 * back stack explicitly — `NavDisplay` invokes it for both a system predictive-back gesture and a
 * plain back press, so wiring it is what makes predictive back actually do something rather than
 * merely being enabled at the manifest level (see `AndroidManifest.xml`'s
 * `enableOnBackInvokedCallback`).
 */
@Composable
fun MuPlayApp(modifier: Modifier = Modifier) {
  val backStack = rememberNavBackStack(SetupRoute)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    modifier = modifier,
    entryProvider = entryProvider {
      entry<SetupRoute> {
        // No destination exists past setup yet -- library browse lands in a later task -- so
        // there is nothing to navigate to on completion. `onSetupComplete = {}` is a real,
        // deliberate no-op rather than an omission: `SetupScreen`'s signature makes the callback
        // mandatory precisely so a future destination cannot be wired in without this call site
        // being touched.
        SetupScreen(onSetupComplete = {})
      }
    },
  )
}
