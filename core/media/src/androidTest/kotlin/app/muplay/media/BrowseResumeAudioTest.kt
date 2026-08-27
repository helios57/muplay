package app.muplay.media

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of "resume" this repository could not yet observe: a stored **second**, reached by a
 * real decoder, on a queue shaped exactly like the one a tapped browse row produces.
 *
 * ### Why this file exists, and what it does not claim
 *
 * Plan 5 Task 5 makes a tapped book expand to its own files positioned at the file the listener was
 * in; `app.muplay.CarResumeJourneyTest` proves that end to end, over IPC, against the real service.
 * The second *within* that file is `ResumePolicy`'s, and the policy `MediaModule` binds today is
 * `NeverResume` -- Plan 4 Task 6 replaces that binding and had not landed when this was written.
 * So the shipped app, measured, resumes a book to the right **file** and starts that file at `0`.
 *
 * What that leaves unproven anywhere is the step after: that a non-zero position chosen by a policy
 * actually survives a real prepare over a real network stream and lands in the audio a listener
 * hears. `MuPlayerTest` proves the seam against `ExoPlayer`'s *masked* state, with URLs that never
 * load; this proves it against decoded audio, which is a different claim -- a seek that is silently
 * dropped during prepare, or a stream with no range support, presents identically in the first and
 * not in the second.
 *
 * ### What makes the assertion impossible to satisfy by waiting
 *
 * The fixture is [RESUME_POSITION_MS] into a book, and the wait for it is bounded by wall clock.
 * The discriminating assertion is not "the position reached 11.5 seconds" -- a player started at
 * zero reaches that in 11.5 seconds -- it is **`reached > elapsed`, by seconds**: the media
 * advanced further than the real time spent waiting for it, which only a seek can produce. A player
 * that ignored the policy satisfies `reached <= elapsed` always, and
 * [aQueueUnderNeverResumeIsStillNearTheStartAfterTheSameWallClock] measures that it does.
 *
 * `RESUME_POSITION_MS` also sits **on no chapter boundary**. `ci/seed-fixtures.sh` gives the
 * longest seeded book four chapters of 4/5/6/6 s, so its boundaries are 0, 4 000, 9 000 and
 * 15 000 ms; a stored position that sat on one would make "resumed exactly" and "resumed at the
 * chapter start" the same observation.
 *
 * **No stream URL is asserted or printed.** It is built by the one shared `SubsonicClient` in
 * [RealTrackBytes] and handed straight to the player.
 */
@RunWith(AndroidJUnit4::class)
class BrowseResumeAudioTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private val players = mutableListOf<ExoPlayer>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "resume-audio-${System.nanoTime()}")
  }

  @After
  fun tearDown() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { players.forEach(ExoPlayer::release) }
    players.clear()
    cacheDir.deleteRecursively()
  }

  @Test
  fun aQueueStartsAtThePositionTheResumePolicyChoseAndGoesOnFromThere() {
    val reading = play(
      ResumePolicy { _, index -> ResumeTarget(index, RESUME_POSITION_MS) },
      awaitMs = RESUME_POSITION_MS,
    )

    // The position audio actually reached, not "setMediaItems returned".
    assertThat(reading.reachedMs)
      .describedAs("the player reported %d ms after %d ms of real time", reading.reachedMs, reading.elapsedMs)
      .isGreaterThan(RESUME_POSITION_MS)
      .isLessThan(RESUME_POSITION_MS + SLACK_MS)

    // The assertion the fixture's length cannot satisfy: more media went by than real time did.
    // Measured on `muplay37`: reached 11 800 ms of media after roughly 1 300 ms of wall clock,
    // where a player started at zero can only ever report `reached <= elapsed`.
    assertThat(reading.reachedMs - reading.elapsedMs).isGreaterThan(SEEK_EVIDENCE_MS)

    // The two discriminating negatives. 0 is what a player that ignored the policy reports; 9 000
    // is where this book's third chapter starts, and a stored position on a boundary would make
    // "resumed exactly" and "resumed at the chapter start" the same observation.
    assertThat(reading.reachedMs).isNotEqualTo(0L)
    assertThat(reading.reachedMs).isNotIn(
      (CHAPTER_THREE_START_MS - 250L..CHAPTER_THREE_START_MS + 250L).toList(),
    )

    // And it is playing, not parked at the seek target: two reads separated by real time.
    assertThat(reading.laterMs).isGreaterThan(reading.reachedMs)
  }

  @Test
  fun aQueueUnderNeverResumeIsStillNearTheStartAfterTheSameWallClock() {
    // The measurement that makes the test above mean something. Same file, same player, same
    // bounded wait -- and the shipped `NeverResume` policy, which is what `MediaModule` binds
    // today. If this reached the stored position too, the assertion above would be measuring the
    // clock rather than the seek.
    val reading = play(NeverResume, awaitMs = LOW_WATER_MARK_MS)

    assertThat(reading.reachedMs).isLessThan(RESUME_POSITION_MS - SLACK_MS)
    // A player at zero can never be further into the media than the time spent playing it.
    assertThat(reading.reachedMs).isLessThanOrEqualTo(reading.elapsedMs + CLOCK_SLACK_MS)
    // ...and it did play, so this is not "nothing happened".
    assertThat(reading.laterMs).isGreaterThan(reading.reachedMs)
  }

  // ---- plumbing --------------------------------------------------------------------------------

  private data class Reading(val reachedMs: Long, val elapsedMs: Long, val laterMs: Long)

  /**
   * Plays the longest seeded book file through the **shipping** player under [policy] and reports
   * where it got to.
   *
   * The player is a real [MuPlayer] over a real `ExoPlayer` built by [MuPlayerFactory] -- the one
   * construction site `PlayerConstructionTest` allows -- so the retry policy, the renderers and the
   * data source are the shipping ones. The `MediaItem` is built by the production [MediaItems.of].
   */
  private fun play(policy: ResumePolicy, awaitMs: Long): Reading {
    val song = bookFile()
    val item = MediaItems.of(
      song = song,
      streamUri = RealTrackBytes.rawStreamUrl(song),
      artworkUri = null,
      isAudiobook = true,
      format = StreamFormat.Raw,
    )

    lateinit var harness: PlayerHarness
    lateinit var player: MuPlayer
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val inner = MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), MediaCache.create(context, cacheDir)),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        resumePolicy = policy,
      ).createExoPlayer().also { players += it }
      harness = PlayerHarness(inner)
      // `MuPlayer` over that same player rather than `factory.create()`, which would build a second
      // one this file could not then wait on. It is the production class, constructed the
      // production way, with the production policy seam.
      player = MuPlayer(inner, policy)
    }

    val startedAt = SystemClock.elapsedRealtime()
    harness.onMain {
      // The overload a session uses for a queue with an index. The caller's position is `0`, and
      // `MuPlayer` discards it -- that is the whole seam.
      player.setMediaItems(mutableListOf<MediaItem>(item), 0, 0L)
      player.prepare()
      player.play()
    }
    // **`isPlaying` and a position strictly past the target, not merely a position at it.**
    // `ExoPlayer` masks a seek synchronously, so waiting for `currentPosition >= 11 500` alone is
    // satisfied 10 ms after `play()` by the masked value -- measured, and it is the whole
    // difference between "the seek was applied" and "audio is coming out at that second". Only a
    // renderer clock moves the position *past* where it was put.
    harness.await("isPlaying with the position past ${awaitMs}ms", timeoutMs = WAIT_BUDGET_MS) {
      harness.player.isPlaying && harness.player.currentPosition > awaitMs
    }
    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
    val reachedMs = harness.onMain { harness.player.currentPosition }

    Thread.sleep(ADVANCE_MILLIS)
    harness.assertNoPlaybackError()
    return Reading(reachedMs, elapsedMs, harness.onMain { harness.player.currentPosition })
  }

  /**
   * The longest seeded audiobook file, read from the container rather than named.
   *
   * `check`ed rather than assumed: this suite's whole argument is that the stored position is well
   * inside the file, so a corpus whose longest book is shorter than that has to fail loudly rather
   * than quietly measure something else.
   */
  private fun bookFile(): Song {
    val song = runBlocking { RealTrackBytes.audiobookFiles() }.first()
    check(song.durationSeconds * 1_000L > RESUME_POSITION_MS + SLACK_MS) {
      "the longest seeded audiobook file is ${song.durationSeconds}s, which is not long enough to " +
        "hold a stored position of ${RESUME_POSITION_MS}ms"
    }
    return song
  }

  private companion object {
    /** Inside the longest book's third chapter and **on no boundary**, on purpose. */
    const val RESUME_POSITION_MS = 11_500L
    const val CHAPTER_THREE_START_MS = 9_000L

    /**
     * How far past the stored position playback may have advanced by the time it is first read.
     * Generous, because a cold start on a CI emulator includes an HTTP round trip and a decoder
     * warm-up -- and small enough that 0 and 9 000 are both well outside it.
     */
    const val SLACK_MS = 2_500L

    /**
     * The margin by which the media must be ahead of the wall clock for a seek to be the only
     * explanation. A player started at zero has `reached <= elapsed`; this demands five seconds
     * more than that, which no amount of waiting produces.
     */
    const val SEEK_EVIDENCE_MS = 5_000L

    /** The position the control waits for: a second in, which a player at zero reaches normally. */
    const val LOW_WATER_MARK_MS = 1_000L

    /** `currentPosition` is extrapolated from a clock, so it may lead real time by a little. */
    const val CLOCK_SLACK_MS = 750L

    const val ADVANCE_MILLIS = 1_200L

    /**
     * The bounded wait, chosen so the claim survives it: it is comfortably under
     * [RESUME_POSITION_MS], so a player that started at zero cannot reach the awaited position
     * inside it at all -- it times out, and the failure names the position it never reached.
     */
    const val WAIT_BUDGET_MS = 8_000L
  }
}
