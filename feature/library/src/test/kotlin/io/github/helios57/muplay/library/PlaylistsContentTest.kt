package io.github.helios57.muplay.library

import io.github.helios57.muplay.database.SyncFailure
import io.github.helios57.muplay.model.LibraryRole
import io.github.helios57.muplay.model.MusicLibrary
import io.github.helios57.muplay.model.Playlist
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [playlistsContent]: which playlists the chosen library leaves on screen.
 *
 * ### Why this decision is pure and lives here
 *
 * Subsonic will not answer it. `getPlaylists` **accepts `musicFolderId` and ignores it** -- measured
 * twice against the CI Navidrome, with one probe playlist per library: both came back for both ids
 * -- and no `getPlaylist` entry names a library either. So the scope of a playlist is *derived*,
 * from the libraries its own tracks are mirrored in, and `PlaylistRepository.librariesOf` does that
 * derivation. What is left over is the part a user actually sees, and it is a fold over three
 * values with no I/O in it at all.
 *
 * ### The rule that matters is what an *unknown* scope does
 *
 * `librariesOf` answers with an **empty set** for a playlist it could not place -- a playlist whose
 * tracks the mirror has never seen, or one the server refused. Hiding those would make a filter
 * that silently deletes playlists from the user's own server, which is the worse failure of the two
 * available: a playlist shown under both libraries is a mild wrong, and a playlist shown under
 * neither is indistinguishable from the server having lost it.
 */
class PlaylistsContentTest {

  private val music = MusicLibrary(1, "Music", LibraryRole.MUSIC)
  private val books = MusicLibrary(2, "Audiobooks", LibraryRole.AUDIOBOOKS)

  private fun playlist(id: String) = Playlist(
    id = id,
    name = id,
    songCount = 2,
    durationSeconds = 400,
    owner = "admin",
    coverArtId = null,
  )

  private fun loaded(vararg scopes: Pair<String, Set<Int>>) = PlaylistsFetch.Loaded(
    playlists = scopes.map { (id, _) -> playlist(id) },
    libraries = scopes.toMap(),
  )

  private fun filter(vararg libraries: MusicLibrary, selected: Int?) =
    LibraryFilterState(libraries.toList(), selected)

  @Test
  fun `only the playlists whose tracks live in the chosen library are shown`() {
    val fetch = loaded("road-trip" to setOf(1), "bedtime" to setOf(2))

    val state = playlistsContent(fetch, filter(music, books, selected = 1))

    assertThat(state).isInstanceOf(PlaylistsUiState.Content::class.java)
    assertThat((state as PlaylistsUiState.Content).playlists.map { it.id })
      .containsExactly("road-trip")
  }

  @Test
  fun `the other library shows the other playlist, so the filter is not a constant`() {
    // The half that a `filter { true }` and a `filter { it.id == "road-trip" }` both pass on their
    // own. Asserted at two values of the selection, on one fixture, for that reason.
    val fetch = loaded("road-trip" to setOf(1), "bedtime" to setOf(2))

    val state = playlistsContent(fetch, filter(music, books, selected = 2)) as PlaylistsUiState.Content

    assertThat(state.playlists.map { it.id }).containsExactly("bedtime")
  }

  @Test
  fun `a playlist that mixes libraries is shown under both of them`() {
    val fetch = loaded("mixtape" to setOf(1, 2))

    assertThat(shownIds(fetch, selected = 1)).containsExactly("mixtape")
    assertThat(shownIds(fetch, selected = 2)).containsExactly("mixtape")
  }

  @Test
  fun `a playlist whose scope is unknown is shown everywhere rather than hidden everywhere`() {
    // The load-bearing rule. An empty set is `librariesOf` saying "I could not place this" -- the
    // mirror has never seen those tracks, or the server refused the read -- and it is **not** a
    // playlist that belongs to no library. Hiding it would be a filter that deletes the user's own
    // playlists from their own server, with no message and no way to get them back.
    val fetch = loaded("from-another-client" to emptySet())

    assertThat(shownIds(fetch, selected = 1)).containsExactly("from-another-client")
    assertThat(shownIds(fetch, selected = 2)).containsExactly("from-another-client")
  }

  @Test
  fun `a server with one library filters nothing at all`() {
    // Most installs, and the case where the derivation is not merely unnecessary but wrong to
    // apply: with one library there is no second place a playlist could be, so a playlist the
    // mirror failed to place must not disappear because its set came back empty.
    val fetch = loaded("road-trip" to setOf(1), "orphan" to emptySet(), "elsewhere" to setOf(9))

    val state = playlistsContent(fetch, filter(music, selected = 1)) as PlaylistsUiState.Content

    assertThat(state.playlists.map { it.id }).containsExactly("road-trip", "orphan", "elsewhere")
    assertThat(state.filter.offersChoice).isFalse()
  }

  @Test
  fun `before any library is known nothing is filtered`() {
    // A first run, before the first sync. There is no selection to filter by, and showing an empty
    // list would blame the user's server for this app not having synced yet.
    val fetch = loaded("road-trip" to setOf(1), "bedtime" to setOf(2))

    val state = playlistsContent(fetch, LibraryFilterState.Unknown) as PlaylistsUiState.Content

    assertThat(state.playlists.map { it.id }).containsExactly("road-trip", "bedtime")
  }

  @Test
  fun `two libraries with none of them chosen yet filters nothing`() {
    // Distinct from `LibraryFilterState.Unknown` above, and reachable: the mirror has answered with
    // two libraries while `LibrarySelection` has not yet resolved one. Filtering on a null
    // selection is the arithmetic that would empty the screen at exactly that moment.
    val fetch = loaded("road-trip" to setOf(1), "bedtime" to setOf(2))

    val state = playlistsContent(fetch, filter(music, books, selected = null)) as PlaylistsUiState.Content

    assertThat(state.playlists.map { it.id }).containsExactly("road-trip", "bedtime")
  }

  @Test
  fun `a playlist the derivation never mentioned is unplaced, not excluded`() {
    // The map is keyed by playlist id, so a playlist it has no key for at all is the same statement
    // as an empty set: "could not place it". Read as "belongs to no library" instead, a repository
    // that answered for three playlists out of four would silently drop the fourth.
    val fetch = PlaylistsFetch.Loaded(
      playlists = listOf(playlist("road-trip"), playlist("unmentioned")),
      libraries = mapOf("road-trip" to setOf(1)),
    )

    assertThat(shownIds(fetch, selected = 2)).containsExactly("unmentioned")
  }

  @Test
  fun `a server with no playlists says so, and says nothing about libraries`() {
    val state = playlistsContent(PlaylistsFetch.Loaded(emptyList(), emptyMap()), filter(music, books, selected = 1))

    assertThat((state as PlaylistsUiState.Content).emptyReason)
      .isEqualTo(PlaylistsEmptyReason.NoneAtAll)
  }

  @Test
  fun `playlists that all belong to the other library is a different sentence`() {
    // The distinction this enum exists for, and the reason it is not a boolean `isEmpty`. "No
    // playlists on the server yet" told to somebody who has four of them, because they are looking
    // at the audiobook library, is a lie about their server -- and it hides the one control that
    // fixes it, which is the chip row directly above.
    val fetch = loaded("road-trip" to setOf(1))

    val state = playlistsContent(fetch, filter(music, books, selected = 2)) as PlaylistsUiState.Content

    assertThat(state.playlists).isEmpty()
    assertThat(state.emptyReason).isEqualTo(PlaylistsEmptyReason.NoneInThisLibrary)
  }

  @Test
  fun `a list with something on it has no empty reason at all`() {
    val fetch = loaded("road-trip" to setOf(1))

    val state = playlistsContent(fetch, filter(music, books, selected = 1)) as PlaylistsUiState.Content

    assertThat(state.emptyReason).isNull()
  }

  @Test
  fun `the two empty states do not say the same thing`() {
    // Held here rather than at the call site: the failure mode is two `when` arms mapped to one
    // constant, which reads as correct and makes the enum above pointless.
    assertThat(PlaylistsEmptyReason.NoneAtAll.toMessage())
      .isNotEqualTo(PlaylistsEmptyReason.NoneInThisLibrary.toMessage())
    assertThat(PlaylistsEmptyReason.NoneInThisLibrary.toMessage()).isNotBlank()
  }

  @Test
  fun `loading and failing pass straight through, filter or no filter`() {
    // The filter has no opinion about a list that does not exist yet, and -- the one that matters
    // -- must not turn a failure into an empty library. That collapse is the defect
    // `PlaylistsViewModel`'s own doc names.
    assertThat(playlistsContent(PlaylistsFetch.Loading, filter(music, books, selected = 1)))
      .isEqualTo(PlaylistsUiState.Loading)
    assertThat(playlistsContent(PlaylistsFetch.Failed(SyncFailure.Unreachable), filter(music, books, selected = 1)))
      .isEqualTo(PlaylistsUiState.Failed(SyncFailure.Unreachable))
  }

  @Test
  fun `the chips the screen draws come back with the content`() {
    // The screen needs the row and the list from one value, or a recomposition can draw a chip row
    // and a list from two different moments -- which is a filter that looks like it is lying.
    val state = playlistsContent(loaded("road-trip" to setOf(1)), filter(music, books, selected = 1))

    val filter = (state as PlaylistsUiState.Content).filter
    assertThat(filter.libraries.map { it.name }).containsExactly("Music", "Audiobooks")
    assertThat(filter.selectedLibraryId).isEqualTo(1)
    assertThat(filter.offersChoice).isTrue()
  }

  private fun shownIds(fetch: PlaylistsFetch, selected: Int) =
    (playlistsContent(fetch, filter(music, books, selected = selected)) as PlaylistsUiState.Content)
      .playlists.map { it.id }
}
