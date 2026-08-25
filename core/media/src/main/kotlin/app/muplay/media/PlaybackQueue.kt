package app.muplay.media

import app.muplay.model.Song

/**
 * What to play, and which item to start with. **Nothing else.**
 *
 * Spec section 3: *the queue is a list of pointers; progress is a property of the item.* This type
 * therefore carries no position, and `PlaybackQueueTest` asserts its declared fields to keep it
 * that way. A `positionMs` here would be the single global "now playing position" that the next
 * thing played overwrites — which is the specific defect that makes every other player lose an
 * audiobook's place after a music session.
 *
 * [startIndex] is not a position. It names an item, which is queue membership; where playback
 * begins *within* that item is `media_progress`'s answer and `MuPlayer`'s to apply (Task 8).
 *
 * Constructed through [of], which validates. The constructor is not private — a `data class` with
 * a private constructor loses `copy` — but the `init` block runs either way, so there is no path
 * to an invalid queue.
 */
data class PlaybackQueue(val songs: List<Song>, val startIndex: Int) {

  init {
    require(songs.isNotEmpty()) { "a playback queue cannot be empty" }
    require(startIndex in songs.indices) {
      "startIndex $startIndex is outside a queue of ${songs.size}"
    }
  }

  val size: Int get() = songs.size

  fun songAt(index: Int): Song = songs[index]

  companion object {
    fun of(songs: List<Song>, startIndex: Int = 0): PlaybackQueue = PlaybackQueue(songs, startIndex)
  }
}
