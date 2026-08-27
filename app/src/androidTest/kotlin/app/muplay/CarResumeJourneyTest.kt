package app.muplay

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.MediaProgressEntryPoint
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.media.MuPlaybackService
import app.muplay.model.browse.BrowseExtras
import app.muplay.model.browse.BrowseSurfaces
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The original complaint, asked from a car.
 *
 * A book is left part-way through; a car connects, browses to Continue, taps it, and playback
 * carries on from **where it was left** rather than from the beginning of the book. Real service,
 * real `MediaLibrarySession`, a real `MediaBrowser` declaring itself a car, real Navidrome, real
 * decoded audio.
 *
 * ### The one thing this file asserts about resuming, and the one it deliberately does not
 *
 * A listening position is two facts: **which file**, and **which second of it**. Plan 5 Task 5 owns
 * the first -- `ResumePolicy.resolve(mediaIds, requestedIndex)` cannot tell "play this book" from
 * "play chapter 1 from the top", so the caller picks the index and the policy picks the position.
 * The second is Plan 4 Task 6's, and **it has not landed**: `MediaModule.provideResumePolicy()`
 * still returns `NeverResume`, measured on master at the time this file was written. So the second
 * of the file a tapped book starts at is `0` today, that number is not this task's to change, and
 * asserting it either way here would be asserting a binding rather than a behaviour. What proves
 * the seam past this boundary -- that a stored second really does reach a decoder, and that
 * reaching it is not the fixture's length doing the work -- is
 * `app.muplay.media.BrowseResumeAudioTest` in `:core:media`, which binds a policy that answers one.
 *
 * ### Why nothing here waits for a state playback could reach on its own
 *
 * The fixtures are seconds long. `Multi Part Book` is Part One (4 s), Part Two (6 s), Part Three
 * (5 s), so a queue started at Part One **plays into Part Two inside any wait for it** -- which is
 * exactly how a `startIndex` assertion in this repository once passed against the defect it existed
 * to catch. Every index read below therefore happens with `playWhenReady` **false**: the queue is
 * set and prepared, never played, and the read is of a state real time cannot manufacture. Playback
 * is started afterwards, as a separate observation, to prove the queue is not merely well-formed.
 *
 * The one wait that remains is for the **queue itself** to arrive, and it is safe by construction:
 * `MediaController.setMediaItem` masks locally to the *one* item it sent, and the session's answer
 * is what replaces it, so `mediaItemCount == the book's file count` is a state neither masking nor
 * playback can produce.
 *
 * ### No library count is hardcoded
 *
 * Every id, count and title below is read from the browse tree at run time. The seeded corpus grew
 * from four files to nine while this task was being written; a test that had counted would have
 * been wrong by then rather than red.
 *
 * **No stream URL is read, asserted or printed anywhere in this file.** Every one carries `u`,
 * `s=salt` and `t=md5(password+salt)`, and an AssertJ failure prints what it saw.
 *
 * Method names are camelCase: `minSdk 26` compiles DEX 035, which forbids a space in a `SimpleName`.
 */
@RunWith(AndroidJUnit4::class)
class CarResumeJourneyTest {

  /**
   * The app itself, walked to a settled library screen.
   *
   * Not decoration and not a UI test: [reachLibraryScreen] is what establishes the credentials, the
   * two `LibraryRole` tags and a committed sync. The browse tree reads the mirror, so without it
   * every tab below is empty and every assertion in this file is vacuous. It is also the only path
   * that goes through the real setup flow rather than writing the app's own state behind its back.
   */
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  private lateinit var context: Context
  private lateinit var browser: MediaBrowser

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    composeRule.reachLibraryScreen()
    browser = connect(BrowseSurfaces.HINT_CAR)
  }

  @After
  fun tearDown() {
    onMain {
      browser.stop()
      browser.clearMediaItems()
      browser.release()
    }
  }

  // ---- the headline ----------------------------------------------------------------------------

  @Test
  fun aBookTappedInTheCarCarriesOnFromTheFileItWasLeftIn() {
    val book = multiPartBook()
    val files = children(book.mediaId).map { it.mediaId }
    check(files.size >= 3) { "the multi-part book fixture must hold at least three files" }

    // What a previous listening session would have left: part way through the **second** file.
    // The second and not the first, so that "resumed" and "started" are different observations,
    // and not the last either, so that "resumed" and "went to the end" are too.
    val resumeFile = files[1]
    store(resumeFile, positionMs = 2_500)

    // The Continue shelf has to show it before anything is played. A shelf that only becomes right
    // after playback is a shelf that is wrong when a driver reads it.
    val shelfRow = children("muplay/continue").single { it.mediaId == book.mediaId }
    val extras = requireNotNull(shelfRow.mediaMetadata.extras)
    assertThat(extras.getInt(BrowseExtras.COMPLETION_STATUS))
      .isEqualTo(BrowseExtras.STATUS_PARTIALLY_PLAYED)
    assertThat(extras.getDouble(BrowseExtras.COMPLETION_PERCENTAGE)).isStrictlyBetween(0.0, 1.0)

    // Set and prepare, never play: see this class's own note on why every index read is taken with
    // `playWhenReady` false.
    queueFromTree(book)
    awaitQueueOf(files.size)

    // The whole claim, in the two readings that can tell it from a queue that started at the top.
    assertThat(onMain { browser.currentMediaItemIndex }).isEqualTo(1)
    assertThat(onMain { browser.currentMediaItem?.mediaId }).isEqualTo(resumeFile)
    assertThat(onMain { (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId } })
      .containsExactlyElementsOf(files)

    // ...and it is a queue that really plays, not merely one that is well-formed. Two reads of the
    // position separated by real time, strictly increasing, is the weakest observation a player
    // rendering silence cannot produce.
    onMain { browser.play() }
    val reached = awaitPositionAtLeast(400L)
    Thread.sleep(1_200)
    assertThat(onMain { browser.currentPosition }).isGreaterThan(reached)

    // Still the file it resumed into -- a queue that resumed the right second of the wrong file is
    // a failure the position assertions above would miss entirely. Part Two is six seconds long
    // and under two of them have passed.
    assertThat(onMain { browser.currentMediaItem?.mediaId }).isEqualTo(resumeFile)
  }

  @Test
  fun theFileABookStartsOnIsTheFileTheRowNamesAndNotAConstant() {
    // The other half of the observation above, and the one that makes it discriminating: three
    // stored positions, three answers. A `startIndex` hardcoded to 0, to 1, or to "the last file"
    // satisfies exactly one of these three and fails the other two.
    //
    // Written as three taps of one book rather than as three books, so nothing but the stored row
    // differs between the observations.
    val book = multiPartBook()
    val files = children(book.mediaId).map { it.mediaId }

    val started = files.indices.map { index ->
      makeMostRecentlyPlayed(files, index)
      queueFromTree(book)
      awaitQueueOf(files.size)
      onMain { browser.currentMediaItemIndex }
    }

    assertThat(started).containsExactlyElementsOf(files.indices.toList())
  }

  // ---- the contrast: music does not resume -------------------------------------------------------

  @Test
  fun aMusicTrackTappedInTheCarStartsAtZeroEvenThoughItHasAStoredPosition() {
    // Spec section 3: *music restarts from 0 -- progress is still recorded, just not honoured on
    // prepare*. The contrast is the assertion: with only the book case above, a policy that
    // resumed everything would pass.
    val album = musicAlbum()
    val tracks = children(album.mediaId).map { it.mediaId }
    check(tracks.size >= 2) { "the seeded music album must hold at least two tracks" }

    val tapped = tracks[1]
    store(tapped, positionMs = 3_000)
    // Read back before the tap, so "a stored position existed when the row was tapped" is an
    // observation rather than an assumption about a write that might not have landed.
    assertThat(runBlocking { progressDao().find(tapped) }?.positionMs).isEqualTo(3_000L)

    queueFromTree(childOf(album.mediaId, index = 1))
    awaitQueueOf(tracks.size)

    // Read before a single sample is rendered, so real time cannot have moved it.
    assertThat(onMain { browser.currentMediaItemIndex }).isEqualTo(1)
    assertThat(onMain { browser.currentMediaItem?.mediaId }).isEqualTo(tapped)
    assertThat(onMain { browser.currentPosition }).isEqualTo(0L)

    // ...and the row is still there. "Recorded but not honoured" is two claims, and a policy that
    // honoured nothing because nothing was ever written would satisfy only one.
    //
    // **Its value is no longer 3 000, and that is the recording rather than a lost write.**
    // `ProgressWriter` listens to `onMediaItemTransition` and captures the current item's position
    // the moment the queue lands, so by the time this reads the row it holds where playback
    // actually is -- measured, after this assertion was first written as `isEqualTo(3_000L)` and
    // failed with `expected:<[300]0L> but was:<[]0L>`. The seeded value's job was done before the
    // tap: it was read back above, and playback ignored it.
    assertThat(runBlocking { progressDao().find(tapped) }).isNotNull
  }

  // ---- the rest of the table ---------------------------------------------------------------------

  @Test
  fun tappingAnAlbumInTheCarQueuesTheWholeAlbumFromTheTop() {
    val album = musicAlbum()
    val tracks = children(album.mediaId).map { it.mediaId }

    queueFromTree(album)
    awaitQueueOf(tracks.size)

    assertThat(onMain { (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId } })
      .containsExactlyElementsOf(tracks)
    assertThat(onMain { browser.currentMediaItemIndex }).isEqualTo(0)
  }

  @Test
  fun tappingATrackInTheMiddleOfAnAlbumQueuesTheAlbumFromThatTrack() {
    // The index has to come from the id that was tapped, not be a constant 0 -- and the queue has
    // to be the album, not the one track. Both are read with nothing playing.
    val album = musicAlbum()
    val tracks = children(album.mediaId).map { it.mediaId }
    val lastIndex = tracks.lastIndex

    queueFromTree(childOf(album.mediaId, index = lastIndex))
    awaitQueueOf(tracks.size)

    assertThat(onMain { browser.currentMediaItemIndex }).isEqualTo(lastIndex)
    assertThat(onMain { browser.currentMediaItem?.mediaId }).isEqualTo(tracks[lastIndex])
  }

  @Test
  fun tappingShuffleInTheCarPlaysMusicAndNeverABook() {
    // Spec section 1, from a car seat. Library-scoped shuffle is Plan 2's; what this asserts is
    // that the browse row reaches it with the right library id.
    // **Every** music album, not the one this suite happens to play from. The seeded corpus has
    // grown twice while this task was being written -- from four files to nine, and then again
    // with an "Offset Track" in a second album, which is what caught this assertion reading one
    // album's titles and calling them "the music library".
    val musicTitles = children("muplay/albums")
      .filter { it.mediaId.startsWith("muplay/album/") }
      .flatMap { children(it.mediaId) }
      .mapNotNull { it.mediaMetadata.title?.toString() }
    val bookTitles = children("muplay/books")
      .flatMap { book -> children(book.mediaId) }
      .mapNotNull { it.mediaMetadata.title?.toString() }
    check(musicTitles.isNotEmpty() && bookTitles.isNotEmpty()) {
      "both libraries must hold something, or the subset assertion below is vacuous"
    }

    val row = shuffleRow()
    queueFromTree(row)
    // **Not** `mediaItemCount > 0`: `MediaController.setMediaItem` masks locally to the one item it
    // sent, so that predicate is satisfied by the shuffle *row itself* before the session has
    // answered at all -- measured, and it read `["Shuffle Music"]` as the queue. Waiting for the
    // current item to stop being the row is a state only the session's answer produces.
    awaitCurrentItem { it != null && it != row.mediaId }

    val queued = onMain {
      (0 until browser.mediaItemCount)
        .mapNotNull { browser.getMediaItemAt(it).mediaMetadata.title?.toString() }
    }
    assertThat(queued).isNotEmpty
    // Read from the tree rather than written down: the seeded corpus grew while this was written.
    assertThat(queued.toSet()).isSubsetOf(musicTitles.toSet())
    assertThat(queued.toSet()).doesNotContainAnyElementsOf(bookTitles)
  }

  @Test
  fun aBrowsableOnlyRowTappedInTheCarQueuesNothing() {
    // Media3 gives `onAddMediaItems` no error channel, so "this is not something to play" arrives
    // as no items. Asserted because the alternative -- an empty `PlaybackQueue` -- throws inside a
    // future and reaches a driver as silence with no explanation.
    queueFromTree(MediaItem.Builder().setMediaId("muplay/artists").build())
    Thread.sleep(SETTLE_MILLIS)
    assertThat(onMain { browser.mediaItemCount }).isEqualTo(0)

    // The control: the same call, one line later, on a row that is playable.
    val album = musicAlbum()
    queueFromTree(album)
    awaitQueueOf(children(album.mediaId).size)
  }

  // ---- plumbing ---------------------------------------------------------------------------------

  private fun multiPartBook(): MediaItem =
    children("muplay/books")
      .firstOrNull { children(it.mediaId).size > 1 }
      ?: error("the audiobook library holds no multi-file book; this suite cannot observe a file index")

  private fun musicAlbum(): MediaItem =
    children("muplay/albums")
      .first { it.mediaId.startsWith("muplay/album/") && children(it.mediaId).size > 1 }

  private fun shuffleRow(): MediaItem =
    children("muplay/albums").first { it.mediaId.startsWith("muplay/shuffle/") }

  private fun childOf(parentId: String, index: Int): MediaItem = children(parentId)[index]

  /** Sets the queue from a browse row and prepares it, **without** playing. */
  private fun queueFromTree(item: MediaItem) {
    onMain {
      browser.pause()
      browser.setMediaItem(item)
      browser.prepare()
    }
  }

  /**
   * Writes a row for `files[index]` and **verifies it is the book's most recent**, retrying if not.
   *
   * `ProgressWriter` captures the item being left on `onMediaItemTransition`, from a coroutine, so
   * replacing a queue writes a row for the *previous* file some time afterwards. Measured twice,
   * and both orderings matter: with no verification at all the third observation read `1` instead
   * of `2`, and with verification but no quiet period the second read `0` instead of `1` -- the
   * stray write landing between the check and the tap.
   *
   * So the queue is emptied first, the writer is allowed to go quiet, and only then is the row
   * written and re-checked. Verified rather than slept on: the loop asserts the precondition the
   * test needs (this file's row is strictly the newest of the book's) instead of assuming a fixed
   * delay is long enough.
   */
  private fun makeMostRecentlyPlayed(files: List<String>, index: Int) {
    val deadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1_000
    var stamps: List<Long> = emptyList()
    while (SystemClock.elapsedRealtime() < deadline) {
      onMain {
        browser.stop()
        browser.clearMediaItems()
      }
      awaitProgressQuiet(files)
      store(files[index], positionMs = 1_000)
      stamps = stampsOf(files)
      val mine = stamps[index]
      if (stamps.withIndex().all { (i, at) -> i == index || at < mine }) return
    }
    throw AssertionError("could not make ${files[index]} the book's most recent row; saw $stamps")
  }

  /** Waits until two consecutive reads of the book's rows agree, i.e. nothing is still writing. */
  private fun awaitProgressQuiet(files: List<String>) {
    val deadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1_000
    var previous = stampsOf(files)
    while (SystemClock.elapsedRealtime() < deadline) {
      Thread.sleep(QUIET_MILLIS)
      val current = stampsOf(files)
      if (current == previous) return
      previous = current
    }
    throw AssertionError("progress writes never went quiet for $files")
  }

  private fun stampsOf(files: List<String>): List<Long> =
    runBlocking { files.map { progressDao().find(it)?.lastPlayedAtEpochMs ?: 0L } }

  private fun store(mediaId: String, positionMs: Long) = runBlocking {
    progressDao().upsert(
      MediaProgressEntity(
        mediaId = mediaId,
        positionMs = positionMs,
        // `lastPlayedAtEpochMs = now`, so this is unambiguously the most recent row for its book
        // however many earlier tests in this suite wrote one.
        isFinished = false,
        lastPlayedAtEpochMs = System.currentTimeMillis(),
        speed = 1.0f,
        skipSilence = false,
        gainDb = 0f,
      ),
    )
  }

  private fun progressDao(): MediaProgressDao =
    EntryPointAccessors.fromApplication(context, MediaProgressEntryPoint::class.java)
      .mediaProgressDao()

  private fun connect(hint: String?): MediaBrowser {
    val token = SessionToken(context, ComponentName(context, MuPlaybackService::class.java))
    val hints = Bundle().apply { hint?.let { putString(BrowseSurfaces.HINT_KEY, it) } }
    return onMain { MediaBrowser.Builder(context, token).setConnectionHints(hints).buildAsync() }
      .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }

  private fun children(parentId: String): List<MediaItem> =
    awaitResult { it.getChildren(parentId, 0, Int.MAX_VALUE, null) }.value.orEmpty()

  private fun <T> awaitResult(
    call: (MediaBrowser) -> ListenableFuture<LibraryResult<T>>,
  ): LibraryResult<T> = onMain { call(browser) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

  private fun awaitQueueOf(size: Int) = awaitQueue { it == size }

  /** Waits for the item the session chose, which is never the browse row that was sent. */
  private fun awaitCurrentItem(predicate: (String?) -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1_000
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate(onMain { browser.currentMediaItem?.mediaId })) return
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
      "the session never replaced the item the controller masked; " +
        "item=${onMain { browser.currentMediaItem?.mediaId }} " +
        "count=${onMain { browser.mediaItemCount }} error=${onMain { browser.playerError }}",
    )
  }

  /**
   * Waits for the **session's** answer to replace the single item the controller masked.
   *
   * Neither masking nor playback can produce a queue of a different length, which is what makes
   * this the one wait in this file that time cannot satisfy on its own.
   */
  private fun awaitQueue(predicate: (Int) -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1_000
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate(onMain { browser.mediaItemCount })) return
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
      "the session never answered with a queue of the expected size; " +
        "count=${onMain { browser.mediaItemCount }} state=${onMain { browser.playbackState }} " +
        "error=${onMain { browser.playerError }} item=${onMain { browser.currentMediaItem?.mediaId }}",
    )
  }

  private fun awaitPositionAtLeast(positionMs: Long): Long {
    val deadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1_000
    while (SystemClock.elapsedRealtime() < deadline) {
      val position = onMain { browser.currentPosition }
      if (position >= positionMs) return position
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
      "position never reached ${positionMs}ms; state=${onMain { browser.playbackState }} " +
        "isPlaying=${onMain { browser.isPlaying }} error=${onMain { browser.playerError }} " +
        "item=${onMain { browser.currentMediaItem?.mediaId }}",
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

  private companion object {
    const val TIMEOUT_SECONDS = 30L
    const val POLL_MILLIS = 50L

    /**
     * How long to let a request that must produce **nothing** settle before reading zero.
     *
     * A deadline cannot be waited out for the absence of an answer, so this is the one fixed sleep
     * in the file; it is generous against the round trip it covers (one in-process Binder call and
     * one Room read) and its assertion is paired with a control that the same call shape does
     * produce a queue.
     */
    const val SETTLE_MILLIS = 2_000L

    /** How long two reads of the progress rows must agree over before the writer is called quiet. */
    const val QUIET_MILLIS = 500L
  }
}
