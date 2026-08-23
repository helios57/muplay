package app.muplay.setup

import app.muplay.model.MusicLibrary
import app.muplay.model.ServerInfo

/**
 * The first-run setup screen's state, exposed by [SetupViewModel] as a `StateFlow` and collected
 * with `collectAsStateWithLifecycle()`. A sealed interface, not a single mutable data class, so a
 * `when` over it is exhaustive at every call site — the compiler, not convention, is what keeps a
 * new state from being missed by the screen or by [SetupViewModel] itself.
 */
sealed interface SetupUiState {

  /** No connection attempt has been made yet. The screen's initial state. */
  data object Idle : SetupUiState

  /** A `ping` request is in flight. */
  data object Connecting : SetupUiState

  /**
   * `ping` succeeded and the server's libraries came back; [serverInfo] is its own reported
   * identity and [libraries] is what `getMusicFolders` returned, in the server's own order.
   *
   * [libraries] has no default, deliberately: an empty list here must mean "this server really
   * has no libraries", never "nobody got round to fetching them". Every [MusicLibrary] carries
   * [app.muplay.model.LibraryRole.UNASSIGNED] — the Subsonic response says nothing about what a
   * folder holds; see that type's own documentation.
   */
  data class Success(val serverInfo: ServerInfo, val libraries: List<MusicLibrary>) : SetupUiState

  /** The attempt failed; [reason] is typed, not a bare message — see [SetupFailureReason]. */
  data class Failure(val reason: SetupFailureReason) : SetupUiState
}
