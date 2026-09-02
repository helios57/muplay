package app.muplay.media

import app.muplay.database.SubsonicSourceProvider
import app.muplay.network.SubsonicSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **The one place a credential-bearing cover URL is built, and the only place it exists.**
 *
 * [ArtworkUri] keeps the credential off every `MediaItem`; this puts it back on, in this process,
 * for the three consumers that genuinely have to fetch the bytes:
 *
 * - [MuPlayBitmapLoader], which loads the bitmap the notification, the lock screen, Android Auto
 *   and Wear all render;
 * - [PlaybackConnection], whose `PlaybackState.artworkUri` is what this app's own Compose UI hands
 *   to Coil;
 * - `CastSources`, which needs the upstream URL for `ProxyRegistry.publishArtwork` -- the cast
 *   proxy fetches it here and gives the renderer a capability token instead.
 *
 * One object rather than three calls to `SubsonicSourceProvider`, because "which id, at what size"
 * is a decision and three copies of it drift. It is also the one seam a test can substitute to
 * observe that a caller asked for the id it was given rather than a hardcoded one.
 *
 * ### Why there are two accessors
 *
 * [urlFor] is the honest one and every caller that can suspend uses it. [cachedUrlFor] exists for
 * exactly one caller: [PlaybackConnection.publish] runs on a `Player.Listener` callback and on a
 * 4 Hz ticker, neither of which can suspend, and a `runBlocking` on either would block the main
 * thread on a DataStore read. It answers `null` until [warm] has run, which costs at most one
 * ticker frame of placeholder artwork and never a wrong image.
 *
 * The cached source is **not** a cache of URLs. `coverArtUrl` mints a fresh salt per call by
 * design (see `SubsonicClient.coverArtUrl`), and caching its output would defeat that; what is
 * held is the `SubsonicSource`, i.e. the credentials, which is the part that costs a DataStore
 * read.
 */
@Singleton
class ArtworkUrls @Inject constructor(private val sourceProvider: SubsonicSourceProvider) {

  /**
   * The credentials, once they have been read.
   *
   * `@Volatile` and never invalidated on failure: a signed-out app has no source and [urlFor]
   * simply answers `null`, and a *changed* server produces a new source at the next [urlFor],
   * which overwrites this. Holding a stale one across a sign-in to another server would produce
   * artwork requests the new server rejects, which is why the write happens on every successful
   * resolve rather than only on the first.
   */
  @Volatile
  private var cached: SubsonicSource? = null

  /**
   * The URL that really fetches the cover [artworkUri] names, or `null`.
   *
   * `null` for a URI that is not one of ours, for an item with no artwork, and for an app that is
   * not signed in. Never throws: this is decoration, and a book that will not play because its
   * cover could not be addressed would be a far worse defect than a missing picture.
   */
  suspend fun urlFor(artworkUri: String?, sizePx: Int = QueueRepository.ARTWORK_SIZE_PX): String? {
    val coverArtId = ArtworkUri.coverArtIdOf(artworkUri) ?: return null
    // `Dispatchers.IO`, because `current()` reads the credential store off disk and two of the three
    // callers reach this from a main-confined scope. Cheap to be right about; a jank nobody
    // attributes to artwork otherwise.
    val source = source() ?: return null
    return runCatching { source.coverArtUrl(coverArtId, sizePx) }.getOrNull()
  }

  /**
   * [urlFor] without suspending, answering `null` until [warm] has succeeded once.
   *
   * See this class's own note for the one caller and why it cannot suspend.
   */
  fun cachedUrlFor(artworkUri: String?, sizePx: Int = QueueRepository.ARTWORK_SIZE_PX): String? {
    val coverArtId = ArtworkUri.coverArtIdOf(artworkUri) ?: return null
    val source = cached ?: return null
    return runCatching { source.coverArtUrl(coverArtId, sizePx) }.getOrNull()
  }

  /** Reads the credentials so [cachedUrlFor] can answer. Safe to call repeatedly. */
  suspend fun warm() {
    source()
  }

  /**
   * The current credentials, cached for [cachedUrlFor], or `null` when the app is not signed in.
   *
   * `runCatching`, because "not configured yet" is an ordinary state of this app and not an error
   * anything here can act on -- `SubsonicSourceProvider.current()` throws `NotConfiguredException`
   * before setup has run, and a cover that cannot be addressed must never take a screen down.
   */
  private suspend fun source(): SubsonicSource? =
    withContext(Dispatchers.IO) { runCatching { sourceProvider.current() }.getOrNull() }
      ?.also { cached = it }
}
