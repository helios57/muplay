package app.muplay.model

/**
 * A single Subsonic "music folder" — Navidrome's term for it, and the one used throughout this
 * codebase, is "library" — as returned by the `getMusicFolders` command.
 *
 * [name] is never absent here even though the underlying `musicFolder` element's `name` field is
 * optional per the OpenSubsonic spec (only `id` is required): the network layer that builds a
 * [MusicLibrary] substitutes a stable, id-derived fallback name rather than surfacing a nullable
 * or blank one to every caller.
 */
data class MusicLibrary(
  val id: Int,
  val name: String,
  val role: LibraryRole,
)
