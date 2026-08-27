package app.muplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.CredentialStoreEntryPoint
import app.muplay.media.PlaybackConnection
import app.muplay.media.PlaybackEntryPoint
import app.muplay.media.PlaybackLauncher
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A transcoded seek **over the session**, from a real `MediaController` bound to the real
 * `MuPlaybackService`.
 *
 * ### The layer this exists for, and why `:core:media`'s suite cannot cover it
 *
 * `TranscodeSeekPlaybackTest` builds a `MuPlayer` and calls `seekTo` on it directly. That proves
 * the seam, and it proves nothing about the two things between the seam and a user's finger:
 *
 *  * **`MediaControllerImplBase.seekTo(long)` starts with
 *    `if (!isPlayerCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return;`** -- read off the
 *    1.11.0 bytecode. The set it checks is the one the *session pushed to it*, which the session
 *    takes from a `Player.Listener` callback carrying the **wrapped** player's commands. A
 *    `ForwardingPlayer` that answers differently has to say so itself, or every seek from the app's
 *    own UI is dropped in silence.
 *  * **`PlaybackLauncher` is the only place `TranscodeOffsetSupport.refreshIfUnknown()` runs.**
 *    Without it the gate answers "not supported" forever and the seek is withdrawn -- correct
 *    behaviour for a server that cannot do it, and a silent removal of the whole feature here.
 *    (It ran in `MuPlaybackService.onCreate` first, which is *earlier than signing in*: the service
 *    is created the moment anything binds a `MediaController`, the negotiation failed, and nothing
 *    retried it. Measured -- this test and the journey both went red.)
 *
 * Both failures look identical from a journey test: the bar moves and the readout does not. This
 * class is where they are told apart, which is why it asserts the command set *before* it asserts
 * the position.
 *
 * **No stream URL is ever read.** They carry `u`, `s=salt` and `t=md5(password+salt)`.
 */
@RunWith(AndroidJUnit4::class)
class TranscodeSeekSessionTest {

  private lateinit var context: Context
  private lateinit var connection: PlaybackConnection
  private lateinit var controller: MediaController
  private lateinit var opus: Song

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    val client = SubsonicClient(SubsonicCredentials(NAVIDROME_URL, USERNAME, PASSWORD))
    runBlocking {
      credentialStore().save(SubsonicCredentials(NAVIDROME_URL, USERNAME, PASSWORD))
      opus = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500)
        .single { it.suffix.equals(OPUS_SUFFIX, ignoreCase = true) }
    }
    warmTheOffsetTranscode(client)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      connection = PlaybackConnection(context)
    }
    controller = runBlocking { connection.controller() }
  }

  @After
  fun tearDown() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      controller.stop()
      controller.clearMediaItems()
      connection.release()
    }
  }

  /**
   * The session offers the seek on a forced transcode, and a seek made through it lands.
   *
   * The player is **paused** before the seek and the pause is asserted, so nothing here can be
   * satisfied by playback advancing on its own: a stopped clock cannot reach twenty-four seconds,
   * and the fixture is thirty seconds long so even a running one could not inside the bound below.
   */
  @Test
  fun aControllerCanSeekAForcedTranscodeAndTheSessionReportsWhereItLanded() {
    runBlocking { PlaybackLauncher(queueRepository(), connection).play(listOf(opus), 0) }
    awaitPositionAtLeast(500L)

    // First: the command reached the controller at all. Without this the position assertion below
    // fails identically whether the seek was refused here or performed and mis-reported.
    assertThat(onMain { controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .describedAs("the session's own answer for a forced transcode")
      .isTrue

    onMain { controller.pause() }
    awaitState("the controller to report paused") { !onMain { controller.isPlaying } }
    val whilePaused = onMain { controller.currentPosition }
    Thread.sleep(PAUSE_OBSERVATION_MS)
    assertThat(onMain { controller.currentPosition })
      .describedAs("the position ${PAUSE_OBSERVATION_MS}ms after pausing")
      .isEqualTo(whilePaused)

    onMain { controller.seekTo(SEEK_TARGET_MS) }

    awaitState("the session to report the seek", SEEK_SETTLE_MS) {
      onMain { controller.currentPosition } >= SEEK_TARGET_MS - POSITION_SLACK_MS
    }
    assertThat(onMain { controller.currentPosition })
      .describedAs("the position the session reports after a seek to ${SEEK_TARGET_MS}ms")
      .isBetween(SEEK_TARGET_MS - POSITION_SLACK_MS, SEEK_TARGET_MS + POSITION_SLACK_MS)
    // ...and the whole track's length, not the six seconds left of the re-issued stream.
    //
    // This only means anything because [warmTheOffsetTranscode] ran: Navidrome answers a cold
    // `format=mp3&timeOffset=N` request chunked with no `Content-Length`, the extractor then has no
    // duration at all, and `MuPlayer.getDuration` correctly reports `C.TIME_UNSET` -- which is what
    // this assertion saw, once, on a run where the container had evicted the entry. Warming it is
    // the difference between an assertion and a coin toss.
    assertThat(onMain { controller.duration })
      .describedAs("the duration the session reports after the seek")
      .isBetween(FIXTURE_DURATION_MS - DURATION_SLACK_MS, FIXTURE_DURATION_MS + DURATION_SLACK_MS)
  }

  /**
   * Fetches the offset transcode over plain HTTP so Navidrome's transcoding cache holds the entry
   * the player is about to ask for, and refuses to run if it did not warm.
   *
   * The cache is keyed on (track, requested bitrate, **offset**) and not on the auth salt --
   * measured against the container -- so this warms exactly the key production will use.
   * `MediaItems`/`QueueRepository` ask for `StreamFormat.forSuffix(.., DEFAULT_TRANSCODE_BITRATE_KBPS)`,
   * which for an `opus` suffix is `Mp3(192)`; asking for any other cap here would warm a different
   * entry and prove nothing.
   */
  private fun warmTheOffsetTranscode(client: SubsonicClient) {
    val format = StreamFormat.forSuffix(opus.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)
    val seconds = (SEEK_TARGET_MS / 1000L).toInt()
    val http = OkHttpClient()
    repeat(2) {
      http.newCall(Request.Builder().url(client.streamUrl(opus.id, format, seconds)).build())
        .execute().use { checkNotNull(it.body).bytes() }
    }
    val warm = http.newCall(
      Request.Builder().url(client.streamUrl(opus.id, format, seconds)).build(),
    ).execute().use { it.header("Accept-Ranges") == "bytes" && it.header("Content-Length") != null }
    check(warm) {
      "the transcoding cache entry for (Offset Track, 192 kbps, ${seconds}s) did not warm; this " +
        "test's duration assertion has no premise. Do NOT flush the cache -- see CLAUDE.md."
    }
  }

  private fun awaitPositionAtLeast(positionMs: Long) =
    awaitState("the position to reach ${positionMs}ms") {
      onMain { controller.currentPosition } >= positionMs
    }

  private fun awaitState(description: String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(POLL_MS)
    }
    throw AssertionError(
      "timed out after ${timeoutMs}ms waiting for $description; " +
        "state=${onMain { controller.playbackState }} " +
        "isPlaying=${onMain { controller.isPlaying }} " +
        "position=${onMain { controller.currentPosition }} " +
        "duration=${onMain { controller.duration }} " +
        "canSeek=${onMain { controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }} " +
        "error=${onMain { controller.playerError }}",
    )
  }

  private fun <T> onMain(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private fun queueRepository() =
    EntryPointAccessors.fromApplication(context, PlaybackEntryPoint::class.java).queueRepository()

  private fun credentialStore() =
    EntryPointAccessors.fromApplication(context, CredentialStoreEntryPoint::class.java)
      .credentialStore()

  private companion object {
    /** Reached from inside the emulator via `adb reverse tcp:4533 tcp:4533`. */
    const val NAVIDROME_URL = "http://localhost:4533"
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"
    const val MUSIC_LIBRARY_ID = 1
    const val OPUS_SUFFIX = "opus"

    /** Into the fixture's loud third region, and twenty-four seconds a paused clock cannot reach. */
    const val SEEK_TARGET_MS = 24_000L
    const val POSITION_SLACK_MS = 1_500L

    const val FIXTURE_DURATION_MS = 30_000L
    const val DURATION_SLACK_MS = 3_000L

    /** Bounded far under the twenty-four seconds ordinary playback would need to get there. */
    const val SEEK_SETTLE_MS = 5_000L

    /** Longer than a position update's own cadence, so a running clock would have moved. */
    const val PAUSE_OBSERVATION_MS = 1_500L

    const val TIMEOUT_MS = 30_000L
    const val POLL_MS = 50L
  }
}
