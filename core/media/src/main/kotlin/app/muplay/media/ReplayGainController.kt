package app.muplay.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Points [GainAudioProcessor] at whatever the current item's tags asked for.
 *
 * A `Player.Listener` rather than something the queue builder calls, because the current item
 * changes for reasons no caller announces -- an automatic transition, a `seekToNext`, a media
 * button on a headset, a `MediaController` in a car. Any of those with no listener behind it would
 * leave the previous track's gain applied to the next one.
 *
 * Reads the **item's own** `mediaMetadata`, never `player.mediaMetadata`: the latter is Media3's
 * blend of the item's metadata with whatever the media source announced, and an ID3 frame in a
 * file has no business overwriting a decision the library mirror made.
 */
class ReplayGainController(private val processor: GainAudioProcessor) : Player.Listener {

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyTo(mediaItem)

  /**
   * Public because the transition callback is not the only entry point that matters: a player that
   * is handed a queue and asked to play reports the first item through `onMediaItemTransition`, but
   * a caller that wants the gain right *before* the first buffer is decoded can say so directly.
   * `GainAudioProcessorTest` uses it that way, and so the same code path serves both.
   */
  fun applyTo(mediaItem: MediaItem?) {
    // `let`, not a second safe call: `MediaItem.mediaMetadata` is non-null in Media3, so
    // `mediaItem?.mediaMetadata?.extras` emits an arm nothing can take -- measured 15/16 BRANCH
    // with exactly that one missing. A `mediaItem` of `null` is real, though: Media3 reports a
    // cleared queue that way, and `anItemThatCarriesNoTagsLeavesTheSamplesAlone` drives it.
    val extras: Bundle? = mediaItem?.let { it.mediaMetadata.extras }
    // `containsKey` before `getFloat`, both times, and not `getFloat(key, sentinel)`: the whole
    // point of `MediaItems`' absent-key encoding is that no float value means "no decision", so a
    // default here would invent one. See `MediaItems.KEY_REPLAY_GAIN_DB`.
    val gainDb = extras?.takeIf { it.containsKey(MediaItems.KEY_REPLAY_GAIN_DB) }
      ?.getFloat(MediaItems.KEY_REPLAY_GAIN_DB)
    val peak = extras?.takeIf { it.containsKey(MediaItems.KEY_REPLAY_GAIN_PEAK) }
      ?.getFloat(MediaItems.KEY_REPLAY_GAIN_PEAK)
    processor.setLinearGain(ReplayGainPolicy.linearGain(gainDb, peak))
  }
}
