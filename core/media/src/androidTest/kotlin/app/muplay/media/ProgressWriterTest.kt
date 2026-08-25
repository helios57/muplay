package app.muplay.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.MuPlayDatabase
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.model.ReplayGain
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first code in this project to write a `media_progress` row.
 *
 * Three groups, because they fail for three different reasons:
 *
 *  * **the write** -- a read-modify-write that must not clobber the columns this plan does not own,
 *    driven directly against a real in-memory Room;
 *  * **the seven persistence points** -- each callback invoked on its own against a player parked
 *    at a position only that callback could report, so every one of the seven is individually
 *    decisive;
 *  * **the wiring** -- a real `ExoPlayer` playing real audio off the real container, which is the
 *    only tier that can say `start()` actually attached the listener and that real positions reach
 *    the table.
 *
 * ### The five-second trap, and how every assertion below is arranged around it
 *
 * `CLAUDE.md`: the seeded fixtures are **five seconds** long, [ProgressWriter.TICK_MS] is five
 * seconds, and three Plan 3 device tests were green against the very mutation they existed to catch
 * because playback reached the awaited state on its own. Two rules follow, and both are applied
 * here without exception.
 *
 *  1. **The ticker must never be able to write the row an assertion is reading.** Every test in the
 *     persistence-point group runs against a writer that was never [ProgressWriter.start]ed, so no
 *     ticker exists at all -- the callbacks are invoked directly, which is exactly how the player
 *     invokes them. The real-playback group calls `start()` only *after* all buffering is over, and
 *     then finishes its observation inside [LISTENER_TIMEOUT_MS], well under one tick. The one test
 *     that is *about* the ticker uses a player that is not playing, so time cannot move the number
 *     it asserts.
 *  2. **Assert a value, not the arrival of a state.** Every row assertion below names an exact
 *     position or a band that only the callback under test could have produced. "A row exists" is
 *     never the assertion, because a writer that persisted a constant would satisfy it.
 */
@RunWith(AndroidJUnit4::class)
class ProgressWriterTest {

  private lateinit var context: Context
  private lateinit var db: MuPlayDatabase
  private lateinit var dao: MediaProgressDao
  private lateinit var scope: CoroutineScope
  private lateinit var cacheDir: File
  private lateinit var songs: List<Song>
  private val harnesses = mutableListOf<PlayerHarness>()

  /** Fixed, so a timestamp assertion is an equality rather than a range. */
  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(FIXED_NOW_MS), ZoneOffset.UTC)

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    dao = db.mediaProgressDao()
    // Main-thread-confined, and built from a `Handler` rather than from `Dispatchers.Main`: this is
    // the scope `MuPlaybackService` hands the writer, and the ticker reads the player, which may
    // only be read from the thread it was built on. Same construction and same stated reason as
    // `PlaybackConnection` -- see `core/media/build.gradle.kts` on why this module does not declare
    // `kotlinx-coroutines-android`.
    val mainExecutor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    scope = CoroutineScope(SupervisorJob() + mainExecutor.asCoroutineDispatcher())
    cacheDir = File(context.cacheDir, "progress-test-${System.nanoTime()}")
    songs = runBlocking { RealTrackBytes.musicTracks() }
  }

  @After
  fun tearDown() {
    harnesses.forEach { it.release() }
    harnesses.clear()
    scope.cancel()
    db.close()
    cacheDir.deleteRecursively()
  }

  // ---- the write ------------------------------------------------------------------------------

  @Test
  fun aFirstWriteCreatesARowWithThePositionTheClocksTimeAndTheDefaultsPlanFourReads() {
    // No player needed: the write is the unit under test here.
    val subject = ProgressWriter(NoOpPlayer(), dao, clock, scope)

    runBlocking { subject.write("song-1", positionMs = 12_345L, finished = false, gainDb = null) }

    val row = row("song-1")
    assertThat(row.positionMs).isEqualTo(12_345L)
    // The injected clock, not `System.currentTimeMillis()`. This is the project's only wall-clock
    // read, and the assertion is an equality precisely so that a direct read cannot pass it.
    assertThat(row.lastPlayedAtEpochMs).isEqualTo(FIXED_NOW_MS)
    assertThat(row.isFinished).isFalse
    // The three columns this plan does not write, on a row that did not exist. Plan 4 reads these
    // as "the values a book starts from", so they are part of this class's contract rather than
    // incidental: a `DEFAULT_SPEED` of anything but 1.0 would make every new book play at the wrong
    // speed the first time its row is read.
    assertThat(row.speed).isEqualTo(ProgressWriter.DEFAULT_SPEED)
    assertThat(row.speed).isEqualTo(1.0f)
    assertThat(row.gainDb).isEqualTo(ProgressWriter.DEFAULT_GAIN_DB)
    assertThat(row.gainDb).isEqualTo(0.0f)
    assertThat(row.skipSilence).isEqualTo(ProgressWriter.DEFAULT_SKIP_SILENCE)
    assertThat(row.skipSilence).isFalse
  }

  @Test
  fun thePositionWrittenIsThePositionGiven() {
    // Two observations. A `positionMs` hardcoded to anything passes at most one of them.
    val subject = ProgressWriter(NoOpPlayer(), dao, clock, scope)

    runBlocking {
      subject.write("song-1", positionMs = 1_000L, finished = false, gainDb = null)
      subject.write("song-2", positionMs = 9_999L, finished = false, gainDb = null)
    }

    assertThat(listOf(row("song-1").positionMs, row("song-2").positionMs))
      .containsExactly(1_000L, 9_999L)
    // ...and each landed on its own row, which is the whole "progress is a property of the item"
    // claim in its smallest form: one global row would have left one of these two ids absent.
    assertThat(runBlocking { dao.findAll() }.map { it.mediaId })
      .containsExactlyInAnyOrder("song-1", "song-2")
  }

  /**
   * The trap. `media_progress` carries `speed`, `skipSilence` and `gainDb`. `speed` and
   * `skipSilence` belong to the audiobook plan and this class never writes them; a writer that
   * constructs a fresh entity and upserts it resets a listener's per-book speed **every five
   * seconds**, which is a data-loss bug that no test of this class's own fields would ever catch.
   *
   * `gainDb` is now written -- Task 11 -- and it is asserted here too, in the case where the player
   * has **nothing loaded**: there is no item, so there is no gain to record, and the stored value
   * must survive rather than be reset to `DEFAULT_GAIN_DB`. The gain group below is where the
   * writing half is measured.
   *
   * Each preserved column is seeded at a value that is **not** its default, so a writer that
   * defaulted them fails on all three rather than passing on a coincidence.
   */
  @Test
  fun aWriteDoesNotClobberTheColumnsThisPlanDoesNotOwn() {
    runBlocking {
      dao.upsert(
        MediaProgressEntity(
          mediaId = "chapter-14",
          positionMs = 500L,
          isFinished = false,
          lastPlayedAtEpochMs = 1L,
          speed = 1.4f,
          skipSilence = true,
          gainDb = 6.0f,
        ),
      )
    }
    val subject = ProgressWriter(NoOpPlayer(), dao, clock, scope)

    runBlocking { subject.write("chapter-14", positionMs = 90_000L, finished = false, gainDb = null) }

    val row = row("chapter-14")
    assertThat(row.positionMs).isEqualTo(90_000L)
    assertThat(row.speed).isEqualTo(1.4f)
    assertThat(row.skipSilence).isTrue
    assertThat(row.gainDb).isEqualTo(6.0f)
  }

  /**
   * `isFinished` is set to `true` and never back to `false`. A ticker that wrote `false` would
   * un-finish a completed book on the next accidental tap. "Un-finish on replay" is real behaviour
   * and it belongs to the plan that has a UI to express it.
   */
  @Test
  fun finishedStaysFinished() {
    val subject = ProgressWriter(NoOpPlayer(), dao, clock, scope)

    runBlocking {
      subject.write("chapter-14", positionMs = 900_000L, finished = true, gainDb = null)
      subject.write("chapter-14", positionMs = 1_000L, finished = false, gainDb = null)
    }

    assertThat(row("chapter-14").isFinished).isTrue
    // ...and the position still moved, so "preserved" does not mean "frozen".
    assertThat(row("chapter-14").positionMs).isEqualTo(1_000L)
  }

  /**
   * A `Player` with no timeline reports `C.TIME_UNSET`, which is negative; a negative position in
   * this table sorts ahead of every real one and would make "resume where you were" mean "start
   * before the beginning".
   */
  @Test
  fun aNegativePositionIsClampedRatherThanStored() {
    val subject = ProgressWriter(NoOpPlayer(), dao, clock, scope)

    runBlocking { subject.write("song-1", positionMs = -9_000L, finished = false, gainDb = null) }

    assertThat(row("song-1").positionMs).isZero
  }

  // ---- gainDb: the column this task stops leaving as decoration ---------------------------------

  /**
   * The gain the item was **actually played at**, recorded on its row.
   *
   * Two items at two different tags, so a writer that stamped a constant -- including
   * `DEFAULT_GAIN_DB`, which is what this line did before Task 11 -- fails. The values are read out
   * of the `MediaItem`'s own extras, built by the production `MediaItems.of` from a `Song`, so this
   * is the same encoding `ReplayGainController` reads and not a second one written for a test.
   *
   * The writer is never [ProgressWriter.start]ed and the player is parked and never prepared, so
   * no ticker exists and nothing but the `write` under test can have produced these rows.
   */
  @Test
  fun aWriteRecordsTheGainThePlayingItemWasPlayedAt() {
    assertThat(gainWrittenFor(taggedItem("tagged-a", -6.5f))).isEqualTo(-6.5f)
    assertThat(gainWrittenFor(taggedItem("tagged-b", 2.75f))).isEqualTo(2.75f)
  }

  /**
   * The album-gain fallback reaches the row too, because the *item* already carries the resolved
   * decision -- `MediaItems` made it once, and this class re-derives nothing.
   */
  @Test
  fun anAlbumOnlyTagIsRecordedJustTheSame() {
    val item = MediaItems.of(
      song = gainSong("album-tagged").copy(replayGain = ReplayGain(null, -7.5f, null)),
      streamUri = "https://host/album-tagged",
      artworkUri = null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    )

    assertThat(gainWrittenFor(item)).isEqualTo(-7.5f)
  }

  /**
   * An **untagged** item has no gain to record, and the row's existing value survives.
   *
   * That is the difference between "record what was played" and "overwrite with a default", and it
   * is the case that would silently erase a real measurement: a listener plays a tagged track, then
   * plays it again after the tag is removed from the file, and the row still says what the last
   * tagged play used rather than `0.0`. Seeded at a non-default value so a defaulting writer fails.
   */
  @Test
  fun aWriteForAnUntaggedItemPreservesTheGainAlreadyOnTheRow() {
    runBlocking {
      dao.upsert(MediaProgressEntity("untagged", 100L, false, 1L, 1.0f, false, -4.25f))
    }
    val untagged = MediaItems.of(
      song = gainSong("untagged"),
      streamUri = "https://host/untagged",
      artworkUri = null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    )

    assertThat(gainWrittenFor(untagged)).isEqualTo(-4.25f)
  }

  /** A first write for an untagged item falls all the way through to the default. */
  @Test
  fun aFirstWriteForAnUntaggedItemGetsTheDefaultGain() {
    val untagged = MediaItems.of(
      song = gainSong("untagged-new"),
      streamUri = "https://host/untagged-new",
      artworkUri = null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    )

    assertThat(gainWrittenFor(untagged)).isEqualTo(ProgressWriter.DEFAULT_GAIN_DB)
  }

  /**
   * The tag **wins over** whatever the row already said, which is the other half of
   * [aWriteForAnUntaggedItemPreservesTheGainAlreadyOnTheRow]: a preserve-always writer would pass
   * that test and fail this one, and a stamp-always writer the reverse.
   */
  @Test
  fun aTagOverwritesAStaleGainOnAnExistingRow() {
    runBlocking {
      dao.upsert(MediaProgressEntity("tagged-c", 100L, false, 1L, 1.0f, false, -4.25f))
    }

    assertThat(gainWrittenFor(taggedItem("tagged-c", 3.5f))).isEqualTo(3.5f)
  }

  /**
   * A discontinuity records the gain of the item being **left**, not the gain of the one the player
   * has already moved to.
   *
   * The mirror image of [aDiscontinuityWritesTheItemBeingLeftAndNotTheOneArrivedAt], and the reason
   * `gainDbOf` takes an item instead of reading `player.currentMediaItem`: by the time this
   * callback arrives the player names the new item, so a writer that read the player here would
   * stamp the arriving track's gain onto the departing track's row. Two disjoint tags, so neither
   * answer can be a coincidence.
   */
  @Test
  fun aDiscontinuityRecordsTheGainOfTheItemBeingLeft() {
    val leaving = taggedItem("left-behind", -6.5f)
    val arrivedAt = taggedItem("arrived-at", 2.75f)
    val harness = rawHarness()
    harness.onMain { harness.player.setMediaItems(listOf(arrivedAt), 0, 1L) }
    val subject = ProgressWriter(harness.player, dao, clock, scope)

    harness.onMain {
      subject.onPositionDiscontinuity(
        positionInfoFor(leaving, 55_000L),
        positionInfoFor(arrivedAt, 0L),
        Player.DISCONTINUITY_REASON_SEEK,
      )
    }

    assertThat(awaitRow("left-behind").gainDb).isEqualTo(-6.5f)
  }

  // ---- the seven persistence points, one at a time ----------------------------------------------

  /**
   * Points 1, 2, 4 and 5, each invoked alone against a player parked at a position **only that
   * callback could report**, and each on its own media id so no two can be confused.
   *
   * The writer is never [ProgressWriter.start]ed here, deliberately: no ticker exists, so nothing
   * else in the process can write these rows. What this group does *not* prove is that `start()`
   * registers the listener at all -- that is what the real-playback group below is for, and the
   * split is stated because a reader who mistook one for the other would think this file proved
   * more than it does.
   */
  @Test
  fun eachPersistencePointWritesThePositionThePlayerIsAt() {
    assertThat(
      pointWrites(4_100L) {
        onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      },
    ).isEqualTo(4_100L)
    assertThat(pointWrites(4_200L) { onIsPlayingChanged(false) }).isEqualTo(4_200L)
    assertThat(
      pointWrites(4_300L) {
        onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
      },
    ).isEqualTo(4_300L)
    assertThat(pointWrites(4_400L) { onPlaybackStateChanged(Player.STATE_IDLE) }).isEqualTo(4_400L)
    assertThat(pointWrites(4_500L) { onPlaybackStateChanged(Player.STATE_ENDED) }).isEqualTo(4_500L)
  }

  /** The other arm of each branch above: the states that are **not** persistence points. */
  @Test
  fun theStatesThatAreNotPersistencePointsWriteNothing() {
    assertThat(pointWritesNothing { onIsPlayingChanged(true) }).isNull()
    assertThat(pointWritesNothing { onPlaybackStateChanged(Player.STATE_READY) }).isNull()
    assertThat(pointWritesNothing { onPlaybackStateChanged(Player.STATE_BUFFERING) }).isNull()
  }

  /** Only `STATE_ENDED` finishes an item. `STATE_IDLE` is a stop, and a stop is not an ending. */
  @Test
  fun onlyReachingTheEndMarksAnItemFinished() {
    val ended = pointRow(1_000L) { onPlaybackStateChanged(Player.STATE_ENDED) }
    val idle = pointRow(1_000L) { onPlaybackStateChanged(Player.STATE_IDLE) }

    assertThat(ended.isFinished).isTrue
    assertThat(idle.isFinished).isFalse
  }

  /**
   * Point 3, and the footnote inside it that spec section 3 calls out by name.
   *
   * Silence skipping (Plan 4) moves the position without the listener having moved, so recording it
   * would inch a book forward every time it skipped a pause. Nothing in Plan 3 skips silence, so
   * the brief predicted **no test could fail** on removing the guard and asked for the assertion to
   * be added rather than pretended. This is it: the reason code is the only difference between the
   * two halves, and one writes while the other does not.
   */
  @Test
  fun aSilenceSkipIsNotProgressButEveryOtherDiscontinuityIs() {
    val skipped =
      discontinuityWrites("silence-skipped", 33_000L, Player.DISCONTINUITY_REASON_SILENCE_SKIP)
    val seeked = discontinuityWrites("seeked", 44_000L, Player.DISCONTINUITY_REASON_SEEK)

    assertThat(skipped).describedAs("a silence skip is not progress").isNull()
    // The positive control, and the reason the assertion above is not merely "nothing was written":
    // the same call with a different reason writes, so the guard discriminates the *reason* rather
    // than disabling the callback.
    assertThat(seeked).describedAs("a seek is progress").isEqualTo(44_000L)
  }

  /**
   * Point 3 again, on the half that no `player.currentPosition` read could get right: the row
   * written belongs to the item being **left**, at the position it was left at. By the time this
   * callback arrives the player is already at the new position, so a writer that read the player
   * here would stamp the destination of the seek onto the row of the item that was abandoned.
   */
  @Test
  fun aDiscontinuityWritesTheItemBeingLeftAndNotTheOneArrivedAt() {
    val (harness, subject) = parkedPlayerAndWriter("arrived-at", 1L)

    harness.onMain {
      subject.onPositionDiscontinuity(
        positionInfo("left-behind", 55_000L),
        positionInfo("arrived-at", 0L),
        Player.DISCONTINUITY_REASON_SEEK,
      )
    }

    assertThat(awaitRow("left-behind").positionMs).isEqualTo(55_000L)
    // ...and nothing was written for the item arrived at, which is what tells "read oldPosition"
    // apart from "read the player".
    assertThat(find("arrived-at")).isNull()
  }

  /**
   * Point 7. Asserted with **no polling whatsoever**, because "blocking" is precisely the claim: if
   * this launched into [scope] the row would not be there on the next line, which is exactly the
   * situation `MuPlaybackService.onDestroy` is in when it cancels that scope a moment later.
   *
   * The writer is not started, so there is no listener and no ticker: the flush is the only thing
   * in the process that could have written this row.
   */
  @Test
  fun flushBlockingHasWrittenBeforeItReturns() {
    val (harness, subject) = parkedPlayerAndWriter("flushed", 2_222L)

    harness.onMain { subject.flushBlocking() }

    assertThat(find("flushed")!!.positionMs).isEqualTo(2_222L)
  }

  /**
   * Both `?: return`s, and `stop()` before `start()`.
   *
   * An inert player has no current item, so neither the flush nor a callback may write a row -- an
   * inert player that quietly wrote a row keyed on an empty id would be worse than one that threw.
   * `stop()` is in the same test because it is the same claim: the teardown path in
   * `MuPlaybackService.onDestroy` runs whether or not `onCreate` got as far as starting the writer.
   */
  @Test
  fun aPlayerWithNothingLoadedWritesNothingAndDoesNotThrow() {
    val subject = ProgressWriter(NoOpPlayer(), dao, clock, scope)

    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      subject.flushBlocking()
      subject.onIsPlayingChanged(false)
      subject.stop()
    }
    Thread.sleep(ABSENCE_WINDOW_MS)

    assertThat(runBlocking { dao.findAll() }).isEmpty()
  }

  /**
   * A discontinuity out of an empty timeline carries no `MediaItem` at all, and there is then no
   * row to write. Media3 reports one when a queue is cleared while something is playing, so this is
   * an arm that really is taken -- not a defensive branch nothing can reach.
   */
  @Test
  fun aDiscontinuityOutOfNothingWritesNothing() {
    val (harness, subject) = parkedPlayerAndWriter("still-here", 500L)

    harness.onMain {
      subject.onPositionDiscontinuity(
        Player.PositionInfo(null, 0, null, null, 0, 7_000L, 7_000L, -1, -1),
        positionInfo("still-here", 0L),
        Player.DISCONTINUITY_REASON_REMOVE,
      )
    }
    Thread.sleep(ABSENCE_WINDOW_MS)

    assertThat(runBlocking { dao.findAll() }).isEmpty()
  }

  /**
   * Point 6, the ticker -- the one test in this file where time passing is the mechanism rather
   * than the hazard, so it is arranged so that time cannot supply the answer.
   *
   * The player is **prepared but never played**: its position is frozen at whatever it was last
   * seeked to, so the number the ticker persists is one only a read of the player can produce. Two
   * observations at two different frozen positions, because a single one would be satisfied by a
   * writer that wrote once at `start()`.
   *
   * `TICK_MS` itself is asserted rather than trusted. The waits below are wall-clock bounds, so a
   * tick of a minute would fail them -- but a tick of 100 ms, which no wait can see, would write
   * the database twenty thousand times an hour, and only the constant assertion catches that.
   */
  @Test
  fun theTickerWritesWhileNothingElseHappens() {
    assertThat(ProgressWriter.TICK_MS)
      .describedAs("spec section 3 asks for a 5-10s ticker")
      .isBetween(5_000L, 10_000L)

    val harness = preparedButNotPlaying(songs[0])
    val subject = ProgressWriter(harness.player, dao, clock, scope)
    harness.onMain { subject.start() }

    // A seek writes `oldPosition` -- 0 -- so the only thing that can put 3333 in the row is a read
    // of where the player now is, and the only thing that reads the player unprompted is a tick.
    harness.onMain { harness.player.seekTo(3_333L) }
    assertThat(awaitRow("ticked", TICKER_TIMEOUT_MS) { it.positionMs == 3_333L }.positionMs)
      .isEqualTo(3_333L)

    // The second observation. This seek writes 3333 (the old position) and the next tick must
    // replace it with 1111 -- a number that can only come from a *second* read of the player.
    harness.onMain { harness.player.seekTo(1_111L) }
    assertThat(awaitRow("ticked", TICKER_TIMEOUT_MS) { it.positionMs == 1_111L }.positionMs)
      .isEqualTo(1_111L)

    harness.onMain { subject.stop() }
  }

  /** `stop()` really stops it: after it, a moved player leaves the row where it was. */
  @Test
  fun stoppingTheWriterStopsTheTicker() {
    val harness = preparedButNotPlaying(songs[0])
    val subject = ProgressWriter(harness.player, dao, clock, scope)
    harness.onMain { subject.start() }
    harness.onMain { harness.player.seekTo(3_333L) }
    awaitRow("ticked", TICKER_TIMEOUT_MS) { it.positionMs == 3_333L }

    harness.onMain { subject.stop() }
    harness.onMain { harness.player.seekTo(1_111L) }
    Thread.sleep(ProgressWriter.TICK_MS * 2)

    // 3333, not 1111. The seek would have written 3333 through the listener -- the same number --
    // so what this actually pins is the *ticker*, whose next write would have been 1111.
    assertThat(find("ticked")!!.positionMs).isEqualTo(3_333L)
  }

  // ---- the wiring: real audio, real player -----------------------------------------------------

  /**
   * That `start()` attaches the listener to the player, and that the position it persists is a real
   * one off a real decoder.
   *
   * The order is the whole design of this test. All buffering happens **before** the writer exists,
   * so the tick clock starts with playback already under way; the pause follows within milliseconds
   * and the row is read inside [LISTENER_TIMEOUT_MS]. The first tick cannot have happened, so the
   * band asserted below can only have come from the pause.
   */
  @Test
  fun pausingRealPlaybackWritesTheRealPosition() {
    val harness = startPlaying(songs.take(1))
    harness.awaitPositionAtLeast(1_000L)
    val subject = startedWriterOn(harness)

    harness.onMain { harness.player.pause() }

    // A *range*, because the number is a real position from a real clock: a hardcoded constant
    // cannot be in this band by accident, and neither can zero.
    val row = awaitRow(songs[0].id) { it.positionMs >= 900L }
    assertThat(row.positionMs).isBetween(900L, 5_000L)
    harness.onMain { subject.stop() }
  }

  /**
   * Points 3 and 4 through a real track boundary, and the one real-playback observation the ticker
   * is structurally incapable of faking: once the queue has moved on, the previous track's row can
   * only be written from `oldPosition`, because every later read of the player names the **new**
   * item.
   */
  @Test
  fun skippingToTheNextTrackLeavesThePreviousTracksPositionBehind() {
    val harness = startPlaying(songs.take(2))
    harness.awaitPositionAtLeast(1_500L)
    val subject = startedWriterOn(harness)

    harness.onMain { harness.player.seekToNextMediaItem() }

    val first = awaitRow(songs[0].id) { it.positionMs >= 1_400L }
    val second = awaitRow(songs[1].id)
    assertThat(first.positionMs).isBetween(1_400L, 5_000L)
    // The new track gets its own row, stamped at the top of it -- point 4, so a "recently played"
    // list is right even if the listener stops immediately.
    assertThat(second.positionMs).isLessThan(1_000L)
    harness.onMain { subject.stop() }
  }

  /**
   * Point 5 at the end of a real track. Ticker-proof by **content** rather than by timing: the
   * ticker only ever writes `finished = false`, and `isFinished` is never written back to false, so
   * no tick can produce this assertion however long the wait is.
   */
  @Test
  fun playingToTheEndMarksTheItemFinished() {
    val harness = startPlaying(songs.take(1))
    harness.awaitPositionAtLeast(500L)
    val subject = startedWriterOn(harness)

    harness.awaitEnded(timeoutMs = 30_000L)

    assertThat(awaitRow(songs[0].id, ENDED_TIMEOUT_MS) { it.isFinished }.isFinished).isTrue
    harness.onMain { subject.stop() }
  }

  /**
   * **Spec section 3's whole point, in one test.** A book's row is written, then something else
   * plays entirely, and the book's row is untouched -- position, speed, skip-silence and timestamp
   * all exactly as they were. Plan 3 does not yet *honour* that position on prepare (that is Plan
   * 4), but the property that makes honouring it possible is true today, and this is where it is
   * proven.
   */
  @Test
  fun playingSomethingElseDoesNotDisturbAnotherItemsProgress() {
    runBlocking {
      dao.upsert(MediaProgressEntity("a-book-chapter", 3_600_000L, false, 1L, 1.4f, true, 6.0f))
    }
    val harness = startPlaying(songs.take(1))
    harness.awaitPositionAtLeast(1_000L)
    val subject = startedWriterOn(harness)

    harness.onMain { harness.player.pause() }
    awaitRow(songs[0].id) { it.positionMs >= 900L }

    val book = find("a-book-chapter")!!
    assertThat(book.positionMs).isEqualTo(3_600_000L)
    assertThat(book.speed).isEqualTo(1.4f)
    assertThat(book.skipSilence).isTrue
    assertThat(book.lastPlayedAtEpochMs).isEqualTo(1L)
    harness.onMain { subject.stop() }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun positionInfo(mediaId: String, positionMs: Long) = Player.PositionInfo(
    /* windowUid = */ null,
    /* mediaItemIndex = */ 0,
    /* mediaItem = */ MediaItem.Builder().setMediaId(mediaId).build(),
    /* periodUid = */ null,
    /* periodIndex = */ 0,
    /* positionMs = */ positionMs,
    /* contentPositionMs = */ positionMs,
    /* adGroupIndex = */ -1,
    /* adIndexInAdGroup = */ -1,
  )

  /**
   * A player parked on one item at one position, and a writer over it that is **not** started.
   *
   * Never prepared and never played, so the position it reports is exactly the one it was given and
   * cannot drift while a test reads it -- `setMediaItems(items, index, positionMs)` is masked
   * synchronously by ExoPlayer, which is what makes that possible without a network fetch.
   */
  private fun parkedPlayerAndWriter(
    mediaId: String,
    positionMs: Long,
  ): Pair<PlayerHarness, ProgressWriter> {
    val harness = rawHarness()
    harness.onMain {
      harness.player.setMediaItems(
        listOf(MediaItem.Builder().setMediaId(mediaId).setUri("https://host/$mediaId").build()),
        0,
        positionMs,
      )
    }
    return harness to ProgressWriter(harness.player, dao, clock, scope)
  }

  private fun positionInfoFor(item: MediaItem, positionMs: Long) = Player.PositionInfo(
    /* windowUid = */ null,
    /* mediaItemIndex = */ 0,
    /* mediaItem = */ item,
    /* periodUid = */ null,
    /* periodIndex = */ 0,
    /* positionMs = */ positionMs,
    /* contentPositionMs = */ positionMs,
    /* adGroupIndex = */ -1,
    /* adIndexInAdGroup = */ -1,
  )

  /** A production `MediaItem` whose song is tagged at [trackGainDb]. */
  private fun taggedItem(mediaId: String, trackGainDb: Float): MediaItem =
    MediaItems.of(
      song = gainSong(mediaId).copy(replayGain = ReplayGain(trackGainDb, null, null)),
      streamUri = "https://host/$mediaId",
      artworkUri = null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    )

  private fun gainSong(id: String): Song = Song(
    id = id,
    libraryId = 1,
    title = "Track",
    albumId = "album-1",
    albumName = "Test Album",
    artistId = "artist-1",
    artistName = "Test Artist",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
  )

  /**
   * Parks a player on [item], drives one persistence point, and returns the row's `gainDb`.
   *
   * The writer is never [ProgressWriter.start]ed, so there is no ticker; the player is never
   * prepared, so nothing moves while the row is read. Whatever is in the row came from this write.
   */
  private fun gainWrittenFor(item: MediaItem): Float {
    val harness = rawHarness()
    harness.onMain { harness.player.setMediaItems(listOf(item), 0, 1_234L) }
    val subject = ProgressWriter(harness.player, dao, clock, scope)

    harness.onMain { subject.onIsPlayingChanged(false) }

    // Waits for **this** write, not merely for a row: where a test seeded a starting row first, a
    // bare `awaitRow` returns that row on the first poll and the assertion measures the seed rather
    // than the write. `aWriteForAnUntaggedItemPreservesTheGainAlreadyOnTheRow` passed vacuously
    // that way -- its expected value *is* the seeded one -- and
    // `aTagOverwritesAStaleGainOnAnExistingRow` is the test that caught it, by expecting a
    // different one. Every seeded row uses `lastPlayedAtEpochMs = 1L`; only the writer stamps
    // [FIXED_NOW_MS], because only the writer reads [clock].
    return awaitRow(item.mediaId) { it.lastPlayedAtEpochMs == FIXED_NOW_MS }.gainDb
  }

  /** Invokes one listener callback on a player parked at [positionMs]; returns what was written. */
  private fun pointWrites(positionMs: Long, call: ProgressWriter.() -> Unit): Long? =
    pointRowOrNull(positionMs, call)?.positionMs

  private fun pointRow(positionMs: Long, call: ProgressWriter.() -> Unit): MediaProgressEntity =
    checkNotNull(pointRowOrNull(positionMs, call)) { "no media_progress row was written" }

  private fun pointWritesNothing(call: ProgressWriter.() -> Unit): Long? =
    pointRowOrNull(9_999L, call, expectRow = false)?.positionMs

  private fun pointRowOrNull(
    positionMs: Long,
    call: ProgressWriter.() -> Unit,
    expectRow: Boolean = true,
  ): MediaProgressEntity? {
    val mediaId = "point-${System.nanoTime()}"
    val (harness, subject) = parkedPlayerAndWriter(mediaId, positionMs)
    harness.onMain { subject.call() }
    return if (expectRow) awaitRow(mediaId) else awaitAbsence(mediaId)
  }

  private fun discontinuityWrites(mediaId: String, positionMs: Long, reason: Int): Long? {
    val (harness, subject) = parkedPlayerAndWriter("current-item", 0L)
    harness.onMain {
      subject.onPositionDiscontinuity(
        positionInfo(mediaId, positionMs),
        positionInfo("current-item", 0L),
        reason,
      )
    }
    return if (reason == Player.DISCONTINUITY_REASON_SILENCE_SKIP) {
      awaitAbsence(mediaId)?.positionMs
    } else {
      awaitRow(mediaId).positionMs
    }
  }

  /**
   * A raw `ExoPlayer`, built through [MuPlayerFactory] because `PlayerConstructionTest` refuses a
   * second construction site anywhere under `core/media/src` -- test sources included.
   */
  private fun rawHarness(): PlayerHarness {
    val cache = MediaCache.create(context, File(cacheDir, "run-${System.nanoTime()}"))
    lateinit var built: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      built = PlayerHarness(
        MuPlayerFactory(
          context = context,
          dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), cache),
          loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
          resumePolicy = NeverResume,
        ).createExoPlayer(),
      )
    }
    harnesses += built
    return built
  }

  private fun startPlaying(items: List<Song>): PlayerHarness {
    val built = rawHarness()
    built.onMain {
      built.player.setMediaItems(
        items.map {
          MediaItem.Builder()
            .setMediaId(it.id)
            .setUri(RealTrackBytes.rawStreamUrl(it))
            .setCustomCacheKey(it.id)
            .build()
        },
      )
      built.player.prepare()
      built.player.play()
    }
    return built
  }

  /** Prepared so the timeline is real, never played, so the position it reports cannot move. */
  private fun preparedButNotPlaying(song: Song): PlayerHarness {
    val built = rawHarness()
    built.onMain {
      built.player.setMediaItem(
        MediaItem.Builder()
          .setMediaId("ticked")
          .setUri(RealTrackBytes.rawStreamUrl(song))
          .setCustomCacheKey(song.id)
          .build(),
      )
      built.player.prepare()
    }
    built.awaitState(Player.STATE_READY)
    return built
  }

  /**
   * Starts the writer **after** the caller has finished waiting on the player.
   *
   * This is where the five-second rule is enforced: the tick clock starts here, so an assertion
   * that completes inside [LISTENER_TIMEOUT_MS] is one no tick can have contributed to.
   */
  private fun startedWriterOn(harness: PlayerHarness): ProgressWriter =
    ProgressWriter(harness.player, dao, clock, scope).also { writer ->
      harness.onMain { writer.start() }
    }

  private fun find(mediaId: String): MediaProgressEntity? = runBlocking { dao.find(mediaId) }

  private fun row(mediaId: String): MediaProgressEntity =
    checkNotNull(find(mediaId)) { "no media_progress row for $mediaId" }

  private fun awaitRow(
    mediaId: String,
    timeoutMs: Long = LISTENER_TIMEOUT_MS,
    predicate: (MediaProgressEntity) -> Boolean = { true },
  ): MediaProgressEntity {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: MediaProgressEntity? = null
    while (System.currentTimeMillis() < deadline) {
      last = find(mediaId)
      last?.takeIf(predicate)?.let { return it }
      Thread.sleep(POLL_MS)
    }
    throw AssertionError(
      "no media_progress row for $mediaId satisfying the predicate within ${timeoutMs}ms; " +
        "last seen: $last",
    )
  }

  /** Waits out the window a row *would* have appeared in, and returns whatever is there. */
  private fun awaitAbsence(mediaId: String): MediaProgressEntity? {
    Thread.sleep(ABSENCE_WINDOW_MS)
    return find(mediaId)
  }

  private companion object {
    const val FIXED_NOW_MS = 1_700_000_000_000L

    /**
     * Deliberately far below [ProgressWriter.TICK_MS]. Every listener assertion has to complete
     * before the first tick could fire, or the ticker becomes an alternative explanation for the
     * row it read -- which is the vacuous-assertion shape `CLAUDE.md` records for this fixture set.
     */
    const val LISTENER_TIMEOUT_MS = 2_000L

    /**
     * The one listener assertion that cannot be held under a tick, because the state it waits for
     * is the end of a five-second track. It is ticker-proof by content instead: no tick ever writes
     * `isFinished = true`.
     */
    const val ENDED_TIMEOUT_MS = 10_000L

    /** Two ticks plus slack: long enough that a *working* ticker has certainly written. */
    const val TICKER_TIMEOUT_MS = 20_000L

    /** How long "nothing was written" waits before believing it. */
    const val ABSENCE_WINDOW_MS = 1_000L

    const val POLL_MS = 50L
  }
}
