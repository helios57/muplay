package app.muplay.media

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import app.muplay.cast.didl.ServedMedia
import app.muplay.model.Song
import app.muplay.model.StreamFormat

/**
 * Turns one mirrored [Song] into the `MediaItem` Media3 plays.
 *
 * Three of the values here are load-bearing well beyond this file:
 *
 * - **`mediaId = song.id`.** `media_progress` is keyed on the server's stable media id, and
 *   `MuPlayer` (Task 8) looks a row up by exactly this value. A constant here would make every
 *   audiobook share one position.
 * - **`customCacheKey = song.id`.** Spec section 4: the cache key must derive from the track id
 *   alone. This client's stream URLs carry a fresh auth salt per call, so Media3's default
 *   URL-derived key produces a cache that is written and never read — the defect Tempo ships.
 * - **`mediaType`, from [isAudiobook] and from nothing else.** Navidrome hardcodes
 *   `child.Type = "music"` for every media file -- the seeded `Test Book.m4b` comes back as
 *   `"type": "music"` -- and the OpenSubsonic `mediaType` enum describes the object *kind*
 *   (`song|album|artist`), not the content, so the protocol cannot answer this question at all.
 *   The user's own `LibraryRole` assignment joined to `Song.libraryId` is the only mechanism there
 *   is (spec section 4), and [QueueRepository] is where that join happens. Do not "fix" this by
 *   inferring a book from a file suffix: `.m4b` is a container, an audiobook library holds plain
 *   `.mp3` chapters, and a music library holds `.m4b` DJ sets.
 *
 *   It is `MediaMetadata.mediaType` rather than a custom `extras` key for two reasons: it is the
 *   field that means this, and Plan 5's car and watch surfaces render from it. One field, no
 *   parallel truth. `PlaybackAudioAttributes` reads it back to decide speech vs music.
 * - **`durationMs = song.durationSeconds * 1000`.** The one value here that is *recoverable from
 *   nowhere else*, and the reason it has to be set is a chain this repository has already
 *   measured end to end. `StreamFormat.forSuffix` sends `opus`/`ogg`/`oga` as `format=mp3`, which
 *   is a **live** transcode; a live transcode answers `Accept-Ranges: none` with **no
 *   `Content-Length`** (see `StreamFormat.Raw`'s own note and `LiveNavidromeTest`), so ExoPlayer
 *   reports `duration == C.TIME_UNSET`; `LegacyConversions` (media3-session) then falls back to
 *   `MediaMetadata.durationMs` for the platform session, and if nothing put a value there the
 *   fallback is null. The visible result of leaving it unset is every Ogg/Opus track showing as
 *   unknown-length on the lock screen, in the notification, in Android Auto and on Wear, with a
 *   collapsed seek bar -- while the mirror knew the length the whole time. This is the last place
 *   that number is in scope.
 *
 * [artworkUri] is passed in rather than derived, because building it needs credentials and this
 * function is pure. [QueueRepository] is where the two are joined.
 *
 * A note on artwork and the salt: like the stream URL, a cover-art URL carries a fresh salt, so
 * the same art gets a different URI in a later session. Media3's session bitmap loader caches by
 * URI, so that costs **one artwork fetch per session per item** and never a wrong image. Within a
 * queue the URI is fixed, because it is built once here.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn`, and on the object rather than propagated as an
// `@UnstableApi` of our own -- the same argument `NavidromeLoadErrorHandlingPolicy` records, for
// the same reason. `MediaItem.Builder.setCustomCacheKey` is the one `@UnstableApi` member touched
// here, and the Kotlin compiler cannot see that at all (Media3's marker is an
// `androidx.annotation.RequiresOptIn`, not a `kotlin.` one): this file compiled clean and failed
// `lintDebug` one task later, with `UnsafeOptInUsageError` naming exactly that call.
//
// The API being opted into is not incidental. `setCustomCacheKey` is what makes spec section 4's
// track-id cache key reachable at all; without it Media3 derives the key from the URI, which for
// this client carries a fresh auth salt per call and yields a cache that is written and never
// read. If a future Media3 removes it, the replacement has to preserve that property -- the
// question is never "how do we compile again", it is "where does the track id go now".
@OptIn(UnstableApi::class)
object MediaItems {

  /**
   * The decibel adjustment the file's own tags asked for, already reduced by
   * [ReplayGainPolicy.gainDbFor] to the one number the gain stage needs.
   *
   * **The key is absent when the file said nothing**, rather than carried at a sentinel value. A
   * `-100f` sentinel is a number [ReplayGainPolicy.linearGain] would happily clamp and apply, so
   * the difference between "no tag" and "a very quiet tag" has to live in the presence of the key
   * and nowhere else. `ReplayGainController` and `ProgressWriter` both read it with a
   * `containsKey` guard for that reason.
   *
   * An `extras` key rather than a `MediaMetadata` field because `MediaMetadata` has none for this,
   * and the value has to ride on the item: the current item changes for reasons no caller
   * announces -- an automatic transition, a `seekToNext`, a media button on a headset -- so a
   * gain held anywhere else would be a second thing to keep in step with the queue.
   */
  const val KEY_REPLAY_GAIN_DB = "app.muplay.replayGainDb"

  /**
   * The file's peak as a fraction of full scale, so a *positive* gain can be clamped short of
   * clipping. Absent, like [KEY_REPLAY_GAIN_DB], when the file did not say.
   */
  const val KEY_REPLAY_GAIN_PEAK = "app.muplay.replayGainPeak"

  /**
   * The `format` wire value the item's URI was built with -- `"raw"` or `"mp3"`.
   *
   * Stamped so that **how this item may be seeked** is answerable from the item alone. A raw stream
   * seeks with a byte `Range`; a transcode has no `Content-Length` to range over and has to be
   * re-issued with `timeOffset` (spec section 4, and `TranscodeSeek`). Nothing else on a `MediaItem`
   * can tell them apart: the MIME type says `audio/mpeg` for a transcoded Opus *and* for a plain
   * mp3 streamed raw, which is exactly the pair that must not be confused.
   *
   * A `String`, not a [StreamFormat], because a `Bundle` cannot hold a sealed interface -- which is
   * why `StreamFormat.Mp3.WIRE_VALUE` exists as a constant rather than as a literal in two files.
   *
   * **Absent** on an item this app did not build. `TranscodeSeek` treats that as "seek in place",
   * which is what every player did before this key existed.
   */
  const val KEY_STREAM_FORMAT = "app.muplay.streamFormat"

  /**
   * The bitrate cap on the item's URI, present only when [KEY_STREAM_FORMAT] is `"mp3"`.
   *
   * Carried rather than re-derived, for the reason `format` is a parameter of [of] rather than
   * something recomputed from `song.suffix`: re-issuing the URI at an offset has to ask for the
   * *same* transcode, and a second decision about the bitrate is free to drift from the first the
   * moment either rule changes. It is also part of Navidrome's transcoding-cache key, so a
   * re-issue at a different cap is a different entry and a different encode mid-track.
   */
  const val KEY_STREAM_MAX_BITRATE_KBPS = "app.muplay.streamMaxBitRateKbps"

  /**
   * How far into the real track this item's stream begins, in milliseconds. Absent, and therefore
   * zero, on every item that was not re-issued at an offset.
   *
   * **On the item rather than in a field on `MuPlayer`**, which is a deliberate difference from the
   * design this task started with. A player-held base has to be reset on every item transition and
   * by every `setMediaItem(s)` overload, and a base that is one transition stale reports a position
   * from the previous track -- the same "one more place to keep in step with the queue" this
   * object's ReplayGain keys are written to avoid. Read off the current item there is nothing to
   * reset and nothing to forget.
   */
  const val KEY_TIME_OFFSET_MS = "app.muplay.timeOffsetMs"

  /**
   * @param isAudiobook whether the user tagged this song's library **Audiobooks** in setup. Not
   *   inferable from anything the server sends -- see this object's own note above for why the
   *   library id plus the user's own `LibraryRole` is the only mechanism there is.
   * @param format the [StreamFormat] [streamUri] was built with. Needed because the **served**
   *   MIME type is not the source file's: a forced transcode (spec section 4's "Never Opus")
   *   delivers MP3 whatever the source was, and Sonos infers the MIME type from the URL's
   *   extension rather than from `Content-Type`. See `ServedMedia`.
   *
   *   Taken as a parameter rather than recomputed from `song.suffix` here, and that is the point of
   *   the parameter existing: [QueueRepository] already calls `StreamFormat.forSuffix` to build
   *   [streamUri], so recomputing it would be a second decision about one fact, free to drift from
   *   the URL the moment either rule changed. One call, two consumers.
   *
   * The two are independent and both are required, which a merge of two lanes established the
   * hard way: [isAudiobook] decides `mediaType` (speech vs music, and Plan 5's surfaces), [format]
   * decides `mimeType` (what the bytes on the wire actually are). Neither is derivable from the
   * other -- a book is served as MP3 as readily as a song is.
   */
  fun of(
    song: Song,
    streamUri: String,
    artworkUri: String?,
    isAudiobook: Boolean,
    format: StreamFormat,
  ): MediaItem =
    MediaItem.Builder()
      .setMediaId(song.id)
      .setUri(streamUri)
      .setCustomCacheKey(song.id)
      // The one statement of what these bytes are, read by the local extractor as a hint and by
      // the cast layer as the truth it tells a renderer. See `ServedMedia`'s KDoc for why three
      // parties must agree and why they all read this one value, and `MimeAgreement` for the check
      // that makes their agreement observable rather than merely intended.
      .setMimeType(ServedMedia.of(song.suffix, format).mimeType)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(song.title)
          .setArtist(song.artistName)
          .setAlbumTitle(song.albumName)
          .setTrackNumber(song.trackNumber)
          .setDiscNumber(song.discNumber)
          // Unconditional, and `Song.durationSeconds` is a non-null `Int`, so there is no branch
          // here. Worth writing down because there is one input that makes it a lie: `Child.duration`
          // carries a kotlinx-serialization default of `0` for a field the Subsonic schema marks
          // required, so a server that omitted it would be reported as a 0 ms track rather than as
          // an unknown-length one. Left unguarded deliberately -- the two are indistinguishable in
          // the only formula that consumes this value (`durationMs.coerceAtLeast(1L)`), and a
          // `takeIf { it > 0 }` would add a BRANCH counter to a mapping whose two branches are both
          // the cover-art decision, which is a fact `coverageFloors[":core:media"]` states out loud.
          .setDurationMs(song.durationSeconds * 1000L)
          .setArtworkUri(artworkUri?.toUri())
          .setIsPlayable(true)
          // Android Auto (Plan 5) renders its browse tree from these flags; an item marked
          // browsable becomes a folder that opens onto nothing.
          .setIsBrowsable(false)
          .setMediaType(
            if (isAudiobook) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
            else MediaMetadata.MEDIA_TYPE_MUSIC,
          )
          // Spec section 4's "the client applies it", carried to the one place that can. Read off
          // `song.replayGain` and so **not** a sixth parameter: unlike `isAudiobook` and `format`,
          // which no `Song` can answer, this one is already on the song. One authority, no second
          // place for it to drift to.
          .setExtras(extrasFor(song, format))
          .build(),
      )
      .build()

  /**
   * The two extras, or an **empty** `Bundle` for an untagged file.
   *
   * Empty rather than null so that `MediaMetadata.extras` is always present and the two readers
   * ask one question (`containsKey`) rather than two. The policy decision -- track gain preferred,
   * album gain as the fallback -- is made here, once, so that everything downstream of the queue
   * handles one number instead of re-deriving the choice per consumer.
   */
  private fun extrasFor(song: Song, format: StreamFormat): Bundle = Bundle().apply {
    ReplayGainPolicy.gainDbFor(song.replayGain)?.let { putFloat(KEY_REPLAY_GAIN_DB, it) }
    song.replayGain?.peakAmplitude?.let { putFloat(KEY_REPLAY_GAIN_PEAK, it) }
    putString(KEY_STREAM_FORMAT, format.wireValue)
    if (format is StreamFormat.Mp3) putInt(KEY_STREAM_MAX_BITRATE_KBPS, format.maxBitRateKbps)
  }

  /**
   * The [StreamFormat] [item]'s URI was built with, or `null` for an item this object did not build.
   *
   * The inverse of the two stamps above, and the only reader of them: reconstructing the format in
   * two places is how the re-issued URI and the original stop agreeing. A `"mp3"` stamp with no
   * bitrate beside it is not a format anyone can ask a server for, so it reads as `null` rather than
   * as a guess -- an item in that state was not built here.
   */
  fun streamFormatOf(item: MediaItem): StreamFormat? {
    val extras = item.mediaMetadata.extras ?: return null
    return when (extras.getString(KEY_STREAM_FORMAT)) {
      StreamFormat.Raw.wireValue -> StreamFormat.Raw
      StreamFormat.Mp3.WIRE_VALUE ->
        extras.takeIf { it.containsKey(KEY_STREAM_MAX_BITRATE_KBPS) }
          ?.let { StreamFormat.Mp3(it.getInt(KEY_STREAM_MAX_BITRATE_KBPS)) }
      else -> null
    }
  }

  /**
   * How far into the real track [item]'s stream begins, in milliseconds -- `0` unless it was
   * re-issued at an offset. See [KEY_TIME_OFFSET_MS].
   */
  fun timeOffsetMsOf(item: MediaItem): Long =
    item.mediaMetadata.extras?.getLong(KEY_TIME_OFFSET_MS, 0L) ?: 0L
}
