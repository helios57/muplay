package app.muplay.castpicker

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.cast.discovery.DiscoveryResult
import app.muplay.cast.session.CastSessionState
import app.muplay.castpicker.CastPickerFakes.DEVICE_HOST
import app.muplay.castpicker.CastPickerFakes.device
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.model.RememberedRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The picker, composed for real on a device against a [CastUiState] built by hand.
 *
 * No Hilt graph, no network, no speaker: the stateless overload takes the state and four lambdas,
 * which is what makes this suite fast and hermetic — and the last test composes the Hilt-bound
 * entry point over a hand-built [CastViewModel] so the `collectAsStateWithLifecycle` hop and the
 * `ModalBottomSheet` wrapper are covered too.
 *
 * **Every assertion is value-bearing, never "it rendered".** A test that asserts two speaker names
 * are on screen passes just as happily when tapping either casts to the same one.
 *
 * camelCase method names: D8 refuses a space in any `SimpleName` at DEX 035, which `minSdk 26`
 * compiles.
 */
@RunWith(AndroidJUnit4::class)
class CastPickerSheetTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val selected = mutableListOf<String>()
  private val volumes = mutableListOf<Float>()
  private val actions = mutableListOf<String>()

  private fun show(uiState: CastUiState) {
    composeRule.setContent {
      CastPickerSheet(
        uiState = uiState,
        onSelect = { selected += it },
        onDisconnect = { actions += "disconnect" },
        onRefresh = { actions += "refresh" },
        onVolume = { volumes += it },
      )
    }
  }

  // ---- looking, and having looked --------------------------------------------------------------

  @Test
  fun searchingSaysSoAndOffersNothingToTap() {
    show(CastUiState.Searching)

    composeRule.onNodeWithText(CAST_SEARCHING_LABEL).assertIsDisplayed()
    // The other half, and the half that matters: a spinner over a stale list is a list a user taps.
    composeRule.onNodeWithText(CAST_NO_DEVICES_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(CAST_REFRESH_LABEL).assertDoesNotExist()
  }

  @Test
  fun anEmptyNetworkSaysSoRatherThanSpinningForever() {
    show(devices())

    composeRule.onNodeWithText(CAST_NO_DEVICES_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(CAST_SEARCHING_LABEL).assertDoesNotExist()
  }

  @Test
  fun searchingAgainIsOfferedOnceASearchHasFinished() {
    show(devices())

    composeRule.onNodeWithText(CAST_REFRESH_LABEL).performClick()

    assertThat(actions).containsExactly("refresh")
  }

  // ---- the list --------------------------------------------------------------------------------

  @Test
  fun everySpeakerIsListedWithTheModelItReported() {
    show(devices(row("uuid:a", "Küche", "Sonos One", isSonos = true), row("uuid:b", "Study Amp", "WXA-50")))

    composeRule.onNodeWithText("Küche").assertIsDisplayed()
    composeRule.onNodeWithText("Sonos One").assertIsDisplayed()
    composeRule.onNodeWithText("Study Amp").assertIsDisplayed()
    composeRule.onNodeWithText("WXA-50").assertIsDisplayed()
  }

  @Test
  fun aSonosThatReportedNoModelIsStillDescribed() {
    // The only thing `isSonos` renders. A device that *did* report a model shows that model --
    // "Sonos One" tells a user more than "Sonos" does -- so this is the fallback and not the rule.
    show(devices(row("uuid:a", "Küche", subtitle = null, isSonos = true), row("uuid:b", "Study Amp", null)))

    composeRule.onNodeWithText(CAST_SONOS_FALLBACK_SUBTITLE).assertIsDisplayed()
    // Exactly one of them: a badge on every row would be a constant.
    assertThat(
      composeRule.onAllNodesWithText(CAST_SONOS_FALLBACK_SUBTITLE).fetchSemanticsNodes(),
    ).hasSize(1)
  }

  @Test
  fun everySpeakerRowIsBigEnoughToTapWhetherOrNotItReportedAModel() {
    // A row with a subtitle is about 48dp and a row without one is about 32dp -- `bodyLarge`'s
    // line box plus 4dp either side -- which is under the 48dp Android's accessibility guidance
    // and Material's `minimumInteractiveComponentSize` both name. **The short row is not the edge
    // case**: it is what a generic DLNA renderer that reports no model renders as, and casting to
    // one of those is half of what this screen is for. `isSonos = false` because the Sonos
    // fallback subtitle would give the row a second line and hide the defect.
    //
    // Both rows asserted, because a fix applied to one branch of `DeviceRow` and not the other
    // would satisfy either assertion alone.
    show(devices(row("uuid:a", "Kuche", "Sonos One", isSonos = true), row("uuid:b", "Study Amp", null)))

    composeRule.onNode(hasText("Study Amp") and hasClickAction())
      .assertHeightIsAtLeast(MuPlaySpacing.minTouchTarget)
    composeRule.onNode(hasText("Kuche") and hasClickAction())
      .assertHeightIsAtLeast(MuPlaySpacing.minTouchTarget)
  }

  @Test
  fun tappingASpeakerSelectsThatSpeakerAndNotAnother() {
    // Two observations, so the row's click cannot forward a constant udn.
    show(devices(row("uuid:a", "Küche", "Sonos One"), row("uuid:b", "Study Amp", "WXA-50")))

    composeRule.onNodeWithText("Study Amp").performClick()
    assertThat(selected).containsExactly("uuid:b")

    composeRule.onNodeWithText("Küche").performClick()
    assertThat(selected).containsExactly("uuid:b", "uuid:a")
  }

  // ---- the connected speaker -------------------------------------------------------------------

  @Test
  fun theConnectedSpeakerIsShownOnceAndOffersToStop() {
    show(
      devices(
        row("uuid:a", "Küche", "Sonos One", isConnected = true),
        row("uuid:b", "Study Amp", "WXA-50"),
        connectedUdn = "uuid:a",
        volumePercent = 40,
      ),
    )

    // Once, not twice: two rows carrying one speaker's name is one a user can tap the wrong copy of.
    assertThat(composeRule.onAllNodesWithText("Küche").fetchSemanticsNodes()).hasSize(1)

    composeRule.onNodeWithText(CAST_DISCONNECT_LABEL).performClick()
    assertThat(actions).containsExactly("disconnect")
  }

  @Test
  fun tappingTheConnectedSpeakerDoesNothingRatherThanRestartingTheSession() {
    show(
      devices(
        row("uuid:a", "Küche", "Sonos One", isConnected = true),
        connectedUdn = "uuid:a",
        volumePercent = 40,
      ),
    )

    composeRule.onNodeWithText("Küche").performClick()

    assertThat(selected).isEmpty()
  }

  @Test
  fun nothingOffersToStopCastingWhileNothingIsCast() {
    show(devices(row("uuid:a", "Küche", "Sonos One")))

    composeRule.onNodeWithText(CAST_DISCONNECT_LABEL).assertDoesNotExist()
  }

  // ---- the remembered speakers that did not answer ---------------------------------------------

  @Test
  fun anUnreachableSpeakerIsNamedAndCannotBeTapped() {
    // `RendererDirectory` surfaces these rather than dropping them, and this is where that becomes
    // something a user can act on. Tapping one would start a cast to an address nothing answers at.
    show(devices(row("uuid:a", "Küche", "Sonos One"), unreachable = listOf("Bedroom")))

    val node = composeRule.onNodeWithText("Bedroom — $CAST_UNREACHABLE_SUFFIX")
    node.assertIsDisplayed()
    assertThat(node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)).isNull()
  }

  // ---- the volume, and what a speaker will not do ----------------------------------------------

  @Test
  fun theVolumeSliderAndTheSpeedNoticeAppearOnlyWhileConnected() {
    show(devices(row("uuid:a", "Küche", "Sonos One")))

    composeRule.onNodeWithText(CAST_VOLUME_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(CAST_SPEED_LIMIT_NOTICE).assertDoesNotExist()
  }

  @Test
  fun aConnectedSpeakerGetsASliderAndIsSaidToPlayAtNormalSpeed() {
    show(
      devices(
        row("uuid:a", "Küche", "Sonos One", isConnected = true),
        connectedUdn = "uuid:a",
        volumePercent = 40,
      ),
    )

    composeRule.onNodeWithText(CAST_VOLUME_LABEL).assertIsDisplayed()
    // Task 5: a renderer accepts only `Speed = "1"`, so a per-item speed is not delivered. A
    // setting that silently does nothing is worse than one that is refused.
    composeRule.onNodeWithText(CAST_SPEED_LIMIT_NOTICE).assertIsDisplayed()
  }

  @Test
  fun draggingTheVolumeSliderReportsTheNewPositionAndNotAConstant() {
    show(
      devices(
        row("uuid:a", "Küche", "Sonos One", isConnected = true),
        connectedUdn = "uuid:a",
        volumePercent = 40,
      ),
    )

    slider().performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
    slider().performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }

    assertThat(volumes).containsExactly(0.25f, 0.75f)
  }

  // ---- failures --------------------------------------------------------------------------------

  @Test
  fun aFailureShowsItsSentenceAndOffersToTryAgain() {
    val failure = castFailure(CastSessionState.Lost("Küche", 42_000L, "track-1"))!!
    show(failure)

    composeRule.onNodeWithText(failure.message).assertIsDisplayed()
    // And nothing to tap that would cast: the list is gone, which is the point of the state.
    composeRule.onNodeWithText("Küche", substring = false).assertDoesNotExist()

    composeRule.onNodeWithText(CAST_RETRY_LABEL).performClick()
    assertThat(actions).containsExactly("refresh")
  }

  // ---- the security property -------------------------------------------------------------------

  /**
   * **No URL reaches the screen.**
   *
   * `CastDevice` carries three of them and `CastUiState` deliberately carries none, so this is the
   * assertion that keeps that true through a rendering: a description URL in a label is a device's
   * address in a screenshot and in an accessibility read-out, and the same surface is where a
   * *stream* URL — which carries the user's Subsonic `u`, `t` and `s` — would land if one ever
   * reached this module. Asserted over the whole semantics tree rather than the node it would most
   * likely land on, because a leak into any node is the same leak.
   */
  @Test
  fun theSheetNeverRendersADeviceUrl() {
    val real = device("uuid:a", "Küche", "Sonos One", isSonos = true)
    val state = castUiState(DiscoveryResult(listOf(real), listOf(REMEMBERED)), CastSessionState.Idle)
    show(state)

    val tree = composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)
    assertThat(tree).doesNotContain(DEVICE_HOST)
    assertThat(tree).doesNotContain("http://")
    assertThat(tree).doesNotContain("device_description")
    // A guard on the guard: an empty or truncated dump would pass all three over nothing at all,
    // which is this project's recorded "assertion that cannot fail".
    assertThat(tree).contains("Küche")
  }

  // ---- the Hilt-bound entry point ---------------------------------------------------------------

  /**
   * The stateful overload and the `ModalBottomSheet` around it, over a real [CastViewModel].
   *
   * This is the hop no JVM test can see: `uiState.collectAsStateWithLifecycle()` into the stateless
   * overload, inside a sheet that is a separate window. The only line left uncovered in this file
   * afterwards is the `hiltViewModel()` default argument, which needs a Hilt graph and therefore
   * `:app`'s journey.
   */
  @Test
  fun theEntryPointRendersWhatTheViewModelFound() {
    val found = DiscoveryResult(listOf(device("uuid:a", "Küche", "Sonos One", isSonos = true)), emptyList())
    val viewModel = CastViewModel(CastPickerFakes.FakeDiscovery(found), CastPickerFakes.FakeControl())
    composeRule.setContent { CastPickerSheet(onDismiss = {}, viewModel = viewModel) }

    viewModel.open()

    composeRule.waitUntil(WAIT_MILLIS) {
      composeRule.onAllNodesWithText("Küche").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Sonos One").assertIsDisplayed()
  }

  // ---- fixtures --------------------------------------------------------------------------------

  private fun slider() =
    composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

  private fun row(
    udn: String,
    name: String,
    subtitle: String?,
    isSonos: Boolean = false,
    isConnected: Boolean = false,
  ) = CastDeviceRow(udn = udn, name = name, subtitle = subtitle, isSonos = isSonos, isConnected = isConnected)

  private fun devices(
    vararg rows: CastDeviceRow,
    unreachable: List<String> = emptyList(),
    connectedUdn: String? = null,
    volumePercent: Int? = null,
  ) = CastUiState.Devices(rows.toList(), unreachable, connectedUdn, volumePercent)


  @Test
  fun noTwoSpeakerRowsFightOverTheSamePixels() {
    // **Both selectable speakers report no model**, and that is the fixture doing work rather than
    // being tidy. Two generic DLNA renderers is an ordinary living room and it is the only shape
    // this sweep can see: a subtitle makes a row two lines and about 56dp, which needs no expansion
    // and so cannot collide with anything. Measured while writing this -- with one short row and
    // one subtitled one, deleting `DeviceRow`'s `heightIn` left this test **green** while the
    // height assertion above went red at 32.38dp. A sweep is only as good as the crowd it is given.
    //
    // The connected speaker is here for the crowd, not as a target: its `onClick` is null, so it
    // carries no click action and never enters the sweep.
    show(
      devices(
        row("uuid:a", "Kuche", "Sonos One", isSonos = true, isConnected = true),
        row("uuid:b", "Study Amp", null),
        row("uuid:c", "Kitchen Display", null),
        connectedUdn = "uuid:a",
      ),
    )

    composeRule.assertEveryTapTargetIsBigEnough()
  }

  private companion object {
    val REMEMBERED = RememberedRenderer("uuid:z", "Bedroom", "http://${DEVICE_HOST}:1400/xml/d.xml")

    /** Long enough for a `viewModelScope` round trip on a loaded emulator, short enough to fail. */
    const val WAIT_MILLIS = 5_000L
  }
}
