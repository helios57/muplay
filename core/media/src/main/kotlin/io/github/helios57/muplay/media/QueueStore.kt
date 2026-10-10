package io.github.helios57.muplay.media

import io.github.helios57.muplay.database.AccountScopedData
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * **The play queue, on disk.**
 *
 * The queue used to exist only inside the player. Media3 stops a service that has sat paused for
 * a few minutes, and the system reclaims the process soon after, so a listener who paused, put the
 * phone down and came back found "Nothing playing" and the queue gone. [QueueKeeper] writes the
 * queue here as it changes; the service reads it back when it starts with an empty player, and
 * `MuPlayLibraryCallback.onPlaybackResumption` offers it to a headset's play button.
 *
 * One file, replaced whole through a temporary sibling and a rename, so a process killed mid-write
 * leaves the previous queue rather than half of a new one. Cleared on sign-out: a queue is the
 * account's songs, and the next account's server would not know their ids.
 */
class QueueStore(private val file: File) : AccountScopedData {

  private val lock = Mutex()

  suspend fun load(): SavedQueue? = lock.withLock {
    withContext(Dispatchers.IO) {
      runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.let(SavedQueueCodec::decode)
    }
  }

  suspend fun save(queue: SavedQueue) = lock.withLock {
    withContext(Dispatchers.IO) { write(queue) }
  }

  /** For `onDestroy`, which must finish the write before the process may be torn down. */
  fun saveBlocking(queue: SavedQueue) = synchronized(file) { write(queue) }

  override suspend fun clear() {
    lock.withLock { withContext(Dispatchers.IO) { synchronized(file) { file.delete() } } }
  }

  private fun write(queue: SavedQueue) = synchronized(file) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(SavedQueueCodec.encode(queue))
    if (!temporary.renameTo(file)) {
      file.delete()
      temporary.renameTo(file)
    }
  }

  companion object {
    const val FILE_NAME = "play_queue.json"
  }
}
