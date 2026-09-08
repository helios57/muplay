package io.github.helios57.muplay.library

import io.github.helios57.muplay.model.FolderListing
import io.github.helios57.muplay.model.FolderNode
import io.github.helios57.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FolderUiStateTest {

  private fun song(id: String, path: String) = Song(
    id = id,
    libraryId = 1,
    title = id,
    albumId = "al",
    albumName = "An Album",
    artistId = "ar",
    artistName = "A Band",
    trackNumber = 1,
    discNumber = 1,
    durationSeconds = 100,
    suffix = "mp3",
    coverArtId = null,
    path = path,
  )

  private val folder = FolderNode(path = "Fourth Author", name = "Fourth Author", trackCount = 3)

  @Test
  fun `the root of a library that has folders shows them`() {
    val state = folderContent(
      listing = FolderListing(path = "", folders = listOf(folder), tracks = emptyList()),
      pathedSongCount = 3,
    )

    assertThat(state.folders).containsExactly(folder)
    assertThat(state.emptyReason).isNull()
    assertThat(state.title).isEqualTo(FOLDERS_LABEL)
    assertThat(state.parentPath).isNull()
  }

  @Test
  fun `a folder is titled by its own name, not by its whole path`() {
    val state = folderContent(
      listing = FolderListing(
        path = "Fourth Author/Multi Part Book",
        folders = emptyList(),
        tracks = listOf(song("s1", "Fourth Author/Multi Part Book/01.mp3")),
      ),
      pathedSongCount = 3,
    )

    assertThat(state.title).isEqualTo("Multi Part Book")
    assertThat(state.parentPath).isEqualTo("Fourth Author")
  }

  @Test
  fun `an empty library with no paths yet says so, rather than claiming it has no folders`() {
    // The upgrade window. Schema 8 adds the column and `MIGRATION_7_8` forces the reconcile that
    // fills it, but until that reconcile finishes every path is null and this screen has nothing.
    // "No folders" would be a lie about a library the user can see albums in on the next tab.
    val state = folderContent(
      listing = FolderListing(path = "", folders = emptyList(), tracks = emptyList()),
      pathedSongCount = 0,
    )

    assertThat(state.emptyReason).isEqualTo(FolderEmptyReason.AwaitingSync)
  }

  @Test
  fun `an empty library that does know its paths has genuinely nothing to show`() {
    val state = folderContent(
      listing = FolderListing(path = "", folders = emptyList(), tracks = emptyList()),
      pathedSongCount = 12,
    )

    assertThat(state.emptyReason).isEqualTo(FolderEmptyReason.Empty)
  }

  @Test
  fun `a folder holding only subfolders is not empty`() {
    // The case a per-directory implementation gets wrong twice over: nothing to list *and*
    // nothing to shuffle, on the folder most worth shuffling.
    val state = folderContent(
      listing = FolderListing(path = "Fourth Author", folders = listOf(folder), tracks = emptyList()),
      pathedSongCount = 3,
    )

    assertThat(state.emptyReason).isNull()
    assertThat(state.canShuffle).isTrue
  }

  @Test
  fun `shuffle is offered only where there is something beneath to shuffle`() {
    val nothing = folderContent(
      listing = FolderListing(path = "Empty", folders = emptyList(), tracks = emptyList()),
      pathedSongCount = 12,
    )
    assertThat(nothing.canShuffle)
      .describedAs("a shuffle control that starts nothing is a button that does not work")
      .isFalse

    val tracks = folderContent(
      listing = FolderListing(
        path = "Fourth",
        folders = emptyList(),
        tracks = listOf(song("s1", "Fourth/a.mp3")),
      ),
      pathedSongCount = 12,
    )
    assertThat(tracks.canShuffle).isTrue
  }

  @Test
  fun `the root's shuffle draws the whole library, so it is offered whenever anything has a path`() {
    // Not derived from this listing: the root listing's own `tracks` are only the loose files
    // beside the top-level folders, and a library whose every track is inside a folder would
    // otherwise offer no shuffle at its root -- which is the normal case, not the edge case.
    val state = folderContent(
      listing = FolderListing(path = "", folders = listOf(folder), tracks = emptyList()),
      pathedSongCount = 3,
    )

    assertThat(state.canShuffle).isTrue
  }

  @Test
  fun `a mirror with no paths yet is told how to get some, not that it has no folders`() {
    // The two empty states read identically to a user unless the message says which is which, and
    // this one is not the user's doing: every install upgraded from before schema 8 has a null
    // `path` on every row until the reconcile runs. "Nothing here" would be a plain lie told to
    // somebody whose albums are visible on the next tab.
    val awaiting = FolderEmptyReason.AwaitingSync.toMessage()

    assertThat(awaiting).isNotEqualTo(FolderEmptyReason.Empty.toMessage())
    // It names the control that fixes it, and the tab that control is on.
    assertThat(awaiting).contains("Refresh")
    assertThat(awaiting).contains("Albums")
  }

  @Test
  fun `one track is one track`() {
    // The count sits under folder names that are themselves full of numbers, where "1 tracks"
    // reads as a broken count rather than as grammar.
    assertThat(trackCountLabel(1)).isEqualTo("1 track")
    assertThat(trackCountLabel(0)).isEqualTo("0 tracks")
    assertThat(trackCountLabel(14)).isEqualTo("14 tracks")
  }
}
