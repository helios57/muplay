package app.muplay.database

import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.BookSummary
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * The shelf's arithmetic and its order, gated in Tier 1.
 *
 * Everything here is a pure function over plain data classes, which is the point: a three-key
 * comparator with two direction flips is exactly the code that looks right and is wrong at one
 * boundary, and gating it on an emulator would mean discovering that at four minutes a run.
 *
 * `lastPlayedAtEpochMs` is a non-null `Long` with `0` meaning "never", because that is the shape
 * [BookSummary] landed in (Plan 5 Task 2 declared the type ahead of this task). The plan text for
 * this task was written against a nullable field; the semantics asserted below are identical and
 * the field is read through `hasStarted` wherever the difference could matter.
 */
class BookSummariesTest {

  private fun album(id: String, name: String, artist: String? = "Author", library: Int = 2) =
    AlbumEntity(
      id = id, libraryId = library, artistId = null, name = name, artistName = artist,
      coverArtId = "art-$id", songCount = 0, durationSeconds = 0, sortName = name.lowercase(),
    )

  private fun file(
    id: String,
    book: String,
    track: Int?,
    disc: Int? = null,
    seconds: Int,
    title: String,
  ) = SongEntity(
    id = id, libraryId = 2, albumId = book, artistId = null, title = title, albumName = "book",
    artistName = "Author", trackNumber = track, discNumber = disc, durationSeconds = seconds,
    suffix = "mp3", coverArtId = null, sortTitle = title.lowercase(),
  )

  private fun progress(id: String, position: Long, finished: Boolean = false, at: Long = 0L) =
    id to MediaProgressEntity(id, position, finished, at, 1f, false, 0f)

  private fun summary(
    id: String,
    name: String,
    position: Long,
    finished: Boolean,
    lastPlayedAt: Long,
  ) = BookSummary(
    bookId = id, libraryId = 2, title = name, author = "Author", coverArtId = null,
    fileCount = 1, durationMs = 60_000, positionMs = position, isFinished = finished,
    lastPlayedAtEpochMs = lastPlayedAt,
  )

  // ---- play order ----------------------------------------------------------------------------

  @Test
  fun `files play in disc then track order, whatever order the mirror handed them over in`() {
    val files = listOf(
      file("c", "b", track = 1, disc = 2, seconds = 5, title = "Disc 2 Track 1"),
      file("b", "b", track = 2, disc = 1, seconds = 6, title = "Disc 1 Track 2"),
      file("a", "b", track = 1, disc = 1, seconds = 4, title = "Disc 1 Track 1"),
    )

    // `containsExactly`. A book whose files play in mirror order is a book that plays chapter 12
    // after chapter 3, and `containsExactlyInAnyOrder` would have nothing to say about it.
    assertThat(BookSummaries.playOrder(files).map { it.id }).containsExactly("a", "b", "c")
  }

  @Test
  fun `a file with no track number sorts after every numbered one, by title`() {
    // Real rips have untagged bonus files. `null` sorting first would put "Afterword" before
    // chapter 1, which is the wrong end of the book.
    val files = listOf(
      file("z", "b", track = null, seconds = 3, title = "Afterword"),
      file("a", "b", track = 1, seconds = 4, title = "One"),
      file("m", "b", track = null, seconds = 3, title = "About the author"),
    )

    assertThat(BookSummaries.playOrder(files).map { it.title })
      .containsExactly("One", "About the author", "Afterword")
  }

  @Test
  fun `a file with no disc number plays with disc one, not before it`() {
    // The other `?:` in the same comparator, and it is not the same defect as the track one: a
    // single-disc rip leaves `discNumber` null on every file while a two-disc rip numbers both, so
    // `null` has to mean "disc 1" rather than "disc 0". Read as `0`, the untagged extra below
    // would open the book.
    val files = listOf(
      file("d2", "b", track = 1, disc = 2, seconds = 5, title = "Disc Two Opener"),
      file("loose", "b", track = 9, disc = null, seconds = 3, title = "Untagged Disc"),
      file("d1", "b", track = 1, disc = 1, seconds = 4, title = "Disc One Opener"),
    )

    assertThat(BookSummaries.playOrder(files).map { it.id }).containsExactly("d1", "loose", "d2")
  }

  @Test
  fun `two untagged files with the same title are ordered by id rather than arbitrarily`() {
    // The last key in the comparator. Without it `sortedWith` is stable and therefore returns the
    // mirror's own order, which SQLite is entitled to change between two identical queries.
    val files = listOf(
      file("zz", "b", track = null, seconds = 3, title = "Bonus"),
      file("aa", "b", track = null, seconds = 3, title = "Bonus"),
    )

    assertThat(BookSummaries.playOrder(files).map { it.id }).containsExactly("aa", "zz")
    assertThat(BookSummaries.playOrder(files.reversed()).map { it.id }).containsExactly("aa", "zz")
  }

  // ---- summarising ---------------------------------------------------------------------------

  @Test
  fun `a book's duration is the sum of its files, not the album row's guess`() {
    // Two books with different file sets, so a constant fails one of them. The album row's own
    // `durationSeconds` is deliberately wrong here (0) -- the mirror's album duration comes from
    // the server and the files are the truth this screen shows.
    val files = listOf(
      file("a", "b", 1, seconds = 4, title = "One"),
      file("b", "b", 2, seconds = 6, title = "Two"),
      file("c", "b", 3, seconds = 5, title = "Three"),
    )

    assertThat(BookSummaries.summarise(album("b", "Multi Part Book"), files, emptyMap()).durationMs)
      .isEqualTo(15_000L)
    assertThat(
      BookSummaries.summarise(album("b", "Multi Part Book"), files.take(2), emptyMap()).durationMs,
    ).isEqualTo(10_000L)
  }

  @Test
  fun `a book's position is the files before the current one plus the position inside it`() {
    // The number a whole-book progress bar shows, and the one place a multi-file book differs from
    // a single-file one. Three observations, one per file, so "the position in the current file"
    // and "the position in the book" cannot be the same program.
    val files = listOf(
      file("a", "b", 1, seconds = 4, title = "One"),
      file("b", "b", 2, seconds = 6, title = "Two"),
      file("c", "b", 3, seconds = 5, title = "Three"),
    )

    assertThat(
      BookSummaries.summarise(album("b", "B"), files, mapOf(progress("a", 1_000, at = 5))).positionMs,
    ).isEqualTo(1_000L)
    assertThat(
      BookSummaries.summarise(album("b", "B"), files, mapOf(progress("b", 1_000, at = 5))).positionMs,
    ).isEqualTo(5_000L)
    assertThat(
      BookSummaries.summarise(album("b", "B"), files, mapOf(progress("c", 1_000, at = 5))).positionMs,
    ).isEqualTo(11_000L)
  }

  @Test
  fun `the current file is the most recently heard one, not the furthest one`() {
    // A listener who jumped back to chapter 1 is in chapter 1. "Furthest" would drag them forward
    // every time they went back, which is a bug you only notice by losing your place.
    val files = listOf(
      file("a", "b", 1, seconds = 4, title = "One"),
      file("b", "b", 2, seconds = 6, title = "Two"),
    )
    val progress = mapOf(progress("a", 2_000, at = 900), progress("b", 3_000, at = 100))

    assertThat(BookSummaries.summarise(album("b", "B"), files, progress).positionMs).isEqualTo(2_000L)
    assertThat(BookSummaries.resumePoint(files, progress)?.mediaId).isEqualTo("a")
  }

  @Test
  fun `two rows written in the same millisecond resolve to the later file`() {
    // A batch write really does produce two rows with one timestamp, and `maxByOrNull` returns the
    // **first** maximal element -- which would answer "part one" here, forever, for a listener who
    // is in part two. `BrowseGraph`'s `bk-alpha` is this case on a device and asserts 110 s rather
    // than 20 s; this is the same rule in the fast tier.
    val files = listOf(
      file("a", "b", 1, seconds = 100, title = "One"),
      file("b", "b", 2, seconds = 100, title = "Two"),
    )
    val progress = mapOf(progress("a", 20_000, at = 5_000), progress("b", 10_000, at = 5_000))

    assertThat(BookSummaries.summarise(album("b", "B"), files, progress).positionMs)
      .isEqualTo(110_000L)
    assertThat(BookSummaries.resumePoint(files, progress)?.mediaId).isEqualTo("b")
  }

  @Test
  fun `an older row on a later file does not win`() {
    // The other side of the tie-break: `>=` must compare timestamps and not positions in the list.
    // Without this, "resolve ties to the later file" and "always take the later file" are the same
    // program.
    val files = listOf(
      file("a", "b", 1, seconds = 100, title = "One"),
      file("b", "b", 2, seconds = 100, title = "Two"),
    )
    val progress = mapOf(progress("a", 20_000, at = 2_000), progress("b", 10_000, at = 1_500))

    assertThat(BookSummaries.summarise(album("b", "B"), files, progress).positionMs)
      .isEqualTo(20_000L)
    assertThat(BookSummaries.resumePoint(files, progress)?.mediaId).isEqualTo("a")
  }

  @Test
  fun `a book nobody has opened has no resume point and no last-played time`() {
    val files = listOf(file("a", "b", 1, seconds = 4, title = "One"))

    val summary = BookSummaries.summarise(album("b", "B"), files, emptyMap())

    assertThat(summary.lastPlayedAtEpochMs).isZero
    assertThat(summary.positionMs).isZero
    assertThat(summary.hasStarted).isFalse
    assertThat(BookSummaries.resumePoint(files, emptyMap())).isNull()
  }

  @Test
  fun `a book whose only progress row belongs to another book is untouched`() {
    // `summarise` is handed the whole progress table, not one book's slice, because `bookshelf()`
    // reads it once for the shelf. A lookup that ignored the file ids would give every book the
    // same position.
    val files = listOf(file("a", "b", 1, seconds = 4, title = "One"))

    val summary = BookSummaries.summarise(
      album("b", "B"),
      files,
      mapOf(progress("somebody-elses-file", 3_000, at = 900)),
    )

    assertThat(summary.positionMs).isZero
    assertThat(summary.lastPlayedAtEpochMs).isZero
  }

  @Test
  fun `a book is finished when its last file is finished, and not before`() {
    val files = listOf(
      file("a", "b", 1, seconds = 4, title = "One"),
      file("b", "b", 2, seconds = 6, title = "Two"),
    )

    // Two observations of the same field with only the *which file* varied.
    assertThat(
      BookSummaries.summarise(
        album("b", "B"),
        files,
        mapOf(progress("a", 4_000, finished = true, at = 5)),
      ).isFinished,
    ).isFalse
    assertThat(
      BookSummaries.summarise(
        album("b", "B"),
        files,
        mapOf(progress("b", 6_000, finished = true, at = 5)),
      ).isFinished,
    ).isTrue
  }

  @Test
  fun `a row on the last file that is not finished does not finish the book`() {
    // The `isFinished` flag itself, isolated from *which* file carries it: with only the test
    // above, `progress[last] != null` would pass.
    val files = listOf(
      file("a", "b", 1, seconds = 4, title = "One"),
      file("b", "b", 2, seconds = 6, title = "Two"),
    )

    assertThat(
      BookSummaries.summarise(
        album("b", "B"),
        files,
        mapOf(progress("b", 6_000, finished = false, at = 5)),
      ).isFinished,
    ).isFalse
  }

  @Test
  fun `the remaining time and the fraction follow the position`() {
    val files = listOf(file("a", "b", 1, seconds = 10, title = "One"))

    val quarter = BookSummaries.summarise(album("b", "B"), files, mapOf(progress("a", 2_500, at = 5)))
    val most = BookSummaries.summarise(album("b", "B"), files, mapOf(progress("a", 9_000, at = 5)))

    assertThat(quarter.remainingMs).isEqualTo(7_500L)
    assertThat(most.remainingMs).isEqualTo(1_000L)
    assertThat(quarter.progressFraction).isCloseTo(0.25, Offset.offset(0.001))
    assertThat(most.progressFraction).isCloseTo(0.9, Offset.offset(0.001))
  }

  @Test
  fun `a book with no files at all does not divide by zero`() {
    // The vacuous case. `positionMs / durationMs` with an empty file list is NaN, and NaN reaches
    // a `LinearProgressIndicator`, which throws.
    val summary = BookSummaries.summarise(album("b", "B"), emptyList(), emptyMap())

    assertThat(summary.durationMs).isZero
    assertThat(summary.progressFraction).isZero
    assertThat(summary.remainingMs).isZero
    assertThat(summary.isFinished).isFalse
    assertThat(summary.fileCount).isZero
  }

  @Test
  fun `the album row supplies the identity a file cannot`() {
    // Five fields that are not arithmetic, each observed once with a value nothing else in this
    // class carries -- because a `summarise` that hardcoded any of them would otherwise be gated
    // by nothing at all.
    val summary = BookSummaries.summarise(
      album("bk-9", "Ninth Book", artist = "Hal Teller", library = 7),
      listOf(file("a", "bk-9", 1, seconds = 4, title = "One")),
      emptyMap(),
    )

    assertThat(summary.bookId).isEqualTo("bk-9")
    assertThat(summary.title).isEqualTo("Ninth Book")
    assertThat(summary.author).isEqualTo("Hal Teller")
    assertThat(summary.libraryId).isEqualTo(7)
    assertThat(summary.coverArtId).isEqualTo("art-bk-9")
    assertThat(summary.fileCount).isEqualTo(1)
  }

  @Test
  fun `a rip with no album artist tag reports an empty author rather than failing`() {
    // `BookSummary.author` is non-null; `AlbumEntity.artistName` is not. `BrowseGraph`'s
    // `bk-empty` is exactly this row on a device.
    val summary = BookSummaries.summarise(
      album("b", "B", artist = null),
      listOf(file("a", "b", 1, seconds = 4, title = "One")),
      emptyMap(),
    )

    assertThat(summary.author).isEmpty()
  }

  // ---- resume point --------------------------------------------------------------------------

  @Test
  fun `the resume point carries the file's own position, not the book's`() {
    // The distinction this type exists for. `summarise` adds the files before the current one;
    // `resumePoint` must not, because what it feeds is a seek inside one file.
    val files = listOf(
      file("a", "b", 1, seconds = 4, title = "One"),
      file("b", "b", 2, seconds = 6, title = "Two"),
    )
    val progress = mapOf(progress("b", 1_500, at = 900))

    val point = BookSummaries.resumePoint(files, progress)

    assertThat(point?.mediaId).isEqualTo("b")
    assertThat(point?.positionMs).isEqualTo(1_500L)
    assertThat(point?.lastPlayedAtEpochMs).isEqualTo(900L)
    // ...and the book position for the same input is the other number.
    assertThat(BookSummaries.summarise(album("b", "B"), files, progress).positionMs)
      .isEqualTo(5_500L)
  }

  @Test
  fun `a book with no files has no resume point`() {
    assertThat(BookSummaries.resumePoint(emptyList(), mapOf(progress("a", 1_000, at = 9)))).isNull()
  }

  // ---- order ---------------------------------------------------------------------------------

  @Test
  fun `the shelf is continue-listening first, then unstarted alphabetically, then finished`() {
    // Three groups, and at least two members in the two groups whose order is by time -- a single
    // member per group is sorted correctly by every implementation there is.
    val books = listOf(
      summary("zed", "Zed", position = 0, finished = false, lastPlayedAt = 0),
      summary("old-finished", "Old Finished", position = 100, finished = true, lastPlayedAt = 10),
      summary("recent", "Recent", position = 100, finished = false, lastPlayedAt = 900),
      summary("alpha", "Alpha", position = 0, finished = false, lastPlayedAt = 0),
      summary("older", "Older", position = 100, finished = false, lastPlayedAt = 500),
      summary("new-finished", "New Finished", position = 100, finished = true, lastPlayedAt = 800),
    )

    assertThat(BookSummaries.order(books).map { it.title })
      .containsExactly("Recent", "Older", "Alpha", "Zed", "New Finished", "Old Finished")
  }

  @Test
  fun `a finished book drops below an unstarted one even though it was heard more recently`() {
    // The boundary between group 2 and group 3, isolated. Sorting purely by `lastPlayedAt` would
    // put the finished book on top, which is the most annoying possible shelf.
    val books = listOf(
      summary("done", "Done", position = 100, finished = true, lastPlayedAt = 999),
      summary("fresh", "Fresh", position = 0, finished = false, lastPlayedAt = 0),
    )

    assertThat(BookSummaries.order(books).map { it.title }).containsExactly("Fresh", "Done")
  }

  @Test
  fun `an unstarted book that somehow carries a timestamp still sorts alphabetically`() {
    // The `if (group == UNSTARTED) 0L` arm, which exists so that group 2 is ordered by title and
    // not by a timestamp its members are not supposed to have. Reachable: `markFinished` then
    // `restart` leaves a book at position zero, and a stale row can outlive the file it named.
    val books = listOf(
      summary("b", "Bravo", position = 0, finished = false, lastPlayedAt = 10),
      summary("a", "Alpha", position = 0, finished = false, lastPlayedAt = 900),
    )

    assertThat(BookSummaries.order(books).map { it.title }).containsExactly("Alpha", "Bravo")
  }

  @Test
  fun `two books heard in the same millisecond are ordered by title and then by id`() {
    // The last two keys of the comparator, which exist so the shelf does not reorder itself
    // between two identical reads.
    val books = listOf(
      summary("z", "Same Title", position = 100, finished = false, lastPlayedAt = 5),
      summary("a", "Same Title", position = 100, finished = false, lastPlayedAt = 5),
      summary("m", "Another Title", position = 100, finished = false, lastPlayedAt = 5),
    )

    assertThat(BookSummaries.order(books).map { it.bookId }).containsExactly("m", "a", "z")
  }

  @Test
  fun `the alphabetical group ignores case`() {
    // `sortName` is the mirror's lower-cased key but `BookSummary.title` is the display string, so
    // the comparator has to lower-case it itself: ASCII order puts every capital before every
    // lower-case letter, which would file "apple" after "Zebra".
    val books = listOf(
      summary("z", "Zebra", position = 0, finished = false, lastPlayedAt = 0),
      summary("a", "apple", position = 0, finished = false, lastPlayedAt = 0),
    )

    assertThat(BookSummaries.order(books).map { it.title }).containsExactly("apple", "Zebra")
  }

  @Test
  fun `ordering an empty shelf is an empty shelf`() {
    assertThat(BookSummaries.order(emptyList())).isEmpty()
  }
}
