package app.muplay.library

import app.muplay.database.SyncFailure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The wording rules for the browse screen's two notices — why a list is empty, and what the last
 * sync did. Pure functions over typed inputs, so the fast tier holds every branch.
 *
 * Three separate defects met here, all of them one screen saying something untrue:
 *
 * - **`"Nothing here yet."` was four states.** A search with no match, a first sync still running,
 *   a sync that failed against an empty mirror, and a genuinely empty library all rendered that
 *   one sentence — so a typo'd server URL looked exactly like a working app with no music.
 * - **Every sync failure was one sentence**, and half of it was a promise: *"Showing your last
 *   synced library"* was printed on a first run that had never synced anything.
 * - **A failed shuffle rendered nothing at all**, which is indistinguishable from a shuffle that
 *   has not been tapped.
 */
class LibraryNoticeTest {

  @Test
  fun `a search with no match says so, and names the query`() {
    val message = LibraryEmptyReason.SearchNoMatch("brubeck").toMessage()

    assertThat(message).contains("brubeck")
    assertThat(message).doesNotContain("Nothing here yet")
  }

  @Test
  fun `a library that is still syncing says it is still working`() {
    assertThat(LibraryEmptyReason.Syncing.toMessage()).isEqualTo("Loading your library…")
  }

  @Test
  fun `an empty library after a failed sync explains the failure rather than claiming emptiness`() {
    // The typo'd-URL case. Claiming the library is empty here is the single most misleading thing
    // this screen can say, because the app looks broken in a way that blames the user's music.
    val message = LibraryEmptyReason.SyncFailed(SyncFailure.SignInRejected(40)).toMessage()

    assertThat(message).doesNotContain("Nothing here yet")
    assertThat(message).contains("sign in")
  }

  @Test
  fun `a genuinely empty library is the only state that says it is empty`() {
    assertThat(LibraryEmptyReason.Empty.toMessage()).isEqualTo("Nothing here yet.")
  }

  @Test
  fun `a rejected sign-in names the credentials, not the connection`() {
    val message = LibraryNotice.Failed(SyncFailure.SignInRejected(40)).toMessage(hasMirror = true)

    assertThat(message).contains("sign in")
    assertThat(message).doesNotContain("Could not reach")
  }

  @Test
  fun `a server error names the status the server actually returned`() {
    val message = LibraryNotice.Failed(SyncFailure.ServerError(502)).toMessage(hasMirror = true)

    assertThat(message).contains("502")
  }

  @Test
  fun `an invalid certificate is not reported as an unreachable server`() {
    val message =
      LibraryNotice.Failed(SyncFailure.CertificateInvalid).toMessage(hasMirror = true)

    assertThat(message).contains("certificate")
    assertThat(message).doesNotContain("Could not reach")
  }

  @Test
  fun `a cleartext refusal names https rather than the connection`() {
    val message =
      LibraryNotice.Failed(SyncFailure.CleartextForbidden).toMessage(hasMirror = true)

    assertThat(message).contains("https")
    assertThat(message).doesNotContain("Could not reach")
  }

  @Test
  fun `a failure with a mirror behind it offers the mirror`() {
    val message = LibraryNotice.Failed(SyncFailure.Unreachable).toMessage(hasMirror = true)

    assertThat(message).contains("last synced")
  }

  @Test
  fun `a failure with no mirror behind it never promises a library that is not there`() {
    // The promise `LibraryUiState.syncMessage`'s own doc forbids, made by the very line that doc
    // was written beside: on a first run there is no last synced library to show.
    val message = LibraryNotice.Failed(SyncFailure.Unreachable).toMessage(hasMirror = false)

    assertThat(message).doesNotContain("last synced")
  }

  @Test
  fun `a successful sync has nothing to say`() {
    assertThat(LibraryNotice.Idle.toMessage(hasMirror = true)).isNull()
  }

  @Test
  fun `a failed shuffle says so instead of rendering an empty list`() {
    val message = LibraryNotice.ShuffleFailed(SyncFailure.Unreachable).toMessage(hasMirror = true)

    assertThat(message).contains("Shuffle")
  }
}
