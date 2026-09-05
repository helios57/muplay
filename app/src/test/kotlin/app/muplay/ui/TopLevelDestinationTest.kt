package app.muplay.ui

import androidx.navigation3.runtime.NavKey
import app.muplay.setup.SetupRoute
import app.muplay.ui.navigation.AlbumRoute
import app.muplay.ui.navigation.BookRoute
import app.muplay.ui.navigation.BookshelfRoute
import app.muplay.ui.navigation.FolderRoute
import app.muplay.ui.navigation.LibraryRoute
import app.muplay.ui.navigation.PlayerRoute
import app.muplay.ui.navigation.PlaylistRoute
import app.muplay.ui.navigation.PlaylistsRoute
import app.muplay.ui.navigation.SettingsRoute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which navigation-bar tab is lit, and what tapping one does to the back stack.
 *
 * Pure, and worth holding here rather than only on a device: "the bar forgets which tab you are on
 * as soon as you open anything" is the single most common way a bottom bar goes wrong, and it is
 * invisible to a compile and to every other gate in this repository.
 */
class TopLevelDestinationTest {

  @Test
  fun `the library is the albums tab`() {
    assertThat(selectedTab(listOf(LibraryRoute))).isEqualTo(TopLevelDestination.Albums)
  }

  @Test
  fun `a tab stays lit while the user is deep inside it`() {
    // The defect this exists for. Descending three folders, or opening a book, must not put the
    // highlight back on Albums -- the user has not left the section they are in.
    assertThat(selectedTab(listOf(LibraryRoute, FolderRoute(""), FolderRoute("A"), FolderRoute("A/B"))))
      .isEqualTo(TopLevelDestination.Folders)
    assertThat(selectedTab(listOf(LibraryRoute, BookshelfRoute, BookRoute("b1"))))
      .isEqualTo(TopLevelDestination.Books)
    assertThat(selectedTab(listOf(LibraryRoute, PlaylistsRoute, PlaylistRoute("p1"))))
      .isEqualTo(TopLevelDestination.Playlists)
    assertThat(selectedTab(listOf(LibraryRoute, AlbumRoute("a1"))))
      .isEqualTo(TopLevelDestination.Albums)
  }

  @Test
  fun `a destination that belongs to no tab leaves the one underneath it lit`() {
    // Settings and the player are pushed from wherever the user was. Lighting nothing would make
    // the bar flicker off; lighting Albums would move the highlight to a tab they did not choose.
    assertThat(selectedTab(listOf(LibraryRoute, PlaylistsRoute, SettingsRoute)))
      .isEqualTo(TopLevelDestination.Playlists)
    assertThat(selectedTab(listOf(LibraryRoute, FolderRoute(""), PlayerRoute)))
      .isEqualTo(TopLevelDestination.Folders)
  }

  @Test
  fun `setup belongs to no tab at all`() {
    // The bar is hidden there, and this is the value that decides it: a highlighted tab on a
    // screen with no navigation bar is a state the two halves would have to agree about.
    assertThat(selectedTab(listOf(SetupRoute))).isNull()
    assertThat(selectedTab(emptyList())).isNull()
  }

  @Test
  fun `choosing a tab rebuilds the stack so back returns to the library`() {
    // Material's rule, and the one users have: back from a non-start tab goes to the start tab,
    // and back from there leaves. The alternative -- pushing tabs -- builds an unbounded stack in
    // which back walks the user's whole browsing history one tab at a time.
    assertThat(stackFor(TopLevelDestination.Albums)).containsExactly(LibraryRoute)
    assertThat(stackFor(TopLevelDestination.Folders))
      .containsExactly(LibraryRoute, FolderRoute(""))
    assertThat(stackFor(TopLevelDestination.Playlists))
      .containsExactly(LibraryRoute, PlaylistsRoute)
    assertThat(stackFor(TopLevelDestination.Books))
      .containsExactly(LibraryRoute, BookshelfRoute)
  }

  @Test
  fun `the folders tab always opens at the library root`() {
    // Not at whatever folder was last visited: a tab tapped from another tab is a fresh start, and
    // restoring a six-deep path the user cannot see the top of is disorienting rather than helpful.
    val deep: List<NavKey> = stackFor(TopLevelDestination.Folders)

    assertThat(deep.last()).isEqualTo(FolderRoute(""))
  }

  @Test
  fun `every tab is reachable and no two share a key`() {
    // A duplicate would light two tabs at once, and -- if it reached `entryProvider` -- is the
    // `An 'entry' with the same 'clazz' has already been added` crash `ConventionTest` now scans
    // for. Cheap to assert here, where the list is.
    val keys = TopLevelDestination.entries.map { it.key }

    assertThat(keys).doesNotHaveDuplicates()
    assertThat(TopLevelDestination.entries.map { it.label }).doesNotHaveDuplicates()
  }
}
