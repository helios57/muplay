package io.github.helios57.muplay.library

import io.github.helios57.muplay.database.OfflinePlaylist
import io.github.helios57.muplay.database.SyncFailure
import io.github.helios57.muplay.media.DownloadActivity
import io.github.helios57.muplay.model.Playlist
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What the playlist screens say about a playlist kept on the phone, and what the list shows when
 * the server cannot be reached -- which used to be an error and nothing else, saved or not.
 */
class OfflineStatusTest {

  private fun playlist(id: String) = Playlist(id, id, 3, 300, "admin", null)

  private fun saved(id: String, downloaded: Int, total: Int = 3) =
    OfflinePlaylist(playlist(id), total = total, downloaded = downloaded)

  @Test
  fun `a playlist nobody kept says nothing about it`() {
    assertThat(offlineStatus(null, DownloadActivity.Idle)).isEqualTo(OfflineStatus.NotKept)
    assertThat(OfflineStatus.NotKept.label()).isNull()
  }

  @Test
  fun `a playlist on its way counts what has arrived`() {
    val status = offlineStatus(saved("a", 1), DownloadActivity.Downloading("Track 2"))

    assertThat(status).isEqualTo(OfflineStatus.Downloading(1, 3))
    assertThat(status.label()).isEqualTo("Downloading 1 of 3…")
  }

  @Test
  fun `a stalled download says it paused rather than that it is still going`() {
    val status = offlineStatus(saved("a", 2), DownloadActivity.Stalled("timeout"))

    assertThat(status).isEqualTo(OfflineStatus.Paused(2, 3))
    assertThat(status.label()).contains("paused").contains("2 of 3")
  }

  @Test
  fun `a complete playlist is on the phone, whatever the downloader is doing for another`() {
    val status = offlineStatus(saved("a", 3), DownloadActivity.Stalled("timeout"))

    assertThat(status).isEqualTo(OfflineStatus.Kept)
    assertThat(status.label()).isEqualTo("On this phone")
  }

  @Test
  fun `a kept playlist whose songs all left the server is not downloading forever`() {
    assertThat(offlineStatus(saved("a", 0, total = 0), DownloadActivity.Idle)).isEqualTo(OfflineStatus.Kept)
  }

  @Test
  fun `an unreachable server shows the kept playlists instead of only an error`() {
    val state = playlistsContent(
      PlaylistsFetch.Failed(SyncFailure.Unreachable),
      LibraryFilterState.Unknown,
      saved = listOf(saved("night", 3), saved("train", 1)),
    ) as PlaylistsUiState.Content

    assertThat(state.playlists.map { it.id }).containsExactly("night", "train")
    assertThat(state.showingKeptOnly).isTrue
    assertThat(state.emptyReason).isNull()
  }

  @Test
  fun `an unreachable server with nothing kept is still the error`() {
    assertThat(
      playlistsContent(PlaylistsFetch.Failed(SyncFailure.Unreachable), LibraryFilterState.Unknown, saved = emptyList()),
    ).isEqualTo(PlaylistsUiState.Failed(SyncFailure.Unreachable))
  }

  @Test
  fun `a refusal is not hidden behind the kept playlists`() {
    // A rejected sign-in is something the listener must fix; covering it with an offline list
    // would leave them wondering why nothing new ever appears.
    val rejected = SyncFailure.SignInRejected(40)

    assertThat(playlistsContent(PlaylistsFetch.Failed(rejected), LibraryFilterState.Unknown, listOf(saved("a", 3))))
      .isEqualTo(PlaylistsUiState.Failed(rejected))
  }

  @Test
  fun `the live list marks which playlists are kept`() {
    val state = playlistsContent(
      PlaylistsFetch.Loaded(listOf(playlist("a"), playlist("b")), emptyMap()),
      LibraryFilterState.Unknown,
      saved = listOf(saved("b", 1)),
    ) as PlaylistsUiState.Content

    assertThat(state.showingKeptOnly).isFalse
    assertThat(state.kept.keys).containsExactly("b")
    assertThat(keptBadge(state.kept["b"])).isEqualTo("Downloading 1 of 3")
    assertThat(keptBadge(saved("c", 3))).isEqualTo("On this phone")
    assertThat(keptBadge(null)).isNull()
  }
}
