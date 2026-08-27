package app.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two derived members of [Chapter]. Both are arithmetic on values a container wrote, so both
 * are gated here rather than at the reader that produces them.
 */
class ChapterTest {

  private fun chapter(startMs: Long, endMs: Long) =
    Chapter(index = 3, startMs = startMs, endMs = endMs, title = "Wolves at the Door")

  @Test
  fun `a chapter's duration is the gap between its two atoms`() {
    // Deliberately not starting at 0: a `durationMs` that returned `endMs` alone would be right
    // for every chapter that starts at zero, which is chapter 0 of every book there is.
    assertThat(chapter(startMs = 62_000L, endMs = 181_500L).durationMs).isEqualTo(119_500L)
    assertThat(chapter(startMs = 0L, endMs = 62_000L).durationMs).isEqualTo(62_000L)
  }

  @Test
  fun `a chapter whose atoms are out of order is zero long rather than negative`() {
    // A real tagger can write these backwards, and every consumer renders this to a screen. An
    // unclamped subtraction gives -1_000 and a progress bar that runs the wrong way.
    assertThat(chapter(startMs = 10_000L, endMs = 9_000L).durationMs).isZero()
    assertThat(chapter(startMs = 10_000L, endMs = 10_000L).durationMs).isZero()
  }

  @Test
  fun `containment is half-open, so a position on a boundary is in the later chapter`() {
    val second = chapter(startMs = 62_000L, endMs = 181_500L)

    // Both sides of both boundaries, which is what makes this discriminate. A `>` at the start
    // would only be visible at exactly 62_000; a `<=` at the end only at exactly 181_500.
    assertThat(second.contains(61_999L)).isFalse()
    assertThat(second.contains(62_000L)).isTrue()
    assertThat(second.contains(181_499L)).isTrue()
    assertThat(second.contains(181_500L)).isFalse()
  }

  @Test
  fun `a position is in exactly one of two adjacent chapters, never both and never neither`() {
    // The property the half-open rule exists for, stated over the pair rather than over one
    // chapter: with a closed upper bound 181_500 would be in both, and "which chapter am I in"
    // would depend on which end of the list the caller searched from.
    val second = Chapter(index = 1, startMs = 62_000L, endMs = 181_500L, title = null)
    val third = Chapter(index = 2, startMs = 181_500L, endMs = 240_000L, title = null)

    assertThat(listOf(61_999L, 62_000L, 181_499L, 181_500L, 239_999L, 240_000L)
      .map { position -> listOf(second, third).count { it.contains(position) } })
      .containsExactly(0, 1, 1, 1, 1, 0)
  }

  @Test
  fun `a zero-length chapter contains nothing at all`() {
    // Falls out of half-open containment and is worth pinning: a chapter atom pair that a tagger
    // wrote identically must not swallow the position that lands on it.
    assertThat(chapter(startMs = 5_000L, endMs = 5_000L).contains(5_000L)).isFalse()
  }
}
