package app.muplay.media

import app.muplay.model.Chapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two shapes an audiobook comes in, unified.
 *
 * A single-file M4B has many chapters inside one media item; a ripped book has one media item per
 * chapter and no chapter atoms at all. Chapter navigation has to work identically over both, and
 * the only way to be sure is to test both **and** a book that is neither — a multi-file book whose
 * files *also* carry chapters, where "one chapter per file" and "chapter start equals book
 * position" are both wrong.
 */
class BookTimelineTest {

  private val singleFile = listOf(BookFile("m4b", "Second Book", 21_000))
  private val secondBookChapters = mapOf(
    "m4b" to listOf(
      Chapter(0, 0, 4_000, "Prologue"),
      Chapter(1, 4_000, 9_000, "The Long Middle"),
      Chapter(2, 9_000, 15_000, "A Turn"),
      Chapter(3, 15_000, 21_000, "Epilogue"),
    ),
  )

  private val multiFile = listOf(
    BookFile("p1", "Part One", 4_000),
    BookFile("p2", "Part Two", 6_000),
    BookFile("p3", "Part Three", 5_000),
  )

  @Test
  fun `a single-file book's chapters all live in item zero`() {
    val timeline = BookTimeline.of(singleFile, secondBookChapters)

    assertThat(timeline.map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    assertThat(timeline.map { it.itemIndex }).containsExactly(0, 0, 0, 0)
    assertThat(timeline.map { it.startInItemMs }).containsExactly(0L, 4_000L, 9_000L, 15_000L)
    assertThat(timeline.map { it.endInItemMs }).containsExactly(4_000L, 9_000L, 15_000L, 21_000L)
    // For a one-file book the book position and the in-item position coincide, which is exactly
    // why this book alone cannot prove `bookStartMs` is computed at all -- see the mixed case.
    assertThat(timeline.map { it.bookStartMs }).containsExactly(0L, 4_000L, 9_000L, 15_000L)
  }

  @Test
  fun `a chapterless multi-file book gets one chapter per file, named after the file`() {
    val timeline = BookTimeline.of(multiFile, chaptersByMediaId = emptyMap())

    assertThat(timeline.map { it.title }).containsExactly("Part One", "Part Two", "Part Three")
    assertThat(timeline.map { it.itemIndex }).containsExactly(0, 1, 2)
    assertThat(timeline.map { it.mediaId }).containsExactly("p1", "p2", "p3")
    assertThat(timeline.map { it.startInItemMs }).containsExactly(0L, 0L, 0L)
    assertThat(timeline.map { it.endInItemMs }).containsExactly(4_000L, 6_000L, 5_000L)
    // The cumulative offsets. 0 / 4000 / 10000 -- unequal, so a constant or an `index * length`
    // is wrong at the second entry and at the third.
    assertThat(timeline.map { it.bookStartMs }).containsExactly(0L, 4_000L, 10_000L)
  }

  @Test
  fun `a multi-file book whose files also carry chapters is neither of the easy cases`() {
    // The discriminating shape. Two files, 2 + 3 chapters, so:
    //   * "one chapter per file" is wrong (five entries, not two);
    //   * "bookStartMs == startInItemMs" is wrong from the third entry on;
    //   * "index == itemIndex" is wrong from the second entry on.
    val files = listOf(BookFile("a", "Disc One", 9_000), BookFile("b", "Disc Two", 12_000))
    val chapters = mapOf(
      "a" to listOf(Chapter(0, 0, 4_000, "One"), Chapter(1, 4_000, 9_000, "Two")),
      "b" to listOf(
        Chapter(0, 0, 3_000, "Three"),
        Chapter(1, 3_000, 7_000, "Four"),
        Chapter(2, 7_000, 12_000, "Five"),
      ),
    )

    val timeline = BookTimeline.of(files, chapters)

    assertThat(timeline.map { it.title }).containsExactly("One", "Two", "Three", "Four", "Five")
    assertThat(timeline.map { it.index }).containsExactly(0, 1, 2, 3, 4)
    assertThat(timeline.map { it.itemIndex }).containsExactly(0, 0, 1, 1, 1)
    assertThat(timeline.map { it.mediaId }).containsExactly("a", "a", "b", "b", "b")
    assertThat(timeline.map { it.startInItemMs }).containsExactly(0L, 4_000L, 0L, 3_000L, 7_000L)
    assertThat(timeline.map { it.endInItemMs })
      .containsExactly(4_000L, 9_000L, 3_000L, 7_000L, 12_000L)
    assertThat(timeline.map { it.bookStartMs })
      .containsExactly(0L, 4_000L, 9_000L, 12_000L, 16_000L)
    // `durationMs` is the only hand-written member on `BookChapter`, and it is five distinct
    // numbers here, so neither a constant nor `endInItemMs` alone satisfies it.
    assertThat(timeline.map { it.durationMs })
      .containsExactly(4_000L, 5_000L, 3_000L, 4_000L, 5_000L)
  }

  @Test
  fun `a chapter whose atoms run backwards has a zero duration rather than a negative one`() {
    // `BookChapter.durationMs` reaches a progress bar and a seek. Its clamp is a statement with no
    // branch that any other assertion in this file can see -- the five-chapter case above only
    // ever exercises the well-formed side.
    val files = listOf(BookFile("a", "Disc One", 9_000))
    val chapters = mapOf("a" to listOf(Chapter(0, 5_000, 1_000, "backwards")))

    assertThat(BookTimeline.of(files, chapters).single().durationMs).isZero
  }

  @Test
  fun `chapters inside one file are ordered by start time, whatever order they arrived in`() {
    // `ChapterAssembly` sorts what it reads, but the timeline is also built from Room rows and
    // from a caller's map, and neither of those is `ChapterAssembly`'s output by construction.
    val files = listOf(BookFile("a", "Disc One", 9_000))
    val chapters = mapOf(
      "a" to listOf(
        Chapter(1, 4_000, 9_000, "Two"),
        Chapter(0, 0, 4_000, "One"),
      ),
    )

    val timeline = BookTimeline.of(files, chapters)

    assertThat(timeline.map { it.title }).containsExactly("One", "Two")
    assertThat(timeline.map { it.startInItemMs }).containsExactly(0L, 4_000L)
  }

  @Test
  fun `an untitled chapter is numbered by its position in the book`() {
    // Two files, and the untitled chapter is in the SECOND one: numbering by position in the file
    // would call it "Chapter 1", and numbering by position in the book calls it "Chapter 3". Both
    // observations are in one list, so a formatter applied to everything renames "Named" too.
    val files = listOf(BookFile("a", "Disc One", 9_000), BookFile("b", "Disc Two", 9_000))
    val chapters = mapOf(
      "a" to listOf(Chapter(0, 0, 4_000, null), Chapter(1, 4_000, 9_000, "Named")),
      "b" to listOf(Chapter(0, 0, 9_000, null)),
    )

    val timeline = BookTimeline.of(files, chapters)

    assertThat(timeline.map { it.title }).containsExactly("Chapter 1", "Named", "Chapter 3")
  }

  @Test
  fun `a position exactly on a boundary belongs to the chapter that starts there`() {
    val timeline = BookTimeline.of(singleFile, secondBookChapters)

    // The half-open rule, asserted on both sides of one boundary. One millisecond apart, two
    // different answers -- which is what makes this an assertion about the rule rather than about
    // one number.
    assertThat(BookTimeline.chapterAt(timeline, "m4b", 3_999)?.title).isEqualTo("Prologue")
    assertThat(BookTimeline.chapterAt(timeline, "m4b", 4_000)?.title).isEqualTo("The Long Middle")
  }

  @Test
  fun `a position past the final chapter's end still answers the final chapter`() {
    // Encoder padding routinely puts the reported position past the last atom's end. Answering
    // `null` there empties the chapter title off the player at the very end of every book.
    val timeline = BookTimeline.of(singleFile, secondBookChapters)

    assertThat(BookTimeline.chapterAt(timeline, "m4b", 99_000)?.title).isEqualTo("Epilogue")
    // ...and a position before the first chapter's start answers the first, not null.
    assertThat(BookTimeline.chapterAt(timeline, "m4b", -1)?.title).isEqualTo("Prologue")
  }

  @Test
  fun `chapterAt is scoped to the item it was asked about`() {
    val timeline = BookTimeline.of(multiFile, emptyMap())

    // Same in-item position, three different answers. A `chapterAt` that ignored `mediaId` and
    // searched by book position would give "Part One" for all three.
    assertThat(BookTimeline.chapterAt(timeline, "p1", 1_000)?.title).isEqualTo("Part One")
    assertThat(BookTimeline.chapterAt(timeline, "p2", 1_000)?.title).isEqualTo("Part Two")
    assertThat(BookTimeline.chapterAt(timeline, "p3", 1_000)?.title).isEqualTo("Part Three")
    assertThat(BookTimeline.chapterAt(timeline, "not-in-this-book", 1_000)).isNull()
  }

  @Test
  fun `next walks forward across a file boundary and stops at the end`() {
    val timeline = BookTimeline.of(multiFile, emptyMap())

    assertThat(BookTimeline.next(timeline, timeline[0])?.title).isEqualTo("Part Two")
    assertThat(BookTimeline.next(timeline, timeline[1])?.title).isEqualTo("Part Three")
    assertThat(BookTimeline.next(timeline, timeline[2])).isNull()
    assertThat(BookTimeline.next(timeline, null)?.title).isEqualTo("Part One")
  }

  @Test
  fun `previous restarts the current chapter unless you are already near its start`() {
    val timeline = BookTimeline.of(singleFile, secondBookChapters)
    val third = timeline[2] // "A Turn", 9000..15000

    // Well inside the chapter: "previous" means "start this one again", which is what every
    // audiobook player does and what a listener who overshot expects.
    assertThat(BookTimeline.previous(timeline, third, positionInItemMs = 12_000)?.title)
      .isEqualTo("A Turn")
    // Just after its start: "previous" means the chapter before.
    assertThat(BookTimeline.previous(timeline, third, positionInItemMs = 9_500)?.title)
      .isEqualTo("The Long Middle")
    // ...and at the very first chapter there is nothing before it, so it restarts.
    assertThat(BookTimeline.previous(timeline, timeline[0], positionInItemMs = 100)?.title)
      .isEqualTo("Prologue")
  }

  @Test
  fun `previous measures how far into the chapter you are, not how far into the file`() {
    // The distinction a single-file book at chapter 1 cannot show: `positionInItemMs` is measured
    // from the file's start, and "am I near the start of this chapter" is measured from the
    // chapter's. A `previous` that compared the raw position against the threshold would call
    // 12000 ms "deep inside" for every chapter in the book, including the one that begins there.
    val timeline = BookTimeline.of(singleFile, secondBookChapters)
    val fourth = timeline[3] // "Epilogue", 15000..21000

    assertThat(BookTimeline.previous(timeline, fourth, positionInItemMs = 15_500)?.title)
      .isEqualTo("A Turn")
  }

  @Test
  fun `the restart threshold is a parameter and it actually moves the answer`() {
    val timeline = BookTimeline.of(singleFile, secondBookChapters)
    val third = timeline[2]

    // Same position, two thresholds, two answers. Without this, `RESTART_THRESHOLD_MS` is a
    // constant that could be any value at all.
    assertThat(
      BookTimeline.previous(timeline, third, positionInItemMs = 11_000, restartThresholdMs = 1_000)?.title,
    ).isEqualTo("A Turn")
    assertThat(
      BookTimeline.previous(timeline, third, positionInItemMs = 11_000, restartThresholdMs = 5_000)?.title,
    ).isEqualTo("The Long Middle")
  }

  @Test
  fun `the default restart threshold is the declared one and not some other number`() {
    // The two-argument form and the explicit-threshold form, over the same position, at the one
    // millisecond where `RESTART_THRESHOLD_MS` and `RESTART_THRESHOLD_MS - 1` disagree. Otherwise
    // the default could be any value at all and every test above would still pass.
    val timeline = BookTimeline.of(singleFile, secondBookChapters)
    val third = timeline[2] // "A Turn", starts at 9000
    val atThreshold = 9_000L + BookTimeline.RESTART_THRESHOLD_MS

    assertThat(BookTimeline.previous(timeline, third, atThreshold)?.title).isEqualTo("A Turn")
    assertThat(BookTimeline.previous(timeline, third, atThreshold - 1)?.title)
      .isEqualTo("The Long Middle")
  }

  @Test
  fun `the book position adds the offset of the item you are in`() {
    val timeline = BookTimeline.of(multiFile, emptyMap())

    // Three items, three offsets. The whole-book progress bar is this number, and with one item
    // it is indistinguishable from the in-item position.
    assertThat(BookTimeline.bookPositionMs(timeline, "p1", 1_000)).isEqualTo(1_000L)
    assertThat(BookTimeline.bookPositionMs(timeline, "p2", 1_000)).isEqualTo(5_000L)
    assertThat(BookTimeline.bookPositionMs(timeline, "p3", 1_000)).isEqualTo(11_000L)
  }

  @Test
  fun `the book position is the item's offset, not its first chapter's start`() {
    // `bookPositionMs` finds the item by its FIRST chapter and then subtracts that chapter's own
    // `startInItemMs` to recover where the item begins. In every book in the seeded corpus the
    // first chapter of a file starts at 0, so `bookStartMs - startInItemMs` and `bookStartMs`
    // agree and the subtraction is invisible -- deleting it leaves every assertion in this file
    // green. **This fixture exists to make that mutation fail.** Disc Two's first chapter atom
    // starts a second in, which a file with unmarked front matter really does; the item still
    // begins at 9000, so 5000 ms into it is 14000 ms into the book, never 15000.
    val files = listOf(BookFile("a", "Disc One", 9_000), BookFile("b", "Disc Two", 12_000))
    val chapters = mapOf(
      "a" to listOf(Chapter(0, 0, 9_000, "One")),
      "b" to listOf(Chapter(0, 1_000, 3_000, "Two"), Chapter(1, 3_000, 12_000, "Three")),
    )

    val timeline = BookTimeline.of(files, chapters)

    assertThat(timeline.map { it.bookStartMs }).containsExactly(0L, 10_000L, 12_000L)
    assertThat(BookTimeline.bookPositionMs(timeline, "b", 5_000)).isEqualTo(14_000L)
    assertThat(BookTimeline.bookPositionMs(timeline, "a", 5_000)).isEqualTo(5_000L)
  }

  @Test
  fun `a book with no files has an empty timeline and no navigation`() {
    // The vacuous-collection case, asserted rather than assumed. Every function above iterates a
    // computed list; on an empty one they run zero times and must still answer sensibly.
    val timeline = BookTimeline.of(emptyList(), emptyMap())

    assertThat(timeline).isEmpty()
    assertThat(BookTimeline.chapterAt(timeline, "anything", 0)).isNull()
    assertThat(BookTimeline.next(timeline, null)).isNull()
    assertThat(BookTimeline.previous(timeline, null, 0)).isNull()
    assertThat(BookTimeline.bookPositionMs(timeline, "anything", 5_000)).isEqualTo(5_000L)
  }
}
