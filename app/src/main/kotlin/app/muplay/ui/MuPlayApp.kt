package app.muplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.muplay.library.AlbumScreen
import app.muplay.library.LibraryScreen
import app.muplay.setup.SetupScreen
import app.muplay.ui.navigation.AlbumRoute
import app.muplay.ui.navigation.LibraryRoute
import app.muplay.ui.navigation.SetupRoute

/**
 * The app's root composable: a Navigation 3 back stack (`androidx.navigation3`, not Navigation
 * Compose) holding first-run setup, the browse screen, and one album's detail screen. `onBack`
 * pops the back stack explicitly — `NavDisplay` invokes it for both a system predictive-back
 * gesture and a plain back press, so wiring it is what makes predictive back actually do
 * something rather than merely being enabled at the manifest level (see `AndroidManifest.xml`'s
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
        SetupScreen(
          onSetupComplete = {
            // Replace rather than push: going "back" into setup after finishing it would offer
            // to re-enter credentials the app already has.
            backStack.clear()
            backStack.add(LibraryRoute)
          },
        )
      }
      entry<LibraryRoute> {
        LibraryScreen(onAlbumClick = { albumId -> backStack.add(AlbumRoute(albumId)) })
      }
      entry<AlbumRoute> { route -> AlbumScreen(albumId = route.albumId) }
    },
  )
}
