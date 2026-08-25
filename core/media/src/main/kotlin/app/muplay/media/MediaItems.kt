package app.muplay.media

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
          .build(),
      )
      .build()
}
