package app.muplay.media

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Speed and silence skipping, on a real `ExoPlayer` playing real audio off the real container.
 *
 * ### Why every transition assertion is read with no wait at all
 *
 * `CLAUDE.md` records three Plan 3 device tests that were green against the very mutation they
 * existed to catch, because a five-second fixture let playback *reach* the awaited state on its
 * own. The rule it draws is to prefer an observation that is true immediately, and here that is
 * available exactly: Media3 dispatches listener callbacks through a `ListenerSet` that is flushed
 * **inside** the call that queued them, so by the time `seekToNextMediaItem()` returns on the
 * player's own thread, `onMediaItemTransition` has already been delivered and this class has
 * already re-applied the settings. Every transition test below therefore does the seek and the
 * read in **one** `onMain` block. Nothing waits, so nothing can drift into passing.
 *
 * The two speed-of-audio tests are the exception, and they measure elapsed **media** over a fixed
 * wall-clock window rather than a property: `player.playbackParameters.speed` being right is the
 * "was asked to play" mistake, satisfied by a decoder that never produced a sample.
 *
 * `skipSilenceEnabled` is asserted as a property, deliberately and with the reason stated: the
 * field under test is *the mapping from book to flag*, and the flag's effect on the audio is
 * Media3's contract rather than this project's. Reading it back off the real player is a real
 * observation; recording that a setter was called would not be.
 *
 * ### The source is a `Map`, not `AudiobookSnapshot`
 *
 * [AudiobookItemSource] is a one-method `fun interface`, so a map is a *complete* implementation of
 * it rather than a stand-in -- the same reason `AudiobookResumePolicyTest` uses one, and the reason
 * the interface is that narrow. It also keeps the subject here to `BookSpeedController`: where the
 * items come from is `AudiobookSnapshot`'s question (Plan 4 Task 6), and answering it here would
 * make a failure ambiguous between two classes.
 *
 * ### A raw `ExoPlayer`, not the `MuPlayer` seam
 *
 * The subject is `ExoPlayer` behaviour -- playback parameters and silence skipping, the latter not
 * reachable through `Player` at all -- and routing through the seam would add a layer with nothing
 * to say about it while making every failure ambiguous between the two.
 *
 * No stream URL is asserted on, logged, or put in a description: they carry `u`, `s` and `t`.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class BookSpeedControllerTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var books: List<Song>
  private lateinit var music: List<Song>

  private val harnesses = mutableListOf<PlayerHarness>()
  private val cleanups = mutableListOf<() -> Unit>()

  /** The live items, keyed by media id. Mutated by [book] before a queue is built. */
  private val items = mutableMapOf<String, AudiobookItem>()
  private val source = AudiobookItemSource { mediaId -> items[mediaId] }

  /**
   * **Every** write, in order, with the book it named -- not a map keyed by book.
   *
   * The defect this class's re-entrancy guard exists for writes the *wrong* book's speed, so a
   * failure has to be able to say which book and which value. A map that overwrote would hide the
   * first of two writes, which is exactly the one that would name the wrong book.
   */
  private val persisted = CopyOnWriteArrayList<Pair<String, Float>>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "book-speed-${System.nanoTime()}")
    // Looked up by the titles `ci/seed-fixtures.sh` writes, never by index and never by count: the
    // corpus is shared with every lane and grows without warning (CLAUDE.md).
    books = runBlocking { RealTrackBytes.audiobookFiles() }
    music = runBlocking { RealTrackBytes.musicTracks() }
  }

  /**
   * Guarded on `isInitialized`, because an unguarded `@After` **replaces the real failure with its
   * own**: a `@Before` that dies before the last assignment leaves this throwing
   * `UninitializedPropertyAccessException`, and that is then the only message in the report. See
   * `GaplessTest.tearDown` for the run that cost two lanes an afternoon.
   */
  @After
  fun tearDown() {
    harnesses.forEach { it.release() }
    harnesses.clear()
    cleanups.forEach { it() }
    cleanups.clear()
    if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
  }

  // ---- the audio really moves at the book's speed ----------------------------------------------

  @Test
  fun aBookAtDoubleSpeedCoversTwiceTheMediaOfTheSameBookAtNormalSpeed(): Unit = runBlocking {
    // The **same fixture**, twice, differing only in the book's stored speed -- so the comparison
    // needs no absolute number and cannot be satisfied by a fixture that happens to be long.
    // `playbackParameters.speed` is asserted too, but only as the diagnostic that says *why* when
    // the elapsed-media assertion fails: a player that was asked for 2.0x and produced no sample
    // satisfies the property and nothing else here.
    val fast = elapsedMediaOverAWindow(speed = 2.0f)
    val slow = elapsedMediaOverAWindow(speed = 1.0f)

    assertThat(fast.first)
      .describedAs("the player really was set to 2.0x")
      .isEqualTo(2.0f)
    assertThat(slow.first)
      .describedAs("the player really was set to 1.0x")
      .isEqualTo(1.0f)
    // Bands on both sides, not one threshold: a stalled player produces a small number and a
    // runaway produces a large one, and only a two-sided assertion tells them from the answer.
    assertThat(slow.second)
      .describedAs("ms of media in %d ms of wall clock at 1.0x", WINDOW_MS)
      .isBetween(WINDOW_MS / 2, (WINDOW_MS * 3) / 2)
    assertThat(fast.second)
      .describedAs("ms of media in %d ms of wall clock at 2.0x", WINDOW_MS)
      .isBetween(WINDOW_MS + WINDOW_MS / 2, WINDOW_MS * 3)
    // And the pair, which is the assertion: whatever the absolute numbers, twice the speed has to
    // be most of twice the audio.
    assertThat(fast.second.toDouble())
      .describedAs("2.0x covered %d ms where 1.0x covered %d ms", fast.second, slow.second)
      .isGreaterThan(slow.second * 1.6)
  }

  // ---- the trap this task is named for ---------------------------------------------------------

  @Test
  fun aBooksSpeedDoesNotFollowYouIntoMusic(): Unit = runBlocking {
    // Playback parameters live on the **player**, so without an explicit reset the song after a
    // book plays at the book's speed and nothing anywhere reports it.
    val harness = startQueue(listOf(book(LONG_BOOK, speed = 1.5f), song(SONG)))

    val (onBook, index, onMusic) = harness.onMain {
      val before = harness.player.playbackParameters.speed
      harness.player.seekToNextMediaItem()
      Triple(before, harness.player.currentMediaItemIndex, harness.player.playbackParameters.speed)
    }

    assertThat(index).describedAs("the queue really did move on").isEqualTo(1)
    // Two observations, two values: a controller that reset everything to 1.0x satisfies the second
    // alone, and one that did nothing at all satisfies the first alone.
    assertThat(listOf(onBook, onMusic)).containsExactly(1.5f, 1.0f)
  }

  @Test
  fun goingBackToTheBookRestoresItsSpeed(): Unit = runBlocking {
    // The other direction, which a one-way "reset to 1.0 on every transition" breaks: it leaves the
    // book at 1.0 when the listener comes back to it, and the first test above is green against it.
    val harness = startQueue(listOf(song(SONG), book(LONG_BOOK, speed = 1.5f)))

    val (onMusic, index, onBook) = harness.onMain {
      val before = harness.player.playbackParameters.speed
      harness.player.seekToNextMediaItem()
      Triple(before, harness.player.currentMediaItemIndex, harness.player.playbackParameters.speed)
    }

    assertThat(index).isEqualTo(1)
    assertThat(listOf(onMusic, onBook)).containsExactly(1.0f, 1.5f)
  }

  @Test
  fun twoBooksInOneQueueEachPlayAtTheirOwnSpeed(): Unit = runBlocking {
    // The observation a single book cannot make: "the book's speed" and "the speed" are the same
    // program until there are two of them, and both of these differ from 1.0 so a reset-to-normal
    // satisfies neither.
    val harness = startQueue(
      listOf(book(LONG_BOOK, speed = 1.5f), book(SHORT_BOOK, speed = 0.75f)),
    )

    val (first, index, second) = harness.onMain {
      val before = harness.player.playbackParameters.speed
      harness.player.seekToNextMediaItem()
      Triple(before, harness.player.currentMediaItemIndex, harness.player.playbackParameters.speed)
    }

    assertThat(index).isEqualTo(1)
    assertThat(listOf(first, second)).containsExactly(1.5f, 0.75f)
  }

  @Test
  fun aClearedQueueLeavesThePlayerAtNormalPlayback(): Unit = runBlocking {
    // `onMediaItemTransition(null, ..)` -- what a `clearMediaItems` delivers. The book's 1.5x must
    // not survive it: whatever is played next may never transition again (a single-item queue set
    // while this listener is already attached does, but a `setMediaItem` on the *same* id does
    // not), and a player left at 1.5x plays the next thing at 1.5x.
    val harness = startQueue(listOf(book(LONG_BOOK, speed = 1.5f)))

    val (before, after, count) = harness.onMain {
      val was = harness.player.playbackParameters.speed
      harness.player.clearMediaItems()
      Triple(was, harness.player.playbackParameters.speed, harness.player.mediaItemCount)
    }

    assertThat(count).describedAs("the queue really is empty").isZero
    assertThat(listOf(before, after)).containsExactly(1.5f, 1.0f)
  }

  // ---- silence skipping ------------------------------------------------------------------------

  @Test
  fun silenceSkippingFollowsTheBookAndIsOffForMusic(): Unit = runBlocking {
    val harness = startQueue(
      listOf(book(LONG_BOOK, speed = 1.0f, skipSilence = true), song(SONG)),
    )

    val (onBook, index, onMusic) = harness.onMain {
      val before = harness.player.skipSilenceEnabled
      harness.player.seekToNextMediaItem()
      Triple(before, harness.player.currentMediaItemIndex, harness.player.skipSilenceEnabled)
    }

    assertThat(index).isEqualTo(1)
    // Two observations, two values. One alone would be satisfied by a constant.
    assertThat(listOf(onBook, onMusic)).containsExactly(true, false)
  }

  @Test
  fun theBooksSpeedAndItsSilenceFlagAreAppliedIndependently(): Unit = runBlocking {
    // A book at normal speed that *does* skip silence, and a book at an unusual speed that does
    // **not**. Without this pair, "apply the book's settings" could be one flag driving both --
    // "books are fast and skip silence, music is neither" -- and every other test here is green
    // against it.
    val harness = startQueue(
      listOf(
        book(LONG_BOOK, speed = 1.0f, skipSilence = true),
        book(SHORT_BOOK, speed = 1.5f, skipSilence = false),
      ),
    )

    val first = harness.onMain {
      harness.player.playbackParameters.speed to harness.player.skipSilenceEnabled
    }
    val second = harness.onMain {
      harness.player.seekToNextMediaItem()
      harness.player.playbackParameters.speed to harness.player.skipSilenceEnabled
    }

    assertThat(first).isEqualTo(1.0f to true)
    assertThat(second).isEqualTo(1.5f to false)
  }

  // ---- the write-back --------------------------------------------------------------------------

  @Test
  fun aSpeedChangeMadeThroughThePlayerIsPersistedAgainstTheRightBook(): Unit = runBlocking {
    // The half a re-entrancy guard can break by being too broad: a real change, from the control
    // surface, must be stored. Two books in the queue so "the right book" is falsifiable.
    val first = book(LONG_BOOK, speed = 1.0f)
    val harness = startQueue(listOf(first, book(SHORT_BOOK, speed = 0.75f)))
    harness.onMain { harness.player.setPlaybackSpeed(1.3f) }

    // No wait: `setPlaybackSpeed` called from the player's own thread flushes its listeners inside
    // the call, so the write has already happened by the time this line runs.
    assertThat(persisted).containsExactly(bookIdOf(first) to 1.3f)
  }

  @Test
  fun aTransitionBetweenTwoBooksDoesNotWriteEitherOnesSpeedOntoTheOther(): Unit = runBlocking {
    // The re-entrancy trap. `applyFor` changes the playback parameters itself, which fires
    // `onPlaybackParametersChanged`; unguarded, transitioning to book B persists B's speed against
    // whichever book the player reports as current at that instant.
    val harness = startQueue(
      listOf(book(LONG_BOOK, speed = 1.5f), book(SHORT_BOOK, speed = 0.75f)),
    )

    harness.onMain { harness.player.seekToNextMediaItem() }
    harness.onMain { harness.player.seekToPreviousMediaItem() }

    assertThat(persisted)
      .describedAs("a programmatic apply must never be written back as a listener's choice")
      .isEmpty()
  }

  @Test
  fun aSpeedChangedWhileMusicIsPlayingIsNotPersistedAgainstAnyBook(): Unit = runBlocking {
    // Music has no book, so there is nothing to write against -- structural rather than a branch
    // someone could delete. Without this, a controller that wrote `bookIdOf(currentItem)` would
    // silently create a `book_settings` row for an album that is not a book.
    val harness = startQueue(listOf(song(SONG)))

    harness.onMain { harness.player.setPlaybackSpeed(1.3f) }

    assertThat(persisted).isEmpty()
    // ...and the control, in the same test, so "nothing was written" is not equally satisfied by a
    // controller that writes nothing ever: the same call on a book does write.
    val theBook = book(LONG_BOOK, speed = 1.0f)
    val onABook = startQueue(listOf(theBook))
    onABook.onMain { onABook.player.setPlaybackSpeed(1.3f) }
    assertThat(persisted).containsExactly(bookIdOf(theBook) to 1.3f)
  }

  @Test
  fun aSpeedChangeWithNothingLoadedIsNotPersistedAndDoesNotThrow(): Unit = runBlocking {
    // A `MediaController` in a car can send a speed command to a session whose queue is empty.
    // There is no current item to name a book with, and an exception thrown from inside a listener
    // callback takes the whole session down.
    val harness = startQueue(emptyList())

    harness.onMain { harness.player.setPlaybackSpeed(1.3f) }

    assertThat(persisted).isEmpty()
    assertThat(harness.onMain { harness.player.playbackParameters.speed }).isEqualTo(1.3f)
  }

  @Test
  fun anImpossibleStoredSpeedIsClampedBeforeItReachesThePlayer(): Unit = runBlocking {
    // `ExoPlayer.setPlaybackSpeed` throws `IllegalArgumentException` for a non-positive or `NaN`
    // speed, from inside a listener callback -- which surfaces as playback dying with no message.
    // This is the only tier that can observe that the clamp is what stops it: the JVM tier proves
    // the arithmetic, and a real player proves the arithmetic is on the path to the setter.
    val harness = startQueue(
      listOf(book(LONG_BOOK, speed = Float.NaN), book(SHORT_BOOK, speed = 99f)),
    )

    val onFirst = harness.onMain { harness.player.playbackParameters.speed }
    val onSecond = harness.onMain {
      harness.player.seekToNextMediaItem()
      harness.player.playbackParameters.speed
    }

    harness.assertNoPlaybackError()
    // Two different impossible inputs landing on two different legal outputs -- a blanket
    // "fall back to 1.0 if it looks wrong" satisfies the first and fails the second.
    assertThat(listOf(onFirst, onSecond)).containsExactly(1.0f, 3.0f)
  }

  // ---- apparatus -------------------------------------------------------------------------------

  /**
   * Plays [LONG_BOOK] at [speed] and returns the player's reported speed and the milliseconds of
   * **media** that passed inside [WINDOW_MS] of wall clock.
   *
   * The window opens only after real audio has been produced, so buffering and the first HTTP
   * round trip are outside it. The player is released before returning: two players decoding at
   * once contend for audio focus, and the second would silently produce nothing.
   */
  private fun elapsedMediaOverAWindow(speed: Float): Pair<Float, Long> {
    val harness = startQueue(listOf(book(LONG_BOOK, speed = speed)))
    harness.awaitPositionAtLeast(FIRST_AUDIO_MS, timeoutMs = PLAYBACK_TIMEOUT_MS)

    val from = harness.onMain { harness.player.currentPosition }
    val startedAt = SystemClock.elapsedRealtime()
    Thread.sleep(WINDOW_MS)
    val to = harness.onMain { harness.player.currentPosition }
    val wallClock = SystemClock.elapsedRealtime() - startedAt
    val reported = harness.onMain { harness.player.playbackParameters.speed }

    harness.assertNoPlaybackError()
    harness.release()
    harnesses.remove(harness.underlying)
    // Normalised to the nominal window, because `Thread.sleep` may overshoot on a loaded emulator
    // and an un-normalised number would drift toward passing as the host gets busier.
    return reported to (to - from) * WINDOW_MS / wallClock
  }

  /** Registers [song] as a file of its own album's book, and returns it. */
  private fun book(title: String, speed: Float, skipSilence: Boolean = false): Song {
    val song = books.first { it.title == title }
    items[song.id] = AudiobookItem(
      mediaId = song.id,
      bookId = bookIdOf(song),
      positionMs = 0L,
      lastPlayedAtEpochMs = 0L,
      isFinished = false,
      speed = speed,
      skipSilence = skipSilence,
    )
    return song
  }

  /** A music track, deliberately never registered in [items] -- that absence is what makes it music. */
  private fun song(title: String): Song = music.first { it.title == title }

  /** `AudiobookRepository.bookIdOf`'s rule: a book is an album, a loose file is its own book. */
  private fun bookIdOf(song: Song): String = song.albumId ?: song.id

  /**
   * [songs] as a queue on a player this controller is already listening to, playing from the top.
   *
   * The controller is attached and then told about the current item explicitly. The explicit
   * [BookSpeedController.applyFor] is not papering over anything: a queue set *before* the listener
   * was attached fires no `onMediaItemTransition` for its first item, and production attaches in
   * `MuPlaybackService.onCreate` -- before any queue exists -- where the transition does fire. A
   * test that relied on that ordering would be relying on an ordering it set up itself.
   */
  private fun startQueue(songs: List<Song>): PlayingHarness {
    val cache = MediaCache.create(context, File(cacheDir, "run-${System.nanoTime()}"))
    cleanups += { cache.release() }
    lateinit var started: PlayingHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val player = MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), cache),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        resumePolicy = NeverResume,
      ).createExoPlayer()
      val harness = PlayerHarness(player)
      harnesses += harness
      val controller =
        BookSpeedController(player, source) { bookId, speed -> persisted += bookId to speed }
      controller.start()
      player.setMediaItems(songs.map(::itemFor))
      controller.applyFor(player.currentMediaItem?.mediaId)
      player.prepare()
      player.play()
      started = PlayingHarness(harness, player)
    }
    return started
  }

  /**
   * One started player, in both the shapes the assertions need.
   *
   * [PlayerHarness] supplies the main-thread hop, the bounded waits and -- the part that matters --
   * *a playback error rethrown as the assertion failure's cause*, so a 404 does not present as a
   * timeout. [player] is the raw [ExoPlayer] beside it, because `skipSilenceEnabled` is not on
   * `Player` at all, which is the whole reason `BookSpeedController` takes an `ExoPlayer` rather
   * than the `MuPlayer` seam.
   */
  private class PlayingHarness(val underlying: PlayerHarness, val player: ExoPlayer) {
    fun <T> onMain(block: () -> T): T = underlying.onMain(block)

    fun awaitPositionAtLeast(positionMs: Long, timeoutMs: Long) =
      underlying.awaitPositionAtLeast(positionMs, timeoutMs)

    fun assertNoPlaybackError() = underlying.assertNoPlaybackError()

    fun release() = underlying.release()
  }

  private fun itemFor(song: Song): MediaItem =
    MediaItems.of(
      song = song,
      streamUri = RealTrackBytes.rawStreamUrl(song),
      artworkUri = null,
      // Every queue item here is built the way the shipping queue builds a book file, because the
      // audio attributes a book gets are what `ContentTypeSwitcher` chooses from -- and whether an
      // item is *treated* as a book by this class is decided by `items`, not by this flag.
      isAudiobook = true,
      format = StreamFormat.Raw,
    )

  private companion object {
    /** 21 s, the corpus's longest book -- long enough that the speed window stays inside it. */
    const val LONG_BOOK = "Second Book"

    /** 12 s, a different book with a different album id: the second of the two "two books" need. */
    const val SHORT_BOOK = "Tail Book"

    const val SONG = "Track 1"

    /** How much real audio must have been produced before the measuring window opens. */
    const val FIRST_AUDIO_MS = 1_000L

    /** The measuring window. Two seconds of media at 1.0x, four at 2.0x, both inside a 21 s file. */
    const val WINDOW_MS = 2_000L

    const val PLAYBACK_TIMEOUT_MS = 30_000L
  }
}
