package app.muplay.library

import app.muplay.model.Album
import app.muplay.model.MusicLibrary
import app.muplay.model.ShuffleResult
import app.muplay.model.Song

/**
 * What the browse screen shows. A sealed interface so the screen's `when` is exhaustive.
 */
sealed interface LibraryUiState {

  /** The mirror has not been read yet. */
  data object Loading : LibraryUiState

  /**
   * There are no libraries at all — setup has not been completed, or the server reports none.
   * A distinct state from an empty [Content], because "finish setup" and "this library is empty"
   * are different problems with different fixes.
   */
  data object NoLibraries : LibraryUiState

  /**
   * @property selectedLibraryId always names a library present in [libraries] — see
   *   [libraryContent], which repairs a stale selection rather than rendering an empty screen.
   * @property albums either the whole selected library or the search results, depending on
   *   [query]. One list, so the screen has no branch of its own to get wrong.
   * @property discardedOutOfScope how many songs the last shuffle dropped because the mirror did
   *   not place them in the selected library. Normally zero.
   * @property syncMessage what the last [LibraryViewModel.refresh] found, or `null` when there is
   *   nothing to say. **Every value of it must be true at the moment it is shown**, which is not a
   *   platitude: this string used to read *"your library will update shortly"* while no code
   *   anywhere re-checked, so a user who first opened the app during a server scan was stranded
   *   with a partial library until they force-stopped it. Nothing here may promise a future the
   *   app does not bring about; where an outcome depends on the user, the message names the
   *   control that produces it. It is rendered from [LibraryNotice], not built by hand, and the
   *   second half of that rule is why [LibraryNotice.toMessage] is told whether a mirror exists:
   *   the old wording offered *"your last synced library"* on a first run that had none.
   * @property emptyReason why [albums] is empty, or `null` when it is not. Four different
   *   situations used to render one `"Nothing here yet."` — see [LibraryEmptyReason].
   */
  data class Content(
    val libraries: List<MusicLibrary>,
    val selectedLibraryId: Int,
    val query: String,
    val albums: List<Album>,
    val shuffled: List<Song>,
    val discardedOutOfScope: Int,
    val syncMessage: String?,
    val emptyReason: LibraryEmptyReason?,
  ) : LibraryUiState
}

/**
 * Every rule the browse screen follows, as one pure function.
 *
 * Deliberately not a method on the ViewModel: this is where the branching lives, and a plain
 * function is testable on the JVM in Tier 1, where a ViewModel wired to Room and a server would
 * not be. The ViewModel's own job is reduced to combining flows and calling this.
 */
internal fun libraryContent(
  libraries: List<MusicLibrary>,
  selectedLibraryId: Int?,
  query: String,
  albums: List<Album>,
  searchAlbums: List<Album>,
  shuffle: ShuffleResult?,
  notice: LibraryNotice,
): LibraryUiState {
  if (libraries.isEmpty()) return LibraryUiState.NoLibraries

  // A selection can go stale between syncs -- a library removed on the server leaves the screen
  // pointed at an id nothing matches, which would render as a permanently empty list.
  val selected = libraries.firstOrNull { it.id == selectedLibraryId }?.id ?: libraries.first().id
  val searching = query.isNotBlank()
  val shown = if (searching) searchAlbums else albums

  // `albums`, not `shown`: whether a *mirror* exists is a question about what was synced, and a
  // search that matches nothing must not be allowed to answer "there is no library behind this".
  val hasMirror = albums.isNotEmpty()

  return LibraryUiState.Content(
    libraries = libraries,
    selectedLibraryId = selected,
    query = query,
    albums = shown,
    shuffled = shuffle?.songs.orEmpty(),
    discardedOutOfScope = shuffle?.discardedOutOfScope ?: 0,
    syncMessage = notice.toMessage(hasMirror),
    emptyReason = emptyReasonFor(shown, searching, query, notice),
  )
}

/**
 * Which of the four empties this is, or `null` when the list has something in it.
 *
 * The order is the whole content of the decision. A search that matched nothing is a search
 * result whatever the sync did, so it comes first; and [LibraryEmptyReason.Empty] — the only
 * member that asserts the library really is empty — is reachable only once every reason to doubt
 * it has been ruled out.
 */
private fun emptyReasonFor(
  shown: List<Album>,
  searching: Boolean,
  query: String,
  notice: LibraryNotice,
): LibraryEmptyReason? = when {
  shown.isNotEmpty() -> null
  searching -> LibraryEmptyReason.SearchNoMatch(query)
  notice is LibraryNotice.Syncing -> LibraryEmptyReason.Syncing
  notice is LibraryNotice.Failed -> LibraryEmptyReason.SyncFailed(notice.failure)
  else -> LibraryEmptyReason.Empty
}
