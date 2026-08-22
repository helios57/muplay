package app.muplay.model

/**
 * The role MuPlay assigns to a Subsonic music folder (what Navidrome calls a "library").
 *
 * The Subsonic `getMusicFolders` command returns only an `id` and an optional `name` for each
 * folder — nothing in the protocol response says what *kind* of content a folder holds. Every
 * [MusicLibrary] built directly from that response therefore carries [UNASSIGNED]; assigning
 * [MUSIC] or [AUDIOBOOKS] to a specific folder is necessarily a later, out-of-band decision (e.g.
 * matching a folder's name against what a setup flow expects) that this response shape alone
 * cannot make.
 */
enum class LibraryRole {
  MUSIC,
  AUDIOBOOKS,
  UNASSIGNED,
}
