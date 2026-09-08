package io.github.helios57.muplay.library

import io.github.helios57.muplay.database.SyncFailure
import io.github.helios57.muplay.database.SyncProgress
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
    assertThat(LibraryEmptyReason.Syncing(SyncProgress.Preparing).toMessage())
      .isEqualTo("Loading your library…")
  }

  @Test
  fun `an empty library with an idle sync is still loading, not empty`() {
    // `SyncProgress.Idle` is the flow's value before the engine has been asked for anything, which
    // is what a cold launch renders for the first moments of its life. It reaches this branch only
    // through an empty mirror, so folding it into `Empty` -- the tempting reading of "idle" -- puts
    // "Nothing here yet." in front of a user whose first sync has not begun.
    assertThat(LibraryEmptyReason.Syncing(SyncProgress.Idle).toMessage())
      .isEqualTo("Loading your library…")
  }

  @Test
  fun `the initial load counts the albums it has read, so the screen can be seen to move`() {
    // The defect this exists for: the first sync makes one `getAlbum` request per album and showed
    // one motionless sentence for the whole of it, which is what a hung app looks like.
    assertThat(LibraryEmptyReason.Syncing(SyncProgress.Reading(done = 37, total = 412)).toMessage())
      .isEqualTo("Loading your library… 37 of 412 albums.")
    // Zero read out of a known total is worth printing: the total is the reassuring half.
    assertThat(LibraryEmptyReason.Syncing(SyncProgress.Reading(done = 0, total = 412)).toMessage())
      .isEqualTo("Loading your library… 0 of 412 albums.")
  }

  @Test
  fun `a listing pass says what it has found and never what remains`() {
    // `fetchAllAlbums` learns it is on the last page only by getting a short one back, so a
    // message shaped "128 of 412" here would be a denominator nobody measured.
    val message = LibraryEmptyReason.Syncing(SyncProgress.Listing(found = 128)).toMessage()

    assertThat(message).isEqualTo("Loading your library… 128 albums so far.")
    assertThat(message).doesNotContain(" of ")
  }

  @Test
  fun `a count of nothing is left unsaid rather than printed as zero`() {
    // Both are reachable -- `Listing(0)` before the first page lands, `Reading(0, 0)` for a library
    // the server reports as empty, which `SyncEngine` mirrors rather than skips. "0 of 0 albums"
    // reads as a defect in the app rather than as a library with nothing in it.
    assertThat(LibraryEmptyReason.Syncing(SyncProgress.Listing(found = 0)).toMessage())
      .isEqualTo("Loading your library…")
    assertThat(LibraryEmptyReason.Syncing(SyncProgress.Reading(done = 0, total = 0)).toMessage())
      .isEqualTo("Loading your library…")
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

  @Test
  fun `a server mid-scan is told apart from a server that failed, and says what to do`() {
    // `ScanInProgress` is the one notice here that is not an error: the server is working and the
    // mirror will fill in by itself. Rendering it as a failure would send a user to check a
    // connection that is fine, and the actionable half -- that tapping Refresh afterwards is what
    // fills the gap -- is the only thing that distinguishes it from "wait and hope".
    val message = LibraryNotice.ScanInProgress.toMessage(hasMirror = true)

    assertThat(message).contains("scanning")
    assertThat(message).contains(REFRESH_LABEL)
    assertThat(message).doesNotContain("Could not")
  }

  @Test
  fun `a sync with no server configured says so rather than blaming the network`() {
    // Reachable in the wild: a sync attempted after a sign-out clears the credentials. The
    // flattened message would say "Could not reach the server", sending the user to debug a
    // network they never used -- and the fix is in Settings, which is where "no server is set up"
    // points them.
    val message = LibraryNotice.Failed(SyncFailure.NotConfigured).toMessage(hasMirror = false)

    assertThat(message).contains("set up")
    assertThat(message).doesNotContain("reach")
  }

  @Test
  fun `a server reporting no libraries says that, not that the sync failed`() {
    // The server answered correctly; it simply has nothing this app can read. Distinct from every
    // failure beside it because the fix is on the server -- tag a library -- rather than in this
    // app, the credentials or the network.
    val message = LibraryNotice.Failed(SyncFailure.NoLibraries).toMessage(hasMirror = false)

    assertThat(message).contains("libraries")
    assertThat(message).doesNotContain("reach")
  }

  @Test
  fun `an unrecognised failure is not silently reported as an unreachable server`() {
    // `SyncFailure.Unknown` catches whatever the classification did not recognise, so it is the
    // member most likely to be reached by something nobody predicted -- which makes it the one
    // most tempting to fold into the catch-all sentence. Folding it in is the exact defect this
    // type replaced: it would tell a user with a working network to go and check their network.
    val message = LibraryNotice.Failed(SyncFailure.Unknown).toMessage(hasMirror = false)

    assertThat(message).isNotEmpty()
    assertThat(message)
      .isNotEqualTo(LibraryNotice.Failed(SyncFailure.Unreachable).toMessage(hasMirror = false))
  }

  @Test
  fun `a screen that does not sync names its own failure instead of the sync's`() {
    // `describe`'s catch-all sentence is "The last sync did not finish", which is simply false on
    // the playlists screens -- they fetch live and never sync anything. Every other arm names a
    // cause that is true wherever it is shown; only this one belongs to the caller.
    val message = SyncFailure.Unknown.describe("Could not load your playlists.")

    assertThat(message).isEqualTo("Could not load your playlists.")
    assertThat(message).doesNotContain("sync")
  }

  @Test
  fun `naming your own fallback does not rewrite the failures that already name themselves`() {
    // The parameter is a fallback, not an override. A rejected sign-in is a rejected sign-in on
    // every screen, and a caller passing its own wording must not be able to bury that.
    val rejected = SyncFailure.SignInRejected(code = 40)
    val named = rejected.describe("Could not load your playlists.")

    assertThat(named).isEqualTo(rejected.describe())
    assertThat(named).contains("credentials")
  }
}
