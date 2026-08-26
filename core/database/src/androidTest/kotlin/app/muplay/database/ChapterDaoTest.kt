package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.dao.ChapterDao
import app.muplay.database.entity.ChapterEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real in-memory Room and real SQL -- rung 2 of the test hierarchy, not a fake.
 *
 * The subject worth stating: **chapter order is a property**. SQLite makes no promise about the
 * order rows come back in without an `ORDER BY`, and on a small table it very often *happens* to
 * return insertion order, which is exactly how a missing `ORDER BY` ships. Every read assertion
 * here inserts out of order on purpose.
 *
 * Method names are camelCase, not backticked: `minSdk 26` compiles DEX 035, which forbids spaces
 * in any SimpleName, and D8 fails the build outright on the backticked form this project's JVM
 * tier uses.
 */
@RunWith(AndroidJUnit4::class)
class ChapterDaoTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: ChapterDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    dao = db.chapterDao()
  }

  @After
  fun tearDown() = db.close()

  private fun chapter(mediaId: String, index: Int, start: Long, end: Long, title: String?) =
    ChapterEntity(
      mediaId = mediaId,
      chapterIndex = index,
      startMs = start,
      endMs = end,
      title = title,
    )

  @Test
  fun chaptersComeBackInIndexOrderNoMatterWhatOrderTheyWentIn(): Unit = runBlocking {
    // Five, not four, and inserted in an order that is neither sorted nor reversed: on a
    // four-row table SQLite's scan very often happens to return insertion order, and a test
    // that only ever sees that cannot tell a present `ORDER BY` from an absent one.
    dao.store(
      "book-1",
      listOf(
        chapter("book-1", 2, 9_000, 15_000, "A Turn"),
        chapter("book-1", 0, 0, 4_000, "Prologue"),
        chapter("book-1", 4, 21_000, 26_500, "Afterword"),
        chapter("book-1", 3, 15_000, 21_000, "Epilogue"),
        chapter("book-1", 1, 4_000, 9_000, "The Long Middle"),
      ),
      scannedAtEpochMs = 1_700_000_000_000L,
    )

    // `containsExactly`, in order. `containsExactlyInAnyOrder` here would let a DAO with no
    // ORDER BY pass, and a book whose chapters come back shuffled plays its epilogue third.
    assertThat(dao.find("book-1").map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue", "Afterword")
    assertThat(dao.find("book-1").map { it.startMs })
      .containsExactly(0L, 4_000L, 9_000L, 15_000L, 21_000L)
    // Every column round-trips, not just the two the ordering assertions read: an entity that
    // dropped `endMs` on the way in would satisfy both of the assertions above.
    assertThat(dao.find("book-1").map { it.endMs })
      .containsExactly(4_000L, 9_000L, 15_000L, 21_000L, 26_500L)
    assertThat(dao.find("book-1").map { it.chapterIndex }).containsExactly(0, 1, 2, 3, 4)
  }

  @Test
  fun oneFilesChaptersAreNotAnotherFilesChapters(): Unit = runBlocking {
    // Two files, two answers. With one file in the table, "chapters for X" and "every chapter
    // there is" are the same query.
    dao.store("book-1", listOf(chapter("book-1", 0, 0, 5_000, "Chapter 1")), 1L)
    dao.store(
      "book-2",
      listOf(chapter("book-2", 0, 0, 7_000, "Head"), chapter("book-2", 1, 7_000, 12_000, "Tail")),
      1L,
    )

    assertThat(dao.find("book-1").map { it.title }).containsExactly("Chapter 1")
    assertThat(dao.find("book-2").map { it.title }).containsExactly("Head", "Tail")
    // Neither answer is "everything": a `find` that ignored its argument would return three rows.
    assertThat(dao.find("book-1")).hasSize(1)
    assertThat(dao.find("book-2")).hasSize(2)
  }

  @Test
  fun aFileWithNoChaptersIsARecordedAnswerAndNotAMissingOne(): Unit = runBlocking {
    // The whole reason `chapter_scans` exists. `find` returning empty is ambiguous between "no
    // chapters" and "never looked"; `findScan` is not. Without this distinction every chapterless
    // file is re-probed over HTTP on every screen open, which is the common case and not the rare
    // one -- the corpus's own `Multi Part Book` is three such files.
    dao.store("part-one", chapters = emptyList(), scannedAtEpochMs = 42L)

    assertThat(dao.find("part-one")).isEmpty()
    assertThat(dao.findScan("part-one")?.chapterCount).isEqualTo(0)
    assertThat(dao.findScan("part-one")?.scannedAtEpochMs).isEqualTo(42L)
    // ...and a file nobody looked at is distinguishable from that.
    assertThat(dao.findScan("never-probed")).isNull()
  }

  @Test
  fun storingAgainReplacesRatherThanAccumulates(): Unit = runBlocking {
    dao.store(
      "book-1",
      listOf(chapter("book-1", 0, 0, 5_000, "old"), chapter("book-1", 1, 5_000, 9_000, "older")),
      1L,
    )

    dao.store("book-1", listOf(chapter("book-1", 0, 0, 4_000, "new")), 2L)

    // Not `hasSize(1)` alone: a store that inserted without deleting would leave "older" behind at
    // index 1 and produce a book with a chapter that no longer exists in the file. Note that the
    // primary key alone does NOT catch this -- index 1 is not index 0, so nothing conflicts.
    assertThat(dao.find("book-1").map { it.title }).containsExactly("new")
    assertThat(dao.findScan("book-1")?.chapterCount).isEqualTo(1)
    assertThat(dao.findScan("book-1")?.scannedAtEpochMs).isEqualTo(2L)
  }

  @Test
  fun clearingAFileTakesItsChaptersWithIt(): Unit = runBlocking {
    dao.store("book-1", listOf(chapter("book-1", 0, 0, 5_000, "Chapter 1")), 1L)
    dao.store("book-2", listOf(chapter("book-2", 0, 0, 7_000, "Head")), 1L)

    dao.clear("book-1")

    assertThat(dao.find("book-1")).isEmpty()
    assertThat(dao.findScan("book-1")).isNull()
    // The control: the cascade must not take the neighbour with it, and `clear` must not be a
    // `DELETE FROM chapter_scans` with no WHERE.
    assertThat(dao.find("book-2").map { it.title }).containsExactly("Head")
    assertThat(dao.findScan("book-2")).isNotNull
  }

  @Test
  fun aChapterWithNoTitleIsStoredAsNullAndComesBackAsNull(): Unit = runBlocking {
    // Spike S3 observed a trailing, empty-titled chapter on one `chap` fixture. A `String?` column
    // that silently became "" would make "untitled" and "titled empty" the same thing.
    dao.store(
      "book-1",
      listOf(chapter("book-1", 0, 0, 5_000, null), chapter("book-1", 1, 5_000, 9_000, "named")),
      1L,
    )

    assertThat(dao.find("book-1").map { it.title }).containsExactly(null, "named")
  }
}
