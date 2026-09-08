package io.github.helios57.muplay.player

import io.github.helios57.muplay.media.QueueSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A hand-written [QueueControls] for the one instrumented case that composes `QueueScreen()`'s
 * Hilt-bound entry point over a real [QueueViewModel] -- see `RecordingPlaybackControls` for why
 * this project's screens have a seam like this at all.
 */
internal class RecordingQueueControls : QueueControls {

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
