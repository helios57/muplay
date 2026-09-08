package io.github.helios57.muplay.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.helios57.muplay.media.PlaybackConnection
import io.github.helios57.muplay.media.QueueEditor
import io.github.helios57.muplay.media.QueueSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The queue operations [QueueViewModel] needs, abstracted for the reason [PlaybackControls] is --
 * see that interface's header. Both halves of the real thing are unreachable on the JVM:
 * `PlaybackConnection` binds a `MediaController` to the main `Looper`, and [QueueEditor] is built
 * over it.
 *
 * **Primitives, not intentions**, again deliberately: [move] takes the destination index rather
 * than the interface offering `moveUp`/`moveDown`. The arithmetic that turns an arrow tap into a
 * destination is the only decision this view model makes, and a coarser seam would push it into
 * the adapter where nothing can reach it.
 */
interface QueueControls {

  /** The live queue, as `PlaybackConnection` publishes it. */
  val queue: StateFlow<QueueSnapshot>

  /** Connects to the session if it is not connected yet. Idempotent. */
  suspend fun connect()

  suspend fun jumpTo(index: Int)

  suspend fun remove(index: Int)

  /** Moves the item at [from] so that it ends up at [to]. */
  suspend fun move(from: Int, to: Int)
}

/**
 * Drives the queue screen.
 *
 * It holds no queue of its own: the timeline in the media session **is** the queue, so every edit
 * goes to the player and the screen redraws from what the player then reports. A view model that
 * kept its own list would be a second answer to "what plays next", and the one the car, the watch
 * and the lock screen use is the player's.
 */
@HiltViewModel
class QueueViewModel(private val controls: QueueControls) : ViewModel() {

  @Inject
  constructor(connection: PlaybackConnection, editor: QueueEditor) : this(
    object : QueueControls {
      override val queue: StateFlow<QueueSnapshot> = connection.queue

      override suspend fun connect() {
        connection.controller()
      }

      override suspend fun jumpTo(index: Int) = editor.jumpTo(index)

      override suspend fun remove(index: Int) = editor.remove(index)

      override suspend fun move(from: Int, to: Int) = editor.move(from, to)
    },
  )

  val uiState: StateFlow<QueueUiState> =
    controls.queue.map(::queueUiState).stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = QueueUiState.Empty,
    )

  init {
    // Without this the screen renders an empty queue forever while audio is audibly playing --
    // the same defect, for the same reason, as `PlayerViewModel`'s own `connect`.
    viewModelScope.launch { controls.connect() }
  }

  fun jumpTo(index: Int) {
    viewModelScope.launch { controls.jumpTo(index) }
  }

  fun remove(index: Int) {
    viewModelScope.launch { controls.remove(index) }
  }

  /** See `the up arrow moves a row one place towards the front` for why this is worth a test. */
  fun moveUp(index: Int) {
    viewModelScope.launch { controls.move(from = index, to = index - 1) }
  }

  fun moveDown(index: Int) {
    viewModelScope.launch { controls.move(from = index, to = index + 1) }
  }

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
