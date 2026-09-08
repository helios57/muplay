package io.github.helios57.muplay.model

/**
 * One server-side playlist, as listed by `getPlaylists`.
 *
 * ### A playlist has no library of its own, and that is a fact about the protocol
 *
 * `getPlaylists` accepts `musicFolderId` and **ignores it** — measured against the CI Navidrome
 * twice, most recently with one playlist holding a music track and another holding an audiobook
 * chapter: both came back for `musicFolderId=1` and both came back for `musicFolderId=2`. Nothing
 * in the listing, and nothing on a `getPlaylist` entry either, names a library.
 *
 * So "show me the playlists in this library" cannot be asked of the server. It has to be *derived*,
 * from the entries, through the mirror — `PlaylistRepository.librariesOf` does that, and [changed]
 * exists so it can be done once rather than on every visit to the tab.
 *
 * @property songCount and [durationSeconds] are the server's own summary, kept so the list can be
 *   drawn without fetching every playlist's contents. They are advisory: an empty playlist really
 *   does report `0`, and nothing here treats a disagreement with the fetched entries as an error.
 * @property changed the server's own "last edited" stamp, kept **opaque**. Nothing parses it or
 *   compares it for order; it is the cache key under which a derived library set stays valid, and
 *   an opaque string is exactly enough for that. `songCount` plus [durationSeconds] was the
 *   tempting alternative and it is a weaker key: swapping one three-minute track for another from a
 *   different library moves neither number. Null when the server does not send it, which is read as
 *   "no key", not as "unchanged".
 */
data class Playlist(
  val id: String,
  val name: String,
  val songCount: Int,
  val durationSeconds: Int,
  val owner: String?,
  val coverArtId: String?,
  val changed: String? = null,
)

/**
 * A playlist and its entries, **in the server's order**.
 *
 * The order is the whole point of a playlist and nothing else in this app preserves it, so every
 * layer that touches this list carries it through unchanged rather than sorting it.
 */
data class PlaylistWithSongs(
  val playlist: Playlist,
  val songs: List<Song>,
)
