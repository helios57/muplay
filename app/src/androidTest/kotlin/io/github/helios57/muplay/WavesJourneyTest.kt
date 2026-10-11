package io.github.helios57.muplay

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.EntryPointAccessors
import io.github.helios57.muplay.binaural.BinauralProfile
import io.github.helios57.muplay.binaural.EndBehaviour
import io.github.helios57.muplay.binaural.Presets
import io.github.helios57.muplay.binaural.Segment
import io.github.helios57.muplay.waves.BinauralPlayback
import io.github.helios57.muplay.waves.BinauralStatus
import io.github.helios57.muplay.waves.WavesEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The binaural generator end to end, on the application's own singletons: real `AudioTrack`
 * output, the real foreground service and its notification, and the Waves tab driving them.
 */
@RunWith(AndroidJUnit4::class)
class WavesJourneyTest {

  @get:Rule(order = 0)
  val notificationPermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

  @get:Rule(order = 1)
  val composeRule = createAndroidComposeRule<MainActivity>()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val waves = EntryPointAccessors.fromApplication(context, WavesEntryPoint::class.java)
  private val playback: BinauralPlayback = waves.binauralPlayback()

  @After
  fun tearDown(): Unit = runBlocking {
    // The player is a process-wide singleton; leave it idle for whichever class runs next.
    playback.stop()
    withTimeout(STOP_TIMEOUT_MILLIS) { playback.status.first { it == BinauralStatus.Idle } }
  }

  @Test
  fun aShortProgramPlaysPostsItsNotificationAndEndsByItself(): Unit = runBlocking {
    playback.play(SHORT)

    val playing = withTimeout(START_TIMEOUT_MILLIS) {
      playback.status.first { it is BinauralStatus.Playing && it.positionSeconds >= 1.0 }
    } as BinauralStatus.Playing
    assertThat(playing.profile.id).isEqualTo(SHORT.id)
    // The position is the synthesiser's frame count, so it only moves while the track is consuming.
    assertThat(binauralNotificationPosted()).describedAs("the foreground notification").isTrue

    withTimeout(SHORT_RUNS_OUT_MILLIS) { playback.status.first { it == BinauralStatus.Idle } }
    composeRule.waitUntil(STOP_TIMEOUT_MILLIS) { !binauralNotificationPosted() }
  }

  @Test
  fun stopFadesOutAndGoesIdleWithinTheFade(): Unit = runBlocking {
    playback.play(Presets.focus)
    withTimeout(START_TIMEOUT_MILLIS) { playback.status.first { it is BinauralStatus.Playing } }

    playback.stop()

    val stopping = withTimeout(START_TIMEOUT_MILLIS) {
      playback.status.first { it is BinauralStatus.Playing && it.stopping }
    }
    assertThat(stopping).isInstanceOf(BinauralStatus.Playing::class.java)
    withTimeout(STOP_TIMEOUT_MILLIS) { playback.status.first { it == BinauralStatus.Idle } }
  }

  @Test
  fun theWavesTabListsTheProfilesAndPlaysConcentrateAtTheOwnersTones() {
    runBlocking { waves.profileLibrary().reset("concentrate") }
    composeRule.reachLibraryScreen()
    composeRule.onNodeWithText("Waves").performClick()
    composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Programs").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onAllNodesWithText("I understand").fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
      composeRule.onNodeWithText("I understand").performClick()
    }

    for (name in listOf("Power nap", "Meditate", "Focus", "Concentrate", "Creative", "Sleep", "Deep sleep")) {
      composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
      composeRule.onNodeWithText(name).assertExists(name)
    }

    composeRule.onNodeWithText("Concentrate").performClick()

    composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
      (playback.status.value as? BinauralStatus.Playing)?.profile?.id == "concentrate"
    }
    composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Stop", substring = true))
    composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("SMR · 13.46 Hz").fetchSemanticsNodes().isNotEmpty()
    }
    // Twice on screen: under the row, and on the now-playing card.
    assertThat(composeRule.onAllNodesWithText("Left 158.44 Hz · right 144.98 Hz").fetchSemanticsNodes())
      .hasSizeGreaterThanOrEqualTo(1)

    composeRule.onNodeWithContentDescription("Beat frequency", substring = true).assertExists()
    composeRule.onNodeWithText("Stop", substring = true).performClick()
    runBlocking { withTimeout(STOP_TIMEOUT_MILLIS) { playback.status.first { it == BinauralStatus.Idle } } }
  }

  private fun binauralNotificationPosted(): Boolean =
    context.getSystemService(NotificationManager::class.java).activeNotifications
      .any { it.notification.channelId == "binaural" }

  private companion object {
    /** Four seconds of tone with one-second fades: long enough to observe, short enough to wait out. */
    val SHORT = BinauralProfile(
      id = "journey-short", name = "Journey short",
      segments = listOf(Segment(10.0, 10.0, 200.0, 200.0, 4.0)),
      fadeInSeconds = 1.0, fadeOutSeconds = 1.0, endBehaviour = EndBehaviour.STOP,
    )
    const val START_TIMEOUT_MILLIS = 5_000L
    const val SHORT_RUNS_OUT_MILLIS = 10_000L
    const val STOP_TIMEOUT_MILLIS = 8_000L
    const val UI_TIMEOUT_MILLIS = 15_000L
  }
}
