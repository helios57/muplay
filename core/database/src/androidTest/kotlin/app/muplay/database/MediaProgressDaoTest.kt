package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented, not a JVM unit test, and not Robolectric — Robolectric is banned project-wide.
 * Room needs the Android framework's SQLite, so the strongest rung available is the real thing
 * on a real device: real Room codegen, real SQL, real SQLite. That puts this class in Tier 2's
 * emulator run, which is required to merge, and its execution data is what
 * `:core:database`'s instrumented coverage floors are measured from.
 */
@RunWith(AndroidJUnit4::class)
class MediaProgressDaoTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: MediaProgressDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    dao = db.mediaProgressDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun unknownMediaHasNoProgress() = runTest {
    assertThat(dao.find("does-not-exist")).isNull()
  }

  @Test
  fun progressRoundTrips() = runTest {
    dao.upsert(
      MediaProgressEntity(
        mediaId = "book-1",
        positionMs = 123_456L,
        isFinished = false,
        lastPlayedAtEpochMs = 1_000L,
        speed = 1.5f,
        skipSilence = true,
        gainDb = -3.5f,
      ),
    )

    val found = dao.find("book-1")
    assertThat(found).isNotNull
    assertThat(found!!.positionMs).isEqualTo(123_456L)
    assertThat(found.speed).isEqualTo(1.5f)
    assertThat(found.skipSilence).isTrue
    assertThat(found.gainDb).isEqualTo(-3.5f)
  }

  /**
   * The failure mode this schema exists to prevent, and the user's original complaint as a test:
   * playing a different item must not disturb the first item's position. A book keeps its place
   * across a music session because music's progress lives in a different row, not because
   * anything remembers to put it back.
   */
  @Test
  fun progressForOneItemSurvivesPlayingAnother() = runTest {
    dao.upsert(MediaProgressEntity("book-1", 900_000L, false, 1_000L, 1.0f, false, 0f))
    dao.upsert(MediaProgressEntity("song-1", 30_000L, false, 2_000L, 1.0f, false, 0f))

    assertThat(dao.find("book-1")!!.positionMs).isEqualTo(900_000L)
  }

  @Test
  fun upsertReplacesTheSameMediaId() = runTest {
    dao.upsert(MediaProgressEntity("book-1", 100L, false, 1L, 1.0f, false, 0f))
    dao.upsert(MediaProgressEntity("book-1", 200L, false, 2L, 1.0f, false, 0f))

    assertThat(dao.find("book-1")!!.positionMs).isEqualTo(200L)
    assertThat(dao.findAll()).hasSize(1)
  }

  @Test
  fun recentlyPlayedExcludesFinishedItemsAndOrdersByMostRecent() = runTest {
    dao.upsert(MediaProgressEntity("old", 1L, false, 1_000L, 1.0f, false, 0f))
    dao.upsert(MediaProgressEntity("new", 1L, false, 3_000L, 1.0f, false, 0f))
    dao.upsert(MediaProgressEntity("done", 1L, true, 9_000L, 1.0f, false, 0f))

    assertThat(dao.recentlyPlayed(limit = 10).map { it.mediaId })
      .containsExactly("new", "old")
  }

  /**
   * `runBlocking`, not `runTest`, and only for the Flow tests: Room's invalidation tracker emits
   * from its own executor in real time, and a Turbine timeout inside `runTest` resolves against a
   * virtual clock instead.
   */
  @Test
  fun observeAllEmitsTheCurrentRowsAndThenEveryChange() = runBlocking {
    dao.upsert(MediaProgressEntity("a", 1_000L, false, 10L, 1f, false, 0f))

    dao.observeAll().test {
      assertThat(awaitItem().map { it.mediaId }).containsExactly("a")
      dao.upsert(MediaProgressEntity("b", 2_000L, false, 20L, 1f, false, 0f))
      // Two emissions, two different contents. The resume snapshot (Task 6) is built on this Flow;
      // an `observeAll` that emitted once would make every resume answer the app's first second of
      // life forever.
      assertThat(awaitItem().map { it.mediaId }).containsExactlyInAnyOrder("a", "b")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun findInReturnsOnlyTheRequestedIdsAndOnlyTheOnesThatExist() = runTest {
    dao.upsert(MediaProgressEntity("a", 1_000L, false, 10L, 1f, false, 0f))
    dao.upsert(MediaProgressEntity("b", 2_000L, false, 20L, 1f, false, 0f))
    dao.upsert(MediaProgressEntity("c", 3_000L, false, 30L, 1f, false, 0f))

    val found = dao.findIn(listOf("a", "c", "missing"))

    // Exact contents, and a positive control ("b" exists and must not appear). `hasSize(2)` alone
    // would be satisfied by returning "a" and "b".
    assertThat(found.map { it.mediaId }).containsExactlyInAnyOrder("a", "c")
    assertThat(found.map { it.positionMs }).containsExactlyInAnyOrder(1_000L, 3_000L)
  }

  @Test
  fun findInWithNoIdsFindsNothingRatherThanEverything() = runTest {
    // `WHERE mediaId IN ()` is the shape that silently becomes "no filter" when someone rewrites
    // the query, and one book's empty file list is a reachable caller.
    dao.upsert(MediaProgressEntity("a", 1_000L, false, 10L, 1f, false, 0f))

    assertThat(dao.findIn(emptyList())).isEmpty()
  }

  @Test
  fun clearRemovesExactlyTheGivenIds() = runTest {
    // "Start this book from the beginning" is expressed by clearing progress, because the
    // ForwardingPlayer seam (Plan 3 Task 8) forbids handing the player a position -- correctly.
    dao.upsert(MediaProgressEntity("a", 1_000L, false, 10L, 1.4f, true, 0f))
    dao.upsert(MediaProgressEntity("b", 2_000L, false, 20L, 1f, false, 0f))
    dao.upsert(MediaProgressEntity("c", 3_000L, false, 30L, 1f, false, 0f))

    dao.clear(listOf("a", "c"))

    assertThat(dao.find("a")).isNull()
    assertThat(dao.find("c")).isNull()
    // The survivor is asserted by value, not just by presence: a `clear` that deleted everything
    // and a `clear` that deleted the right two are the same thing to an `isNull` pair alone.
    assertThat(dao.find("b")?.positionMs).isEqualTo(2_000L)
  }
}
