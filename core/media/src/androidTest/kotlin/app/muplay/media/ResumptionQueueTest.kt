package app.muplay.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.media.browse.BrowseGraph
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What *"carry on"* resolves to, tested at the level that can be tested: the **decision** -- which
 * book, which file.
 *
 * That is where "carry on" can be wrong in a way nobody notices until a reboot. The callback that
 * asks this question is gated next door in `PlaybackResumptionTest`, on the real
 * `MuPlayLibraryCallback` a session is built with.
 *
 * The corpus is `BrowseGraph`'s nine-book, two-library seed -- six started books at six distinct
 * timestamps, one genuinely finished, one finished only on an early part, one with a position of
 * zero *in its third file*, and a music library that must never be an answer. With one book,
 * "the most recently heard unfinished book" and "the only book" are the same program.
 */
@RunWith(AndroidJUnit4::class)
class ResumptionQueueTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var graph: BrowseGraph

  @After
  fun tearDown() {
    if (::graph.isInitialized) graph.close()
  }

  private fun seed(
    withProgress: Boolean = true,
    withAudiobooks: Boolean = true,
  ): BrowseGraph = BrowseGraph.create(
    context,
    withProgress = withProgress,
    withAudiobooks = withAudiobooks,
  ).also { graph = it }

  private suspend fun store(
    mediaId: String,
    positionMs: Long,
    at: Long,
    finished: Boolean = false,
  ) = graph.database.mediaProgressDao()
    .upsert(MediaProgressEntity(mediaId, positionMs, finished, at, 1f, false, 0f))

  private suspend fun subject() = graph.resumptionQueue.mostRecent()

  @Test
  fun theMostRecentlyHeardUnfinishedBookComesBackAtTheFileItWasLeftIn(): Unit = runBlocking {
    // `bk-test` is the three-file book whose seeded rows sit on parts one AND two, with part two
    // written later. Pushed to the top of the shelf here, so the answer is a book whose start index
    // is neither 0 nor `lastIndex` -- two values a wrong implementation reaches by accident.
    seed()
    store("bk-test-p2", positionMs = 20_000L, at = 9_000L)

    val queue = subject()!!

    assertThat(queue.songs.map { it.id }).containsExactly("bk-test-p1", "bk-test-p2", "bk-test-p3")
    assertThat(queue.startIndex).isEqualTo(1)
  }

  @Test
  fun anotherBookHeardMoreRecentlyIsTheOneThatComesBack(): Unit = runBlocking {
    // The pair for the test above, and the one it cannot make on its own: with a single
    // observation, "the most recently heard book" and "this particular book" are the same claim.
    // Two runs over the same seed, differing only in which row is newest.
    seed()
    store("bk-beta-p1", positionMs = 40_000L, at = 9_000L)

    val queue = subject()!!

    assertThat(queue.songs.map { it.id }).containsExactly("bk-beta-p1", "bk-beta-p2")
    assertThat(queue.startIndex).isZero
  }

  @Test
  fun aFinishedBookIsNotWhatYouCarryOnWith(): Unit = runBlocking {
    // `bk-test` is finished by finishing its **last** file, which is what finishing a book means --
    // `bk-gamma` in this seed is finished only on part one of two and must stay an answer.
    seed()
    store("bk-test-p3", positionMs = 300_000L, at = 9_000L, finished = true)

    val queue = subject()!!

    // The next unfinished book down the shelf, not the finished one that was heard most recently.
    assertThat(queue.songs.map { it.id }).containsExactly("bk-second-p1", "bk-second-p2")
  }

  /**
   * The `!it.isFinished` half of the filter, actually **evaluated** -- which the test above never
   * manages, and that is worth reading before trusting either of them.
   *
   * `BookSummaries.order` groups the shelf in-progress, then unstarted, then **finished last**, and
   * `firstOrNull` stops at its first match. So on any shelf that holds an in-progress book at all,
   * the finished one is never looked at: `aFinishedBookIsNotWhatYouCarryOnWith` passes because the
   * answer sorts *ahead* of `bk-test`, not because the predicate rejected it. Deleting
   * `&& !it.isFinished` leaves that test green.
   *
   * Measured, and this is how it surfaced: with all eight of this class's other tests running, the
   * `isFinished == true` arm of this predicate was the one uncovered branch in `ResumptionQueue`'s
   * BRANCH ratio (6/8, floor 0.90) -- the filter had never once rejected anything.
   *
   * Here the finished book is the **only** started book on the shelf, so the loop has to reach it,
   * ask the question, and reject it. `bk-tail` is a one-file book, so its only file is also its
   * last and `isFinished` on that row is the whole book finished; `withProgress = false` leaves
   * every other book unstarted, which is what makes this one the only thing the filter can be asked
   * about. Delete `&& !it.isFinished` and this returns `bk-tail` instead of null.
   */
  @Test
  fun aShelfWhoseOnlyStartedBookIsFinishedHasNothingToCarryOnWith(): Unit = runBlocking {
    seed(withProgress = false)
    store("bk-tail-p1", positionMs = 100_000L, at = 9_000L, finished = true)

    assertThat(subject()).isNull()
  }

  @Test
  fun aBookFinishedOnlyPartWayThroughIsStillWhatYouCarryOnWith(): Unit = runBlocking {
    // The control for the test above. `bk-gamma` carries `isFinished = true` on part one of two --
    // a listener who reached the end of a chapter -- and a rule reading `isFinished` off any row
    // would take it off the shelf the first time a chapter ran out.
    seed()
    store("bk-gamma-p1", positionMs = 60_000L, at = 9_000L, finished = true)

    val queue = subject()!!

    assertThat(queue.songs.map { it.id }).containsExactly("bk-gamma-p1", "bk-gamma-p2")
  }

  @Test
  fun aMusicTrackIsNeverWhatYouCarryOnWith(): Unit = runBlocking {
    // Pressing play after a reboot and getting a random song is a worse answer than getting
    // nothing, and it is exactly what happens if "most recent" is read off `media_progress`
    // directly. The music row is the newest thing in the table by a wide margin.
    seed()
    store("tr-a1", positionMs = 4_000L, at = 99_999L)

    val queue = subject()!!

    assertThat(queue.songs.map { it.id }).doesNotContainAnyElementsOf(BrowseGraph.MUSIC_SONG_IDS)
    assertThat(queue.songs.map { it.id }).containsExactly("bk-second-p1", "bk-second-p2")
  }

  @Test
  fun aLibraryTheUserNeverTaggedAsBooksIsNotAShelfToCarryOnFrom(): Unit = runBlocking {
    // The same rows, the same positions, one tag withheld: nothing to carry on with. Without this,
    // "the answer is a book" and "the answer is whatever library 2 holds" are the same claim.
    seed(withAudiobooks = false)
    store("tr-a1", positionMs = 4_000L, at = 99_999L)

    assertThat(subject()).isNull()
  }

  @Test
  fun nothingHeardMeansNothingToCarryOnWith(): Unit = runBlocking {
    // A full shelf of books, none of them started. Failing the resumption future is what tells the
    // system to leave the shade's resumption control alone rather than starting silence.
    seed(withProgress = false)

    assertThat(subject()).isNull()
  }

  @Test
  fun aBookOpenedAtPositionZeroInALaterFileStillCountsAsStarted(): Unit = runBlocking {
    // `bk-multi`'s seeded row is position **0 in its third file** -- the one case where "the row
    // says 0" and "the listener has not started" are different answers, and the one a
    // `positionMs > 0` test on the *row* rather than on the *book* gets wrong.
    seed()
    store("bk-multi-p3", positionMs = 0L, at = 9_000L)

    val queue = subject()!!

    assertThat(queue.songs.map { it.id })
      .containsExactly("bk-multi-p1", "bk-multi-p2", "bk-multi-p3", "bk-multi-p4")
    assertThat(queue.startIndex).isEqualTo(2)
  }

  @Test
  fun theQueueCarriesNoPositionOfItsOwn(): Unit = runBlocking {
    // Spec section 3, at this layer: the queue names an item and `AudiobookResumePolicy` supplies
    // the position when `MuPlayer` sets the items. Two places deciding a position is how they come
    // to disagree, and `PlaybackQueue`'s declared fields are what stop a second one appearing here.
    seed()

    // The reflection half of this used to live here too, with a `filterNot { it.isSynthetic }`.
    // That filter is wrong -- a Kotlin `companion object` compiles to a non-synthetic
    // `public static final PlaybackQueue$Companion Companion` field, so it reported
    // `["Companion", "songs", "startIndex"]` and failed against a *correct* PlaybackQueue.
    //
    // It is gone rather than fixed: `PlaybackQueueTest.the queue carries no playback position of
    // its own` already makes exactly this assertion on the JVM tier, with the filter that names
    // `Companion` alone -- deliberately not a blanket `isStatic`, because a companion's property
    // backing fields are statics on the containing class and `companion object { var positionMs }`
    // is the worst form of the defect. Read that test's KDoc before reinstating anything here.
    // A device is not needed to reflect over a data class, and duplicating it here bought nothing.
    assertThat(subject()).isNotNull
  }
}
