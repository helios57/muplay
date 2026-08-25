package app.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * The three derived properties of [BookSummary].
 *
 * See [BookSummary]'s own doc for why this type is here at all: Plan 4 Task 4 owns it and has not
 * landed, and Plan 5 Task 2's browse tree cannot be written without it. This file exists so the
 * stand-in is not the one class in `:core:model` with hand-written arithmetic and no test on it;
 * when Plan 4 Task 4 lands, both files are its to reconcile.
 */
class BookSummaryTest {

  @Test
  fun `remaining time is the gap between duration and position, clamped at zero`() {
    // Clamped, not signed: Media3 reports a position past a container's declared duration on a
    // stream whose duration was estimated, and every consumer of this value renders it.
    assertThat(listOf(0L, 1_000L, 30_000L, 30_001L, 90_000L).map { book(positionMs = it).remainingMs })
      .containsExactly(30_000L, 29_000L, 0L, 0L, 0L)
  }

  @Test
  fun `the progress fraction is position over duration, clamped into zero to one`() {
    assertThat(listOf(0L, 6_000L, 15_000L, 30_000L, 90_000L).map { book(positionMs = it).progressFraction })
      .containsExactly(0.0, 0.2, 0.5, 1.0, 1.0)
  }

  @Test
  fun `a book of unknown length reports no progress rather than dividing by zero`() {
    // A duration of 0 is what an unscanned or unreadable container reports. `positionMs /
    // durationMs` would be a `NaN` that silently poisons every progress bar downstream, and
    // `NaN.coerceIn(0.0, 1.0)` is still `NaN`.
    assertThat(book(durationMs = 0L, positionMs = 5_000L).progressFraction).isEqualTo(0.0)
    assertThat(book(durationMs = 0L, positionMs = 5_000L).remainingMs).isEqualTo(0L)
    assertThat(book(durationMs = -1L, positionMs = 5_000L).progressFraction).isEqualTo(0.0)
  }

  @Test
  fun `a book has started once its position leaves zero, and not before`() {
    // The boundary on both sides. `hasStarted` is what puts a book on the Continue shelf, so
    // `>= 0` instead of `> 0` would put every book a user has never opened onto it.
    assertThat(listOf(0L, 1L, 30_000L).map { book(positionMs = it).hasStarted })
      .containsExactly(false, true, true)
  }

  @Test
  fun `the derived properties read the fields they name, not a neighbour`() {
    // Two summaries differing only in `durationMs`, so a `remainingMs` that read `fileCount` or a
    // constant cannot agree with both.
    assertThat(book(durationMs = 30_000L, positionMs = 10_000L).remainingMs).isEqualTo(20_000L)
    assertThat(book(durationMs = 50_000L, positionMs = 10_000L).remainingMs).isEqualTo(40_000L)
    assertThat(book(durationMs = 50_000L, positionMs = 10_000L).progressFraction)
      .isEqualTo(0.2, within(1e-9))
  }

  private companion object {
    fun book(durationMs: Long = 30_000L, positionMs: Long = 0L) = BookSummary(
      bookId = "b-1",
      libraryId = 2,
      title = "Test Book",
      author = "Anonymous",
      coverArtId = "cov-1",
      fileCount = 1,
      durationMs = durationMs,
      positionMs = positionMs,
      isFinished = false,
      lastPlayedAtEpochMs = 0L,
    )
  }
}
