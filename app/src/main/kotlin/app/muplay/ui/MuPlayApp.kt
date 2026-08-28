package app.muplay.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.muplay.castpicker.CastButton
import app.muplay.castpicker.CastPickerSheet
import app.muplay.castpicker.CastViewModel
import app.muplay.library.AlbumScreen
import app.muplay.library.LibraryScreen
import app.muplay.player.MiniPlayer
import app.muplay.player.PlayerScreen
import app.muplay.settings.SettingsScreen
import app.muplay.setup.SetupScreen
import app.muplay.ui.navigation.AlbumRoute
import app.muplay.ui.navigation.LibraryRoute
import app.muplay.ui.navigation.PlayerRoute
import app.muplay.ui.navigation.SettingsRoute
import app.muplay.ui.navigation.SetupRoute

/**
 * The app's root composable: it decides where the app opens from stored state, then hands that
 * start key to a Navigation 3 back stack (`androidx.navigation3`, not Navigation Compose).
 *
 * Deciding the start destination is product behaviour first — before it, every launch landed on
 * setup, which made the stored credentials pointless — and, second, it is what lets a Tier 2
 * journey reach the library screen from *either* starting state with one helper, so the emulator
 * suite has no hidden dependence on which journey ran before it.
 */
@Composable
fun MuPlayApp(
  modifier: Modifier = Modifier,
  viewModel: StartDestinationViewModel = hiltViewModel(),
) {
  val start by viewModel.startDestination.collectAsStateWithLifecycle()

  when (start) {
    StartDestination.Loading -> Unit
    StartDestination.Setup -> MuPlayNavigation(SetupRoute, modifier)
    StartDestination.Library -> MuPlayNavigation(LibraryRoute, modifier)
  }
}

/**
 * `onBack` pops the back stack explicitly — `NavDisplay` invokes it for both a system
 * predictive-back gesture and a plain back press, so wiring it is what makes predictive back
 * actually do something rather than merely being enabled at the manifest level (see
 * `AndroidManifest.xml`'s `enableOnBackInvokedCallback`). Spec §7 requires it and, until
 * `BrowseJourneyTest.backFromAnAlbumReturnsToTheLibraryRatherThanLeavingTheApp`, nothing asserted
 * it: replacing this lambda with `{}` left every test in both tiers green while the back gesture
 * closed the app from the album screen.
 */
@Composable
private fun MuPlayNavigation(start: NavKey, modifier: Modifier) {
  val backStack = rememberNavBackStack(start)
  val openPlayer = { backStack.add(PlayerRoute) }

  // **One `CastViewModel`, hoisted here**, and handed to both the button and the sheet rather than
  // left to each of them to resolve for itself. `hiltViewModel()` answers against the nearest
  // `ViewModelStoreOwner`, and a `NavDisplay` entry brings its own -- so a button inside the player
  // entry and a sheet hosted out here would be two view models, two discovery passes, and a sheet
  // that never learned which speaker the button was pointing at.
  val castViewModel: CastViewModel = hiltViewModel()
  val castDeviceName by castViewModel.connectedDeviceName.collectAsStateWithLifecycle()
  // `rememberSaveable`, so a rotation with the picker open does not close it. The search is started
  // from the effect below rather than from the tap, so a restored `true` starts one too -- a
  // restored sheet over a view model that was never opened would render nothing at all.
  var pickerOpen by rememberSaveable { mutableStateOf(false) }

  LaunchedEffect(pickerOpen) {
    if (pickerOpen) castViewModel.open() else castViewModel.close()
  }

  if (pickerOpen) {
    CastPickerSheet(onDismiss = { pickerOpen = false }, viewModel = castViewModel)
  }

  Scaffold(
    modifier = modifier,
    bottomBar = {
      // **Around the `NavDisplay`, not inside a destination.** A mini player that lived in the
      // library entry would be torn down and rebuilt on every navigation, and would simply not
      // exist on the album screen -- so the one control that gets a user back to what is playing
      // would vanish exactly where they went looking for it.
      //
      // Hidden on the player screen itself: a mini player under a full player is two controls for
      // one thing, and the top one wins by accident.
      //
      // `MiniPlayer` renders nothing at all when nothing is playing, so this bar takes no space
      // before the first track -- that decision is the composable's, not this call site's.
      if (backStack.lastOrNull() != PlayerRoute) {
        MiniPlayer(onOpenPlayer = { openPlayer() })
      }
    },
  ) { padding ->
    NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      modifier = Modifier.padding(padding),
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
          LibraryScreen(
            onAlbumClick = { albumId -> backStack.add(AlbumRoute(albumId)) },
            onOpenPlayer = { openPlayer() },
            // Plan 6 Task 12. The settings screen is a *slot* (`:feature:settings`), so this entry
            // names no setting and no feature that contributes one -- see `SettingsSection`.
            onOpenSettings = { backStack.add(SettingsRoute) },
          )
        }
        // `route.albumId`, not the brief's parameterless `AlbumScreen()`: Task 9's own fix round
        // established that Navigation 3 populates no `SavedStateHandle` argument for a `NavKey`'s
        // properties, so the key's id is passed as an ordinary parameter. See `AlbumScreen`'s doc.
        entry<AlbumRoute> { route ->
          AlbumScreen(albumId = route.albumId, onOpenPlayer = { openPlayer() })
        }
        entry<PlayerRoute> {
          PlayerScreen(
            castDeviceName = castDeviceName,
            castButton = {
              CastButton(onClick = { pickerOpen = true }, viewModel = castViewModel)
            },
          )
        }
        entry<PlayerRoute> { PlayerScreen() }
        entry<SettingsRoute> { SettingsScreen() }
      },
    )
  }
}
