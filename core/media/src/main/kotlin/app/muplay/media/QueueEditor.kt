package app.muplay.media

import app.muplay.model.Song
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that changes the queue without replacing it.
 *
 * [PlaybackLauncher] is the other half and the difference is the whole design: `play` **replaces**
 * the timeline, which is what a user means by tapping a track, and every method here **edits** the
 * one that is already there, which is what a user means by "add to queue". Two classes rather than
 * flags on one, because the two have nothing in common past the controller they use -- `play`
 * negotiates transcode seeking and chooses a start index, and none of that has any meaning for an
 * item appended to the end of something already playing.
 *
 * ### Nothing here chooses a playback position
 *
 * Spec section 3 gives that permission to `ResumePolicy` alone, and this class is exactly where it
 * would be easy to take it back. [jumpTo] therefore calls `seekToDefaultPosition(index)` and not
 * `seekTo(index, 0L)`: the two are the same for most items and only one of them is a *statement*
 * about a position. Tapping a queue row names an item; where playback begins inside it is still not
 * this layer's answer.
 *
 * ### Every index is checked against the controller, not against what the screen drew
 *
 * See [QueueSnapshot]'s header. The screen enables a control from the snapshot it rendered; by the
 * time the tap arrives here a track can have ended or a car can have skipped, and Media3 answers an
 * out-of-range index with `IllegalSeekPositionException` rather than by ignoring it. So the guards
 * below read `mediaItemCount` off the live player and a tap that lost its race does nothing.
 */
@Singleton
class QueueEditor @Inject constructor(
  private val playbackConnection: PlaybackConnection,
  private val queueRepository: QueueRepository,
) {

  /** Appends [songs] to the end of whatever is queued. */
  suspend fun enqueue(songs: List<Song>) {
    val items = mediaItemsFor(songs) ?: return
    playbackConnection.onController { controller ->
      val wasEmpty = controller.mediaItemCount == 0
      controller.addMediaItems(items)
      if (wasEmpty) controller.prepare()
    }
  }

  /** Inserts [songs] directly after whatever is playing. */
  suspend fun playNext(songs: List<Song>) {
    val items = mediaItemsFor(songs) ?: return
    playbackConnection.onController { controller ->
      val wasEmpty = controller.mediaItemCount == 0
      controller.addMediaItems(
        playNextIndexIn(controller.currentMediaItemIndex, controller.mediaItemCount),
        items,
      )
      if (wasEmpty) controller.prepare()
    }
  }

  suspend fun remove(index: Int) {
    playbackConnection.onController { controller ->
      if (canRemoveFrom(index, controller.mediaItemCount)) controller.removeMediaItem(index)
    }
  }

  suspend fun move(from: Int, to: Int) {
    playbackConnection.onController { controller ->
      if (canMoveWithin(from, to, controller.mediaItemCount)) controller.moveMediaItem(from, to)
    }
  }

  /** "Play this one now." */
  suspend fun jumpTo(index: Int) {
    playbackConnection.onController { controller ->
      if (!canRemoveFrom(index, controller.mediaItemCount)) return@onController
      controller.seekToDefaultPosition(index)
      controller.play()
    }
  }

  /**
   * `null` for an empty list, which is ordinary rather than a programming error: "add this album to
   * the queue" against songs the mirror has not delivered yet is a race a user can lose, and
   * `PlaybackQueue`'s own `require` would turn it into a crash. The same decision `launchQueue`
   * makes for the same reason.
   *
   * `startIndex = 0` is inert here. `QueueRepository.mediaItems` returns the whole queue whatever
   * the index says -- its own header explains why -- and nothing in this class passes an index to
   * the player.
   */
  private suspend fun mediaItemsFor(songs: List<Song>) =
    if (songs.isEmpty()) null else queueRepository.mediaItems(PlaybackQueue.of(songs, 0))
}
