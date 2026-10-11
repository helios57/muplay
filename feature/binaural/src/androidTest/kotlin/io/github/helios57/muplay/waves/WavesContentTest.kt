package io.github.helios57.muplay.waves

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.helios57.muplay.binaural.Presets
import io.github.helios57.muplay.binaural.ProfileOrigin
import io.github.helios57.muplay.binaural.ProfileTimeline
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Waves list and the profile editor, composed from state on the device. */
@RunWith(AndroidJUnit4::class)
class WavesContentTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val played = mutableListOf<String>()
  private val edited = mutableListOf<String>()
  private var acknowledged = 0

  private fun row(profile: io.github.helios57.muplay.binaural.BinauralProfile, playing: Boolean = false) =
    ProfileRow(profile.id, profile.name, summaryOf(profile), detailOf(profile), edited = false, playing = playing)

  private fun list(state: WavesUiState) = composeRule.setContent {
    WavesContent(
      state = state,
      onPlay = { played += it },
      onStop = {},
      onEdit = { edited += it },
      onAcknowledge = { acknowledged++ },
      onVolumeChange = {},
      onVolumeSet = {},
      onCreate = {},
    )
  }

  private val baseState = WavesUiState(
    programs = listOf(row(Presets.powerNap)),
    tones = listOf(Presets.meditate, Presets.focus, Presets.concentrate, Presets.creative, Presets.sleep, Presets.deepSleep).map { row(it) },
    yours = emptyList(),
    current = null,
    volume = 1.0,
    safetyAcknowledged = false,
  )

  @Test
  fun theSafetyNoteComesFirstAndIsAcknowledgedOnce() {
    list(baseState)

    composeRule.onNodeWithText("Before you start").assertIsDisplayed()
    composeRule.onNodeWithText(ACKNOWLEDGE_LABEL).performClick()
    assertThat(acknowledged).isEqualTo(1)
  }

  @Test
  fun everyRequiredProfileIsListedAndATapPlaysIt() {
    list(baseState.copy(safetyAcknowledged = true))

    for (name in listOf("Power nap", "Meditate", "Focus", "Concentrate", "Creative", "Sleep", "Deep sleep")) {
      composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
      composeRule.onNodeWithText(name).assertIsDisplayed()
    }
    composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Concentrate"))
    composeRule.onNodeWithText("Left 158.44 Hz · right 144.98 Hz").assertIsDisplayed()
    composeRule.onNodeWithText("Concentrate").performClick()
    composeRule.onNodeWithContentDescription("$EDIT_LABEL Concentrate").performClick()

    assertThat(played).containsExactly("concentrate")
    assertThat(edited).containsExactly("concentrate")
  }

  @Test
  fun theNowPlayingCardShowsTheBeatTheTonesAndTheCurve() {
    val profile = Presets.powerNap
    val at = 25 * 60.0
    val sample = ProfileTimeline(profile).at(at)
    list(
      baseState.copy(
        safetyAcknowledged = true,
        current = NowPlaying(profile.id, profile.name, profile, sample, at, stopping = false),
      ),
    )

    composeRule.onNodeWithText("25:00 of 36:00").assertIsDisplayed()
    composeRule.onNodeWithText(leftRightText(sample.carrierHz, sample.beatHz)).assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Beat frequency, 8 → 2.5 → 14 Hz, over 36 min").assertIsDisplayed()
  }

  private var saved = 0
  private var draft by mutableStateOf(ProfileDraft.of(Presets.concentrate))

  private fun editor(origin: ProfileOrigin) = composeRule.setContent {
    val state = EditorUiState.Editing(draft, origin, draft.problems())
    EditorContent(
      state = state,
      onEdit = { draft = it(draft) },
      onSave = { saved++ },
      onTry = {},
      onReset = {},
      onDuplicate = {},
      onDelete = {},
      onBack = {},
    )
  }

  @Test
  fun anEditedBuiltInOffersResetAndNotDelete() {
    editor(ProfileOrigin.EDITED)

    composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(RESET_LABEL))
    composeRule.onNodeWithText(RESET_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(DELETE_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(SAVE_LABEL).assertIsEnabled().performClick()
    assertThat(saved).isEqualTo(1)
  }

  @Test
  fun aDraftWithABlockingProblemNamesItAndCannotBeSaved() {
    draft = draft.withSegment(0) { it.copy(startBeat = "") }
    editor(ProfileOrigin.CUSTOM)

    composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Step 1: every field needs a number."))
    composeRule.onNodeWithText("Step 1: every field needs a number.").assertIsDisplayed()
    composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(SAVE_LABEL))
    composeRule.onNodeWithText(SAVE_LABEL).assertIsNotEnabled()
    composeRule.onNodeWithText(DELETE_LABEL).assertIsDisplayed()
  }

  @Test
  fun typingIntoAStepReachesTheDraftWithACommaAsTheDecimalPoint() {
    editor(ProfileOrigin.BUILT_IN)

    composeRule.onNodeWithContentDescription("Step 1 Beat (Hz) from").performTextReplacement("12,5")

    assertThat(draft.segments.first().startBeat).isEqualTo("12,5")
    assertThat(draft.toProfile()!!.segments.first().startBeatHz).isEqualTo(12.5)
  }
}
