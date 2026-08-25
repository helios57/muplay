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
    val mediaType = mediaItem?.mediaMetadata?.mediaType ?: return
    player.setAudioAttributes(PlaybackAudioAttributes.of(mediaType), /* handleAudioFocus = */ true)
  }
}
