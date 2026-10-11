package io.github.helios57.muplay.media.di

import javax.inject.Qualifier

/**
 * The cache that holds saved playlists' audio, as opposed to the unqualified streaming cache.
 *
 * Two caches because they are two promises. The streaming cache is a bounded LRU in `cacheDir`
 * that the system may empty at will; a song the listener asked to keep must survive both its
 * eviction and the system's, so it lives in `filesDir` with no evictor at all and leaves only when
 * the playlist that wanted it is removed.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OfflineAudioCache
