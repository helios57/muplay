package io.github.helios57.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [PromotedPlaylist]: what one thumb tap does to the listener's `promoted-<user>` playlist.
 *
 * The decision is separated from the requests that carry it out because the interesting cases are
 * all *absences* — the playlist does not exist yet, the song is already in it, the song is not in
 * it — and each of them is a different request or no request at all. Getting one wrong is silent:
 * the rating still saves, and the playlist quietly grows a duplicate or keeps a demoted track.
 *
 * ### The duplicate is measured, not hypothetical
 *
 * `updatePlaylist&songIdToAdd=<id>` on a song the playlist already contains **appends a second
 * copy** — measured against the CI Navidrome. So "add" is only correct after checking membership,
 * and that check is this object's whole reason to exist.
 */
class PromotedPlaylistTest {

  @Test
  fun `the playlist is named for the user, so two listeners never share one`() {
    // The requirement in one line: "each user has his promoted songs". The server's ratings are
    // already per user; the playlist is a shared object and has to be named apart by hand.
    assertThat(PromotedPlaylist.nameFor("alice")).isEqualTo("promoted-alice")
    assertThat(PromotedPlaylist.nameFor("bob")).isEqualTo("promoted-bob")
  }

  @Test
  fun `promoting with no playlist yet creates one`() {
    val action = PromotedPlaylist.actionFor(existing = null, songId = "s-1", promoted = true)

    assertThat(action).isEqualTo(PromotedPlaylistAction.Create)
  }

  @Test
  fun `promoting a song the playlist does not hold appends it`() {
    val action = PromotedPlaylist.actionFor(playlistOf("s-1", "s-2"), songId = "s-3", promoted = true)

    assertThat(action).isEqualTo(PromotedPlaylistAction.Add)
  }

  @Test
  fun `promoting a song the playlist already holds does nothing`() {
    // The measured duplicate. Without this branch the playlist grows a second copy every time the
    // listener taps a thumb up on a track that is already promoted -- which, since the thumb is a
    // toggle, is a thing they do by accident.
    val action = PromotedPlaylist.actionFor(playlistOf("s-1", "s-2"), songId = "s-2", promoted = true)

    assertThat(action).isEqualTo(PromotedPlaylistAction.None)
  }

  @Test
  fun `clearing a promoted song removes it at the index it sits at`() {
    // Subsonic has no `songIdToRemove`: removal is by position, which is why the caller has to
    // fetch the playlist before it can un-promote anything.
    val action = PromotedPlaylist.actionFor(playlistOf("s-1", "s-2", "s-3"), songId = "s-3", promoted = false)

    assertThat(action).isEqualTo(PromotedPlaylistAction.Remove(listOf(2)))
  }

  @Test
  fun `a song the playlist holds twice is removed everywhere at once`() {
    // A playlist edited elsewhere, or one this app duplicated before the membership check existed.
    // Both indices go in one request: measured against the CI Navidrome, repeated
    // `songIndexToRemove` values are resolved against the *original* list, so removing 1 and 2
    // together removes the two songs that were at 1 and 2 rather than shifting under itself.
    val action = PromotedPlaylist.actionFor(playlistOf("s-1", "s-2", "s-2", "s-3"), "s-2", promoted = false)

    assertThat(action).isEqualTo(PromotedPlaylistAction.Remove(listOf(1, 2)))
  }

  @Test
  fun `clearing a song the playlist does not hold does nothing`() {
    val action = PromotedPlaylist.actionFor(playlistOf("s-1"), songId = "s-9", promoted = false)

    assertThat(action).isEqualTo(PromotedPlaylistAction.None)
  }

  @Test
  fun `clearing when there is no playlist at all does nothing rather than creating an empty one`() {
    // The tempting bug: a "make sure the playlist exists" step that runs before the promote/demote
    // branch would leave every listener with an empty `promoted-<user>` playlist the first time
    // they tapped thumbs *down* on anything.
    val action = PromotedPlaylist.actionFor(existing = null, songId = "s-1", promoted = false)

    assertThat(action).isEqualTo(PromotedPlaylistAction.None)
  }

  @Test
  fun `the playlist is found by exact name, not by prefix`() {
    // `promoted-al` is a prefix of `promoted-alice`, and a `startsWith` here would hand one
    // listener's playlist to another. The lookup is exact and case-sensitive, which is what the
    // server's own name comparison is.
    val playlists = listOf(
      playlist("p-1", "promoted-alice"),
      playlist("p-2", "promoted-al"),
      playlist("p-3", "Promoted-Alice"),
    )

    assertThat(PromotedPlaylist.find(playlists, "al")?.id).isEqualTo("p-2")
    assertThat(PromotedPlaylist.find(playlists, "alice")?.id).isEqualTo("p-1")
    assertThat(PromotedPlaylist.find(playlists, "carol")).isNull()
  }

  private fun playlist(id: String, name: String) = Playlist(
    id = id,
    name = name,
    songCount = 0,
    durationSeconds = 0,
    owner = null,
    coverArtId = null,
  )

  private fun playlistOf(vararg songIds: String) = PlaylistWithSongs(
    playlist = playlist("p-1", "promoted-alice"),
    songs = songIds.map { id ->
      Song(
        id = id,
        libraryId = 1,
        title = id,
        albumId = null,
        albumName = null,
        artistId = null,
        artistName = null,
        trackNumber = null,
        discNumber = null,
        durationSeconds = 1,
        suffix = "mp3",
        coverArtId = null,
      )
    },
  )
}
