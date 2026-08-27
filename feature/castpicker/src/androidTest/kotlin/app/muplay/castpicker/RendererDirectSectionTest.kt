package app.muplay.castpicker

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.CastSettings
import app.muplay.settings.SettingsScreen
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The renderer-direct switch, composed, over a **real** DataStore.
 *
 * The thing being proved is not that a `Switch` toggles. It is that the control a user actually
 * sees and the boolean `CastRouter.confirm` actually reads are **the same value**, in both
 * directions -- because every other test in this plan takes one of those two on trust. A switch
 * wired to a second preference, or to `remember { mutableStateOf(false) }`, looks perfect on screen
 * and changes nothing about what a speaker is handed.
 *
 * camelCase names and `fun x(): Unit = runBlocking { }`, per CLAUDE.md: D8 refuses spaces in any
 * SimpleName at `minSdk 26`, and JUnit 4 refuses a test method that returns an AssertJ assert.
 */
@RunWith(AndroidJUnit4::class)
class RendererDirectSectionTest {

  @get:Rule
  val composeRule = createComposeRule()

  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var settings: CastSettings
  private lateinit var section: RendererDirectSection

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    file = File(context.filesDir, "renderer-direct-section-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    settings = CastSettings(dataStore)
    section = RendererDirectSection(settings, CoroutineScope(SupervisorJob() + Dispatchers.Default))
  }

  @After
  fun tearDown() {
    // Guarded, per CLAUDE.md: an `@After` that throws over an unassigned `lateinit` replaces the
    // real failure with its own and the cause never reaches the report.
    if (::file.isInitialized) file.delete()
  }

  @Test
  fun theSwitchOpensOffAndSaysWhatTurningItOnCosts() {
    composeRule.setContent { section.Content() }

    composeRule.onNodeWithText(RENDERER_DIRECT_TITLE).assertIsOff()
    // The copy is not merely a constant that exists -- it is on the screen, beside the control.
    // `RendererDirectCopyTest` asserts what it says; this asserts that a user sees it.
    composeRule.onNodeWithText(RENDERER_DIRECT_EXPLANATION).assertIsDisplayed()
  }

  @Test
  fun aStoredYesIsShownAsOnBeforeTheUserTouchesAnything(): Unit = runBlocking {
    // The read direction. Without it, a switch hardcoded to `false` passes every "starts off" test
    // in this file and tells a user their setting is off while a speaker is being handed the URL.
    settings.setAllowRendererDirect(true)

    composeRule.setContent { section.Content() }

    composeRule.waitUntil(TIMEOUT_MS) { runCatching { onSwitch().assertIsOn() }.isSuccess }
  }

  @Test
  fun turningItOnStoresTheChoiceWhereTheRouterWillReadIt(): Unit = runBlocking {
    composeRule.setContent { section.Content() }
    composeRule.onNodeWithText(RENDERER_DIRECT_TITLE).assertIsOff()

    composeRule.onNodeWithText(RENDERER_DIRECT_TITLE).performClick()

    // Read back out of the store, not off the screen: a `mutableStateOf` in the composable would
    // satisfy an on-screen assertion and persist nothing.
    composeRule.waitUntil(TIMEOUT_MS) { runBlocking { settings.allowRendererDirectNow() } }
    composeRule.onNodeWithText(RENDERER_DIRECT_TITLE).assertIsOn()
  }

  @Test
  fun turningItBackOffStoresThatToo(): Unit = runBlocking {
    // Both directions, because a user who cannot revoke this has not been given a choice -- and a
    // setter that only ever wrote `true` passes the test above on its own.
    settings.setAllowRendererDirect(true)
    composeRule.setContent { section.Content() }
    composeRule.waitUntil(TIMEOUT_MS) { runCatching { onSwitch().assertIsOn() }.isSuccess }

    composeRule.onNodeWithText(RENDERER_DIRECT_TITLE).performClick()

    composeRule.waitUntil(TIMEOUT_MS) { !runBlocking { settings.allowRendererDirectNow() } }
    composeRule.onNodeWithText(RENDERER_DIRECT_TITLE).assertIsOff()
  }

  @Test
  fun theSectionArrivesOnTheSettingsScreenThroughTheSlotAndNotByName() {
    // The join between the two modules, composed for real: `SettingsScreen` has never heard of
    // `RendererDirectSection` -- it takes a list of `SettingsSection` -- and the switch appears on
    // it anyway. That is the severability contract as a rendered screen rather than as a promise.
    composeRule.setContent { SettingsScreen(sections = listOf(section)) }

    composeRule.onNodeWithText(RENDERER_DIRECT_TITLE).assertIsOff()
    composeRule.onNodeWithText(RENDERER_DIRECT_EXPLANATION).assertIsDisplayed()
  }

  @Test
  fun theSectionSitsWhereItSaysItDoes() {
    // `order` is the only thing `:feature:settings` reads off a section, and a section that
    // reported a different number than it meant would be invisible here and wrong on a screen with
    // a second section on it. Cheap, and it is the one field with no other observer.
    assertThat(section.order).isEqualTo(200)
  }

  private fun onSwitch() = composeRule.onNodeWithText(RENDERER_DIRECT_TITLE)

  private companion object {
    /** Generous: this waits on a real DataStore write reaching a real file on an emulator. */
    const val TIMEOUT_MS = 5_000L
  }
}
