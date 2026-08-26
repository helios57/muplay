package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.muplay.database.dao.BookSettingsDao
import app.muplay.database.entity.BookSettingsEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `book_settings`, the table that exists because per-item speed is the wrong grain for a book made
 * of thirty MP3s.
 *
 * `runBlocking` rather than `runTest`, deliberately, and it matters for the two Flow tests:
 * Room's invalidation tracker emits from its own executor in **real** time, while `runTest` runs
 * the body against a virtual clock that a Turbine timeout would resolve against instantly. The
 * plain suspend tests would be fine either way; using one builder for the whole class keeps the
 * distinction from looking like an accident.
 */
@RunWith(AndroidJUnit4::class)
class BookSettingsDaoTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: BookSettingsDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    dao = db.bookSettingsDao()
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun twoBooksKeepTwoSpeeds() = runBlocking {
    // The single most important property of this table, and the reason it is keyed on the book.
    // With one book, "the speed for book X" and "the speed" are the same value.
    dao.upsert(BookSettingsEntity("book-1", speed = 1.4f, skipSilence = true))
    dao.upsert(BookSettingsEntity("book-2", speed = 0.9f, skipSilence = false))

    assertThat(listOf(dao.find("book-1")!!.speed, dao.find("book-2")!!.speed))
      .containsExactly(1.4f, 0.9f)
    // Both fields, and both differ between the two books: a `find` that returned the first row of
    // the table would agree with a one-field assertion on whichever book was written first.
    assertThat(listOf(dao.find("book-1")!!.skipSilence, dao.find("book-2")!!.skipSilence))
      .containsExactly(true, false)
    assertThat(dao.find("book-1")!!.bookId).isEqualTo("book-1")
    assertThat(dao.find("book-2")!!.bookId).isEqualTo("book-2")
  }

  @Test
  fun aBookNobodyHasTouchedHasNoRow() = runBlocking {
    // `null` and "the defaults" are different facts; the repository turns one into the other
    // (Task 4) and the DAO must not pre-empt it.
    dao.upsert(BookSettingsEntity("book-1", speed = 1.4f, skipSilence = true))

    assertThat(dao.find("never-opened")).isNull()
  }

  @Test
  fun upsertingTheSameBookReplacesRatherThanDuplicating() = runBlocking {
    dao.upsert(BookSettingsEntity("book-1", speed = 1.0f, skipSilence = false))
    dao.upsert(BookSettingsEntity("book-1", speed = 2.2f, skipSilence = true))
    dao.upsert(BookSettingsEntity("book-2", speed = 1.0f, skipSilence = false))

    assertThat(dao.find("book-1")!!.speed).isEqualTo(2.2f)
    assertThat(dao.find("book-1")!!.skipSilence).isTrue
    // The neighbour proves the row count is not just "one because only one book was written".
    assertThat(dao.observeAllOnce().map { it.bookId }).containsExactlyInAnyOrder("book-1", "book-2")
  }

  @Test
  fun observingABookEmitsItsCurrentValueAndThenEveryChange() = runBlocking {
    dao.upsert(BookSettingsEntity("book-1", speed = 1.0f, skipSilence = false))

    dao.observe("book-1").test {
      assertThat(awaitItem()?.speed).isEqualTo(1.0f)
      dao.upsert(BookSettingsEntity("book-1", speed = 1.6f, skipSilence = false))
      // Two distinct values from one Flow. An `observe` that emitted once and stopped would pass
      // an assertion on the first value alone.
      assertThat(awaitItem()?.speed).isEqualTo(1.6f)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun observingEveryBookEmitsTheWholeTableAndThenEveryChange() = runBlocking {
    dao.upsert(BookSettingsEntity("book-1", speed = 1.4f, skipSilence = true))

    dao.observeAll().test {
      assertThat(awaitItem().map { it.bookId }).containsExactly("book-1")
      dao.upsert(BookSettingsEntity("book-2", speed = 0.9f, skipSilence = false))
      // Two emissions, two different contents -- the snapshot Tasks 6 and 7 read is built on this
      // Flow, and one that emitted once would freeze every book's speed at app start.
      assertThat(awaitItem().map { it.bookId }).containsExactlyInAnyOrder("book-1", "book-2")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun aNeighboursWriteDoesNotMoveThisBooksSettings() = runBlocking {
    // The read-modify-write trap Plan 3 named, one table over. Whatever writes speed must not
    // write anything else, and whatever writes silence skipping must not write speed.
    dao.upsert(BookSettingsEntity("book-1", speed = 1.4f, skipSilence = true))
    dao.upsert(BookSettingsEntity("book-2", speed = 3.0f, skipSilence = false))

    val untouched = dao.find("book-1")!!
    assertThat(untouched.speed).isEqualTo(1.4f)
    assertThat(untouched.skipSilence).isTrue
  }

  private suspend fun BookSettingsDao.observeAllOnce(): List<BookSettingsEntity> {
    var seen: List<BookSettingsEntity> = emptyList()
    observeAll().test {
      seen = awaitItem()
      cancelAndIgnoreRemainingEvents()
    }
    return seen
  }
}
