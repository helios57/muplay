package io.github.helios57.muplay.ui

import androidx.navigation3.runtime.NavKey
import io.github.helios57.muplay.setup.SetupRoute
import io.github.helios57.muplay.ui.navigation.AlbumRoute
import io.github.helios57.muplay.ui.navigation.BookRoute
import io.github.helios57.muplay.ui.navigation.BookshelfRoute
import io.github.helios57.muplay.ui.navigation.FolderRoute
import io.github.helios57.muplay.ui.navigation.LibraryRoute
import io.github.helios57.muplay.ui.navigation.PlayerRoute
import io.github.helios57.muplay.ui.navigation.PlaylistRoute
import io.github.helios57.muplay.ui.navigation.PlaylistsRoute
import io.github.helios57.muplay.ui.navigation.SettingsRoute
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
  // ---- the way back up out of a folder ----------------------------------------------------------
  //
  // Folders are the one section whose detail screen has no header of its own: the top bar carries
  // the folder's name, and before this there was nothing on it that went up. System back worked, so
  // nothing was *broken* -- and a user three folders deep could see no way out of them.

  @Test
  fun `a folder inside the library offers a way back up`() {
    assertThat(offersBackUp(FolderRoute("Fourth Author/Book"))).isTrue()
    assertThat(offersBackUp(FolderRoute("Fourth Author"))).isTrue()
  }

  @Test
  fun `the folders tab root offers none, because there is nothing above it`() {
    // Its parent is the tab bar. A back arrow there would leave the section from a screen the user
    // reached by tapping the very tab it would leave -- which is what the navigation bar is for.
    assertThat(offersBackUp(FolderRoute(""))).isFalse()
  }

  @Test
  fun `no other screen the top bar draws offers one`() {
    // The bar renders for the four section roots and for a folder. The three non-folder roots are
    // tabs, and a null screen is the state before the first composition.
    assertThat(offersBackUp(LibraryRoute)).isFalse()
    assertThat(offersBackUp(PlaylistsRoute)).isFalse()
    assertThat(offersBackUp(BookshelfRoute)).isFalse()
    assertThat(offersBackUp(null)).isFalse()
  }
}
