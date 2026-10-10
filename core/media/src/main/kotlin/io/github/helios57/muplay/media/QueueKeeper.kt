package io.github.helios57.muplay.media

import androidx.media3.common.Player
import io.github.helios57.muplay.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps [QueueStore] in step with whichever player the session is driving.
 *
 * Writes a moment after the queue, the current item, repeat or shuffle change, and every
 * [TICK_MS] while playing so a restored queue resumes near where it stopped rather than at the
 * start of the track. All of it runs on [scope], which must be the player's own (main) thread.
 *
 * Two rules keep it from destroying the thing it exists to keep:
 *
 * - **Nothing is written until [restoreFinished].** A service starts with an empty player and
 *   fills it from the file a moment later; writing that empty player first would erase the queue
 *   it was about to restore.
 * - **An empty player clears the file only when it emptied under a queue this keeper saw.** A
 *   player that never had anything says nothing about the saved queue.
 */
class QueueKeeper(
  private val store: QueueStore,
  private val songFor: (String) -> Song?,
  private val scope: CoroutineScope,
) {

  private var player: Player? = null
  private var restored = false
  private var hadItems = false
  private var pending: Job? = null
  private var ticker: Job? = null

  private val listener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
      if (events.containsAny(*TRIGGERS)) schedule()
      if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) tick(player.isPlaying)
    }
  }

  fun attach(next: Player) {
    if (next === player) return
    player?.removeListener(listener)
    player = next
    next.addListener(listener)
    tick(next.isPlaying)
  }

  fun restoreFinished() {
    restored = true
    hadItems = (player?.mediaItemCount ?: 0) > 0
    schedule()
  }

  /** Writes now, on the calling thread. For `onDestroy` and `onTaskRemoved`. */
  fun flushBlocking() {
    pending?.cancel()
    snapshot()?.let(store::saveBlocking)
  }

  fun stop() {
    pending?.cancel()
    ticker?.cancel()
    player?.removeListener(listener)
    player = null
  }

  private fun schedule() {
    if (!restored) return
    pending?.cancel()
    pending = scope.launch {
      delay(SETTLE_MS)
      writeNow()
    }
  }

  private fun tick(playing: Boolean) {
    ticker?.cancel()
    ticker = if (!playing) null else scope.launch {
      while (true) {
        delay(TICK_MS)
        if (restored) writeNow()
      }
    }
  }

  private suspend fun writeNow() {
    val current = player ?: return
    if (current.mediaItemCount == 0) {
      if (hadItems) store.clear()
      hadItems = false
      return
    }
    hadItems = true
    snapshot()?.let { store.save(it) }
  }

  private fun snapshot(): SavedQueue? {
    val current = player ?: return null
    if (!restored || current.mediaItemCount == 0) return null
    val playing = current.currentMediaItemIndex
    val songs = mutableListOf<Song>()
    var index = 0
    for (i in 0 until current.mediaItemCount) {
      val song = songFor(current.getMediaItemAt(i).mediaId) ?: continue
      if (i <= playing) index = songs.size
      songs += song
    }
    if (songs.isEmpty()) return null
    return SavedQueue(
      songs = songs,
      currentIndex = index,
      positionMs = current.currentPosition.coerceAtLeast(0L),
      repeatMode = current.repeatMode,
      shuffle = current.shuffleModeEnabled,
    )
  }

  companion object {
    const val SETTLE_MS = 500L
    const val TICK_MS = 15_000L

    private val TRIGGERS = intArrayOf(
      Player.EVENT_TIMELINE_CHANGED,
      Player.EVENT_MEDIA_ITEM_TRANSITION,
      Player.EVENT_REPEAT_MODE_CHANGED,
      Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
      Player.EVENT_IS_PLAYING_CHANGED,
      Player.EVENT_POSITION_DISCONTINUITY,
    )
  }
}
