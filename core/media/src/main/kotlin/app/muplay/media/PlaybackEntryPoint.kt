package app.muplay.media

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A way into the real production Hilt graph for `app.muplay.MuPlaybackServiceTest` (`:app`'s
 * instrumented tests), which builds its queue with the **same** [QueueRepository] the app itself
 * plays through.
 *
 * Declared in this module's `main` source set rather than in `:app`'s `androidTest` source set
 * because Hilt aggregates `@InstallIn` entry points from a variant's *main* compilation only -- an
 * `@EntryPoint` declared in `androidTest` is not part of the running `MuPlayApplication`'s
 * generated `SingletonComponent` at all. Same reasoning, and same cost, as `SyncWatermarkEntryPoint`
 * (`:core:database`) and `LibraryRepositoryEntryPoint` (`:feature:setup`); see the first of those
 * for the fuller argument.
 *
 * Why it is worth that cost here: the alternative is for the test to build its own `MediaItem`s,
 * which would exercise `MediaItems.of` but not the chain that actually feeds the service in
 * production -- stored credentials, `SubsonicSourceProvider`, one source per queue, the stream URL,
 * the artwork URL. The service test is the only place that chain is driven end to end.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaybackEntryPoint {
  fun queueRepository(): QueueRepository
}
