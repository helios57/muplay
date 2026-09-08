package io.github.helios57.muplay.model

/**
 * What one thumb tap has to do to the listener's `promoted-<user>` playlist.
 *
 * Kept as a decision rather than a set of calls so that the branches — no playlist yet, already a
 * member, not a member, a duplicate left by something else — are testable without a server. Each of
 * them is a *different request or none at all*, and every wrong answer is silent: the rating still
 * saves, and only the playlist is wrong.
 *
 * ### The one measured fact this exists for
 *
 * `updatePlaylist&songIdToAdd=<id>` on a song the playlist already contains **appends a second
 * copy** — measured against the CI Navidrome, not inferred from the specification, which says
 * nothing either way. So there is no "add, idempotently"; membership has to be read first, and
 * reading it costs the `getPlaylist` that [actionFor] takes as its argument.
 */
object PromotedPlaylist {

  /**
   * The playlist name for [username].
   *
   * Subsonic ratings are already per user — `setRating` records the account the request
   * authenticates as — but a playlist is a named object on a shared server, so the *name* is what
   * keeps two listeners apart.
   */
  fun nameFor(username: String): String = "promoted-$username"

  /**
   * The playlist named [nameFor] for [username], or `null` if the user has none yet.
   *
   * Exact match. `promoted-al` is a prefix of `promoted-alice`, so a `startsWith` here would hand
   * one listener the other's playlist.
   */
  fun find(playlists: List<Playlist>, username: String): Playlist? {
    val name = nameFor(username)
    return playlists.firstOrNull { it.name == name }
  }

  /**
   * The edit [songId] needs, given the playlist as the server currently has it.
   *
   * [existing] is `null` when the user has no promoted playlist at all. [promoted] is the state the
   * song is moving *to*, not the tap: a thumb up on an already-promoted song clears the rating, and
   * arrives here as `promoted = false`.
   */
  fun actionFor(existing: PlaylistWithSongs?, songId: String, promoted: Boolean): PromotedPlaylistAction {
    if (existing == null) {
      // No playlist and nothing to promote means no playlist. Creating one here — "so it is ready"
      // — would give every listener an empty `promoted-<user>` the first time they tapped a thumb
      // *down*, which is a thing the server would then show them forever.
      return if (promoted) PromotedPlaylistAction.Create else PromotedPlaylistAction.None
    }

    val indices = existing.songs.withIndex().filter { (_, song) -> song.id == songId }.map { it.index }
    return when {
      promoted && indices.isEmpty() -> PromotedPlaylistAction.Add
      promoted -> PromotedPlaylistAction.None
      indices.isEmpty() -> PromotedPlaylistAction.None
      // Every occurrence, in one request. Repeated `songIndexToRemove` values are resolved against
      // the original list rather than applied one after another — measured — so a list of indices
      // does not shift under itself.
      else -> PromotedPlaylistAction.Remove(indices)
    }
  }
}

/** One edit to the promoted playlist, or [None] for "the server is already right". */
sealed interface PromotedPlaylistAction {

  /** Nothing to do. */
  data object None : PromotedPlaylistAction

  /** `createPlaylist`, with this song as its only member. */
  data object Create : PromotedPlaylistAction

  /** `updatePlaylist&songIdToAdd`, appending this song. */
  data object Add : PromotedPlaylistAction

  /** `updatePlaylist&songIndexToRemove`, once per [indices] entry, in one request. */
  data class Remove(val indices: List<Int>) : PromotedPlaylistAction
}
