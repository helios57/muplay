package app.muplay.media

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.MediaItem
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
    if (::harness.isInitialized) harness.release()
    if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
    if (::server.isInitialized) server.close()
  }

  @Test
  fun anotherAppTakingTransientFocusPausesPlayback() {
    takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)

    harness.await("playback to pause for a transient focus loss") { !harness.player.isPlaying }
    val paused = harness.onMain { harness.player.currentPosition }
    Thread.sleep(1_000L)
    // Not merely `isPlaying == false`: the position must actually have stopped moving. A player
    // reporting paused while its clock ran would look identical to the first assertion alone.
    assertThat(harness.onMain { harness.player.currentPosition }).isEqualTo(paused)
  }

  @Test
  fun givingTransientFocusBackResumesPlayback() {
    takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    harness.await("playback to pause") { !harness.player.isPlaying }
    val paused = harness.onMain { harness.player.currentPosition }

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

    harness.await("playback to pause for a permanent focus loss") { !harness.player.isPlaying }
    abandonFocus()
    Thread.sleep(2_000L)
    assertThat(harness.onMain { harness.player.isPlaying }).isFalse
  }

  /**
   * `ACTION_AUDIO_BECOMING_NOISY` is a **protected broadcast**: an app cannot send it and
   * `sendBroadcast` throws `SecurityException`. The `shell` uid is on `ActivityManagerService`'s
   * allow-list, so this drives the real system broadcast through `UiAutomation` — which is as
   * close to unplugging headphones as an emulator gets, and closer than asserting a receiver was
   * registered.
   */
  @Test
  fun theAudioRouteBecomingNoisyPausesPlayback() {
    InstrumentationRegistry.getInstrumentation().uiAutomation
      .executeShellCommand("am broadcast -a android.media.AUDIO_BECOMING_NOISY")
      .close()

    harness.await("playback to pause when the audio route became noisy") { !harness.player.isPlaying }
    val paused = harness.onMain { harness.player.currentPosition }
    Thread.sleep(1_000L)
    assertThat(harness.onMain { harness.player.currentPosition }).isEqualTo(paused)
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
}
