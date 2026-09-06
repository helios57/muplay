package app.muplay.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.ui.navigation.AlbumRoute
import app.muplay.ui.navigation.BookRoute
import app.muplay.ui.navigation.BookshelfRoute
import app.muplay.ui.navigation.FolderRoute
import app.muplay.ui.navigation.LibraryRoute
import app.muplay.ui.navigation.PlaylistRoute
import app.muplay.ui.navigation.PlaylistsRoute

/**
 * The four places the app is *about*, as a navigation bar.
 *
 * Before this, everything hung off one library screen: the shelf behind a card in the middle of the
 * album list, settings and refresh behind a row of text buttons below it, and no home at all for
 * anything new. Four named destinations is the smallest structure that makes the app's shape
 * legible without a menu -- and it is what gives folders and playlists somewhere to live that is
 * not "further down the library screen".
 */
enum class TopLevelDestination(
  val key: NavKey,
  val label: String,
  val icon: ImageVector,
) {
  Albums(LibraryRoute, "Albums", MuPlayIcons.Album),

  /** Opens at the library root; [FolderRoute]`(path)` is pushed for each folder descended into. */
  Folders(FolderRoute(""), "Folders", MuPlayIcons.Folder),
  Playlists(PlaylistsRoute, "Playlists", MuPlayIcons.Playlist),
  Books(BookshelfRoute, "Books", MuPlayIcons.Book),
  ;

  /** Whether [key] is this section -- its own key, or a detail screen belonging to it. */
  internal fun owns(key: NavKey): Boolean = when (this) {
    Albums -> key == LibraryRoute || key is AlbumRoute
    Folders -> key is FolderRoute
    Playlists -> key == PlaylistsRoute || key is PlaylistRoute
    Books -> key == BookshelfRoute || key is BookRoute
  }
}

/**
 * Which tab the bar should light for a given back stack, or null when none applies.
 *
 * **The topmost key that belongs to a section wins**, not the bottom one and not the last tab
 * tapped. That is what keeps the highlight on Folders while the user is three folders deep, and
 * what leaves it where it was when they push Settings or a player -- destinations that belong to
 * no section and should not move it.
 */
fun selectedTab(backStack: List<NavKey>): TopLevelDestination? =
  backStack.asReversed().firstNotNullOfOrNull { key ->
    TopLevelDestination.entries.firstOrNull { it.owns(key) }
  }

/**
 * The whole back stack after tapping [tab].
 *
 * Rebuilt rather than pushed: Material's rule, and the one users already have, is that back from a
 * non-start tab returns to the start tab and back from there leaves the app. Pushing tabs instead
 * builds an unbounded stack in which back walks the user's entire browsing history one screen at a
 * time -- and, on this app, would let the same tab appear on it twice.
 */
fun stackFor(tab: TopLevelDestination): List<NavKey> =
  if (tab == TopLevelDestination.Albums) listOf(LibraryRoute) else listOf(LibraryRoute, tab.key)

/**
 * Whether the top bar should draw a back arrow for [onScreen].
 *
 * True for a folder inside the library and nothing else. The bar renders only for the four section
 * roots and for a folder (see `MuPlayApp`'s `topBar`), and a section root's parent is the navigation
 * bar -- an arrow there would offer to leave a section by the same tap that entered it. A nested
 * folder is the one screen the bar draws that has somewhere above it, and the one screen in this app
 * with no header of its own to carry the affordance.
 *
 * A function here rather than an expression in the `topBar` lambda, for the reason `selectedTab` is
 * one: nothing on the JVM tier composes `MuPlayApp`, so a navigation decision written inline is a
 * decision only a device can check -- and this project's device tier is the one that disappears.
 */
fun offersBackUp(onScreen: NavKey?): Boolean =
  onScreen is FolderRoute && onScreen.path.isNotEmpty()
