package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.BookSettingsEntity
import app.muplay.database.entity.LibraryEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.BookSettings
import app.muplay.model.LibraryRole
import app.muplay.model.Song
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real in-memory Room, real SQL, three books.
 *
 * **Three, not one.** With one book, "the settings for book X" and "the settings" are the same
 * value, "the shelf order" is sorted by every implementation there is, and "resume onto the right
 * file" is unfalsifiable. The corpus here mirrors `ci/fixtures`: a single-file book, a
 * three-file book, and a record in the *music* library that must never appear.
 *
 * Test names are camelCase because this is the device tier: `minSdk 26` compiles DEX 035, which
 * forbids spaces in any SimpleName, and a backticked `runTest` names its lambda's synthetic class
 * after the enclosing method.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var repository: AudiobookRepository

  /**
   * Fixed, and read back as a value below. `markFinished` is the only method here that reads a
   * clock, and a `Clock.systemUTC()` would make its stamp unassertable.
   */
  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(FIXED_NOW_MS), ZoneOffset.UTC)

  @Before
  fun setUp() = runBlocking {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    repository = AudiobookRepository(
      db.audiobookDao(),
      db.mediaProgressDao(),
      db.bookSettingsDao(),
      clock,
    )

    db.libraryDao().mergeFromServer(
      listOf(
        LibraryEntity(musicFolderId = 1, name = "Music", role = LibraryRole.UNASSIGNED),
        LibraryEntity(musicFolderId = 2, name = "Audiobooks", role = LibraryRole.UNASSIGNED),
      ),
    )
    // Through `setRole`: `mergeFromServer` deliberately never writes the role column, so seeding
    // it in the entity above would leave both libraries UNASSIGNED and every assertion here would
    // be about an empty shelf.
    db.libraryDao().setRole(1, LibraryRole.MUSIC)
    db.libraryDao().setRole(2, LibraryRole.AUDIOBOOKS)

    db.browseDao().replaceLibraryContents(
      libraryId = 2,
      artists = emptyList(),
      albums = listOf(album("single", "Test Book", 2), album("multi", "Multi Part Book", 2)),
      songs = listOf(
        song("single-1", "single", track = 1, seconds = 15, title = "Test Book", library = 2),
        song("multi-1", "multi", track = 1, seconds = 4, title = "Part One", library = 2),
        song("multi-2", "multi", track = 2, seconds = 6, title = "Part Two", library = 2),
        song("multi-3", "multi", track = 3, seconds = 5, title = "Part Three", library = 2),
      ),
    )
    db.browseDao().replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(album("record", "Test Album", 1)),
      songs = listOf(song("track-1", "record", track = 1, seconds = 5, title = "Track 1", library = 1)),
    )
  }

  @After
  fun tearDown() = db.close()

  private fun album(id: String, name: String, library: Int) = AlbumEntity(
    id = id, libraryId = library, artistId = null, name = name, artistName = "$name Author",
    coverArtId = "art-$id", songCount = 0, durationSeconds = 0, sortName = name.lowercase(),
  )

  private fun song(
    id: String,
    albumId: String,
    track: Int,
    seconds: Int,
    title: String,
    library: Int,
  ) = SongEntity(
    id = id, libraryId = library, albumId = albumId, artistId = null, title = title,
    albumName = albumId, artistName = "Author", trackNumber = track, discNumber = 1,
    durationSeconds = seconds, suffix = "mp3", coverArtId = null, sortTitle = title.lowercase(),
  )

  private suspend fun record(
    mediaId: String,
    positionMs: Long,
    at: Long,
    finished: Boolean = false,
  ) = db.mediaProgressDao()
    .upsert(MediaProgressEntity(mediaId, positionMs, finished, at, 1f, false, 0f))

  // ---- what a book is ------------------------------------------------------------------------

  @Test
  fun theShelfHoldsBooksAndOnlyBooks(): Unit = runBlocking {
    // The headline constraint, at the repository. A music album on the shelf means a music album
    // in the audiobook resume path, which is spec section 3's "music restarts from 0" broken.
    repository.bookshelf().test {
      assertThat(awaitItem().map { it.title })
        .containsExactlyInAnyOrder("Test Book", "Multi Part Book")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun aMusicAlbumIsNotABookEvenWhenAskedForByName() = runBlocking {
    // `bookshelf()` cannot see this: it never asks about `record` at all. `book()` is asked
    // directly, which is exactly how a music album gets summarised as a book if the role guard is
    // missing from `findBookAlbum`.
    assertThat(repository.book("record")).isNull()
    assertThat(repository.files("record")).isEmpty()
    assertThat(repository.resumePoint("record")).isNull()
    // ...and the control, so "returns null for everything" cannot pass this test.
    assertThat(repository.book("multi")?.title).isEqualTo("Multi Part Book")
  }

  @Test
  fun aBookCarriesTheAlbumRowsIdentityAndItsFilesArithmetic() = runBlocking {
    val book = repository.book("multi")!!

    assertThat(book.bookId).isEqualTo("multi")
    assertThat(book.title).isEqualTo("Multi Part Book")
    assertThat(book.author).isEqualTo("Multi Part Book Author")
    assertThat(book.coverArtId).isEqualTo("art-multi")
    assertThat(book.libraryId).isEqualTo(2)
    assertThat(book.fileCount).isEqualTo(3)
    // The album row says `durationSeconds = 0`; the files say 15 s. The files win.
    assertThat(book.durationMs).isEqualTo(15_000L)
    // The single-file book, so no field above can be a constant.
    val single = repository.book("single")!!
    assertThat(single.fileCount).isEqualTo(1)
    assertThat(single.durationMs).isEqualTo(15_000L)
    assertThat(single.coverArtId).isEqualTo("art-single")
  }

  @Test
  fun theShelfPutsTheBookYouWereListeningToFirst(): Unit = runBlocking {
    record("multi-2", positionMs = 1_000, at = 900)
    record("single-1", positionMs = 2_000, at = 100)

    repository.bookshelf().test {
      // Two started books with two different times. With one, "sorted by time" and "any order" are
      // the same list.
      assertThat(awaitItem().map { it.title }).containsExactly("Multi Part Book", "Test Book")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun theShelfUpdatesWhenProgressIsWritten(): Unit = runBlocking {
    repository.bookshelf().test {
      assertThat(awaitItem().single { it.title == "Multi Part Book" }.positionMs).isZero

      record("multi-2", positionMs = 1_000, at = 900)

      // A second emission with a different value. A `bookshelf()` that emitted once and stopped
      // would leave the shelf showing the app's first second of life forever, and an assertion on
      // the first emission alone would never notice.
      val updated = awaitItem().single { it.title == "Multi Part Book" }
      assertThat(updated.positionMs).isEqualTo(5_000L)
      assertThat(updated.lastPlayedAtEpochMs).isEqualTo(900L)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun theShelfUpdatesWhenTheMirrorChanges(): Unit = runBlocking {
    // The *other* two flows in the `combine`. A `bookshelf()` built on `observeAll()` alone would
    // never notice a sync, and the first emission would be right forever.
    repository.bookshelf().test {
      assertThat(awaitItem()).hasSize(2)

      db.browseDao().replaceLibraryContents(
        libraryId = 2,
        artists = emptyList(),
        albums = listOf(album("single", "Test Book", 2), album("third", "Third Book", 2)),
        songs = listOf(
          song("single-1", "single", track = 1, seconds = 15, title = "Test Book", library = 2),
          song("third-1", "third", track = 1, seconds = 9, title = "Only Part", library = 2),
        ),
      )

      val after = awaitItem()
      assertThat(after.map { it.title }).containsExactlyInAnyOrder("Test Book", "Third Book")
      assertThat(after.single { it.bookId == "third" }.durationMs).isEqualTo(9_000L)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun booksIsTheSameShelfInTheSameOrderAsTheFlow(): Unit = runBlocking {
    // `Bookshelf.books()` is what the browse tree reads and `bookshelf()` is what the screen reads.
    // Plan 5 Task 4's stand-in derived them separately; this asserts they are one derivation.
    record("multi-2", positionMs = 1_000, at = 900)
    record("single-1", positionMs = 2_000, at = 100)

    val snapshot = repository.books()

    repository.bookshelf().test {
      val streamed = awaitItem()
      assertThat(snapshot.map { it.bookId }).containsExactly("multi", "single")
      assertThat(snapshot).isEqualTo(streamed)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun anAlbumWhoseFilesHaveNotSyncedYetIsStillABook(): Unit = runBlocking {
    // A real state -- an album row a sync reached before its songs -- and the one `book()` answers
    // differently from "no such book". `BrowseGraph`'s `bk-empty` is this row on a device.
    db.browseDao().replaceLibraryContents(
      libraryId = 2,
      artists = emptyList(),
      albums = listOf(album("empty", "Empty Book", 2)),
      songs = emptyList(),
    )

    val book = repository.book("empty")!!

    assertThat(book.fileCount).isZero
    assertThat(book.durationMs).isZero
    assertThat(book.progressFraction).isZero
    assertThat(repository.resumePoint("empty")).isNull()
    // ...and it reaches the shelf the same way, through the other read path: `bookshelf()` groups
    // the whole library's songs by book and looks this album's up in that map, which is the one
    // place a book with no files takes the `orEmpty()` arm.
    repository.bookshelf().test {
      assertThat(awaitItem().single { it.bookId == "empty" }.fileCount).isZero
      cancelAndIgnoreRemainingEvents()
    }
  }

  // ---- the resume point ----------------------------------------------------------------------

  @Test
  fun theResumePointNamesTheFileTheListenerWasIn() = runBlocking {
    record("multi-2", positionMs = 3_500, at = 900)

    val point = repository.resumePoint("multi")

    // The file, not the first file. A resume that always answered file one would pass every
    // single-file test in this plan.
    assertThat(point?.mediaId).isEqualTo("multi-2")
    assertThat(point?.positionMs).isEqualTo(3_500L)
    assertThat(point?.lastPlayedAtEpochMs).isEqualTo(900L)
    // The narrowed view the browse tree gets, from the same input.
    assertThat(repository.resumeFileId("multi")).isEqualTo("multi-2")
  }

  @Test
  fun twoBooksKeepTwoResumePoints() = runBlocking {
    // The original complaint, at its smallest. One book cannot express it.
    record("multi-3", positionMs = 1_500, at = 900)
    record("single-1", positionMs = 12_345, at = 800)

    assertThat(repository.resumePoint("multi")?.mediaId).isEqualTo("multi-3")
    assertThat(repository.resumePoint("multi")?.positionMs).isEqualTo(1_500L)
    assertThat(repository.resumePoint("single")?.positionMs).isEqualTo(12_345L)
    assertThat(repository.resumeFileId("single")).isEqualTo("single-1")
  }

  @Test
  fun aBookNobodyHasOpenedHasNoResumePoint() = runBlocking {
    assertThat(repository.resumePoint("multi")).isNull()
    assertThat(repository.resumeFileId("multi")).isNull()
  }

  @Test
  fun filesComeBackInPlayOrder() = runBlocking {
    assertThat(repository.files("multi").map { it.title })
      .containsExactly("Part One", "Part Two", "Part Three")
    assertThat(repository.files("multi").map(Song::id))
      .containsExactly("multi-1", "multi-2", "multi-3")
  }

  @Test
  fun bookIdOfAnswersTheAlbumAndFallsBackToTheFile() = runBlocking {
    val part = repository.files("multi").first()
    val loose = Song(
      id = "loose-1", libraryId = 2, title = "A Loose File", albumId = null, albumName = null,
      artistId = null, artistName = null, trackNumber = null, discNumber = null,
      durationSeconds = 30, suffix = "m4b", coverArtId = null,
    )

    assertThat(AudiobookRepository.bookIdOf(part)).isEqualTo("multi")
    assertThat(AudiobookRepository.bookIdOf(loose)).isEqualTo("loose-1")
    // The entity overload, over a row the mirror actually holds.
    val entity = db.browseDao().findSong("multi-2")!!
    assertThat(AudiobookRepository.bookIdOf(entity)).isEqualTo("multi")
    assertThat(AudiobookRepository.bookIdOf(entity.copy(albumId = null))).isEqualTo("multi-2")
  }

  // ---- settings ------------------------------------------------------------------------------

  @Test
  fun settingsDefaultWhenNobodyHasSetThem() = runBlocking {
    val settings = repository.settings("multi")

    assertThat(settings.bookId).isEqualTo("multi")
    assertThat(settings.speed).isEqualTo(BookSettings.DEFAULT_SPEED)
    assertThat(settings.skipSilence).isFalse
  }

  @Test
  fun twoBooksKeepTwoSpeeds() = runBlocking {
    repository.setSpeed("multi", 1.4f)
    repository.setSpeed("single", 0.8f)

    assertThat(listOf(repository.settings("multi").speed, repository.settings("single").speed))
      .containsExactly(1.4f, 0.8f)
  }

  @Test
  fun observedSettingsFollowTheWrites(): Unit = runBlocking {
    // The Flow, and one book's slice of it: a `observeSettings` that ignored its `bookId` would
    // pass every `settings()` assertion above.
    repository.observeSettings("multi").test {
      assertThat(awaitItem().speed).isEqualTo(BookSettings.DEFAULT_SPEED)

      repository.setSpeed("multi", 1.4f)
      assertThat(awaitItem().speed).isEqualTo(1.4f)

      repository.setSkipSilence("multi", true)
      val both = awaitItem()
      assertThat(both.speed).isEqualTo(1.4f)
      assertThat(both.skipSilence).isTrue
      assertThat(both.bookId).isEqualTo("multi")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun observedSettingsIgnoreTheNeighboursWrites(): Unit = runBlocking {
    repository.observeSettings("multi").test {
      assertThat(awaitItem().speed).isEqualTo(BookSettings.DEFAULT_SPEED)

      repository.setSpeed("single", 2.5f)

      // No emission for the other book's row, and the value is still this book's default. Without
      // the `WHERE bookId = :bookId`, a speed control on one screen would move another book's.
      expectNoEvents()
      assertThat(repository.settings("multi").speed).isEqualTo(BookSettings.DEFAULT_SPEED)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun settingTheSpeedDoesNotTurnSilenceSkippingOff() = runBlocking {
    // Plan 3 Task 8 named this trap on `media_progress`; it exists identically on `book_settings`.
    // A setter that constructs a whole fresh row turns off a feature the listener switched on, and
    // nothing reports it.
    repository.setSkipSilence("multi", true)

    repository.setSpeed("multi", 1.4f)

    assertThat(repository.settings("multi").skipSilence).isTrue
    assertThat(repository.settings("multi").speed).isEqualTo(1.4f)
  }

  @Test
  fun turningSilenceSkippingOnDoesNotResetTheSpeed() = runBlocking {
    // The same trap in the other direction, because a read-modify-write can be got right one way
    // and wrong the other.
    repository.setSpeed("multi", 1.4f)

    repository.setSkipSilence("multi", true)

    assertThat(repository.settings("multi").speed).isEqualTo(1.4f)
    assertThat(repository.settings("multi").skipSilence).isTrue
  }

  @Test
  fun anImpossibleSpeedIsClampedOnTheWayInAndOnTheWayOut() = runBlocking {
    repository.setSpeed("multi", 99f)
    assertThat(repository.settings("multi").speed).isEqualTo(BookSettings.MAX_SPEED)

    repository.setSpeed("multi", 0.01f)
    assertThat(repository.settings("multi").speed).isEqualTo(BookSettings.MIN_SPEED)

    // ...and a row that got past the setter -- a hand-edited database, a future bug -- still cannot
    // reach `ExoPlayer.setPlaybackSpeed`. `Float.NaN.coerceIn(...)` returns NaN, and NaN there
    // throws from inside a listener callback, which surfaces as playback dying with no message.
    db.bookSettingsDao().upsert(BookSettingsEntity("multi", Float.NaN, false))
    assertThat(repository.settings("multi").speed).isEqualTo(BookSettings.DEFAULT_SPEED)

    // The clamp on the way out is not the clamp on the way in: a stored 4.0 also has to be capped.
    db.bookSettingsDao().upsert(BookSettingsEntity("multi", 4.0f, false))
    assertThat(repository.settings("multi").speed).isEqualTo(BookSettings.MAX_SPEED)
  }

  // ---- restart and finish ---------------------------------------------------------------------

  @Test
  fun restartingABookClearsItsProgressAndNobodyElses() = runBlocking {
    record("multi-1", positionMs = 2_000, at = 500)
    record("multi-2", positionMs = 3_500, at = 900)
    record("single-1", positionMs = 12_345, at = 800)

    repository.restart("multi")

    assertThat(repository.resumePoint("multi")).isNull()
    // Every file, not just the most recent one: a clear that only removed the row the resume point
    // named would leave the book resuming from part one.
    assertThat(db.mediaProgressDao().find("multi-1")).isNull()
    // The control. A `clear` with a wrong `IN` clause takes the neighbour's place with it, and
    // that is the one failure this whole application exists to prevent.
    assertThat(repository.resumePoint("single")?.positionMs).isEqualTo(12_345L)
  }

  @Test
  fun markingABookFinishedShowsUpOnTheShelfAndCanBeUndone(): Unit = runBlocking {
    // Plan 3 Task 8 deferred "un-finish on replay" to the plan with a UI to express it. This is
    // that plan, and `restart` is that expression.
    repository.markFinished("multi")
    assertThat(repository.book("multi")?.isFinished).isTrue

    repository.restart("multi")
    assertThat(repository.book("multi")?.isFinished).isFalse
  }

  @Test
  fun markingABookFinishedWritesEveryFileToItsOwnEndAtTheInjectedClock() = runBlocking {
    repository.markFinished("multi")

    val rows = db.mediaProgressDao().findIn(listOf("multi-1", "multi-2", "multi-3"))
      .associateBy { it.mediaId }
    // Each file's *own* duration. A constant would be right for none of them.
    assertThat(rows["multi-1"]!!.positionMs).isEqualTo(4_000L)
    assertThat(rows["multi-2"]!!.positionMs).isEqualTo(6_000L)
    assertThat(rows["multi-3"]!!.positionMs).isEqualTo(5_000L)
    assertThat(rows.values.map { it.isFinished }).containsExactly(true, true, true)
    // The injected clock, not the wall clock. `Clock.systemUTC()` here would be a number no
    // assertion could name.
    assertThat(rows.values.map { it.lastPlayedAtEpochMs })
      .containsExactly(FIXED_NOW_MS, FIXED_NOW_MS, FIXED_NOW_MS)
    // The neighbour is untouched.
    assertThat(db.mediaProgressDao().find("single-1")).isNull()
  }

  @Test
  fun markingABookFinishedPreservesTheColumnsThisPlanDoesNotOwn() = runBlocking {
    // `gainDb` is Plan 3 Task 11's measured ReplayGain, and `speed`/`skipSilence` are Plan 2's
    // columns. A `markFinished` that constructed a fresh entity would silently reset all three --
    // the same trap `setSpeed` carries one table over, on the table where it has been named since
    // Plan 3 Task 8.
    db.mediaProgressDao().upsert(
      MediaProgressEntity("multi-2", 1_000L, false, 5L, speed = 1.6f, skipSilence = true, gainDb = -7.5f),
    )

    repository.markFinished("multi")

    val row = db.mediaProgressDao().find("multi-2")!!
    assertThat(row.speed).isEqualTo(1.6f)
    assertThat(row.skipSilence).isTrue
    assertThat(row.gainDb).isEqualTo(-7.5f)
    // ...while a file that had no row at all gets this plan's defaults rather than a NaN.
    val fresh = db.mediaProgressDao().find("multi-1")!!
    assertThat(fresh.speed).isEqualTo(BookSettings.DEFAULT_SPEED)
    assertThat(fresh.skipSilence).isFalse
    assertThat(fresh.gainDb).isEqualTo(0.0f)
  }

  // ---- the audiobook item map -------------------------------------------------------------------

  @Test
  fun theAudiobookItemMapNamesEveryBookFileAndNoMusicFile(): Unit = runBlocking {
    repository.observeAudiobookItems().test {
      val items = awaitItem()
      // Exact keys and exact values. A map that answered every media id would make music resume,
      // and a map keyed by book would make no file resume.
      assertThat(items.keys)
        .containsExactlyInAnyOrder("single-1", "multi-1", "multi-2", "multi-3")
      assertThat(items["multi-2"]).isEqualTo("multi")
      assertThat(items["single-1"]).isEqualTo("single")
      assertThat(items).doesNotContainKey("track-1")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun aLooseAudiobookFileIsItsOwnBookInTheItemMap(): Unit = runBlocking {
    // The `albumId ?: mediaId` arm. A file with no album has no shelf row -- stated limitation --
    // but its position and its settings still have to work, and that needs a book id.
    db.browseDao().replaceLibraryContents(
      libraryId = 2,
      artists = emptyList(),
      albums = emptyList(),
      songs = listOf(
        SongEntity(
          id = "loose-1", libraryId = 2, albumId = null, artistId = null, title = "Loose",
          albumName = null, artistName = null, trackNumber = null, discNumber = null,
          durationSeconds = 30, suffix = "m4b", coverArtId = null, sortTitle = "loose",
        ),
      ),
    )

    repository.observeAudiobookItems().test {
      val items = awaitItem()
      assertThat(items.keys).containsExactly("loose-1")
      assertThat(items["loose-1"]).isEqualTo("loose-1")
      cancelAndIgnoreRemainingEvents()
    }
    // ...and it is not on the shelf, because the shelf is a list of albums.
    assertThat(repository.books().map { it.bookId }).doesNotContain("loose-1")
  }

  private companion object {
    /** 2023-11-14T22:13:20Z. Distinctive enough that finding it in a row is evidence. */
    const val FIXED_NOW_MS = 1_700_000_000_000L
  }
}
