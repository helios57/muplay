package app.muplay.model.browse

/**
 * What kind of thing a node is, in this app's own vocabulary.
 *
 * Deliberately **not** `androidx.media3.common.MediaMetadata.MEDIA_TYPE_*`. Those are `Int`s on an
 * Android class, and referencing them here would drag `:core:model` -- which spec section 9 defines
 * as *"pure Kotlin, no Android"* -- onto an Android runtime, taking every test in this file with it.
 * `BrowseItems` in `:core:media` maps this enum to Media3's constants, on a device, in one place.
 */
enum class BrowseMediaType {
  MIXED,
  ALBUM,
  ARTIST,
  TRACK,
  AUDIO_BOOK,
  AUDIO_BOOK_CHAPTER,
  FOLDER_ALBUMS,
  FOLDER_ARTISTS,
  FOLDER_MIXED,
}

/** How a node's **children** should be laid out by whatever is rendering them. */
enum class BrowseStyle { LIST, GRID }

/** How far through an item the listener is, as a car head unit understands it. */
enum class BrowseCompletionStatus { NOT_PLAYED, PARTIALLY_PLAYED, FULLY_PLAYED }

/**
 * A book's progress, as two facts rather than one.
 *
 * Android Auto reads both: the status decides whether a progress bar is drawn at all, and
 * [fraction] decides how far along it is. They are separable -- a book at 0.0 that has been started
 * is `PARTIALLY_PLAYED`, and drawing no bar for it would lose the only signal that it is in
 * progress.
 */
data class BrowseCompletion(
  val status: BrowseCompletionStatus,
  val fraction: Double,
)

/**
 * One row of the browse tree, with no Android type in it.
 *
 * [artworkId] is a **Navidrome `coverArt` id**, not a URL: turning it into a URL needs credentials
 * and a configured server, which live behind `SubsonicSourceProvider` in `:core:database`, and a
 * URL embedded here would be stale the moment the user's session changed. `BrowseItems` resolves it
 * at the last possible moment.
 *
 * That is a security decision as much as a freshness one, and it is not hypothetical: a review of
 * the playback queue measured `MediaMetadata.toBundle()` serialising `FIELD_ARTWORK_URI` across the
 * session IPC boundary, and MuPlay's artwork URLs carry `u`, `s=<salt>` and `t=md5(password+salt)`
 * -- a replayable, non-expiring credential. **No credential, token or stream URL belongs in a
 * `BrowseNode`, its [title], its [subtitle] or anything derived from them.**
 *
 * [childStyle] describes this node's **children**, not itself, which is how Android Auto's content
 * style hints are defined -- a browsable item carries the hint that applies inside it.
 */
data class BrowseNode(
  val id: BrowseId,
  val title: String,
  val subtitle: String? = null,
  val isBrowsable: Boolean,
  val isPlayable: Boolean,
  val mediaType: BrowseMediaType,
  val artworkId: String? = null,
  val childStyle: BrowseStyle = BrowseStyle.LIST,
  val completion: BrowseCompletion? = null,
  val durationMs: Long? = null,
)
