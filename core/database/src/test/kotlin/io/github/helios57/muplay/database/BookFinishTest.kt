package io.github.helios57.muplay.database

import io.github.helios57.muplay.database.entity.MediaProgressEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookFinishTest {

  private fun row(id: String, finished: Boolean, at: Long) =
    id to MediaProgressEntity(id, 1_000L, finished, at, 1f, false, 0f)

  private val files = listOf("a", "b", "c")

  @Test
  fun `a book whose last file is finished is finished`() {
    assertThat(BookFinish.isFinished(files, mapOf(row("c", true, 10)))).isTrue
  }

  @Test
  fun `a finished file that is not the last one does not finish the book`() {
    assertThat(BookFinish.isFinished(files, mapOf(row("a", true, 10)))).isFalse
  }

  @Test
  fun `a last file that is not flagged does not finish the book`() {
    assertThat(BookFinish.isFinished(files, mapOf(row("c", false, 10)))).isFalse
  }

  @Test
  fun `hearing an earlier file after the ending un-finishes the book`() {
    // Resume on a finished book starts at part one. The flag on part three is stale from then on.
    val progress = mapOf(row("c", true, 10), row("a", false, 20))
    assertThat(BookFinish.isFinished(files, progress)).isFalse
  }

  @Test
  fun `an earlier file heard before the ending leaves the book finished`() {
    val progress = mapOf(row("a", false, 5), row("c", true, 10))
    assertThat(BookFinish.isFinished(files, progress)).isTrue
  }

  @Test
  fun `a tie in time leaves the book finished, because mark-finished stamps every file alike`() {
    val progress = mapOf(row("a", true, 10), row("b", true, 10), row("c", true, 10))
    assertThat(BookFinish.isFinished(files, progress)).isTrue
  }

  @Test
  fun `a book with no files is not finished`() {
    assertThat(BookFinish.isFinished(emptyList(), mapOf(row("c", true, 10)))).isFalse
  }
}
