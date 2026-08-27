package app.muplay

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.LibraryRole
import app.muplay.setup.LibraryRepositoryEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Takes the Play Store phone screenshots, off the real app on the real emulator.
 *
 * This is a screenshot *generator* and a journey test at once, on purpose. A generator that
 * photographs whatever happens to be on screen ships a store asset of a spinner, or of an error
 * message, and nobody notices until a reviewer does — so every capture below is preceded by an
 * assertion that the screen it claims to be really is on screen. Delete the assertions and this
 * stops being able to fail, which is the class of check this repository exists to keep out.
 *
 * It also means the screens the store listing shows are re-walked by
 * `:app:connectedDebugAndroidTest` on every emulator run, so a UI change that invalidates a
 * published screenshot is red here rather than quietly wrong on Play.
 *
 * ### What produces the files, and how they leave the device
 *
 * Each capture is written as a PNG into the app-under-test's own `filesDir`
 * (`/data/user/0/app.muplay/files/store-screenshots`). `ci/store-screenshots.sh` runs this class
 * and pulls them out with `run-as`, which works because the debug APK is debuggable. Running this
 * class on its own leaves the PNGs on the device and pulls nothing — the script is the deliverable,
 * not this file alone.
 *
 * The set of names is derived from this file rather than written down anywhere else:
 * `StoreListingTest` (JVM tier) reads the `capture(…, "…")` literals below and holds them against
 * both `docs/STORE-LISTING.md`'s inventory and the files committed under `play/screenshots/phone/`,
 * so a capture added, removed or renamed here fails `check` until the listing catches up.
 *
 * ### Why the visible server address is not the one it connects to
 *
 * The journey connects to `ci/navidrome.compose.yml`'s container over `adb reverse`, i.e. to
 * `http://localhost:4533` as `admin` — and a store screenshot showing `localhost` and `admin` is a
 * picture of a test rig. So the setup screen is filled with [SHOWCASE_SERVER_URL] and
 * [SHOWCASE_USERNAME] and *photographed*, then cleared and re-filled with the real values before
 * Connect is pressed; after the server answers, the two non-secret fields are re-filled with the
 * showcase values again for the tagging shot. Nothing about the screen is faked — the libraries,
 * the albums, the audio and the server's own version string are all real — only the text a user
 * would have typed differs. The password field carries a `PasswordVisualTransformation`, so no
 * password is legible in any frame either way.
 *
 * **Point it at a real library instead.** The seeded corpus is called "Test Album" and "Test Book",
 * which is honest and unappealing. Every credential is overridable as an instrumentation runner
 * argument, so a human with their own Navidrome can regenerate the whole set against it:
 *
 *     ci/store-screenshots.sh --server https://music.example.com --user alice --password hunter2
 *
 * (Note that a password on a command line is visible in `ps` to every user on that machine.)
 *
 * ### Preconditions
 *
 * The same three `FirstRunJourneyTest` documents, for the same reasons: the container up and
 * seeded, `adb reverse tcp:4533 tcp:4533`, and the emulator booted with
 * `-feature Minigbm -prop qemu.hardware.gralloc=minigbm`. `ci/prepare-emulator.sh` establishes all
 * three and `ci/store-screenshots.sh` runs it.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {

  val composeRule = createAndroidComposeRule<MainActivity>()

  /**
   * The reset has to happen before the Activity is created — see `FirstRunJourneyTest`'s own note
   * on why this is a `RuleChain` and not an `@Before`. Untagging every library is what makes
   * `StartDestinationViewModel` choose `Setup`, which is the first screenshot.
   */
  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(StartOnSetup()).around(composeRule)

  private inner class StartOnSetup : ExternalResource() {
    override fun before() = runBlocking {
      val repository = libraryRepository()
      repository.allIds().forEach { id -> repository.setRole(id, LibraryRole.UNASSIGNED) }
    }
  }

  private fun libraryRepository() =
    EntryPointAccessors.fromApplication(
      InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
      LibraryRepositoryEntryPoint::class.java,
    ).libraryRepository()

  /**
   * One method, not seven: this is a walk, and each screen is reached by having been through the
   * one before it. Splitting it would relaunch the Activity per screenshot and re-drive the whole
   * setup flow six more times against the shared emulator for no gain.
   */
  @Test
  fun captureTheStoreScreenshots() {
    val output = outputDirectory()

    // ---- 1. Setup ---------------------------------------------------------------------------
    composeRule.waitUntil(SETTLE_TIMEOUT_MILLIS) { nodesWithText(SERVER_URL_LABEL).isNotEmpty() }
    fillSetupForm(SHOWCASE_SERVER_URL, SHOWCASE_USERNAME, SHOWCASE_PASSWORD)
    composeRule.onNodeWithText(SETUP_HEADING).assertIsDisplayed()
    composeRule.onNodeWithText(SHOWCASE_SERVER_URL).assertIsDisplayed()
    capture(output, "01-connect-to-your-own-server")

    // ---- 2. Tagging -------------------------------------------------------------------------
    clearSetupForm()
    fillSetupForm(serverUrl(), username(), password())
    composeRule.onNodeWithText(CONNECT_LABEL).performClick()
    composeRule.waitUntil(CONNECT_TIMEOUT_MILLIS) { nodesWithText(CONTINUE_LABEL).isNotEmpty() }
    // The server really answered, and said what it is -- this distinguishes "connected" from a
    // failure message rendered in the same place.
    composeRule.onNodeWithText(CONNECTED_PREFIX, substring = true).assertIsDisplayed()
    composeRule.onNodeWithText(TAGGING_HEADING).assertIsDisplayed()
    // Back to a presentable address for the photograph -- see this class's own note. Only the two
    // non-secret fields; the password is masked and is still the real one.
    composeRule.onNodeWithText(SERVER_URL_LABEL).performTextClearance()
    composeRule.onNodeWithText(SERVER_URL_LABEL).performTextInput(SHOWCASE_SERVER_URL)
    composeRule.onNodeWithText(USERNAME_LABEL).performTextClearance()
    composeRule.onNodeWithText(USERNAME_LABEL).performTextInput(SHOWCASE_USERNAME)
    capture(output, "02-choose-what-each-library-is-for")

    // ---- 3. Browse the first library --------------------------------------------------------
    composeRule.onAllNodesWithText(TAG_AS_MUSIC_LABEL)[MUSIC_ROW_CHIP].performClick().assertIsSelected()
    composeRule.onAllNodesWithText(TAG_AS_AUDIOBOOKS_LABEL)[AUDIOBOOKS_ROW_CHIP].performClick().assertIsSelected()
    composeRule.onNodeWithText(CONTINUE_LABEL).performClick()

    composeRule.waitUntil(SYNC_TIMEOUT_MILLIS) { nodesWithText(SHUFFLE_LABEL).isNotEmpty() }
    // The launch sync has committed, so the album list below is the server's and not an empty
    // mirror. Same signal `JourneyNavigation.reachLibraryScreen` waits on, for the same reason.
    composeRule.waitUntil(SYNC_TIMEOUT_MILLIS) { runBlocking { journeyWatermarkDao().read() } != null }
    composeRule.waitUntil(SYNC_TIMEOUT_MILLIS) { nodesWithText(OPEN_LABEL).isNotEmpty() }
    composeRule.onNodeWithText(SEARCH_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(SHUFFLE_LABEL).assertIsDisplayed()

    val musicLibrary = selectedLibraryLabel()
    val musicBrowse = browseText()
    assertThat(musicBrowse).describedAs("what the $musicLibrary library lists").isNotEmpty()
    capture(output, "03-browse-your-music")

    // ---- 4. Browse the other library --------------------------------------------------------
    // By the library's own name, read off the chips: which names exist is the server's business,
    // and this repository's fixture corpus has been renamed and regrown mid-task before.
    val libraries = libraryChipLabels()
    assertThat(libraries)
      .describedAs("library chips -- this journey needs a server with more than one library")
      .hasSizeGreaterThan(1)
    val otherLibrary = libraries.first { it != musicLibrary }
    composeRule.onNodeWithText(otherLibrary).performClick()
    // The list really changed. "Some albums are on screen" would be satisfied by the first
    // library still being displayed, which is exactly the screenshot this must not take.
    composeRule.waitUntil(SYNC_TIMEOUT_MILLIS) {
      browseText().let { it.isNotEmpty() && it != musicBrowse }
    }
    composeRule.onNodeWithText(otherLibrary).assertIsSelected()
    capture(output, "04-browse-your-audiobooks")

    // ---- 5. Library-scoped shuffle -----------------------------------------------------------
    composeRule.onNodeWithText(musicLibrary).performClick()
    composeRule.waitUntil(SYNC_TIMEOUT_MILLIS) { browseText() == musicBrowse }
    composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
    composeRule.waitUntil(SYNC_TIMEOUT_MILLIS) { nodesWithText(SHUFFLE_HEADING).isNotEmpty() }
    composeRule.onNodeWithText(SHUFFLE_HEADING).assertIsDisplayed()
    // Nothing was dropped for being outside the library. Had anything been, this screen would
    // carry a line in red, which is not what this asset is for -- and the shuffle would not be
    // demonstrating the thing the caption claims.
    composeRule.onNodeWithText(OUT_OF_SCOPE_SUFFIX, substring = true).assertDoesNotExist()
    val shuffled = shuffledTitles(musicBrowse)
    assertThat(shuffled).describedAs("rows under the $SHUFFLE_HEADING heading").isNotEmpty()
    capture(output, "05-shuffle-only-this-library")

    // ---- 6. The player -----------------------------------------------------------------------
    composeRule.onAllNodesWithText(shuffled.first())[FIRST_MATCH].performClick()
    // `Pause` renders only while `isPlaying` is true, so finding it is the assertion that real
    // audio is coming out of the emulator rather than that a screen was merely navigated to.
    composeRule.waitUntil(PLAYBACK_TIMEOUT_MILLIS) { nodesWithText(PAUSE_LABEL).isNotEmpty() }
    composeRule.onNodeWithText(PREVIOUS_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(NEXT_LABEL).assertIsDisplayed()
    capture(output, "06-now-playing")

    // ---- 7. The mini player over the library --------------------------------------------------
    InstrumentationRegistry.getInstrumentation().uiAutomation
      .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    composeRule.waitUntil(SETTLE_TIMEOUT_MILLIS) { nodesWithText(SHUFFLE_LABEL).isNotEmpty() }
    composeRule.waitUntil(SETTLE_TIMEOUT_MILLIS) {
      composeRule.onAllNodes(hasContentDescriptionOf(MINI_PLAYER_LABEL)).fetchSemanticsNodes().isNotEmpty()
    }
    capture(output, "07-what-is-playing-follows-you")

    // Leave the shared emulator quiet: the queue would otherwise keep playing into whatever runs
    // next under the device lock.
    composeRule.onAllNodesWithText(PAUSE_LABEL)[FIRST_MATCH].performClick()

    // Derived from what was written, never compared against a total typed here -- a hardcoded
    // count is the shape that has gone stale three times in this repository.
    val written = output.listFiles().orEmpty().filter { it.extension == "png" }
    assertThat(written.map { it.name })
      .describedAs("PNGs written to ${output.path}")
      .isNotEmpty()
    assertThat(written.filter { it.length() == 0L })
      .describedAs("empty screenshot files")
      .isEmpty()
  }

  /** Wipes and recreates the output directory, so a renamed capture leaves no orphan behind. */
  private fun outputDirectory(): File {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.filesDir, OUTPUT_DIRECTORY)
    directory.listFiles()?.forEach { it.delete() }
    check(directory.isDirectory || directory.mkdirs()) { "could not create ${directory.path}" }
    assertThat(directory.listFiles().orEmpty()).describedAs("a cleared $OUTPUT_DIRECTORY").isEmpty()
    return directory
  }

  /**
   * Photographs the Compose root and writes it as `<name>.png`.
   *
   * The root, not the device screen: `enableEdgeToEdge()` makes the app's window cover the whole
   * display, so this is the full frame at the device's own resolution — minus the system status
   * bar, which is a separate window. That is deliberate. `uiAutomation.takeScreenshot()` would
   * bake the emulator's clock and battery icon into a published asset and make the bytes differ on
   * every run; Play does not require a status bar, and a reproducible file is worth more.
   */
  private fun capture(output: File, name: String) {
    composeRule.waitForIdle()
    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    // Play rejects a screenshot with any side under 320px or over 3840px. Checked here, where the
    // pixels are, rather than left to the human pasting the file into the Console.
    assertThat(minOf(bitmap.width, bitmap.height))
      .describedAs("$name is ${bitmap.width}x${bitmap.height}; Play's minimum side is $MIN_SIDE_PX")
      .isGreaterThanOrEqualTo(MIN_SIDE_PX)
    assertThat(maxOf(bitmap.width, bitmap.height))
      .describedAs("$name is ${bitmap.width}x${bitmap.height}; Play's maximum side is $MAX_SIDE_PX")
      .isLessThanOrEqualTo(MAX_SIDE_PX)

    val file = File(output, "$name.png")
    FileOutputStream(file).use { stream ->
      check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)) {
        "PNG encoding failed for ${file.path}"
      }
    }
    assertThat(file.length()).describedAs("bytes written to ${file.path}").isGreaterThan(0L)
  }

  private fun fillSetupForm(url: String, user: String, secret: String) {
    composeRule.onNodeWithText(SERVER_URL_LABEL).performTextInput(url)
    composeRule.onNodeWithText(USERNAME_LABEL).performTextInput(user)
    composeRule.onNodeWithText(PASSWORD_LABEL).performTextInput(secret)
  }

  private fun clearSetupForm() {
    composeRule.onNodeWithText(SERVER_URL_LABEL).performTextClearance()
    composeRule.onNodeWithText(USERNAME_LABEL).performTextClearance()
    composeRule.onNodeWithText(PASSWORD_LABEL).performTextClearance()
  }

  private fun nodesWithText(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes()

  /** Every string any node on screen renders. */
  private fun visibleText(): List<String> =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
      .fetchSemanticsNodes()
      .flatMap { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }

  /**
   * What the browse list is *showing* — album and artist names — with the screen's own furniture
   * removed.
   *
   * Read from the screen rather than from the fixture corpus, deliberately: the container
   * bind-mounts a fixture tree shared by every lane, it has grown mid-task before, and a journey
   * pinned to "Test Album" is the next thing to break.
   *
   * [SYNC_MESSAGE_MARKER] drops `LibraryViewModel`'s transient sync line (all three of its wordings
   * name the server). Without that filter, "the list changed after I switched library" could be
   * satisfied by that one line appearing or disappearing on its own, which is a comparison that
   * cannot fail for the reason it claims to.
   */
  private fun browseText(): List<String> {
    val furniture = setOf(
      SEARCH_LABEL, SHUFFLE_LABEL, REFRESH_LABEL, SHUFFLE_HEADING, OPEN_LABEL,
      EMPTY_LIBRARY_LABEL, PLAY_LABEL, PAUSE_LABEL,
    ) + libraryChipLabels()
    return visibleText()
      .filterNot { it in furniture || it.contains(SYNC_MESSAGE_MARKER, ignoreCase = true) }
      .sorted()
  }

  /** The library chips' labels, i.e. the library names the server reported. */
  private fun libraryChipLabels(): List<String> =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
      .fetchSemanticsNodes()
      .flatMap { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }
      .distinct()

  private fun selectedLibraryLabel(): String =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
      .fetchSemanticsNodes()
      .first { it.config.getOrElse(SemanticsProperties.Selected) { false } }
      .config.getOrElse(SemanticsProperties.Text) { emptyList() }
      .first().text

  /**
   * The shuffled rows: the clickable text on the browse screen that is neither a control nor an
   * album the list was already showing.
   *
   * `LibraryScreen` renders each shuffled song as a bare clickable `Text`, so "clickable, and not
   * furniture" identifies exactly those rows without depending on their order on screen or on any
   * fixture title.
   */
  private fun shuffledTitles(alreadyListed: List<String>): List<String> {
    val furniture = setOf(SEARCH_LABEL, SHUFFLE_LABEL, REFRESH_LABEL, OPEN_LABEL) + libraryChipLabels()
    return composeRule.onAllNodes(hasClickAction())
      .fetchSemanticsNodes()
      .flatMap { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }
      .filterNot { it in furniture || it in alreadyListed }
  }

  private fun hasContentDescriptionOf(description: String) =
    SemanticsMatcher("content description is $description") { node ->
      node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(description)
    }

  private fun argument(name: String, fallback: String): String =
    InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() } ?: fallback

  private fun serverUrl() = argument("muplayServerUrl", SERVER_URL)

  private fun username() = argument("muplayUsername", USERNAME)

  private fun password() = argument("muplayPassword", PASSWORD)

  private companion object {
    const val OUTPUT_DIRECTORY = "store-screenshots"

    /** What the screenshots show a user having typed. Never used to authenticate anything. */
    const val SHOWCASE_SERVER_URL = "https://music.example.com"
    const val SHOWCASE_USERNAME = "alice"
    const val SHOWCASE_PASSWORD = "a-password-nobody-can-read"

    // The literal strings the real screens render, duplicated here rather than shared with the
    // production code -- the same rule every other journey in this package keeps, and the reason
    // is sharper here: these are the words a store screenshot puts in front of a stranger.
    const val SETUP_HEADING = "Connect to your server"
    const val SERVER_URL_LABEL = "Server URL"
    const val USERNAME_LABEL = "Username"
    const val PASSWORD_LABEL = "Password"
    const val CONNECT_LABEL = "Connect"
    const val CONNECTED_PREFIX = "Connected to "
    const val TAGGING_HEADING = "What is each library for?"
    const val TAG_AS_MUSIC_LABEL = "Tag as Music"
    const val TAG_AS_AUDIOBOOKS_LABEL = "Tag as Audiobooks"
    const val CONTINUE_LABEL = "Continue"
    const val SEARCH_LABEL = "Search this library"
    const val SHUFFLE_LABEL = "Shuffle this library"
    const val REFRESH_LABEL = "Refresh library"
    const val SHUFFLE_HEADING = "Shuffled"
    const val OPEN_LABEL = "Open"
    const val EMPTY_LIBRARY_LABEL = "Nothing here yet."
    const val OUT_OF_SCOPE_SUFFIX = "were outside this library"
    const val PLAY_LABEL = "Play"
    const val PAUSE_LABEL = "Pause"
    const val NEXT_LABEL = "Next"
    const val PREVIOUS_LABEL = "Previous"
    const val MINI_PLAYER_LABEL = "Now playing"

    /** Common to all three of `LibraryViewModel`'s sync messages, and to nothing else it renders. */
    const val SYNC_MESSAGE_MARKER = "server"

    /** Row positions in the tagging list, not roles -- see `FirstRunJourneyTest`'s own note. */
    const val MUSIC_ROW_CHIP = 0
    const val AUDIOBOOKS_ROW_CHIP = 1
    const val FIRST_MATCH = 0

    const val MIN_SIDE_PX = 320
    const val MAX_SIDE_PX = 3840
    const val PNG_QUALITY = 100

    const val SETTLE_TIMEOUT_MILLIS = 15_000L
    const val CONNECT_TIMEOUT_MILLIS = 30_000L
    const val SYNC_TIMEOUT_MILLIS = 30_000L
    const val PLAYBACK_TIMEOUT_MILLIS = 30_000L
  }
}
