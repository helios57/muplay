package app.muplay.model

/**
 * One folder in the library's file tree.
 *
 * **Not to be confused with Subsonic's "music folder"**, which in that protocol means a whole
 * *library* and is what `musicFolderId` identifies throughout this codebase. This is a directory
 * inside one — the thing a listener recognises as "the folder this album is in".
 *
 * [path] is relative to the library root and `/`-separated, so it is exactly the prefix of the
 * songs beneath it. [trackCount] is recursive: it counts everything below [path] at any depth,
 * because that is the number "shuffle this folder" will draw from and a count of the folder's own
 * files would promise the wrong thing.
 */
data class FolderNode(
  val path: String,
  val name: String,
  val trackCount: Int,
)

/**
 * What one folder screen shows: the folders immediately below it and the tracks lying in it.
 *
 * [path] is `""` at the library root. [tracks] excludes anything in a subfolder — those are
 * reachable through [folders] — so the two lists never show the same song twice.
 */
data class FolderListing(
  val path: String,
  val folders: List<FolderNode>,
  val tracks: List<Song>,
)
