package io.github.helios57.muplay.player

import io.github.helios57.muplay.media.QueueEdit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a queue edit is reported as.
 *
 * Every assertion here is on the **literal sentence**, which is deliberate and is the exception to
 * this project's rule against retyping a production string into a test. That rule is about tests
 * that *find* a control by name, where a retyped copy goes on matching after the screen stops
 * saying it. This suite is not looking for anything: the words are the behaviour, and a test that
 * asserted `queueEditMessage(..) == queueAddedLabel(..)` would pass for any wording at all,
 * including none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueFeedbackTest {

  @Test
  fun `one appended track is reported without a count`() {
    assertThat(queueEditMessage(QueueEdit.Appended(1))).isEqualTo("Added to the queue")
  }

  @Test
  fun `an appended album is reported with the number of tracks it added`() {
    assertThat(queueEditMessage(QueueEdit.Appended(12)))
      .isEqualTo("Added 12 tracks to the queue")
  }

  @Test
  fun `one track inserted next is reported without a count`() {
    assertThat(queueEditMessage(QueueEdit.InsertedNext(1))).isEqualTo("Playing next")
  }

  @Test
  fun `several tracks inserted next are reported with their number`() {
    assertThat(queueEditMessage(QueueEdit.InsertedNext(3))).isEqualTo("3 tracks playing next")
  }

  /**
   * The two edits say different things. Without this the whole mapping could collapse to one
   * sentence and every other assertion here would still pass, since each names only its own arm.
   */
  @Test
  fun `appending and inserting next are not reported as the same thing`() {
    assertThat(queueEditMessage(QueueEdit.Appended(4)))
      .isNotEqualTo(queueEditMessage(QueueEdit.InsertedNext(4)))
  }

  @Test
  fun `the view model reports one message per edit, in order`() = runTest {
    val edits = MutableSharedFlow<QueueEdit>(extraBufferCapacity = 4)
    val viewModel = QueueFeedbackViewModel(edits)

    val seen = mutableListOf<String>()
    val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
      viewModel.messages.toList(seen)
    }

    edits.emit(QueueEdit.InsertedNext(1))
    edits.emit(QueueEdit.Appended(2))
    collector.cancel()

    assertThat(seen).containsExactly("Playing next", "Added 2 tracks to the queue")
  }
}
