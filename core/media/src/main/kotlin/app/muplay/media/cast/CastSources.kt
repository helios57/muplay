package app.muplay.media.cast

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.session.CastSource

/**
 * One `MediaItem` read as the five facts a renderer needs.
 *
 * The boundary Task 8 stopped at: `:core:cast` is pure JVM and has never seen a Media3 type, so
 * *"extract a `MediaItem`"* had to live here. Everything below is a read off the item and nothing
 * is re-derived, which is the whole point -- `MediaItems.of` already decided the served MIME and
 * the media type from the `Song` and the `StreamFormat`, and a second decision here would be free
 * to drift from the URL the item is carrying.
 *
 * ### The upstream URL is credential-bearing and this object is the last place that is obvious
 *
 * `localConfiguration.uri` is a Subsonic `/rest/stream` URL complete with `u`, `t` and `s`. It goes
 * into [CastSource.upstreamUrl], whose `toString` redacts it, and from there into
 * `ProxyRegistry.publish`, which mints a token so the renderer never sees it. Nothing here logs,
 * prints or shortens it -- and a `require`/`check` message naming the item would print it, which is
 * why the failures below are `null`s and not exceptions.
 */
object CastSources {

  /**
   * The item, or `null` when it carries no playable URL at all.
   *
   * `null` rather than a throw because the caller is a handover: a queue with one unplayable item
   * in it must cast the rest, not fail. `MediaItem.localConfiguration` is null for an item built
   * from a media id alone, which is what a `MediaController` sends when it asks the session to play
   * something from the browse tree.
   */
  fun of(item: MediaItem): CastSource? {
    val configuration = item.localConfiguration ?: return null
    val metadata = item.mediaMetadata
    return CastSource(
      mediaId = item.mediaId,
      // A renderer displays this. `MediaMetadata.title` is a `CharSequence?`; the empty fallback is
      // what DIDL renders as `<dc:title></dc:title>`, which every renderer accepts, whereas a
      // missing element is a 402 on a strict one.
      title = metadata.title?.toString().orEmpty(),
      artist = metadata.artist?.toString(),
      albumTitle = metadata.albumTitle?.toString(),
      artworkUri = metadata.artworkUri?.toString(),
      // `C.TIME_UNSET` is `Long.MIN_VALUE + 1`. `CastItems.of` normalises a negative duration to
      // zero -- "length unknown, play it anyway" -- so this hands the sentinel straight through
      // rather than making a second decision about it here.
      durationMs = metadata.durationMs ?: C.TIME_UNSET,
      // `MediaMetadata.mediaType` and nothing else. `MediaItems.of` sets it from the user's own
      // `LibraryRole` assignment, which is the only mechanism there is -- Navidrome hardcodes
      // `child.Type = "music"` for every file and a suffix cannot tell a book from a DJ set.
      isAudiobook = metadata.mediaType == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
      upstreamUrl = configuration.uri.toString(),
      served = servedMedia(configuration.mimeType),
    )
  }

  /** Every item that can be cast, in queue order. */
  fun of(items: List<MediaItem>): List<CastSource> = items.mapNotNull(::of)

  /**
   * What the renderer will be told these bytes are.
   *
   * Read back off the item's own `mimeType`, which `MediaItems.of` set from
   * `ServedMedia.of(song.suffix, format)` -- so the three statements of the format that
   * `ServedMedia` exists to keep in agreement all still descend from that one call.
   *
   * The fallback is `ServedMedia`'s own, and it is reached for an item this app did not build:
   * a `MediaItem` from a browse-tree resolution carries no `mimeType` at all. MP3 is the guess most
   * likely to play, and a renderer that disagrees sniffs the bytes.
   */
  private fun servedMedia(mimeType: String?): ServedMedia =
    ServedMedia.forMimeType(mimeType)
      ?: ServedMedia(ServedMedia.FALLBACK_MIME, ServedMedia.FALLBACK_EXTENSION)
}
