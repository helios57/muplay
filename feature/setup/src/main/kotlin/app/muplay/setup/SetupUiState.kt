package app.muplay.setup

import app.muplay.model.MusicLibrary
import app.muplay.model.ServerInfo

/**
 * The first-run flow's state, exposed by [SetupViewModel] as a `StateFlow` and collected with
 * `collectAsStateWithLifecycle()`. A sealed interface so a `when` over it is exhaustive at every
 * call site — the compiler, not convention, is what keeps a new state from being missed.
 */
sealed interface SetupUiState {

  /** No connection attempt has been made yet. */
  data object Idle : SetupUiState

  /** A `ping` request is in flight. */
  data object Connecting : SetupUiState

  /**
   * Connected, credentials stored, and the server's libraries listed **for the user to tag**.
   *
   * This state replaced the old `Success`, and the rename is the point: connecting is not the end
   * of setup. A Navidrome server cannot say what a library holds — it reports
   * `child.Type = "music"` for audiobooks too — so the Music/Audiobooks decision is made here, by
   * the user, once, and everything downstream keys off it.
   *
   * [canContinue] is false while any library is still [app.muplay.model.LibraryRole.UNASSIGNED]:
   * an untagged library is invisible to every browse and shuffle path, so letting the user past
   * this screen would hand them an app that silently shows nothing.
   */
  data class Tagging(
    val serverInfo: ServerInfo,
    val libraries: List<MusicLibrary>,
    val canContinue: Boolean,
  ) : SetupUiState

  /** Setup is complete; the host navigates away. */
  data object Ready : SetupUiState

  /** The attempt failed; [reason] is typed, not a bare message — see [SetupFailureReason]. */
  data class Failure(val reason: SetupFailureReason) : SetupUiState
}

/**
 * The slice of credential storage setup needs. A one-method interface rather than a dependency on
 * `CredentialStore` itself, so this module's JVM tests can run: the real store talks to the
 * Android Keystore, which does not exist on a JVM, and which `:core:database`'s own instrumented
 * `CredentialStoreTest` already proves on a device.
 */
interface SetupCredentialSink {
  suspend fun save(credentials: app.muplay.model.SubsonicCredentials)
}

/**
 * The slice of the library repository setup needs. Same reasoning as [SetupCredentialSink]: the
 * real one is backed by Room, which needs a device.
 */
interface SetupLibrarySink {
  suspend fun refreshFromServer()
  suspend fun setRole(musicFolderId: Int, role: app.muplay.model.LibraryRole)
  suspend fun current(): List<MusicLibrary>
}
