package app.muplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.setup.LibraryRepositoryEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
   * [completingEveryTagPersistsBothRolesAndLandsOnTheLibraryScreen], [theFlowCannotBeFinishedUntilEveryLibraryIsTagged]
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
    val repository = libraryRepository()
    repository.allIds().forEach { id -> repository.setRole(id, LibraryRole.UNASSIGNED) }
  }

  /**
   * The real, singleton `LibraryRepository` the app's own `SetupViewModel` writes through --
   * shared by [resetLibraryTagging] and by [readPersistedLibraries], which
   * [completingEveryTagPersistsBothRolesAndLandsOnTheLibraryScreen] uses to prove a role actually got
   * written, not merely displayed.
   */
  private fun libraryRepository() =
    EntryPointAccessors.fromApplication(
      InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
      LibraryRepositoryEntryPoint::class.java,
    ).libraryRepository()

  private fun readPersistedLibraries(): List<MusicLibrary> = runBlocking { libraryRepository().libraries.first() }

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
    // A bare `onNodeWithText` is safe here specifically because the role chips are labelled
    // "Tag as Music"/"Tag as Audiobooks" (see SetupScreen's Tagging branch), not the bare
    // "Music"/"Audiobooks" an earlier draft used -- that draft made this exact assertion pass on
    // any server with two libraries called anything at all, because `onAllNodesWithText("Music")`
    // picking an index by *position* cannot tell a chip from a name if both carry the same text.
    // A selector that can only match the name is what makes this genuinely a contract on server
    // state again.
    composeRule.onNodeWithText("Music").assertIsDisplayed()
    composeRule.onNodeWithText("Audiobooks").assertIsDisplayed()
  }

  /**
   * Walks all the way to [app.muplay.setup.SetupUiState.Ready] and checks what actually renders
   * there, not just that the state transition happened -- and, more importantly, reads the real
   * persisted role back through [LibraryRepositoryEntryPoint] rather than trusting the screen.
   *
   * Tags each library **twice**, at two different roles, before moving on -- first to match its
   * own name, then the opposite -- and reads the persisted role back after each round. A single
   * observation per library (the original version of this test: opposite-of-name, once) cannot
   * tell a real per-tap write from a sink that ignores `role` entirely and returns a constant
   * keyed on the library id (`if (musicFolderId == 1) AUDIOBOOKS else MUSIC`) -- that mutant
   * "coincidentally" matches whichever single value each library was ever tagged, and previously
   * left `:feature:setup:test` and `:app:connectedDebugAndroidTest` fully green. Two disjoint
   * observations of the *same* id at two different roles is what rules that out.
   *
   * The final, opposite-of-name round is also what defeats a hardcoded, inverted, or swapped
   * role: a mutant that hardcodes (or swaps, or inverts) the role "coincidentally" produces the
   * name-matching answer the first round asserts, but not the opposite answer the second round
   * asserts. Confirmed directly: with only matching-name tagging, both the `@Inject` ctor's
   * `libraryRepository.setRole(musicFolderId, LibraryRole.MUSIC)` (role hardcoded) and the two
   * `FilterChip` role literals in `SetupScreen` swapped left every test in both tiers green.
   *
   * Each tap also asserts [assertIsSelected] on the chip just clicked: the two `FilterChip`
   * `selected =` predicates are the user's only feedback on which role is recorded, and were
   * previously unobserved by any test -- a mutant that swapped them would light up the *other*
   * chip than the one tapped, inviting a user to "correct" it by hand and genuinely mis-tag the
   * library, while every persisted-role assertion here kept passing.
   *
   * The read-back after the final round also closes the coverage gap the state-transition
   * assertion alone found: without walking to `Ready` at all, JaCoCo reported `SetupScreenKt`'s
   * `is SetupUiState.Ready ->` source line as "covered", because Kotlin compiles an exhaustive
   * sealed `when` as a chain of `instanceof` checks -- the `Ready` check itself runs on *every*
   * composition regardless of which state is current, so the line lit up green while the branch
   * it guards, and the text it renders, had never once executed.
   *
   * **N-1 (review round 1, task-9-review.md): this test used to end by asserting `"Setup complete"`
   * was displayed, and Task 9 made that assertion permanently false.** `SetupScreen`'s
   * `LaunchedEffect(uiState) { if (uiState is Ready) onSetupComplete() }` has existed since Task 8,
   * but `MuPlayApp` wired `onSetupComplete = {}`, so `Ready` rendered `Text("Setup complete")` and
   * stayed there. Task 9 wired the callback to `backStack.clear(); backStack.add(LibraryRoute)`, so
   * `Ready` now navigates away in the same frame it is reached and that string is never displayed
   * to anyone. Measured on this device: base `7ee8a85` 4/4 green, `5aec4d4` 3/4.
   *
   * Two things changed here as a result, and the order of the second one is the point:
   *
   * 1. The final assertion is now what actually happens after Continue -- the browse screen, by its
   *    own two control labels. That also discharges the instruction Task 8's review left in
   *    `SetupScreen.kt` ("whoever wires real navigation should add a test that actually observes
   *    this firing"): deleting that `LaunchedEffect`, or reverting `onSetupComplete` to a no-op,
   *    strands this test on the setup screen.
   * 2. **The persisted-role read-back moved *above* the Continue click.** It was below the
   *    `"Setup complete"` assertion, so from `5aec4d4` onwards the strongest assertion in this
   *    class -- the two-disjoint-observation proof that a tag reached the database at all -- was
   *    not executing. It does not depend on navigation in any way (every role is written by the
   *    chip taps, each of which is already confirmed by `assertIsSelected`), so nothing downstream
   *    can take it down with it again.
   */
  @Test
  fun completingEveryTagPersistsBothRolesAndLandsOnTheLibraryScreen() {
    connectAs(PASSWORD)

    // First round: each library tagged to match its own name -- the first of two disjoint
    // observations of each id's role.
    composeRule.onAllNodesWithText(TAG_AS_MUSIC_LABEL)[MUSIC_ROW_CHIP].performClick().assertIsSelected()
    composeRule.onAllNodesWithText(TAG_AS_AUDIOBOOKS_LABEL)[AUDIOBOOKS_ROW_CHIP].performClick().assertIsSelected()
    val afterNameMatchingTaps = readPersistedLibraries()
    assertEquals(LibraryRole.MUSIC, afterNameMatchingTaps.single { it.id == MUSIC_LIBRARY_ID }.role)
    assertEquals(LibraryRole.AUDIOBOOKS, afterNameMatchingTaps.single { it.id == AUDIOBOOKS_LIBRARY_ID }.role)

    // Second, final round: each library re-tagged the opposite way -- the second, disjoint
    // observation of the same two ids, and the state this test's other assertions build on.
    composeRule.onAllNodesWithText(TAG_AS_AUDIOBOOKS_LABEL)[MUSIC_ROW_CHIP].performClick().assertIsSelected()
    composeRule.onAllNodesWithText(TAG_AS_MUSIC_LABEL)[AUDIOBOOKS_ROW_CHIP].performClick().assertIsSelected()

    // The read-back that actually proves it: reached through the real LibraryRepository, the
    // same singleton the app's own SetupViewModel and its @Inject-constructed SetupLibrarySink
    // write through -- not through what the screen merely displays.
    //
    // Taken *before* Continue on purpose (N-1, see this test's own doc). Both roles are already
    // written at this point -- each tap above was confirmed by assertIsSelected, and that chip's
    // `selected` predicate reads the library's persisted role back out of SetupViewModel's own
    // `tagging()` refresh -- so this observation owes nothing to navigation, and no later
    // assertion about where the app went next can stop it from running.
    val libraries = readPersistedLibraries()
    assertEquals(LibraryRole.AUDIOBOOKS, libraries.single { it.id == MUSIC_LIBRARY_ID }.role)
    assertEquals(LibraryRole.MUSIC, libraries.single { it.id == AUDIOBOOKS_LIBRARY_ID }.role)

    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsEnabled().performClick()

    // What Continue does now: SetupUiState.Ready fires SetupScreen's LaunchedEffect, which calls
    // onSetupComplete, which MuPlayApp wires to `backStack.clear(); backStack.add(LibraryRoute)`.
    // Waited for rather than asserted immediately -- the state change, the effect and the back
    // stack edit all land on the main dispatcher after the click returns.
    composeRule.waitUntil(timeoutMillis = NAVIGATION_TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_LIBRARY_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    // Both of the browse screen's own controls, by the literal strings LibraryScreen renders --
    // duplicated here rather than shared, for the same reason every other label in this class is.
    composeRule.onNodeWithText(SEARCH_LIBRARY_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(SHUFFLE_LIBRARY_LABEL).assertIsDisplayed()

    // Replaced, not pushed: `backStack.clear()` is what keeps a back gesture from offering to
    // re-enter credentials the app already has. Without the clear, SetupScreen is still the entry
    // underneath -- NavDisplay renders only the top one, so this asserts the *setup screen itself*
    // is gone from the composition, which is also what makes the removed "Setup complete"
    // assertion unrecoverable rather than merely relocated.
    composeRule.onNodeWithText(CONNECT_LABEL).assertDoesNotExist()
  }

  @Test
  fun theFlowCannotBeFinishedUntilEveryLibraryIsTagged() {
    connectAs(PASSWORD)

    // Both libraries untagged: Continue is inert. This is the assertion that keeps the tagging
    // step from becoming skippable, and an untagged library is invisible to browse and shuffle.
    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsNotEnabled()

    composeRule.onAllNodesWithText(TAG_AS_MUSIC_LABEL)[MUSIC_ROW_CHIP].performClick()
    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsNotEnabled()

    composeRule.onAllNodesWithText(TAG_AS_AUDIOBOOKS_LABEL)[AUDIOBOOKS_ROW_CHIP].performClick()
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
     * The two controls `LibraryScreen` renders in its `Content` state -- what the app shows once
     * setup hands over. Same duplicate-the-literal rule as the setup labels above: a shared
     * constant would let a change to what the user actually sees pass unnoticed here.
     */
    const val SEARCH_LIBRARY_LABEL = "Search this library"
    const val SHUFFLE_LIBRARY_LABEL = "Shuffle this library"

    /**
     * The two role chips' own labels -- distinct from the bare library-name text on purpose (see
     * SetupScreen's own doc on this): a first draft of this suite labelled the chips "Music"/
     * "Audiobooks" too, which made every `onNodeWithText("Music")` ambiguous the moment a chip and
     * a library name could carry the identical string, and (worse, found on review) made the
     * server-state assertion in [firstRunConnectsToNavidromeAndListsBothSeededLibraries] pass on
     * any server with two libraries called anything at all.
     */
    const val TAG_AS_MUSIC_LABEL = "Tag as Music"
    const val TAG_AS_AUDIOBOOKS_LABEL = "Tag as Audiobooks"

    /**
     * Every library row renders **both** role chips, so each label above still matches two nodes
     * (one per row) -- `onAllNodesWithText(TAG_AS_MUSIC_LABEL)` matches the Music row's own chip
     * and the Audiobooks row's chip, in that document order, and likewise for
     * `TAG_AS_AUDIOBOOKS_LABEL`. `MUSIC_ROW_CHIP`/`AUDIOBOOKS_ROW_CHIP` name that row position, not
     * a role, so the same pair of constants indexes into either label's node list depending on
     * which library a given test means to tag.
     *
     * Indices rather than test tags, deliberately: this journey is a black-box walk through what
     * a user sees, and adding tags to the production UI purely so a test can find things makes
     * the test pass on a screen the user could not use.
     */
    const val MUSIC_ROW_CHIP = 0
    const val AUDIOBOOKS_ROW_CHIP = 1

    /**
     * The seeded libraries' real Subsonic ids, per `ci/configure-libraries.sh`: Navidrome's
     * pinned library 1 is renamed "Music"; "Audiobooks" is created after it and gets id 2. Used
     * only to read back a specific library's role through [LibraryRepositoryEntryPoint] -- by id,
     * not by name, so that read-back cannot itself be satisfied by a name-based coincidence.
     */
    const val MUSIC_LIBRARY_ID = 1
    const val AUDIOBOOKS_LIBRARY_ID = 2

    /**
     * Generous on purpose. A first `ping` against an already-running container on a
     * swiftshader-rendered emulator is nowhere near this slow in practice (see task-8-report.md
     * for the measured figure), but a timeout tight enough to flake would make this required gate
     * worse than useless.
     */
    const val CONNECT_TIMEOUT_MILLIS = 30_000L

    /**
     * How long to wait for the setup -> browse hand-over. Much shorter than
     * [CONNECT_TIMEOUT_MILLIS] because nothing here touches the network: `continueToLibrary` reads
     * the already-loaded library list back out of Room, and the back-stack edit and the first
     * `LibraryScreen` composition are both main-thread work. Generous anyway -- a gate that flakes
     * is worse than no gate.
     */
    const val NAVIGATION_TIMEOUT_MILLIS = 10_000L
  }
}
