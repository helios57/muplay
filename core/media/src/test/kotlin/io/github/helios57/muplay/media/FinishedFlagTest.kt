package io.github.helios57.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FinishedFlagTest {

  private val end = 900_000L

  @Test
  fun `reaching the end finishes an item that was not finished`() {
    assertThat(FinishedFlag.next(false, 0L, end, reachedTheEnd = true)).isTrue
  }

  @Test
  fun `reaching the end again keeps it finished`() {
    assertThat(FinishedFlag.next(true, end, end, reachedTheEnd = true)).isTrue
  }

  @Test
  fun `an ordinary write never finishes an unfinished item`() {
    assertThat(FinishedFlag.next(false, 10_000L, 20_000L, reachedTheEnd = false)).isFalse
  }

  @Test
  fun `the ticker parked at the end after the ending leaves the item finished`() {
    assertThat(FinishedFlag.next(true, end, end, reachedTheEnd = false)).isTrue
  }

  @Test
  fun `a position that jitters back by less than the tolerance leaves it finished`() {
    val jitter = end - FinishedFlag.REWIND_TOLERANCE_MS
    assertThat(FinishedFlag.next(true, end, jitter, reachedTheEnd = false)).isTrue
  }

  @Test
  fun `a position one millisecond past the tolerance is the listener starting again`() {
    val back = end - FinishedFlag.REWIND_TOLERANCE_MS - 1
    assertThat(FinishedFlag.next(true, end, back, reachedTheEnd = false)).isFalse
  }

  @Test
  fun `playing again from the start clears it`() {
    assertThat(FinishedFlag.next(true, end, 0L, reachedTheEnd = false)).isFalse
  }
}
