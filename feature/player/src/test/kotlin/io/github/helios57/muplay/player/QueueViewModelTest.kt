package io.github.helios57.muplay.player

import io.github.helios57.muplay.media.QueueItem
import io.github.helios57.muplay.media.QueueSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [QueueViewModel]'s own logic, on the JVM, reachable through the [QueueControls] seam for the
 * reason [PlaybackControls] exists -- see that interface's header. `PlaybackConnection` binds a
 * `MediaController` to the main `Looper` and `QueueEditor` needs one, so neither can be built here,
 * and this project bans mock frameworks.
 *
 * The seam is primitives again: `move(from, to)` rather than `moveUp(index)`, so that the
 * off-by-one this class exists to get right is decided *here*, where a test can see it, and not in
 * the adapter, where nothing can.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {

  private class FakeQueueControls : QueueControls {
    val calls = mutableListOf<String>()

    private val published = MutableStateFlow(QueueSnapshot.EMPTY)
    override val queue: StateFlow<QueueSnapshot> = published

    fun publish(snapshot: QueueSnapshot) {
      published.value = snapshot
    }

    override suspend fun connect() {
      calls += "connect"
    }

    override suspend fun jumpTo(index: Int) {
      calls += "jumpTo($index)"
    }

    override suspend fun remove(index: Int) {
      calls += "remove($index)"
    }

    override suspend fun move(from: Int, to: Int) {
      calls += "move($from, $to)"
    }
  }

  private val dispatcher = StandardTestDispatcher()

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  private fun snapshotOf(currentIndex: Int, vararg titles: String) = QueueSnapshot(
    items = titles.map { QueueItem(mediaId = "id-$it", title = it, artist = "Test Artist") },
    currentIndex = currentIndex,
  )

  /** `stateIn(WhileSubscribed(..))` produces nothing without a collector; see `PlayerViewModelTest`. */
  private fun TestScope.warm(controls: FakeQueueControls): QueueViewModel {
    val viewModel = QueueViewModel(controls)
    backgroundScope.launch { viewModel.uiState.collect {} }
    return viewModel
  }

  @Test
  fun `an unconnected screen shows an empty queue rather than nothing at all`() = runTest(dispatcher) {
    val viewModel = warm(FakeQueueControls())
    advanceUntilIdle()

    assertThat(viewModel.uiState.value).isEqualTo(QueueUiState.Empty)
  }

  @Test
  fun `constructing the view model connects to the session`() = runTest(dispatcher) {
    val controls = FakeQueueControls()
    warm(controls)
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("connect")
  }

  @Test
  fun `the screen renders whatever the connection reports about the queue`() = runTest(dispatcher) {
    val controls = FakeQueueControls()
    val viewModel = warm(controls)

    controls.publish(snapshotOf(1, "First", "Second"))
    advanceUntilIdle()
    val rows = (viewModel.uiState.value as QueueUiState.Content).rows
    assertThat(rows.map { it.title }).containsExactly("First", "Second")
    assertThat(rows.map { it.isCurrent }).containsExactly(false, true)

    // A second, disjoint observation: a `uiState` that latched onto the first snapshot passes
    // the assertion above and fails this one.
    controls.publish(snapshotOf(0, "Third"))
    advanceUntilIdle()
    assertThat((viewModel.uiState.value as QueueUiState.Content).rows.map { it.title })
      .containsExactly("Third")
  }

  @Test
  fun `tapping a row plays that row`() = runTest(dispatcher) {
    val controls = FakeQueueControls()
    val viewModel = warm(controls)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.jumpTo(2)
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("jumpTo(2)")
  }

  @Test
  fun `removing a row removes that row`() = runTest(dispatcher) {
    val controls = FakeQueueControls()
    val viewModel = warm(controls)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.remove(3)
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("remove(3)")
  }

  /**
   * The arrows, and the whole reason this class has a test.
   *
   * Media3's `moveMediaItem(from, to)` takes the index the item should **end up at**, so "up" is
   * `index - 1` and "down" is `index + 1`. Getting either backwards is invisible on a two-item
   * queue -- moving item 1 to index 0 and item 0 to index 1 produce the same list -- so both
   * assertions below run against a queue of three.
   */
  @Test
  fun `the up arrow moves a row one place towards the front`() = runTest(dispatcher) {
    val controls = FakeQueueControls()
    val viewModel = warm(controls)
    controls.publish(snapshotOf(0, "First", "Second", "Third"))
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.moveUp(2)
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("move(2, 1)")
  }

  @Test
  fun `the down arrow moves a row one place towards the end`() = runTest(dispatcher) {
    val controls = FakeQueueControls()
    val viewModel = warm(controls)
    controls.publish(snapshotOf(0, "First", "Second", "Third"))
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.moveDown(0)
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("move(0, 1)")
  }
}
