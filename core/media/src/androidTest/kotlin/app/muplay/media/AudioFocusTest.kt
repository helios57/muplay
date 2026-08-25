package app.muplay.media

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focus and becoming-noisy, observed as **playback that stopped**, never as a flag that was set.
 *
 * `handleAudioFocus = true` and `setHandleAudioBecomingNoisy(true)` are both single builder calls in
 * [MuPlayerFactory], and a test that asserted they were called would be satisfied by a player that
 * ignores both. What is asserted here instead is that `isPlaying` goes false **and the position
 * stops advancing** when another app takes focus and when the system says the audio route became
 * noisy — and, for the transient case, that both resume when focus comes back.
 *
 * The player is built through [MuPlayerFactory] like every other player in this project: it is the
 * only construction site, `PlayerConstructionTest` refuses a second one, and a hand-built player
 * here would be a player with different audio attributes from the one that ships — i.e. this
 * suite's entire subject, quietly substituted.
 *
 * ### `!isPlaying` alone is not "paused", and that is a measurement rather than a precaution
 *
 * The seeded fixture is a **five-second** track. Written as `await { !player.isPlaying }`, three of
 * the four tests below were green with `handleAudioFocus = false` — the player simply reached the
 * end of the track inside the wait, `isPlaying` went false, and the position stopped advancing for
 * the most ordinary reason there is. That is the vacuous-assertion class this project keeps
 * finding, reached from a direction nobody had written down: a *timeout* was doing the work that
 * looked like a *pause*.
 *
 * So every wait here is for **paused and still ready to play** — `STATE_READY` with `isPlaying`
 * false — which end-of-track cannot satisfy (`STATE_ENDED`), and each carries [PAUSE_TIMEOUT_MS]
 * rather than the harness default so a player that never pauses fails in ten seconds instead of
 * thirty. Measured both ways: see this task's report for the mutation transcript.
 */
@RunWith(AndroidJUnit4::class)
class AudioFocusTest {

  private lateinit var context: Context
  private lateinit var audioManager: AudioManager
  private lateinit var server: MockWebServer
  private lateinit var harness: PlayerHarness
  private lateinit var cacheDir: File
  private lateinit var audio: ByteArray
  private var focusRequest: AudioFocusRequest? = null
  private var playerReleased = false

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    audioManager = context.getSystemService(AudioManager::class.java)
    audio = runBlocking { RealTrackBytes.twoDifferentTracks().first }
    assertThat(audio.size).isGreaterThan(1000)

    server = MockWebServer()
    server.start()
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "audio/mpeg")
        .addHeader("Accept-Ranges", "bytes")
        .body(Buffer().write(audio))
        .build(),
    )

    // A per-test directory: `SimpleCache` refuses a second live instance on one folder, and a
    // shared folder would make these tests depend on the order they ran in.
    cacheDir = File(context.cacheDir, "focus-test-${System.nanoTime()}")
    val cache = MediaCache.create(context, cacheDir)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness = PlayerHarness(
        MuPlayerFactory(
          context = context,
          dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), cache),
          loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        ).create(),
      )
      harness.player.setMediaItem(
        MediaItem.Builder()
          .setUri(server.url("/stream").toString())
          // Required, not decoration: `TrackIdCacheKeyFactory` refuses a `DataSpec` with no key at
          // all, so an item without one fails the load rather than the focus assertion.
          .setCustomCacheKey("focus-test")
          .build(),
      )
      harness.player.prepare()
      harness.player.play()
    }
    // Real audio actually rendering before any of this asks it to stop. Without this the tests
    // below would be satisfied by a player that never started.
    harness.awaitPositionAtLeast(500L)
  }

  @After
  fun tearDown() {
    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    if (::harness.isInitialized && !playerReleased) harness.release()
    if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
    if (::server.isInitialized) server.close()
  }

  @Test
  fun anotherAppTakingTransientFocusPausesPlayback() {
    takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)

    val paused = awaitPausedNotFinished("a transient focus loss")
    Thread.sleep(1_000L)
    // Not merely `isPlaying == false`: the position must actually have stopped moving. A player
    // reporting paused while its clock ran would look identical to the first assertion alone.
    assertThat(harness.onMain { harness.player.currentPosition }).isEqualTo(paused)
  }

  @Test
  fun givingTransientFocusBackResumesPlayback() {
    takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    val paused = awaitPausedNotFinished("a transient focus loss")

    abandonFocus()

    harness.await("playback to resume after focus is returned") { harness.player.isPlaying }
    harness.await("the position to move past where it paused") {
      harness.player.currentPosition > paused
    }
  }

  @Test
  fun aPermanentFocusLossStopsPlaybackAndKeepsItStopped() {
    // The control that makes the resume test mean something: transient and permanent must produce
    // different outcomes, or "resumed" is just "never really paused".
    takeFocus(AudioManager.AUDIOFOCUS_GAIN)

    awaitPausedNotFinished("a permanent focus loss")
    abandonFocus()
    Thread.sleep(2_000L)
    assertThat(harness.onMain { harness.player.isPlaying }).isFalse
  }

  /**
   * The becoming-noisy receiver is **registered with `ActivityManagerService`** for as long as the
   * shipping player exists, and given up when it is released.
   *
   * ### Why this is not the pause assertion it should be
   *
   * `ACTION_AUDIO_BECOMING_NOISY` is a protected broadcast, so an app cannot send it. This test was
   * written to drive the real one through `UiAutomation.executeShellCommand("am broadcast -a
   * android.media.AUDIO_BECOMING_NOISY")`, on the stated premise that the `shell` uid is on
   * `ActivityManagerService`'s allow-list for protected broadcasts. **On this platform it is not.**
   * Measured on `muplay37`, Android 17 / API 37, by running that exact command by hand:
   *
   * ```
   * Broadcasting: Intent { act=android.media.AUDIO_BECOMING_NOISY flg=0x400000 }
   * java.lang.SecurityException: Permission Denial: not allowed to send broadcast
   *   android.media.AUDIO_BECOMING_NOISY from pid=15663, uid=2000
   *     at com.android.server.am.BroadcastController.broadcastIntentLockedTraced(...)
   * ```
   *
   * `executeShellCommand` hands back the shell's *stdout* and the test closed it unread, so that
   * denial was invisible — and the test then passed anyway, because on a five-second fixture the
   * track simply ended inside the wait. Two vacuities stacked: a broadcast that was never sent, and
   * an `isPlaying == false` produced by end-of-track. Both are now impossible here — the wait
   * requires `STATE_READY`, and this test no longer pretends to send anything.
   *
   * There is no way to unplug headphones on an emulator, no A2DP device to disconnect, and
   * `adb root` is not available on a shared device, so **the pause itself cannot be observed on
   * this hardware by any means available to this project.**
   *
   * ### What this does observe, and what it does not
   *
   * It observes `ActivityManagerService`'s own registry — state outside this app's code, read back
   * through `dumpsys` — showing this process holding a receiver for exactly that action, and losing
   * it when the player is released. That is the whole of the mechanism `setHandleAudioBecomingNoisy`
   * controls except the last step, and the last step is Media3's own
   * `AudioBecomingNoisyManager` calling `setPlayWhenReady(false, ..._AUDIO_BECOMING_NOISY)`, which
   * is Media3's code and Media3's tests, not this project's.
   *
   * It does **not** observe playback pausing. It is weaker than the three focus tests above, and it
   * is kept rather than deleted because it goes red on the one mutation that matters
   * (`setHandleAudioBecomingNoisy(false)` — measured, see this task's report) while an assertion on
   * the builder flag would not be an observation at all. The release half is the control: without
   * it, a receiver registered by some other player in this test process would satisfy the first
   * assertion.
   */
  @Test
  fun theShippingPlayerHoldsAnAudioBecomingNoisyReceiverUntilItIsReleased() {
    assertThat(registeredBroadcastActions())
      .describedAs("actions %s has registered with ActivityManagerService", context.packageName)
      .contains(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

    harness.release()
    playerReleased = true

    // Unregistering is posted to Media3's background handler, so it is polled rather than sampled.
    val deadline = System.currentTimeMillis() + PAUSE_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      if (AudioManager.ACTION_AUDIO_BECOMING_NOISY !in registeredBroadcastActions()) return
      Thread.sleep(200L)
    }
    throw AssertionError(
      "the becoming-noisy receiver was still registered ${PAUSE_TIMEOUT_MS}ms after the player " +
        "was released, so the assertion above cannot be attributed to this player",
    )
  }

  /**
   * Every broadcast action this process currently has a **registered** receiver for, read out of
   * `ActivityManagerService` rather than out of this app's own objects.
   *
   * Scoped to the `Registered Receivers:` section: the rest of `dumpsys activity broadcasts` is
   * broadcast *history*, where the action string appears for broadcasts that were merely delivered
   * somewhere, and a substring search over the whole dump would match those and never fail.
   */
  private fun registeredBroadcastActions(): Set<String> {
    val command = "dumpsys activity broadcasts ${context.packageName}"
    val actions = mutableSetOf<String>()
    var inRegisteredReceivers = false
    ParcelFileDescriptor.AutoCloseInputStream(
      InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().useLines { lines ->
      for (line in lines) {
        val trimmed = line.trim()
        when {
          trimmed == "Registered Receivers:" -> inRegisteredReceivers = true
          trimmed == "Receiver Resolver Table:" -> inRegisteredReceivers = false
          inRegisteredReceivers && trimmed.startsWith("Action: \"") ->
            actions += trimmed.removePrefix("Action: \"").removeSuffix("\"")
        }
      }
    }
    // A parse that found nothing at all would make `contains` fail for the wrong reason and
    // `!in` pass for the wrong reason -- the second of which is the release-half assertion.
    check(inRegisteredReceivers || actions.isNotEmpty()) {
      "`$command` produced no Registered Receivers section this parser understood"
    }
    return actions
  }

  /**
   * Waits until the player is **paused and still ready to play**, and returns the position it
   * stopped at.
   *
   * `STATE_READY` is the whole point: `isPlaying` also goes false at `STATE_ENDED`, and on this
   * five-second fixture a player that ignored [reason] entirely reaches the end of the track well
   * inside a thirty-second wait. Asserted as a state rather than as a deadline, because a deadline
   * is a flake on a loaded emulator and this is not.
   */
  private fun awaitPausedNotFinished(reason: String): Long {
    harness.await("playback to pause for $reason, still ready to play", PAUSE_TIMEOUT_MS) {
      !harness.player.isPlaying && harness.player.playbackState == Player.STATE_READY
    }
    return harness.onMain { harness.player.currentPosition }
  }

  private fun takeFocus(gain: Int) {
    val request = AudioFocusRequest.Builder(gain)
      .setAudioAttributes(
        PlatformAudioAttributes.Builder()
          .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
          .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
          .build(),
      )
      .setOnAudioFocusChangeListener { }
      .build()
    focusRequest = request
    val result = audioManager.requestAudioFocus(request)
    // If the request itself was refused, every assertion below would be testing nothing.
    assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
  }

  private fun abandonFocus() {
    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    focusRequest = null
  }

  private companion object {
    /**
     * Ten seconds, not the harness's thirty: a real focus pause lands in well under a second, and
     * the only thing a longer wait buys is more time for the five-second fixture to end on its own
     * and look like one.
     */
    const val PAUSE_TIMEOUT_MS = 10_000L
  }
}
