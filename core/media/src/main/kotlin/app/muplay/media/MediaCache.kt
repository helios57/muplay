package app.muplay.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * The one read-through media cache in the process.
 *
 * **Exactly one**, and that is a correctness requirement rather than a performance choice:
 * `SimpleCache` throws if a second live instance is constructed for a directory another instance
 * already holds. `MediaModule` provides it `@Singleton` for that reason — the same situation, and
 * the same reasoning, as `DataModule`'s note about DataStore refusing a second instance for one
 * file.
 *
 * The directory lives under `context.cacheDir`, which the OS may reclaim under storage pressure.
 * That is the right trade for a cache of data the server still has: losing it costs a re-download
 * and never a wrong answer. Downloads that must survive are deferred by spec section 9 and would
 * need a different directory and a different service.
 *
 * A `CacheDataSource` can only cache a **bounded** resource, which is the second reason this
 * client asks Navidrome for `format=raw`. Measured against the real container: `format=raw`
 * always sends an accurate `Content-Length`, while a live transcode sends none and refuses
 * ranges — so a transcoded stream is not merely unseekable, it is uncacheable as a complete
 * resource. Anyone tempted to "save bandwidth" by defaulting to mp3 is turning this cache off.
 */
@OptIn(UnstableApi::class)
object MediaCache {

  /** Under `cacheDir`. Named, not derived, so an on-device inspection knows what it is looking at. */
  const val DIRECTORY_NAME: String = "media"

  /**
   * 512 MiB. Large enough to hold a long listening session and several audiobooks; small enough
   * that it is a fraction of any device this app targets. Eviction is least-recently-used, so the
   * book being listened to survives a shuffle session.
   */
  const val MAX_BYTES: Long = 512L * 1024L * 1024L

  /**
   * [directory] is a parameter with a default rather than a hardcoded path so an instrumented test
   * can hold its own cache without evicting, or being evicted by, another test's. Production
   * always takes the default — `MediaCacheModule` calls the one-argument form.
   *
   * [maxBytes] is a parameter for the same shape of reason, and it closes a hole that was real:
   * neither the evictor nor its bound could be observed at all. `SimpleCache` exposes no evictor
   * and `LeastRecentlyUsedCacheEvictor` has no `maxBytes` getter (both checked in 1.11.0), so the
   * only assertion that ever touched [MAX_BYTES] read the *constant's declaration*. Replacing
   * `LeastRecentlyUsedCacheEvictor(MAX_BYTES)` with `NoOpCacheEvictor()` — an unbounded cache that
   * fills the user's device — left all 43 tests in this module green, and so did multiplying the
   * bound by a hundred. Filling 512 MiB in a test is not an option; taking the bound as a
   * parameter means a test can fill a cache of a size it chooses, and the same line then carries
   * three facts at once: an evictor exists, it is least-recently-used, and its bound is the number
   * it was given.
   */
  fun create(
    context: Context,
    directory: File = File(context.cacheDir, DIRECTORY_NAME),
    maxBytes: Long = MAX_BYTES,
  ): Cache =
    SimpleCache(
      directory,
      LeastRecentlyUsedCacheEvictor(maxBytes),
      StandaloneDatabaseProvider(context),
    )
}
