package app.muplay.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Keeps the player's audio attributes matching what is currently playing.
 *
 * A queue can hold both music and an audiobook chapter — a user can queue a chapter after a song,
 * and Plan 5's car surface can too — so the attributes cannot be decided once when the player is
 * built. This listener re-applies them at every item transition.
 *
 * `handleAudioFocus = true` on every call, which is what makes Media3 request focus, duck and pause
 * on its own. Re-applying attributes while playing can cause the underlying `AudioTrack` to be
 * recreated, which is audible as a brief gap — accepted knowingly, because it happens only at a
 * boundary between a song and a book, which is already a hard cut.
 *
 * Note the flag is passed on *every* call and never `false`: `setAudioAttributes(attrs, false)`
 * makes Media3 **abandon** focus handling, so a switcher that dropped the flag would silently
 * disable audio focus at the first track change rather than at construction — the harder half of
 * the same defect to see.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn`: `ExoPlayer` is `@UnstableApi`, which the Kotlin
// compiler cannot see at all (Media3's marker is an `androidx.annotation.RequiresOptIn`), so
// without this the file compiles clean and fails `lintDebug` with `UnsafeOptInUsageError`.
@OptIn(UnstableApi::class)
class ContentTypeSwitcher(private val player: ExoPlayer) : Player.Listener {

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    // Null when the queue is cleared. Nothing is playing, so there is nothing to choose attributes
    // for, and the ones already on the player are the right ones for whatever plays next until the
    // transition into it says otherwise.
    //
    // Two decisions, two branches, all four arms driven -- rather than the three this started as.
    // `mediaItem?.mediaMetadata?.mediaType ?: return` measured 5/6 BRANCH: `mediaMetadata` is a
    // *platform* type (a plain Java field), so the middle safe call emitted a null check whose
    // other arm nothing can reach, and the missed branch was a compiler artefact rather than an
    // untested case. Written as a receiver it emits no check at all.
    if (mediaItem == null) return
    // `MediaMetadata.mediaType` is a nullable `Integer`, and genuinely null for an item built
    // without metadata (`MediaItem.fromUri`, and every item in this module's own device suites bar
    // the ones `MediaItems` builds). Leaving the attributes alone is right for it: they belong to
    // whatever was playing, and the next item that *does* declare a type replaces them.
    val mediaType = mediaItem.mediaMetadata.mediaType ?: return
    player.setAudioAttributes(PlaybackAudioAttributes.of(mediaType), /* handleAudioFocus = */ true)
  }
}
