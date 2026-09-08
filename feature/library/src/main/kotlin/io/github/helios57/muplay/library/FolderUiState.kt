package io.github.helios57.muplay.library

import io.github.helios57.muplay.model.FolderListing
import io.github.helios57.muplay.model.FolderNode
import io.github.helios57.muplay.model.Song

internal const val FOLDERS_LABEL = "Folders"

/**
 * The title for the folder screen at [path]: the folder's own name, or [FOLDERS_LABEL] at the root.
 *
 * `public`, and called from two places on purpose. `MuPlayApp` draws the top bar around the screen
 * rather than inside it, so it needs this title from the navigation key alone -- before any state
 * exists -- while [folderContent] needs it from the listing. Written twice they drifted the moment
 * one of them learned that a path can be one segment deep; written once they cannot.
 */
fun folderTitle(path: String): String = path.substringAfterLast('/').ifEmpty { FOLDERS_LABEL }

/** Why a folder screen has nothing to list. Null when it has. */
enum class FolderEmptyReason {
  /**
   * The mirror does not know any paths yet.
   *
   * A mirror written before schema version 8 has a null `path` on every row until the reconcile
   * [io.github.helios57.muplay.database.MIGRATION_7_8] forces has run. Rendering that as "no folders" would be a
   * plain lie about a library whose albums are visible on the next tab, and the kind of lie a user
   * reasonably reads as a broken feature rather than a pending one.
   */
  AwaitingSync,

  /** The mirror knows its paths and this folder really is empty. */
  Empty,
}

/**
 * What one folder screen shows.
 *
 * A data class with a pure builder rather than logic in the ViewModel, for the reason
 * [libraryContent] gives: these are the decisions a user sees, and they are worth holding to a
 * unit test rather than to an emulator.
 */
data class FolderUiState(
  val path: String,
  val title: String,
  val parentPath: String?,
  val folders: List<FolderNode>,
  val tracks: List<Song>,
  val emptyReason: FolderEmptyReason?,
  val canShuffle: Boolean,
)

/**
 * Folds a [FolderListing] and the library's pathed-song count into what the screen renders.
 *
 * [pathedSongCount] is not derivable from [listing] and both callers need it: it separates "this
 * mirror has no paths yet" from "this folder is empty", and it is what makes the **root** offer a
 * shuffle. A root listing's own `tracks` are only the loose files lying beside the top-level
 * folders, so a library whose every track is inside a folder -- which is the normal case, not the
 * edge one -- would otherwise show a root that cannot be shuffled.
 */
fun folderContent(listing: FolderListing, pathedSongCount: Int): FolderUiState {
  val isRoot = listing.path.isEmpty()
  val empty = listing.folders.isEmpty() && listing.tracks.isEmpty()
  return FolderUiState(
    path = listing.path,
    title = folderTitle(listing.path),
    parentPath = if (isRoot) null else listing.path.substringBeforeLast('/', missingDelimiterValue = ""),
    folders = listing.folders,
    tracks = listing.tracks,
    emptyReason = when {
      !empty -> null
      pathedSongCount == 0 -> FolderEmptyReason.AwaitingSync
      else -> FolderEmptyReason.Empty
    },
    // At the root, anything with a path is in scope. Below it, only what this listing can see --
    // and a folder holding nothing but subfolders is still shuffleable, because those subfolders
    // are counted from the songs beneath them.
    canShuffle = if (isRoot) pathedSongCount > 0 else !empty,
  )
}

/**
 * The sentence a folder screen shows instead of a list, for each reason it can have none.
 *
 * Here rather than beside the `Composable` that renders it, for the reason `LibraryNoticeKt`'s own
 * floor records: these are decisions a user reads, and a `when` over a sealed set of them is worth
 * holding to a unit test rather than to an emulator this repository regularly does not have.
 */
internal fun FolderEmptyReason.toMessage(): String = when (this) {
  // Names the tab and the control that fixes it. "No folders" would be a bare fact about a state
  // the user did not cause and cannot interpret.
  FolderEmptyReason.AwaitingSync -> AWAITING_SYNC_LABEL
  FolderEmptyReason.Empty -> EMPTY_FOLDER_LABEL
}

/**
 * `"1 track"` / `"14 tracks"` -- the count under a folder's name, through every subfolder.
 *
 * Singular is not a nicety here. The line sits directly under a folder called something like
 * `Live 1975`, and `"1 tracks"` next to a name full of numbers reads as a rendering bug in the
 * count itself rather than as grammar.
 */
internal fun trackCountLabel(count: Int): String = if (count == 1) "1 track" else "$count tracks"

internal const val AWAITING_SYNC_LABEL =
  "Folders appear once the library has been read. Pull Refresh on the Albums tab."
internal const val EMPTY_FOLDER_LABEL = "Nothing in this folder."
