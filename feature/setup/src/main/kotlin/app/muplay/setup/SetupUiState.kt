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
  ) : SetupUiState {

    /**
     * What to say above the (possibly empty) list of libraries.
     *
     * The empty case is not hypothetical and it is not a server fault: measured against
     * `deluan/navidrome:0.63.2`, a **freshly created non-admin user is granted no libraries at
     * all**, and `getMusicFolders` then answers `status: ok` with an empty set. So a perfectly
     * good sign-in reaches this state with no rows and [canContinue] false forever -- the user
     * sees "Connected to navidrome ...", a heading asking them to tag libraries that are not
     * there, and a dead Continue button.
     *
     * That is the single most likely way the Play reviewer route in `docs/REVIEWER-ACCESS.md`
     * fails, and an app that dead-ends a reviewer with no explanation is the rejection that
     * document exists to prevent. The fix is one sentence naming the actual remedy, which is on
     * the server, not in the app.
     *
     * A property here rather than an `if` inside `SetupScreen`: this module's `SetupScreenKt`
     * floor is a LINE floor reachable only from the emulator (see the coverage table), so a
     * branch written in the composable would be a branch no JVM test can reach. Here it is
     * ordinary Kotlin, covered by `SetupUiStateTest`, and the composable gains one line that runs
     * on every render.
     */
    val prompt: String
      get() =
        if (libraries.isEmpty()) {
          "This account can see no libraries. Give it access to at least one on the server, " +
            "then press Connect again."
        } else {
          "What is each library for?"
        }
  }

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
