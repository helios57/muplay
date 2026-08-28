package app.muplay.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.muplay.model.BookSettings

/**
 * What the player should be doing for the item that is playing right now.
 *
 * A separate type from the controller that applies it, and the split is where the gating happens:
 * *applying* is `ExoPlayer` plumbing and needs an emulator, *deciding* is where the bug is, and a
 * decision that costs an emulator boot per mutation does not get mutated.
 */
data class BookPlaybackSettings(val speed: Float, val skipSilence: Boolean) {
  companion object {
    /**
     * What anything that is not a book plays like.
     *
     * This constant is the fix for the bug that names this task: playback parameters live on the
     * **player**, so without an explicit reset a song after a book plays at the book's speed and
     * nothing anywhere reports it.
     */
    val MUSIC = BookPlaybackSettings(BookSettings.DEFAULT_SPEED, skipSilence = false)

    /**
     * [item]'s settings, or [MUSIC] for anything that is not an audiobook file.
     *
     * Clamped on the way out even though `AudiobookRepository` clamps on the way in, and the second
     * clamp is not belt-and-braces: `ExoPlayer.setPlaybackSpeed(NaN)` throws
     * `IllegalArgumentException` from inside a listener callback, which surfaces as playback dying
     * with no message any listener could act on. A `NaN` reaches an [AudiobookItem] from a
     * corrupted `REAL` column, or from arithmetic on one, without passing a setter.
     */
    fun of(item: AudiobookItem?): BookPlaybackSettings = when (item) {
      null -> MUSIC
      else -> BookPlaybackSettings(BookSettings.clampSpeed(item.speed), item.skipSilence)
    }
  }
}

/**
 * Keeps the player's speed and silence skipping matched to whatever is playing, and persists a
 * speed the listener changed.
 *
 * ### Applied on every transition
 *
 * `PlaybackParameters` are a property of the **player**, not of the item. Set a book to 1.4x, skip
 * to a song, and the song plays at 1.4x; play a song and then a book, and the book has quietly lost
 * the speed its listener chose. Neither reports anything -- no error, no log, no state anywhere
 * that says why. So the settings are re-applied at every item transition, from the item's own book:
 * the book's speed for a book file, [BookPlaybackSettings.MUSIC] for everything else.
 *
 * ### Persisted from the player, not from the button
 *
 * The speed control reaches the player through a `MediaController` -- and later through a car and a
 * watch -- so [onPlaybackParametersChanged] is the only place that sees *every* change. A
 * `setSpeed` wired to a button would miss the other three surfaces.
 *
 * ### The write-back guard, and why it is an equality rather than a flag
 *
 * [applyFor] changes the playback parameters itself, which fires the same callback. Without a
 * guard, transitioning into book B writes B's speed against whatever book the player reports as
 * current at that instant.
 *
 * The obvious guard is a re-entrancy flag set around the programmatic apply. **It does not work,
 * and that was measured rather than reasoned about** -- see
 * `BookSpeedControllerTest.aTransitionBetweenTwoBooksDoesNotWriteEitherOnesSpeedOntoTheOther` for
 * the run. Media3 dispatches listener callbacks through a `ListenerSet`, and a `setPlaybackSpeed`
 * called from *inside* another callback finds a flush already in progress: the parameters-changed
 * event is appended to the flush queue and delivered by the **outer** loop, after [applyFor]'s
 * `finally` has already cleared the flag. The flag is therefore never held when the callback it
 * exists for arrives.
 *
 * What does work is comparing the incoming speed with the one the current item's book already has:
 * a programmatic apply is by construction equal to it, and a listener's change is by definition
 * not. It needs no ordering assumption at all, which is what makes it correct on a callback whose
 * delivery point is Media3's business rather than this class's.
 *
 * ### Why it takes the raw `ExoPlayer`
 *
 * `setSkipSilenceEnabled` is on `ExoPlayer` and not on `Player`, so the `MuPlayer` seam cannot
 * reach it. `MuPlayerFactory.wrap` exists so `MuPlaybackService` can hold both halves -- the seam
 * for the session, the raw player for this.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn` -- see `MuPlayerFactory` for the full argument.
// `ExoPlayer` and `ExoPlayer.setSkipSilenceEnabled` are `@UnstableApi`, which the Kotlin compiler
// cannot see at all; without this the file compiles clean and `check` fails much later at
// `lintDebug` with `UnsafeOptInUsageError`.
@OptIn(UnstableApi::class)
class BookSpeedController(
  private val player: ExoPlayer,
  private val source: AudiobookItemSource,
  private val persist: (bookId: String, speed: Float) -> Unit,
) : Player.Listener {

  fun start() = player.addListener(this)

  fun stop() = player.removeListener(this)

  /**
   * `mediaItem` is null when the queue is cleared, and [applyFor] handles that as "not a book" --
   * which is the right answer: nothing is playing, and the next thing to play gets its own
   * transition. It is one branch rather than two because `MediaItem.mediaId` is non-null in Media3,
   * so a `mediaItem?.mediaId ?: return` would emit a second null check nothing can ever take. This
   * module deletes uncoverable branches rather than excusing them; see `ContentTypeSwitcher`.
   */
  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
    applyFor(mediaItem?.mediaId)

  /**
   * A speed the listener chose, written against the book they chose it for.
   *
   * See the class documentation for why the guard is an equality against the stored speed and not a
   * re-entrancy flag. Both early returns are real states rather than defensive noise: a queue with
   * nothing in it reports no current item, and a music item has no book to write against at all --
   * which is what makes "only books remember a speed" structural.
   */
  override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
    val mediaId = player.currentMediaItem?.mediaId ?: return
    val item = source.itemFor(mediaId) ?: return
    val speed = BookSettings.clampSpeed(playbackParameters.speed)
    // The programmatic apply, arriving back as a callback. It is exactly the value this class just
    // read out of the book, so writing it would be a no-op at best -- and at worst, on a
    // book-to-book transition, a write of the new book's speed onto the old one's row.
    if (speed == BookSettings.clampSpeed(item.speed)) return
    persist(item.bookId, speed)
  }

  /**
   * Point the player at [mediaId]'s book, or back at normal playback for anything else.
   *
   * Public because the first item of a queue set *before* this listener was attached fires no
   * transition, so a caller that builds a queue and then a controller has to say so. In production
   * `MuPlaybackService` attaches in `onCreate`, before any queue exists, and the transition does
   * fire -- forgetting this call there would make the first book of every session play at 1.0x and
   * every book after it correct, which is the hardest kind of defect to see: it works the second
   * time.
   */
  fun applyFor(mediaId: String?) {
    val settings = BookPlaybackSettings.of(mediaId?.let(source::itemFor))
    player.setPlaybackSpeed(settings.speed)
    player.skipSilenceEnabled = settings.skipSilence
  }
}
