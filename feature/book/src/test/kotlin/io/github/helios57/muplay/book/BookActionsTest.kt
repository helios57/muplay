package io.github.helios57.muplay.book

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What the book screen offers, by where the listener is in the book. It used to offer `Resume`
 * whatever the state -- on a book never opened, and on a finished one, where it replayed the last
 * seconds of the last file.
 */
class BookActionsTest {

  @Test
  fun `a book nobody has opened is played, and there is nothing to start over`() {
    val actions = bookActions(hasStarted = false, isFinished = false)

    assertThat(actions.primary).isEqualTo(BookPrimary.Play)
    assertThat(actions.offersStartOver).isFalse
    assertThat(actions.confirmsStartOver).isFalse
    assertThat(actions.finishToggle).isEqualTo(FinishToggle.MarkFinished)
  }

  @Test
  fun `a book in progress resumes, and starting over asks first because it forgets the place`() {
    val actions = bookActions(hasStarted = true, isFinished = false)

    assertThat(actions.primary).isEqualTo(BookPrimary.Resume)
    assertThat(actions.offersStartOver).isTrue
    assertThat(actions.confirmsStartOver).isTrue
    assertThat(actions.finishToggle).isEqualTo(FinishToggle.MarkFinished)
  }

  @Test
  fun `a finished book is listened to again from the start, and can be marked unfinished`() {
    val actions = bookActions(hasStarted = true, isFinished = true)

    assertThat(actions.primary).isEqualTo(BookPrimary.ListenAgain)
    assertThat(actions.offersStartOver).isFalse
    assertThat(actions.finishToggle).isEqualTo(FinishToggle.MarkUnfinished)
  }

  @Test
  fun `each primary action has its own words`() {
    assertThat(BookPrimary.entries.map { it.label }).doesNotHaveDuplicates()
    assertThat(BookPrimary.Resume.label).isEqualTo(RESUME_LABEL)
  }

  @Test
  fun `a finished book says it is finished rather than how little is left`() {
    assertThat(remainingLabel(remainingMs = 0L, isFinished = true)).isEqualTo(FINISHED_LABEL)
    assertThat(remainingLabel(remainingMs = 3_600_000L, isFinished = false)).isEqualTo("1 h 0 m left")
  }
}
