package app.muplay.testing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The committed oracle, parsed.
 *
 * Every assertion here is over **exact lists in exact order**, never `containsAnyOf` or
 * `anyMatch`: chapter order is a property of a book, and a `containsExactlyInAnyOrder` here would
 * let a reader that returned chapters backwards pass in Task 3 and produce a book that plays its
 * epilogue first.
 *
 * The numbers are `ci/probe-chapters.sh`'s, i.e. ffprobe's, not this project's opinion — see
 * [BookFixtures]'s own doc. Where an mp3 duration is not the round number `ci/seed-fixtures.sh`
 * asked for, that is LAME frame padding and it is recorded as measured; the script's header
 * explains it.
 */
class BookFixturesTest {

  @Test
  fun `the four books are distinguishable by chapter count alone`() {
    // The corpus exists to break constants. If two books had the same chapter count *and* the
    // same boundaries, a chapter reader could return one of them for both and pass.
    assertThat(BookFixtures.ALL_BOOKS.map { it.albumName to it.chapters.size })
      .containsExactly(
        "Multi Part Book" to 0,
        "Second Book" to 4,
        "Tail Book" to 2,
        "Test Book" to 3,
      )
  }

  @Test
  fun `Second Book's chapters are unequal in length and in order`() {
    // Unequal lengths are the whole reason this fixture exists: `startMs == index * 5000` is true
    // of Test Book and false here, at chapter 2 and at every chapter after it.
    assertThat(BookFixtures.SECOND_BOOK.chapters.map { it.startMs })
      .containsExactly(0L, 4_000L, 9_000L, 15_000L)
    assertThat(BookFixtures.SECOND_BOOK.chapters.map { it.endMs })
      .containsExactly(4_000L, 9_000L, 15_000L, 21_000L)
    assertThat(BookFixtures.SECOND_BOOK.chapters.map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    // ...and the lengths really are four different numbers, stated as the property rather than
    // left to be re-derived by a reader: 4/5/6/6 s. Two of them are equal, which is deliberate --
    // "every chapter has a distinct length" is a stronger claim than this corpus makes, and a
    // test that asserted it would be asserting something the fixture does not guarantee.
    assertThat(BookFixtures.SECOND_BOOK.chapters.map { it.endMs - it.startMs })
      .containsExactly(4_000L, 5_000L, 6_000L, 6_000L)
  }

  @Test
  fun `Test Book is still the equal-length baseline it always was`() {
    // A second observation of the same fields on a different book. Together with the assertion
    // above, a parser that returned a hardcoded chapter list fails one of the two.
    assertThat(BookFixtures.TEST_BOOK.chapters.map { it.startMs })
      .containsExactly(0L, 5_000L, 10_000L)
    assertThat(BookFixtures.TEST_BOOK.chapters.map { it.title })
      .containsExactly("Chapter 1", "Chapter 2", "Chapter 3")
  }

  @Test
  fun `Tail Book is the two-chapter book, and its boundary is not any other book's`() {
    // The third observation, and the one that makes "chapter boundaries come from the file" the
    // only surviving explanation: 7000 appears in no other book's chapter table.
    assertThat(BookFixtures.TAIL_BOOK.chapters.map { it.startMs }).containsExactly(0L, 7_000L)
    assertThat(BookFixtures.TAIL_BOOK.chapters.map { it.endMs }).containsExactly(7_000L, 12_000L)
    assertThat(BookFixtures.TAIL_BOOK.chapters.map { it.title }).containsExactly("Head", "Tail")
  }

  @Test
  fun `Multi Part Book is three files with three different durations, in track order`() {
    // Not monotonic on purpose (4, 6, 5): "sorted by duration" and "sorted by track number" are
    // different lists here, so a repository that sorted by the wrong key cannot pass by accident.
    //
    // 4049/6034/5042 rather than 4000/6000/5000 because libmp3lame pads to a whole 1152-sample
    // frame; ffprobe and Media3's Mp3Extractor both report the untrimmed frame span. See
    // ci/probe-chapters.sh's header. The property this test needs -- three different, non-sorted
    // numbers -- is unaffected.
    assertThat(BookFixtures.MULTI_PART_BOOK.tracks.map { it.title })
      .containsExactly("Part One", "Part Two", "Part Three")
    assertThat(BookFixtures.MULTI_PART_BOOK.tracks.map { it.durationMs })
      .containsExactly(4_049L, 6_034L, 5_042L)
    assertThat(BookFixtures.MULTI_PART_BOOK.tracks.map { it.trackNumber })
      .containsExactly(1, 2, 3)
    // The paths, in the same order. Without this, "resume came back on the right file" -- the
    // half of per-book resume this fixture exists for -- has no expected value to be right
    // *against*, and every assertion above would still hold if the three rows were swapped
    // between files whose titles happened to line up.
    assertThat(BookFixtures.MULTI_PART_BOOK.tracks.map { it.path }).containsExactly(
      "Audiobooks/Fourth Author/Multi Part Book/01 - Part One.mp3",
      "Audiobooks/Fourth Author/Multi Part Book/02 - Part Two.mp3",
      "Audiobooks/Fourth Author/Multi Part Book/03 - Part Three.mp3",
    )
  }

  @Test
  fun `a book's duration is the sum of its files`() {
    // Two observations, one single-file and one multi-file, because "duration == the one file's
    // duration" and "duration == the sum" are the same program for every book but this one.
    assertThat(BookFixtures.MULTI_PART_BOOK.durationMs).isEqualTo(15_125L)
    assertThat(BookFixtures.TAIL_BOOK.durationMs).isEqualTo(12_000L)
    // ...and the sum is not any single file's duration, which is what makes the first line above
    // evidence rather than a coincidence.
    assertThat(BookFixtures.MULTI_PART_BOOK.tracks.map { it.durationMs })
      .doesNotContain(BookFixtures.MULTI_PART_BOOK.durationMs)
  }

  @Test
  fun `each book names its own author, and no two books share one`() {
    // The shelf groups by author, so "author" has to be a property of the book rather than a
    // constant. Four books, four distinct authors -- against a corpus with one author, a shelf
    // that printed the same name on every row would be indistinguishable from a correct one.
    assertThat(BookFixtures.ALL_BOOKS.map { it.authorName })
      .containsExactly("Fourth Author", "Second Author", "Third Author", "Test Author")
  }

  @Test
  fun `the music tracks are not books`() {
    // The oracle covers the whole corpus, and the Music subtree must stay chapterless.
    //
    // Four tracks since Plan 3 Task 12, and the fourth is deliberately unlike the other three:
    // `Offset Track` is Opus, thirty seconds, and the only file here that `StreamFormat.forSuffix`
    // sends through Navidrome's transcoder. In track order, so it is last -- which is what keeps
    // `Track 1`/`Track 2`/`Track 3` at the indices the gapless measurement and every journey read
    // them at. (`RealTrackBytes.musicTracks()` filters this module's *device* consumers back down
    // to the three mp3s for exactly that reason; see its own note.)
    assertThat(BookFixtures.MUSIC_TRACKS.map { it.title })
      .containsExactly("Track 1", "Track 2", "Track 3", "Offset Track")
    // 30006, not 30000: `ffprobe` reads Opus's pre-skip into `format.duration` the same way it
    // reads libmp3lame's padding into the mp3s' 5042. Neither is the number the seed script asked
    // ffmpeg for, and rounding either here would invent a third answer no reader gives.
    assertThat(BookFixtures.MUSIC_TRACKS.map { it.durationMs })
      .containsExactly(5_042L, 5_042L, 5_042L, 30_006L)
    // No music path carries a chapter row. Asserted through the parsed table rather than by
    // reading the file again, because this is the claim `ScopedShuffleJourneyTest` and every
    // "books are not music" assertion downstream rest on.
    assertThat(BookFixtures.MUSIC_TRACKS.map { BookFixtures.chaptersOf(it.path) })
      .containsExactly(emptyList(), emptyList(), emptyList(), emptyList())
  }

  @Test
  fun `every fixture in the table is accounted for by a named constant`() {
    // Rule 5, applied to this class. If someone adds a fixture and forgets to name it, the parsed
    // table and the named constants diverge -- and every other test in this file would stay green,
    // because they only look at the constants they know about.
    val named = (BookFixtures.ALL_BOOKS.flatMap { it.tracks } + BookFixtures.MUSIC_TRACKS)
      .map { it.path }

    assertThat(named).containsExactlyInAnyOrderElementsOf(BookFixtures.allTrackPaths())
    // ...and the table is not empty, which `containsExactlyInAnyOrder` on two empty lists would
    // have satisfied. Ten: four music tracks (three mp3s and Task 12's Opus fixture), three
    // single-file books, three book parts.
    assertThat(named).hasSize(10)
  }

  @Test
  fun `every chapter row in the table belongs to a book that names it`() {
    // The chapter half of the assertion above, which that one cannot make: it compares *track*
    // paths, so a chapter row against a path no book claims -- the shape a fifth book would
    // arrive in -- passes it untouched.
    val namedChapters = BookFixtures.ALL_BOOKS.flatMap { it.chapters }

    assertThat(namedChapters).containsExactlyInAnyOrderElementsOf(BookFixtures.allChapters())
    assertThat(namedChapters).hasSize(9)
  }

  @Test
  fun `a book directory with no fixture under it fails loudly rather than yielding an empty book`() {
    // The vacuity guard on the parser itself. An `ExpectedBook` with no tracks has a `durationMs`
    // of 0 and no chapters, and every `containsExactly` over its (empty) lists would have to be
    // written as an empty expectation to pass -- so the failure has to happen here, at
    // construction, naming the directory.
    assertThatThrownBy { BookFixtures.bookAt("Test Book", "Test Author", "Audiobooks/No Such Book") }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("Audiobooks/No Such Book")
  }

  @Test
  fun `a missing table fails by name instead of parsing to nothing`() {
    // The first of the two vacuity guards, driven rather than described. Without it, a
    // `BookFixtures` whose resource had vanished would yield empty lists, and every
    // `containsExactly` above would have to be written with an empty expectation to pass -- which
    // is precisely the defect the whole corpus exists to make impossible.
    assertThatThrownBy { BookFixtures.rowsFrom("/fixtures/no-such-table.tsv") }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("/fixtures/no-such-table.tsv")
      .hasMessageContaining("ci/probe-chapters.sh")
  }

  @Test
  fun `a table of nothing but comments fails instead of yielding zero rows`() {
    // The second guard, and a genuinely different failure from the one above: the resource is
    // present and readable, and still carries no records. `/vacuity/books-comments-only.tsv` is a
    // TEST resource -- it is deliberately not under `/fixtures/`, so nothing can mistake it for
    // part of the oracle.
    assertThatThrownBy { BookFixtures.rowsFrom("/vacuity/books-comments-only.tsv") }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("parsed to zero rows")
  }

  @Test
  fun `the parsed table is the committed resource, not a hardcoded copy of it`() {
    // The resource path is part of the contract with `ci/probe-chapters.sh` (which writes exactly
    // this file) and with the APK's Java resources (which is how the instrumented tier reads it).
    // A `BookFixtures` that had quietly stopped reading the file would still satisfy every
    // assertion above; it cannot satisfy this one.
    assertThat(BookFixtures.RESOURCE).isEqualTo("/fixtures/books.tsv")
    val raw = checkNotNull(BookFixtures::class.java.getResourceAsStream(BookFixtures.RESOURCE))
      .use { it.readBytes().decodeToString() }

    assertThat(raw).startsWith("# generated by ci/probe-chapters.sh")
    // Every path a named constant knows about is a line in the file that was actually read.
    assertThat(BookFixtures.allTrackPaths()).allSatisfy { path ->
      assertThat(raw).contains("track\t$path\t")
    }
  }
}
