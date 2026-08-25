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
 * Why the first member is worth that cost: the alternative is for the test to build its own `MediaItem`s,
 * which would exercise `MediaItems.of` but not the chain that actually feeds the service in
 * production -- stored credentials, `SubsonicSourceProvider`, one source per queue, the stream URL,
 * the artwork URL. The service test is the only place that chain is driven end to end.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaybackEntryPoint {
  fun queueRepository(): QueueRepository

  /**
   * The **application's own** `@Singleton` [PlaybackConnection] -- the one every screen's
   * `PlayerViewModel` connects through, not a second one built by a test.
   *
   * It is here for one reason, and it is a measured one. `MuPlayApp` puts `MiniPlayer` in the
   * `Scaffold`'s `bottomBar`, so **every** screen in the app builds a `PlayerViewModel`, whose
   * `init` connects this singleton and leaves a `MediaController` bound to `MuPlaybackService` for
   * the life of the process. A bound client is exactly what `stopService` cannot destroy, so from
   * the moment the mini player existed, `MuPlaybackServiceTest`'s stop-and-restart test stopped
   * reaching `onDestroy` at all -- silently, because the test still passed. Measured on
   * `muplay37`: `MuPlaybackServiceTest` **alone** covers `MuPlaybackService` 27/31 LINE, and
   * `BrowseJourneyTest` running before it in the same process takes that to 20/31, below the 0.85
   * floor `coverageFloors` sets. The seven lines lost are `onDestroy`'s -- the player release whose
   * absence is a leaked `ExoPlayer` holding audio focus, codecs and a loading thread, which is the
   * one defect in that class no other test can see.
   *
   * Releasing this before `stopService` is what makes the service genuinely destroyable again. It
   * is safe for whatever runs next: `PlaybackConnection.controller()` reconnects on demand, so a
   * later screen simply binds again.
   */
  fun playbackConnection(): PlaybackConnection
}
