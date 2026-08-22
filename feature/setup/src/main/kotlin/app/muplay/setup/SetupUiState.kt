package app.muplay.setup

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

  /** `ping` succeeded; [serverInfo] is the server's own reported identity. */
  data class Success(val serverInfo: ServerInfo) : SetupUiState

  /** The attempt failed; [reason] is typed, not a bare message — see [SetupFailureReason]. */
  data class Failure(val reason: SetupFailureReason) : SetupUiState
}
