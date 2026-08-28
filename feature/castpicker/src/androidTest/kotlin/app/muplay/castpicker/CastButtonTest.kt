package app.muplay.castpicker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.cast.session.CastSessionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cast button, composed for real on a device.
 *
 * Both halves are covered here: the stateless overload against a name built by hand, and the
 * Hilt-bound entry point against a [CastViewModel] built by hand over the two fakes in
 * [CastPickerFakes]. The second is what covers the `collectAsStateWithLifecycle` hop, which no JVM
 * test can see; the only line left for `:app`'s journey is the `hiltViewModel()` default argument
 * itself.
 *
 * camelCase method names throughout: D8 refuses a space in any `SimpleName` at DEX 035, which is
 * what `minSdk 26` compiles, so this project's JVM-tier backticked names cannot come here.
 */
@RunWith(AndroidJUnit4::class)
class CastButtonTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val clicks = mutableListOf<Unit>()

  @Test
  fun theButtonSaysOnlyCastWhileNothingIsBeingCast() {
    composeRule.setContent { CastButton(connectedDeviceName = null, onClick = { clicks += Unit }) }

    composeRule.onNodeWithContentDescription(CAST_BUTTON_LABEL).assertIsDisplayed()
  }

  @Test
  fun theButtonNamesTheSpeakerItIsCastingTo() {
    // Exact-match content description, not a substring: "Cast" alone must NOT match once a speaker
    // is connected, or the journey could not tell the two states apart -- which is the whole reason
    // the name is on the description rather than only in the sheet.
    composeRule.setContent { CastButton(connectedDeviceName = "Küche", onClick = {}) }

    composeRule.onNodeWithContentDescription(castButtonDescription("Küche")).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(CAST_BUTTON_LABEL).assertDoesNotExist()
  }

  @Test
  fun aSecondSpeakerGetsASecondDescription() {
    // Rule 2 on the one field this composable renders: one name is not evidence that the name is
    // read at all.
    composeRule.setContent { CastButton(connectedDeviceName = "Study Amp", onClick = {}) }

    composeRule.onNodeWithContentDescription(castButtonDescription("Study Amp")).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(castButtonDescription("Küche")).assertDoesNotExist()
  }

  @Test
  fun tappingTheButtonAsksForThePicker() {
    composeRule.setContent { CastButton(connectedDeviceName = null, onClick = { clicks += Unit }) }

    composeRule.onNodeWithContentDescription(CAST_BUTTON_LABEL).performClick()

    assertThat(clicks).hasSize(1)
  }

  /**
   * The stateful entry point, over a real [CastViewModel] and its two seams.
   *
   * The button reads the **session**, not `uiState`, and this is what proves it: the picker is
   * closed here, so `uiState` is [CastUiState.Hidden], and a button wired to `uiState` would say
   * "Cast" while a track played in the kitchen.
   */
  @Test
  fun theEntryPointNamesTheSpeakerEvenWhileThePickerIsClosed() {
    val control = CastPickerFakes.FakeControl()
    val viewModel = CastViewModel(CastPickerFakes.FakeDiscovery(), control)
    composeRule.setContent { CastButton(onClick = {}, viewModel = viewModel) }

    control.emit(CastSessionState.Playing("Küche"))

    composeRule.waitUntil(WAIT_MILLIS) {
      composeRule
        .onAllNodesWithContentDescription(castButtonDescription("Küche"))
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    assertThat(viewModel.uiState.value).isEqualTo(CastUiState.Hidden)
  }

  private companion object {
    /** Long enough for a `viewModelScope` round trip on a loaded emulator, short enough to fail. */
    const val WAIT_MILLIS = 5_000L
  }
}
