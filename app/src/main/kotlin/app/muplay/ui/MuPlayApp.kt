package app.muplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.muplay.castpicker.CastButton
import app.muplay.castpicker.CastPickerSheet
import app.muplay.castpicker.CastViewModel
import app.muplay.book.BookPlayerScreen
import app.muplay.book.BookScreen
import app.muplay.book.BookshelfScreen
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.library.AlbumScreen
import app.muplay.library.FolderScreen
import app.muplay.library.LibraryScreen
import app.muplay.library.PlaylistScreen
import app.muplay.library.PlaylistsScreen
import app.muplay.library.folderTitle
import app.muplay.player.MiniPlayer
import app.muplay.player.PlayerScreen
import app.muplay.requests.IntegrationsPresenceViewModel
import app.muplay.requests.IntegrationsRoute
import app.muplay.requests.IntegrationsScreen
import app.muplay.requests.RequestsRoute
import app.muplay.requests.RequestsScreen
import app.muplay.settings.SettingsScreen
import app.muplay.setup.SetupRoute
import app.muplay.setup.SetupScreen
import app.muplay.ui.navigation.AlbumRoute
import app.muplay.ui.navigation.BookPlayerRoute
import app.muplay.ui.navigation.BookRoute
import app.muplay.ui.navigation.BookshelfRoute
import app.muplay.ui.navigation.FolderRoute
import app.muplay.ui.navigation.LibraryRoute
import app.muplay.ui.navigation.PlayerRoute
import app.muplay.ui.navigation.PlaylistRoute
import app.muplay.ui.navigation.PlaylistsRoute
import app.muplay.ui.navigation.SettingsRoute

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MuPlayNavigation(
  start: NavKey,
  modifier: Modifier,
  playerDestinationViewModel: PlayerDestinationViewModel = hiltViewModel(),
) {
  val backStack = rememberNavBackStack(start)

  // **Which player opens is decided here, from `PlaybackState.isAudiobook`, and nowhere else.**
  // `:feature:book` and `:feature:player` do not depend on each other -- a feature-to-feature edge
  // is how two screens stop being able to change independently -- so `:app` is the only place that
  // can hold both keys, and this is the only expression in the app that reads `isAudiobook`.
  val isAudiobook by playerDestinationViewModel.isAudiobook.collectAsStateWithLifecycle()
  val openPlayer = { backStack.add(if (isAudiobook) BookPlayerRoute else PlayerRoute) }

  // **And corrected here once the session has answered**, which is the half that was missing.
  //
  // Every caller of `openPlayer` starts playback and opens the player in the same tap --
  // `viewModel.resume(); onOpenPlayer()` -- and the first of those two launches a coroutine. So
  // `isAudiobook` above is still describing whatever was playing *before* the tap, and the player
  // that opens is the previous item's. Measured on a device by `AudiobookResumeJourneyTest`: from a
  // cold session, tapping a book opened `PlayerScreen` (no chapters, no speed, no sleep timer), and
  // tapping a music track straight afterwards opened `BookPlayerScreen`, which renders
  // "Nothing playing" and has no transport at all -- with the mini player hidden underneath it,
  // because `MuPlayApp` hides it on both player screens. The audiobook player was, in practice,
  // unreachable.
  //
  // A **swap of the top entry**, not a push and not a pop-then-push: the player destination shows
  // whatever the session is playing (that is why both keys are `data object`s), so the choice
  // between the two has to follow the session for the same reason the screen's contents do. It
  // fires only when a player is already on top, so navigating anywhere else is untouched, and it is
  // idempotent -- the arms are mutually exclusive and each one removes its own trigger.
  LaunchedEffect(isAudiobook) {
    val index = backStack.lastIndex
    when (backStack.lastOrNull()) {
      PlayerRoute -> if (isAudiobook) backStack[index] = BookPlayerRoute
      BookPlayerRoute -> if (!isAudiobook) backStack[index] = PlayerRoute
      else -> Unit
    }
  }

  // **One `CastViewModel`, hoisted here**, and handed to both the button and the sheet rather than
  // left to each of them to resolve for itself. `hiltViewModel()` answers against the nearest
  // `ViewModelStoreOwner`, and a `NavDisplay` entry brings its own -- so a button inside the player
  // entry and a sheet hosted out here would be two view models, two discovery passes, and a sheet
  // that never learned which speaker the button was pointing at.
  // Plan 7 Task 10. **Whether the requests destination exists at all**, and it is read here because
  // `entryProvider` is the only place that decision can be made. Its two lines live in
  // `:feature:requests` rather than in this module so that they are gated on the fast tier; see
  // `IntegrationsPresenceViewModel`.
  val presenceViewModel: IntegrationsPresenceViewModel = hiltViewModel()
  val anyIntegrationConfigured by presenceViewModel.anyConfigured.collectAsStateWithLifecycle()

  val castViewModel: CastViewModel = hiltViewModel()
  val castDeviceName by castViewModel.connectedDeviceName.collectAsStateWithLifecycle()
  // `rememberSaveable`, so a rotation with the picker open does not close it. The search is started
  // from the effect below rather than from the tap, so a restored `true` starts one too -- a
  // restored sheet over a view model that was never opened would render nothing at all.
  var pickerOpen by rememberSaveable { mutableStateOf(false) }

  LaunchedEffect(pickerOpen) {
    if (pickerOpen) castViewModel.open() else castViewModel.close()
  }

  // A destination that stops existing must not stay on the back stack. `NavDisplay` throws for a key
  // its `entryProvider` has no entry for, and forgetting the last integration is done from the
  // integrations screen -- which a user can perfectly well have reached with the requests screen
  // still underneath it.
  LaunchedEffect(anyIntegrationConfigured) {
    if (!anyIntegrationConfigured) backStack.removeAll { it == RequestsRoute }
  }

  if (pickerOpen) {
    CastPickerSheet(onDismiss = { pickerOpen = false }, viewModel = castViewModel)
  }

  // Which section the bar lights, and whether there is any chrome at all. Both read the whole back
  // stack rather than only its top, so a player or the settings screen pushed from a section does
  // not move the highlight off it -- see `selectedTab`.
  val onScreen = backStack.lastOrNull()
  val onPlayer = onScreen == PlayerRoute || onScreen == BookPlayerRoute
  val tab = selectedTab(backStack)

  Scaffold(
    modifier = modifier,
    topBar = {
      // **Only on a section's own screens**, not on album, book, player or settings -- those four
      // draw their own headers, and a second title bar above one is two answers to "where am I".
      // A nested folder is the exception that proves it: it has no header of its own, and its own
      // name is the only thing that says which folder is open.
      val folder = onScreen as? FolderRoute
      val sectionRoot = onScreen == LibraryRoute || onScreen == PlaylistsRoute ||
        onScreen == BookshelfRoute || folder != null
      if (tab != null && sectionRoot) {
        TopAppBar(
          title = {
            // `folderTitle`, not a second copy of its arithmetic: the same title is computed
            // inside `folderContent` from the listing, and two derivations of one string drift.
            Text(text = if (folder != null) folderTitle(folder.path) else tab.label)
          },
          actions = {
            CastButton(onClick = { pickerOpen = true }, viewModel = castViewModel)
            // Out of the library screen's button row, where it sat between Refresh and a card
            // opening the bookshelf. Settings is not a library action and never was.
            IconButton(onClick = { backStack.add(SettingsRoute) }) {
              Icon(
                imageVector = MuPlayIcons.Settings,
                contentDescription = SETTINGS_LABEL,
                modifier = Modifier.size(MuPlaySpacing.xl),
              )
            }
          },
        )
      }
    },
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
      //
      // Hidden on **both** player screens. The audiobook player is a second full player, so the
      // "two controls for one thing, and the top one wins by accident" the paragraph above
      // describes applies to it identically.
      //
      // The navigation bar sits **under** the mini player, so the bar the thumb rests on is the one
      // that never changes and the now-playing strip floats above it. Both are hidden on a player
      // screen: that screen is the destination, and a bar offering to leave it competes with its
      // own back gesture.
      Column {
        if (!onPlayer) {
          MiniPlayer(onOpenPlayer = { openPlayer() })
        }
        if (tab != null && !onPlayer) {
          NavigationBar {
            TopLevelDestination.entries.forEach { destination ->
              NavigationBarItem(
                selected = destination == tab,
                onClick = {
                  // Rebuilt, not pushed -- see `stackFor`. Re-tapping the section you are already
                  // in returns to its root, which is what every other Android app does and is the
                  // only way back out of six nested folders in one tap.
                  backStack.clear()
                  stackFor(destination).forEach(backStack::add)
                },
                // A handle for the journeys, and a necessary one rather than a convenience.
                // `NavigationBarItem` sets `Selected` and carries its label as text, which is
                // exactly the shape `StoreScreenshotsTest` uses to find the library chips and to
                // read the browse list -- so without a way to tell a tab from a chip, four tabs
                // become four libraries and four album titles. See `NAV_TAB_TAG_PREFIX`.
                modifier = Modifier.testTag(NAV_TAB_TAG_PREFIX + destination.name),
                icon = {
                  Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    modifier = Modifier.size(MuPlaySpacing.xl),
                  )
                },
                label = { Text(destination.label) },
              )
            }
          }
        }
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
        // These three bodies are each **one statement on one line**, which is a coverage decision
        // and not a style one. `:app`'s only floor is a BUNDLE LINE 0.90 over merged JVM +
        // instrumented data, and nothing navigates to these three destinations until Plan 4 Task
        // 10's journeys exist -- so every line inside these lambdas is a line that floor counts
        // and cannot cover. The `entry<...>` lines themselves run on every composition and are
        // fine; it is only the bodies. See the note on `:app`'s entry in the root build script.
        // Folders. One entry serves every depth -- the key carries the path -- so descending is a
        // push and the system back gesture is what walks back up. See `FolderRoute`.
        entry<FolderRoute> { route ->
          FolderScreen(
            path = route.path,
            onOpenFolder = { backStack.add(FolderRoute(it)) },
            onOpenPlayer = { openPlayer() },
          )
        }
        entry<PlaylistsRoute> {
          PlaylistsScreen(onOpenPlaylist = { backStack.add(PlaylistRoute(it)) })
        }
        entry<PlaylistRoute> { route ->
          PlaylistScreen(playlistId = route.playlistId, onOpenPlayer = { openPlayer() })
        }
        entry<BookshelfRoute> {
          BookshelfScreen(onBookClick = { backStack.add(BookRoute(it)) }, onOpenPlayer = { openPlayer() })
        }
        // `route.bookId`, for the reason `entry<AlbumRoute>` above passes `route.albumId`:
        // Navigation 3 populates no `SavedStateHandle` argument from a key's own properties.
        entry<BookRoute> { route -> BookScreen(bookId = route.bookId, onOpenPlayer = { openPlayer() }) }
        entry<BookPlayerRoute> { BookPlayerScreen() }
        entry<SettingsRoute> {
          SettingsScreen(
            onNavigate = { key ->
              // **`SetupRoute` is a restart, not a push**, and `:app` is the only thing that can
              // make it one -- a section composed inside `:feature:settings` is handed
              // `(NavKey) -> Unit` and nothing else, which is the point of that slot.
              //
              // `ServerSection` reaches here after signing out, i.e. after the credentials the
              // library and player screens underneath are built on have been destroyed. Pushing
              // setup on top of them would leave a back gesture landing on a browse screen with no
              // server, and a restored back stack would do the same without any gesture at all.
              if (key == SetupRoute) {
                backStack.clear()
                backStack.add(SetupRoute)
              } else {
                backStack.add(key)
              }
            },
          )
        }
        // Plan 7 Task 10. Always registered: a user has to be able to turn the feature on, and this
        // screen is the only thing that can. It is reached from the one always-present settings row,
        // which `:feature:requests` contributes into `:feature:settings`'s slot -- so nothing here
        // names it and deleting that module takes the row away with it.
        entry<IntegrationsRoute> { IntegrationsScreen() }
        // **Registered only while at least one integration is configured, and not
        // registered-and-hidden.** A destination that exists is reachable by a restored back stack
        // and by a stale navigation event, and either would land a user who runs neither service on
        // a screen for a feature they do not have.
        if (anyIntegrationConfigured) {
          entry<RequestsRoute> { RequestsScreen(onOpenAlbum = { backStack.add(AlbumRoute(it)) }) }
        }
      },
    )
  }
}

/** The top bar's settings control. Its own name, because the icon carries no text. */
private const val SETTINGS_LABEL = "Settings"

/**
 * The prefix on every navigation bar tab's test tag, followed by the [TopLevelDestination] name.
 *
 * `internal` so `:app`'s own instrumented tests can filter the bar out of a text or `Selected`
 * sweep. This is the navigation bar's version of `MiniPlayer`'s `"Now playing"` content
 * description: chrome that sits on every browse screen and carries text of its own, which any
 * matcher over the screen underneath will otherwise read as content. `JourneyNavigation`'s
 * `notTheMiniPlayer` records what that costs when it is missing.
 */
internal const val NAV_TAB_TAG_PREFIX = "nav:tab:"
