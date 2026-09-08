package io.github.helios57.muplay.model

/**
 * One server-side playlist, as listed by `getPlaylists`.
 *
 * **Playlists are not library-scoped.** `getPlaylists` takes no `musicFolderId` — measured against
 * the CI Navidrome, which answers with every playlist the user owns whatever library its entries
 * came from — so unlike albums, a playlist does not belong under a library chip and this app does
 * not pretend it does.
 *
 * @property songCount and [durationSeconds] are the server's own summary, kept so the list can be
 *   drawn without fetching every playlist's contents. They are advisory: an empty playlist really
 *   does report `0`, and nothing here treats a disagreement with the fetched entries as an error.
 */
data class Playlist(
  val id: String,
  val name: String,
  val songCount: Int,
  val durationSeconds: Int,
  val owner: String?,
  val coverArtId: String?,
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
