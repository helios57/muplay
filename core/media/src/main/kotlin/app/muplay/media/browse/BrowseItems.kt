package app.muplay.media.browse

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.muplay.model.browse.BrowseExtras
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseMediaType
import app.muplay.model.browse.BrowseNode
import app.muplay.model.browse.BrowseSurface

/**
 * The one place a [BrowseNode] becomes a Media3 `MediaItem`.
 *
 * Everything *interesting* was decided before this file: which nodes, in which order, with which
 * titles and which extras. What is left here is a translation, and it is on a device only because
 * `MediaItem` cannot exist off one -- it reaches `android.net.Uri` and `android.os.Bundle`, both
 * unimplemented stubs in the JVM's `android.jar`, and spec sections 2 and 10 ban Robolectric.
 *
 * **No stream `Uri` is set on the item itself.** A browse item is an *identity*, not a stream: when
 * a controller asks to play one, Task 5's `onAddMediaItems` resolves it into the real,
 * authenticated `format=raw` items Plan 3 builds. Putting a stream URL on a browse item would put
 * an authenticated, non-expiring Subsonic credential into Android Auto's persisted recents, where
 * it would outlive the session it was minted for.
 */
object BrowseItems {

  fun of(node: BrowseNode, artworkUri: String?): MediaItem {
    val metadata = MediaMetadata.Builder()
      .setTitle(node.title)
      .setSubtitle(node.subtitle)
      .setIsBrowsable(node.isBrowsable)
      .setIsPlayable(node.isPlayable)
      .setMediaType(mediaTypeOf(node.mediaType))
      // `takeIf` rather than a bare `?.let(Uri::parse)`: `Uri.parse("")` is a *valid, empty* Uri,
      // and an image loader renders it as a broken image instead of falling back to a placeholder.
      .setArtworkUri(artworkUri?.takeIf(String::isNotBlank)?.let(Uri::parse))
      .setDurationMs(node.durationMs)
      .setExtras(bundleOf(BrowseExtras.forNode(node)))
      .build()

    return MediaItem.Builder()
      .setMediaId(node.id.encode())
      .setMediaMetadata(metadata)
      .build()
  }

  /** The tree's root, whose extras are the host's defaults for everything below it. */
  fun root(surface: BrowseSurface): MediaItem {
    val metadata = MediaMetadata.Builder()
      .setTitle(ROOT_TITLE)
      .setIsBrowsable(true)
      .setIsPlayable(false)
      .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
      .setExtras(bundleOf(BrowseExtras.forRoot(surface)))
      .build()

    return MediaItem.Builder()
      .setMediaId(BrowseId.Root.encode())
      .setMediaMetadata(metadata)
      .build()
  }

  /**
   * This app's vocabulary, mapped to Media3's.
   *
   * An exhaustive `when` with no `else`: a new [BrowseMediaType] must fail to compile here rather
   * than fall into a default that shows a book as a song.
   */
  fun mediaTypeOf(type: BrowseMediaType): Int = when (type) {
    BrowseMediaType.MIXED -> MediaMetadata.MEDIA_TYPE_MIXED
    BrowseMediaType.ALBUM -> MediaMetadata.MEDIA_TYPE_ALBUM
    BrowseMediaType.ARTIST -> MediaMetadata.MEDIA_TYPE_ARTIST
    BrowseMediaType.TRACK -> MediaMetadata.MEDIA_TYPE_MUSIC
    BrowseMediaType.AUDIO_BOOK -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
    BrowseMediaType.AUDIO_BOOK_CHAPTER -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
    BrowseMediaType.FOLDER_ALBUMS -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
    BrowseMediaType.FOLDER_ARTISTS -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
    BrowseMediaType.FOLDER_MIXED -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
  }

  /**
   * A `Bundle` from the plain map `BrowseExtras` produced.
   *
   * `Int`, `Double` and `Boolean` are the only value kinds those maps contain, and an unexpected
   * one throws rather than being silently dropped -- a missing extra in a car is invisible, and
   * "the progress pip stopped appearing" is not a bug anyone traces back to a `Bundle` put.
   */
  fun bundleOf(values: Map<String, Any>): Bundle = Bundle(values.size).apply {
    values.forEach { (key, value) ->
      when (value) {
        is Int -> putInt(key, value)
        is Double -> putDouble(key, value)
        is Boolean -> putBoolean(key, value)
        else -> error("unsupported extra $key of ${value::class.java.name}")
      }
    }
  }

  /**
   * What the root item is called.
   *
   * The app's name, not a resource lookup: `BrowseItems` takes no `Context`, and the title a host
   * shows above the tree is this app's own name in every language. `BrowseText`'s header records
   * the same ruling for the tree's other strings.
   */
  const val ROOT_TITLE: String = "MuPlay"
}
