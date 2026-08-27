package app.muplay.media

import app.muplay.model.StreamFormat

/** How a seek on the current item has to be performed. */
sealed interface SeekMethod {

  /** Byte ranges work. Delegate to the wrapped player and change nothing. */
  data object InPlace : SeekMethod

  /** Re-issue the item's URI with `timeOffset`, and report positions relative to it. */
  data class ReissueWithOffset(val timeOffsetSeconds: Int) : SeekMethod

  /** A transcode on a server with no `transcodeOffset`. The command is withdrawn, not swallowed. */
  data object NotOffered : SeekMethod
}

/**
 * Spec section 4: *"Transcoded seek uses `timeOffset` (the `transcodeOffset` extension), which
 * means re-issuing the URI, not `AVTransport::Seek`."*
 *
 * ### The three answers, and why "silently do nothing" is not one of them
 *
 * A raw stream seeks [SeekMethod.InPlace] because Navidrome honours `Range` on `format=raw` with a
 * byte-exact 206 -- proved live in Task 1 -- and re-issuing a URI for that would throw away a
 * working seek and buy a round trip and a rebuffer with it.
 *
 * A transcode has no such option: a live transcode answers `Accept-Ranges: none` with no
 * `Content-Length` at all, so there are no byte ranges to seek with and ExoPlayer's seek either does
 * nothing or resolves against a length it does not have. **Nothing throws.** The seek bar moves and
 * the audio does not, which is the silent-wrong-answer class this whole task exists to remove. So a
 * transcode is either [SeekMethod.ReissueWithOffset] -- the server starts the transcode later --
 * or, when the server does not advertise `transcodeOffset`, [SeekMethod.NotOffered].
 *
 * [SeekMethod.NotOffered] is what spec section 4's *"unsupported features are silent no-ops, not
 * errors"* looks like when it is applied honestly. A silent no-op **on a seek** is a silent wrong
 * answer; the honest reading of that rule is "do not raise an error at the user", and the strongest
 * form of it is *do not offer the command*. `MuPlayer` removes
 * `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` from its command set, Media3's own transport controls
 * disable the bar without this project writing a line of UI, and the user is told by the interface
 * rather than misled by it.
 *
 * ### Why it is here rather than inside `MuPlayer`
 *
 * Pure, and with no Android and no Media3 import, so Tier 1 sees every branch. The whole decision is
 * three questions, and a `when` inside a `ForwardingPlayer` on a device is the most expensive place
 * in this project to test one.
 */
object TranscodeSeek {

  /**
   * Which of the three [SeekMethod]s applies to an item served as [formatWireValue], on a server
   * that does or does not support the extension, for a seek to [targetPositionMs].
   *
   * [formatWireValue] is the value the item's URI was built with (`MediaItems.KEY_STREAM_FORMAT`),
   * as a `String` and not a [StreamFormat], because that is what a `Bundle` on a `MediaItem` can
   * hold. An item this app did not build -- a session the system restored, say -- carries no such
   * extra and arrives here as `""`; [SeekMethod.InPlace] is the conservative answer for it, because
   * it is what every player did before this task and it is right for the raw streams that are the
   * overwhelming majority.
   */
  fun methodFor(
    formatWireValue: String,
    serverSupportsTranscodeOffset: Boolean,
    targetPositionMs: Long,
  ): SeekMethod = when {
    formatWireValue != StreamFormat.Mp3.WIRE_VALUE -> SeekMethod.InPlace
    !serverSupportsTranscodeOffset -> SeekMethod.NotOffered
    else -> SeekMethod.ReissueWithOffset(offsetSecondsFor(targetPositionMs))
  }

  /**
   * [targetPositionMs] as a whole number of seconds, clamped at zero and **floored**.
   *
   * Flooring is not a rounding preference. The server starts the transcode at or before the second
   * asked for, so the listener never loses audio they asked to hear; rounding up would clip the
   * first word of a sentence, and there would be nothing to see.
   */
  private fun offsetSecondsFor(targetPositionMs: Long): Int =
    (targetPositionMs.coerceAtLeast(0L) / MILLIS_PER_SECOND).toInt()

  /**
   * The media-cache key for [mediaId] streamed from [timeOffsetSeconds] in.
   *
   * **Not decoration, and not the same key as the un-offset stream.** Spec section 4 says the cache
   * key derives from the track id alone, and `TrackIdCacheKeyFactory` enforces it -- but the id was
   * a complete identifier only while one track meant one stream of bytes. A re-issued transcode is a
   * *different* stream of the same track: it starts at second [timeOffsetSeconds] and its first byte
   * is not the track's first byte. Filed under the bare track id it would be written into the middle
   * of the full track's cache entry, and every later read of that track would be served audio from
   * the wrong place -- a silent wrong answer of exactly the kind the seek itself was fixed to
   * remove.
   *
   * Offset zero is the bare id, deliberately: `timeOffset=0` and no `timeOffset` are the same audio
   * (measured against the container -- 300369 bytes either way), so a seek back to the top hits the
   * entry the ordinary stream already filled instead of making a duplicate of it.
   */
  fun cacheKeyFor(mediaId: String, timeOffsetSeconds: Int): String =
    if (timeOffsetSeconds <= 0) mediaId else "$mediaId$OFFSET_KEY_SEPARATOR$timeOffsetSeconds"

  /**
   * Separates a track id from an offset in a cache key. `@` rather than `-` or `:` because Subsonic
   * ids are opaque and this one must not be a character an id can contain: Navidrome's are
   * base62-ish (`6EUaYPHUsmgDIhnjWB6YA2`, measured off the container), so a `-` would be ambiguous
   * against a server that used one.
   */
  private const val OFFSET_KEY_SEPARATOR = '@'

  private const val MILLIS_PER_SECOND = 1000L
}
