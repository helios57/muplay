package app.muplay.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * Thrown when a [DataSpec] reaches the cache with no custom cache key.
 *
 * A distinct type rather than a bare `IllegalStateException` so a test can assert on it and a
 * reader can find every throw site by its name.
 */
class MissingCacheKeyException(uri: String) : IllegalStateException(
  "A media request reached the cache with no custom cache key: $uri. Every MediaItem this app " +
    "plays must set setCustomCacheKey(song.id) -- see MediaItems.kt. Media3's default factory " +
    "would fall back to the URI here, and this client's stream URLs carry a fresh auth salt on " +
    "every call, so a URL-derived key produces a cache that is written, never read, and grows " +
    "forever. That is the defect Tempo ships and spec section 4 names.",
)

/**
 * The cache key is the **track id**, and nothing else.
 *
 * Media3's `CacheKeyFactory.DEFAULT` returns `dataSpec.key` when present and the URI otherwise.
 * The fallback is removed here on purpose: a missing key is a programming error that this project
 * would otherwise discover as an unexplained 0% hit rate, months later, on a device.
 *
 * `androidx.annotation.OptIn`, not `kotlin.OptIn`, for the reason
 * [NavidromeLoadErrorHandlingPolicy] states at length: Media3's `@UnstableApi` is a Java
 * annotation marked with `androidx.annotation.RequiresOptIn`, which the Kotlin compiler does not
 * enforce at all. Android Lint's `UnsafeOptInUsageError` does, and `check` runs lint, so a file
 * missing this compiles cleanly and reddens the build a task later.
 */
@OptIn(UnstableApi::class)
object TrackIdCacheKeyFactory : CacheKeyFactory {
  override fun buildCacheKey(dataSpec: DataSpec): String =
    dataSpec.key ?: throw MissingCacheKeyException(dataSpec.uri.toString())
}
