package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The Android-free half of chapter reading, gated in Tier 1.
 *
 * Every assertion is over an **exact list in exact order**. Chapter order is the property this
 * whole feature rests on: a book whose chapters come back sorted by title, or de-duplicated into a
 * different order, is a book that plays its epilogue third, and `containsExactlyInAnyOrder` would
 * say nothing about it.
 */
class ChapterAssemblyTest {

  @Test
  fun `chapters are ordered by start time, whatever order they arrived in`() {
    val raw = listOf(
      RawChapter(9_000, 15_000, "A Turn"),
      RawChapter(0, 4_000, "Prologue"),
      RawChapter(15_000, 21_000, "Epilogue"),
      RawChapter(4_000, 9_000, "The Long Middle"),
    )

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 21_000)

    assertThat(chapters.map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    assertThat(chapters.map { it.startMs }).containsExactly(0L, 4_000L, 9_000L, 15_000L)
  }

  @Test
  fun `the index is the position in the ordered list, not the order of arrival`() {
    val raw = listOf(RawChapter(5_000, 9_000, "second"), RawChapter(0, 5_000, "first"))

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 9_000)

    // Two observations of `index`. An implementation that copied an arrival index would give
    // "first" index 1 here, and a constant 0 would give both the same.
    assertThat(chapters.map { it.index }).containsExactly(0, 1)
    assertThat(chapters.map { it.title }).containsExactly("first", "second")
  }

  @Test
  fun `a missing end time is filled from the next chapter's start`() {
    // The `C.TIME_UNSET` case spike S3 found, arriving here as a null. Three chapters, two of them
    // missing an end, and the two fills are DIFFERENT numbers -- a constant satisfies neither.
    val raw = listOf(
      RawChapter(0, null, "one"),
      RawChapter(4_000, null, "two"),
      RawChapter(9_000, 21_000, "three"),
    )

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 21_000)

    assertThat(chapters.map { it.endMs }).containsExactly(4_000L, 9_000L, 21_000L)
  }

  @Test
  fun `the last chapter's missing end time comes from the content duration`() {
    // Two observations with two different durations, because "the content duration" and "21000"
    // are the same program if only one duration is ever passed.
    val raw = listOf(RawChapter(0, null, "only"))

    assertThat(ChapterAssembly.assemble(raw, contentDurationMs = 21_000).single().endMs)
      .isEqualTo(21_000L)
    assertThat(ChapterAssembly.assemble(raw, contentDurationMs = 12_000).single().endMs)
      .isEqualTo(12_000L)
  }

  @Test
  fun `a populated end time is never overwritten by the duration`() {
    // The other direction, and the one that would hide a reader that ignored what Media3 returned.
    val raw = listOf(RawChapter(0, 4_000, "one"), RawChapter(4_000, 9_000, "two"))

    assertThat(ChapterAssembly.assemble(raw, contentDurationMs = 21_000).map { it.endMs })
      .containsExactly(4_000L, 9_000L)
  }

  @Test
  fun `duplicate entries for the same start time collapse to one, keeping the titled one`() {
    // Media3 surfaces metadata per track format, and a file with more than one track can present
    // the same chapter list twice. Left alone that doubles every book's chapter list.
    val raw = listOf(
      RawChapter(0, 4_000, null),
      RawChapter(0, 4_000, "Prologue"),
      RawChapter(4_000, 9_000, "The Long Middle"),
      RawChapter(4_000, 9_000, "The Long Middle"),
    )

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 9_000)

    assertThat(chapters.map { it.title }).containsExactly("Prologue", "The Long Middle")
  }

  @Test
  fun `a duplicate contributes its end time to a twin that has none`() {
    // The other half of de-duplication, and the shape a real two-track M4B presents: the `chpl`
    // track carries titles with unset ends, the `chap` track carries ends. Keeping only the first
    // entry seen would drop the end and silently fall back to the next chapter's start -- right
    // here by luck, wrong the moment a chapter does not abut its neighbour.
    val raw = listOf(
      RawChapter(0, null, "Prologue"),
      RawChapter(0, 3_000, null),
      RawChapter(4_000, 9_000, "The Long Middle"),
    )

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 9_000)

    // Titled by the first entry, ended by the second -- neither entry alone gives this pair.
    assertThat(chapters.map { it.title }).containsExactly("Prologue", "The Long Middle")
    assertThat(chapters.map { it.endMs }).containsExactly(3_000L, 9_000L)
  }

  @Test
  fun `an untitled chapter keeps a null title, and a titled one keeps its own`() {
    // Two observations in one list. A formatter applied to everything would rename "Prologue".
    val raw = listOf(RawChapter(0, 4_000, "Prologue"), RawChapter(4_000, 9_000, null))

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 9_000)

    assertThat(chapters.map { it.title }).containsExactly("Prologue", null)
    // The *display* name is the caller's business -- `BookTimeline` numbers it -- and this is the
    // assertion that keeps "untitled" a fact rather than a string that looks like a title.
  }

  @Test
  fun `a blank title is the same fact as no title`() {
    val raw = listOf(RawChapter(0, 4_000, "   "), RawChapter(4_000, 9_000, ""))

    assertThat(ChapterAssembly.assemble(raw, contentDurationMs = 9_000).map { it.title })
      .containsExactly(null, null)
  }

  @Test
  fun `a title with surrounding whitespace is trimmed rather than kept as written`() {
    // The other side of the blank rule, and the one a `takeIf { it.isNotBlank() }` written over
    // the untrimmed string would pass while leaving "\tPrologue " on the screen.
    val raw = listOf(RawChapter(0, 4_000, "  Prologue\t"))

    assertThat(ChapterAssembly.assemble(raw, contentDurationMs = 4_000).single().title)
      .isEqualTo("Prologue")
  }

  @Test
  fun `an end time before its own start is clamped rather than producing a negative duration`() {
    // A malformed atom is a real thing in the wild. A negative `durationMs` reaches a progress bar
    // and reaches `seekTo`, and neither of those has a sensible behaviour for it.
    val raw = listOf(RawChapter(9_000, 4_000, "backwards"))

    val chapter = ChapterAssembly.assemble(raw, contentDurationMs = 21_000).single()

    assertThat(chapter.endMs).isEqualTo(9_000L)
    assertThat(chapter.durationMs).isZero
  }

  @Test
  fun `a chapter that starts before the file does is dropped rather than clamped`() {
    // A negative start is not a chapter anyone can seek to, and clamping it to zero would put a
    // second "chapter 1" in front of the real one. Two entries in, one out -- so the guard is
    // observed doing something rather than merely being present.
    val raw = listOf(RawChapter(-1_000, 4_000, "before the beginning"), RawChapter(0, 4_000, "one"))

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 9_000)

    assertThat(chapters.map { it.title }).containsExactly("one")
    assertThat(chapters.map { it.startMs }).containsExactly(0L)
  }

  @Test
  fun `an empty input produces an empty list rather than an invented chapter`() {
    // "No chapters" is a real answer for most audiobook files there are. Fabricating a single
    // whole-file chapter here would make `Multi Part Book` indistinguishable from a chaptered one
    // and would put a wrong chapter title on every music track that ever passed through.
    assertThat(ChapterAssembly.assemble(emptyList(), contentDurationMs = 21_000)).isEmpty()
  }
}
