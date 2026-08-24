package app.muplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.LibraryRole
import app.muplay.setup.LibraryRepositoryEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2: the first-run journey, on a real emulator, against a real Navidrome.
 *
 * Nothing here is faked. The app under test is the real debug APK, launched through its real
 * [MainActivity]; the server is the pinned `deluan/navidrome:0.63.2` container from
 * `ci/navidrome.compose.yml`, seeded by `ci/configure-libraries.sh`; the credentials are that
 * container's real ones. The success journey's final assertion — that both library names come
 * back — is a contract on *server state*, not on response shape: a Navidrome that is running and
 * answering perfectly well, but whose libraries were never configured, fails it. The rejection
 * journey is the same kind of contract on the server's own authentication behaviour.
 *
 * **Preconditions this test cannot establish for itself**, all handled by `ci/prepare-emulator.sh`
 * (which `.github/workflows/e2e.yml` runs, and which a local run must run too):
 *
 * 1. The container is up and seeded (`docker compose -f ci/navidrome.compose.yml up -d --wait`
 *    then `ci/configure-libraries.sh`).
 * 2. `adb reverse tcp:4533 tcp:4533`, which is what makes [SERVER_URL]'s `localhost` inside the
 *    emulator reach the container on the host. Note that a missing reverse forward does not fail
 *    fast: the connection attempt simply times out, so this test would report the
 *    [waitUntilSettled] timeout below rather than anything naming the real cause.
 * 3. The emulator itself was started with `-feature Minigbm -prop qemu.hardware.gralloc=minigbm`.
 *    Without it, this emulator + system-image pair aborts SurfaceFlinger and system_server on
 *    every CPU read of a graphics buffer — which happens at every activity teardown, i.e. once
 *    per test — and the run dies with "INSTRUMENTATION_ABORTED: System has crashed" rather than
 *    with any assertion. `ci/prepare-emulator.sh` fails fast if it is missing; see its header for
 *    the crash signature and the evidence.
 *
 * Cleartext HTTP is allowed only because this is the debug build — see
 * `app/src/debug/AndroidManifest.xml`.
 *
 * `createAndroidComposeRule` comes from the `...junit4.v2` package, not `...junit4`: the
 * identically-named factory in the latter is `@Deprecated` as of the Compose BOM this project
 * pins (it warns at compile time and names the v2 one as its replacement). The two differ only in
 * which test dispatcher backs the rule — v2 uses `StandardTestDispatcher`, so coroutines queue
 * rather than run eagerly — which these tests do not depend on either way: they never drive a
 * dispatcher themselves, they wait on what the real UI shows (see [waitUntilSettled]).
 */
@RunWith(AndroidJUnit4::class)
class FirstRunJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  /**
   * Resets every library back to [LibraryRole.UNASSIGNED] before each test.
   *
   * The Room database this app writes to is a real file on the emulator, shared by every test
   * method in this class across the *same* instrumentation process -- unlike the Activity, which
   * `composeRule` relaunches fresh per test, nothing recreates the database between methods.
   * JUnit4 does not guarantee method execution order (no `@FixMethodOrder` is declared here, nor
   * should there be one for a black-box journey), so a test that tags every library --
   * [completingEveryTagReachesReadyAndShowsSetupComplete], [theFlowCannotBeFinishedUntilEveryLibraryIsTagged]
   * -- can and did run before a later test that needs a fresh, untagged start: observed directly,
   * the first version of this suite without this reset flaked exactly that way, with
   * `Continue` already enabled before either role chip had been tapped.
   *
   * Reached through [LibraryRepositoryEntryPoint] (declared in `:feature:setup`, not here) — see
   * that interface's own doc for why an `@EntryPoint` declared in this `androidTest` source set
   * cannot be used the same way: it would not be part of the real `MuPlayApplication`'s generated
   * Hilt component at all.
   */
  @Before
  fun resetLibraryTagging() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    val libraryRepository =
      EntryPointAccessors.fromApplication(context, LibraryRepositoryEntryPoint::class.java)
        .libraryRepository()
    libraryRepository.allIds().forEach { id -> libraryRepository.setRole(id, LibraryRole.UNASSIGNED) }
  }

  @Test
  fun firstRunConnectsToNavidromeAndListsBothSeededLibraries() {
    connectAs(PASSWORD)

    // The connected state itself: the server answered `ping` and reported its own identity.
    // Asserted as a substring match on the server type alone -- the exact serverVersion string
    // ("0.63.2 (be10f89c)") is the container image's business, not this journey's.
    composeRule.onNodeWithText("Connected to navidrome", substring = true).assertIsDisplayed()

    // The contract on server state: both seeded libraries, by name. `ci/configure-libraries.sh`
    // renames Navidrome's pinned library 1 to "Music" and creates "Audiobooks" as library 2.
    //
    // Not a bare `onNodeWithText`: every library row also renders a "Music" role chip and an
    // "Audiobooks" role chip (see SetupScreen's Tagging branch), so with two libraries the exact
    // text "Music" appears three times (the Music row's own name, plus one role chip per row)
    // and likewise for "Audiobooks" -- `onNodeWithText` demands exactly one match and throws on
    // the ambiguity. Observed directly: the first run of this test failed with "Expected at most
    // 1 node but found 3 nodes". `MUSIC_LIBRARY_NAME`/`AUDIOBOOKS_LIBRARY_NAME` pick out the name
    // specifically, per the ordering documented on those constants.
    composeRule.onAllNodesWithText("Music")[MUSIC_LIBRARY_NAME].assertIsDisplayed()
    composeRule.onAllNodesWithText("Audiobooks")[AUDIOBOOKS_LIBRARY_NAME].assertIsDisplayed()
  }

  /**
   * Walks all the way to [app.muplay.setup.SetupUiState.Ready] and checks what actually renders
   * there, not just that the state transition happened.
   *
   * This closes a real gap, not a hypothetical one: without this test, JaCoCo still reported
   * `SetupScreenKt`'s `is SetupUiState.Ready ->` source line as "covered", because Kotlin compiles
   * an exhaustive sealed `when` as a chain of `instanceof` checks -- the `Ready` check itself runs
   * on *every* composition regardless of which state is current, so the line lit up green while
   * the branch it guards, and the text it renders, had never once executed. A metric that is green
   * without the behaviour it names being real is exactly the failure mode this project keeps
   * finding.
   */
  @Test
  fun completingEveryTagReachesReadyAndShowsSetupComplete() {
    connectAs(PASSWORD)

    composeRule.onAllNodesWithText("Music")[MUSIC_ROLE_CHIP].performClick()
    composeRule.onAllNodesWithText("Audiobooks")[AUDIOBOOK_ROLE_CHIP].performClick()
    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsEnabled().performClick()

    composeRule.onNodeWithText("Setup complete").assertIsDisplayed()
  }

  @Test
  fun theFlowCannotBeFinishedUntilEveryLibraryIsTagged() {
    connectAs(PASSWORD)

    // Both libraries untagged: Continue is inert. This is the assertion that keeps the tagging
    // step from becoming skippable, and an untagged library is invisible to browse and shuffle.
    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsNotEnabled()

    composeRule.onAllNodesWithText("Music")[MUSIC_ROLE_CHIP].performClick()
    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsNotEnabled()

    composeRule.onAllNodesWithText("Audiobooks")[AUDIOBOOK_ROLE_CHIP].performClick()
    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsEnabled()
  }

  /**
   * The other outcome of the same real journey: the server rejects the credentials, on purpose,
   * and the screen says so instead of listing anything.
   *
   * Distinguishes rejection from unreachability, which is the distinction `SetupFailureReason`
   * exists to draw: a container that was not running at all would render "Could not reach the
   * server…" and fail the first assertion below. The rejection *message body* — Navidrome's own
   * "Wrong username or password" — is deliberately not pinned here, matching the same choice
   * `LiveNavidromeTest` documents for asserting error code `40` but not its text.
   */
  @Test
  fun aWrongPasswordIsRejectedByTheRealServerAndListsNoLibraries() {
    connectAs("not-the-real-password")

    composeRule.onNodeWithText("Could not sign in", substring = true).assertIsDisplayed()

    composeRule.onNodeWithText("Music").assertDoesNotExist()
    composeRule.onNodeWithText("Audiobooks").assertDoesNotExist()
  }

  /** Fills the setup form with [password] (and the real URL and username), presses Connect, and
   * waits for the attempt to settle. */
  private fun connectAs(password: String) {
    composeRule.onNodeWithText(SERVER_URL_LABEL).performTextInput(SERVER_URL)
    composeRule.onNodeWithText(USERNAME_LABEL).performTextInput(USERNAME)
    composeRule.onNodeWithText(PASSWORD_LABEL).performTextInput(password)

    composeRule.onNodeWithText(CONNECT_LABEL).performClick()

    waitUntilSettled()
  }

  /**
   * Blocks until the screen leaves its "Connecting…" state, or fails after [CONNECT_TIMEOUT_MILLIS].
   *
   * Needed because Compose's test framework only auto-synchronises with the composition and its
   * own effects — it has no idea a `viewModelScope` coroutine is waiting on a socket, so an
   * assertion made immediately after the click would run against the pre-response frame. Waits on
   * the Connect button's own label reverting from "Connecting…" rather than on any one outcome, so
   * that success and failure both end this wait and each test's own assertions report what was
   * actually on screen instead of a bare timeout.
   */
  private fun waitUntilSettled() {
    composeRule.waitUntil(timeoutMillis = CONNECT_TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(CONNECTING_LABEL).fetchSemanticsNodes().isEmpty()
    }
  }

  private companion object {
    /**
     * `localhost`, not `10.0.2.2`: the emulator reaches the host container through
     * `adb reverse tcp:4533 tcp:4533` (see this class's own doc). Both work on an emulator, but
     * only the reverse-forward form is also how a physical device would reach it, and it is what
     * `ci/prepare-emulator.sh` sets up.
     */
    const val SERVER_URL = "http://localhost:4533"
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"

    // The literal strings SetupScreen renders. Duplicated here rather than shared: these tests are
    // a black-box journey through the app's real UI, and a shared constant would let a change to
    // what the user actually sees pass unnoticed.
    const val SERVER_URL_LABEL = "Server URL"
    const val USERNAME_LABEL = "Username"
    const val PASSWORD_LABEL = "Password"
    const val CONNECT_LABEL = "Connect"
    const val CONNECTING_LABEL = "Connecting…"
    const val CONTINUE_LABEL = "Continue"

    /**
     * Every library row renders **both** role chips ("Music" and "Audiobooks"), so with the two
     * seeded libraries the exact text "Music" appears three times in document order: the Music
     * row's own name (index 0), that row's "Music" chip (index 1), and the Audiobooks row's
     * "Music" chip (index 2). "Audiobooks" is the mirror image: the Audiobooks row's "Audiobooks"
     * chip is first (index 0, it is the *second* row but its chip is the *first* node with this
     * exact text -- the Music row has no "Audiobooks"-labelled node before it), then the
     * Audiobooks row's own name (index 1), then that row's "Audiobooks" chip (index 2).
     *
     * Indices rather than test tags, deliberately: this journey is a black-box walk through what
     * a user sees, and adding tags to the production UI purely so a test can find things makes
     * the test pass on a screen the user could not use. If these indices become fragile, add
     * distinct visible labels ("Tag as Music") rather than invisible ones.
     */
    const val MUSIC_LIBRARY_NAME = 0
    const val MUSIC_ROLE_CHIP = 1
    const val AUDIOBOOKS_LIBRARY_NAME = 1
    const val AUDIOBOOK_ROLE_CHIP = 2

    /**
     * Generous on purpose. A first `ping` against an already-running container on a
     * swiftshader-rendered emulator is nowhere near this slow in practice (see task-8-report.md
     * for the measured figure), but a timeout tight enough to flake would make this required gate
     * worse than useless.
     */
    const val CONNECT_TIMEOUT_MILLIS = 30_000L
  }
}
