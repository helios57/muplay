package app.muplay.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.entity.BookSettingsEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.media.browse.BrowseGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The in-memory view the resume policy answers from, over a **real** Room.
 *
 * The test that matters most here is the **cold** one: a snapshot whose collector has never run must
 * still answer correctly after `refresh()`. If it does not, the first book played after a process
 * start resumes at zero -- once a month, on a slow device, and never in a suite that happened to
 * warm it up first.
 *
 * ### The corpus is `BrowseGraph`'s, not a new one
 *
 * The plan for this task asked for a fresh `AudiobookFixtures.seed(db)` in `core/testing`, shared
 * with `:core:database`'s `AudiobookRepositoryTest`. That is not possible as written -- `:core:
 * testing` is a `muplay.jvm.library` with no Room on its classpath at all, and it cannot see
 * `MuPlayDatabase` -- and it is not necessary either: this module already has a nine-book,
 * two-library seed behind a real `AudiobookRepository`, used by six suites, whose progress rows
 * were chosen to make exactly the distinctions this file needs (books on different files, a book
 * with no rows, a finished row on a part rather than a book). Adding a fifth corpus is what the
 * plan's own warning about drift is about.
 *
 * Test names are camelCase and every `@Test` declares `: Unit`: `minSdk 26` compiles DEX 035, which
 * forbids a space in any SimpleName, and a `runBlocking` block whose last expression is an AssertJ
 * assertion makes the method non-`void`, which JUnit 4 refuses at class-load time.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookSnapshotTest {

  private lateinit var context: Context
  private lateinit var graph: BrowseGraph
  private lateinit var scope: CoroutineScope

  private val snapshot: AudiobookSnapshot get() = graph.audiobookSnapshot

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    graph = BrowseGraph.create(context)
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }

  @After
  fun tearDown() {
    // Guarded rather than assumed initialised: a failure inside `setUp` before these are assigned
    // would otherwise replace the real cause with an `UninitializedPropertyAccessException` from
    // here, which is the only message the report would carry.
    if (::scope.isInitialized) scope.cancel()
    if (::graph.isInitialized) graph.close()
  }

  private suspend fun store(mediaId: String, positionMs: Long, finished: Boolean = false) =
    graph.database.mediaProgressDao().upsert(
      MediaProgressEntity(mediaId, positionMs, finished, STORED_AT_MS, 1f, false, 0f),
    )

  @Test
  fun aColdSnapshotAnswersCorrectlyAfterRefreshWithNoCollectorRunning(): Unit = runBlocking {
    store("bk-multi-p2", 3_500L)

    // Deliberately no `start(scope)`. This is the process-start path, and it is the one the
    // resumption callback runs on.
    snapshot.refresh()

    assertThat(snapshot.itemFor("bk-multi-p2")?.positionMs).isEqualTo(3_500L)
    // A second file of the same book, whose row `BrowseGraph` seeds at a different position: with
    // one observation, "refresh read the table" and "refresh read one row" are the same claim.
    assertThat(snapshot.itemFor("bk-multi-p4")?.positionMs).isEqualTo(5_000L)
    assertThat(snapshot.isLoaded).isTrue
  }

  @Test
  fun aSnapshotNobodyStartedOrRefreshedKnowsNothing(): Unit = runBlocking {
    store("bk-multi-p2", 3_500L)

    // The control for the test above. Without it, "refresh() works" and "the constructor works"
    // are the same claim, and the cold-start race would be invisible.
    assertThat(snapshot.itemFor("bk-multi-p2")).isNull()
    assertThat(snapshot.items()).isEmpty()
    assertThat(snapshot.isLoaded).isFalse
  }

  @Test
  fun theCollectorKeepsTheSnapshotCurrent(): Unit = runBlocking {
    snapshot.start(scope)
    withTimeout(AWAIT_MS) { snapshot.awaitLoaded() }
    assertThat(snapshot.itemFor("bk-nine-p1")?.positionMs)
      .describedAs("bk-nine has no seeded progress row")
      .isZero

    store("bk-nine-p1", 7_777L)

    // Two observations of the same field, the second after a write. A snapshot that loaded once
    // and stopped would pass an assertion on the first alone -- and would hand back a stale
    // position for the rest of the process's life, which is what "my book forgot where it was"
    // looks like from the outside.
    awaitValue("bk-nine-p1 to reach 7777") { snapshot.itemFor("bk-nine-p1")?.positionMs == 7_777L }
  }

  @Test
  fun theCollectorAlsoFollowsTheBooksSettings(): Unit = runBlocking {
    // The other table the snapshot combines, and the one a collector wired to `media_progress`
    // alone would silently drop: a speed set while a book is playing has to reach the player.
    snapshot.start(scope)
    withTimeout(AWAIT_MS) { snapshot.awaitLoaded() }
    assertThat(snapshot.itemFor("bk-beta-p1")?.speed).isEqualTo(1.0f)

    graph.database.bookSettingsDao().upsert(BookSettingsEntity("bk-beta", 1.7f, true))

    awaitValue("bk-beta-p1 to reach 1.7x") { snapshot.itemFor("bk-beta-p1")?.speed == 1.7f }
    assertThat(snapshot.itemFor("bk-beta-p1")?.skipSilence).isTrue
  }

  @Test
  fun onlyAudiobookFilesAreInTheSnapshot(): Unit = runBlocking {
    // A music track with a real, recent progress row -- the state spec section 3 is about. It is
    // written here rather than seeded because `BrowseGraph`'s music library deliberately carries
    // no progress at all, so "no music id is in the map" would otherwise be true of a table that
    // never had one.
    store("tr-a1", 4_000L)
    store("bk-multi-p2", 3_500L)

    snapshot.refresh()

    // Exact keys, derived from the seed rather than written out. A snapshot built from
    // `media_progress` rather than from the audiobook item map would contain "tr-a1", and that one
    // extra key is the whole of "music resumes too".
    assertThat(snapshot.items().keys)
      .containsExactlyInAnyOrderElementsOf(BrowseGraph.BOOK_SONG_IDS)
    assertThat(snapshot.items().keys).doesNotContainAnyElementsOf(BrowseGraph.MUSIC_SONG_IDS)
    assertThat(snapshot.itemFor("tr-a1")).isNull()
    // The premise: both lists are real, so neither assertion above is vacuous.
    assertThat(BrowseGraph.BOOK_SONG_IDS).isNotEmpty
    assertThat(BrowseGraph.MUSIC_SONG_IDS).isNotEmpty
  }

  @Test
  fun aLibraryTheUserNeverTaggedAsBooksIsNotInTheSnapshotEither(): Unit = runBlocking {
    // The same rule from the other side, and the one that says the map is keyed off the **role**
    // rather than off "library 2": with the tag withheld, the identical rows produce no entries at
    // all. A rule that hardcoded a library id, or that treated every non-music library as books,
    // passes the test above and fails here.
    graph.close()
    graph = BrowseGraph.create(context, withAudiobooks = false)
    graph.database.mediaProgressDao()
      .upsert(MediaProgressEntity("bk-multi-p2", 3_500L, false, STORED_AT_MS, 1f, false, 0f))

    graph.audiobookSnapshot.refresh()

    assertThat(graph.audiobookSnapshot.items()).isEmpty()
    assertThat(graph.audiobookSnapshot.itemFor("bk-multi-p2")).isNull()
  }

  @Test
  fun anAudiobookFileWithNoProgressRowIsStillInTheSnapshot(): Unit = runBlocking {
    // Because the snapshot also carries `speed` and `skipSilence`, and a book whose settings were
    // set before it was ever played must still play at that speed. `bk-nine` is the seed's
    // never-opened book.
    graph.database.bookSettingsDao().upsert(BookSettingsEntity("bk-nine", 1.4f, true))

    snapshot.refresh()

    val item = snapshot.itemFor("bk-nine-p1")!!
    assertThat(item.positionMs).isZero
    assertThat(item.lastPlayedAtEpochMs).isZero
    assertThat(item.isFinished).isFalse
    assertThat(item.speed).isEqualTo(1.4f)
    assertThat(item.skipSilence).isTrue
    // ...and another book keeps the defaults, so "1.4" is not a constant and "true" is not either.
    assertThat(snapshot.itemFor("bk-beta-p1")!!.speed).isEqualTo(1.0f)
    assertThat(snapshot.itemFor("bk-beta-p1")!!.skipSilence).isFalse
  }

  @Test
  fun everyFileOfABookCarriesTheBooksSettings(): Unit = runBlocking {
    graph.database.bookSettingsDao().upsert(BookSettingsEntity("bk-multi", 1.6f, false))

    snapshot.refresh()

    // The whole reason `book_settings` is keyed by book. Per-item storage gives four different
    // answers here; the book's grain gives one, and this is the assertion that says which.
    assertThat(listOf("bk-multi-p1", "bk-multi-p2", "bk-multi-p3", "bk-multi-p4")
      .map { snapshot.itemFor(it)!!.speed })
      .containsExactly(1.6f, 1.6f, 1.6f, 1.6f)
    // ...and the book id really is per book, which is what makes that lookup possible at all.
    assertThat(snapshot.itemFor("bk-multi-p1")!!.bookId).isEqualTo("bk-multi")
    assertThat(snapshot.itemFor("bk-beta-p1")!!.bookId).isEqualTo("bk-beta")
  }

  @Test
  fun aSpeedNoPlayerCouldAcceptIsClampedOnTheWayOut(): Unit = runBlocking {
    // A hand-edited database, a future bug, or arithmetic on a corrupt `REAL` column.
    //
    // **One row, not two.** This test also inserted `BookSettingsEntity("bk-multi", Float.NaN, ..)`
    // and died on the insert, not the assertion:
    //
    //     SQLiteConstraintException: NOT NULL constraint failed: book_settings.speed (code 1299)
    //
    // SQLite has no NaN. A `REAL` bound to NaN is stored as **NULL**, so a NOT NULL column rejects
    // it and the corrupt-column premise cannot be created through Room at all. The clamp is real
    // and still gated, on the tier where NaN exists: `BookSettingsTest.clampSpeed(Float.NaN)` and
    // `BookPlaybackSettingsTest`'s NaN case, both JVM. What only a device can show is the path
    // *through Room*, which is the out-of-range row below.
    graph.database.bookSettingsDao().upsert(BookSettingsEntity("bk-beta", 40f, false))

    snapshot.refresh()

    assertThat(snapshot.itemFor("bk-beta-p1")!!.speed).isEqualTo(3.0f)
  }

  @Test
  fun startingTwiceDoesNotLeaveTwoCollectorsWritingOneField(): Unit = runBlocking {
    // `MuPlaybackService.onCreate` runs again after the service is destroyed and recreated, and
    // the snapshot is a `@Singleton`. Two collectors over one field is two writers racing for the
    // rest of the process's life -- silent, and only visible as an occasional stale position.
    snapshot.start(scope)
    snapshot.start(scope)
    withTimeout(AWAIT_MS) { snapshot.awaitLoaded() }

    // Observed rather than asserted on a field: stop the (single) collector and show the snapshot
    // has genuinely stopped following the table. With two collectors running, one `stop()` cancels
    // one job and the other keeps writing, so this reaches 9999 and the assertion fires.
    snapshot.stop()
    graph.database.mediaProgressDao()
      .upsert(MediaProgressEntity("bk-nine-p1", 9_999L, false, STORED_AT_MS, 1f, false, 0f))
    Thread.sleep(SETTLE_MS)

    assertThat(snapshot.itemFor("bk-nine-p1")?.positionMs)
      .describedAs("a stopped snapshot must stop following the table")
      .isZero
  }

  private fun awaitValue(description: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + AWAIT_MS
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(POLL_MS)
    }
    throw AssertionError("timed out waiting for $description")
  }

  private companion object {
    const val STORED_AT_MS = 900L
    const val AWAIT_MS = 10_000L
    const val POLL_MS = 50L

    /** Long enough that a still-running collector would have overwritten the field by now. */
    const val SETTLE_MS = 1_500L
  }
}
