package app.muplay.media

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A way into the real production Hilt graph for `app.muplay.MuPlaybackServiceTest` (`:app`'s
 * instrumented tests), which builds its queue with the **same** [QueueRepository] the app itself
 * plays through.
 *
 * ### Why `src/debug/`, and why it is not `src/main/`
 *
 * Hilt aggregates `@InstallIn` from a variant's **main compilation**, so an `@EntryPoint` declared
 * in `:app`'s `androidTest` source set is not part of the running `MuPlayApplication`'s generated
 * `SingletonComponent` at all -- confirmed directly once, as a
 * `ClassCastException: Cannot cast ...SingletonCImpl to ...EntryPoint`. That is true, and it was
 * read as "therefore it must be in `src/main/`", which does not follow: a **build-type source set
 * is part of that same compilation**. `app/src/debug/kotlin/app/muplay/di/CleartextPolicyModule.kt`
 * and its `src/release/` twin are this repository's own proof, and the instrumented tests run the
 * debug variant.
 *
 * So this file is compiled into debug, is absent from release, and -- the part that actually
 * matters -- is not public API of its module. In `src/main/` any module depending on this one could
 * pull the binding below out of the graph instead of injecting it, which is the pattern
 * constructor injection exists to remove. The confidentiality difference is close to zero either
 * way (in-process code can already reach what this exposes); the architectural difference is not.
 *
 * `ConventionTest`'s `every Hilt entry point is declared in a debug source set` is what keeps this
 * from drifting back.
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

  /**
   * The **application's own** singleton [PlaybackConnection] — not one the test built.
   *
   * Needed for exactly one thing, and it is a fact about this process rather than about any test:
   * `:feature:player`'s `PlayerViewModel` calls `connection.controller()` behind the mini player,
   * so from the first screen that composes it the singleton holds a `MediaController` **bound to
   * `MuPlaybackService` for the life of the process**, and it is never released. A bound service
   * cannot be destroyed, so `stopService` is a no-op and `onDestroy` never runs.
   *
   * `MuPlaybackServiceTest.theServiceCanBeStoppedAndComesBackWithAWorkingSession` therefore has to
   * release this connection as well as its own, or its premise ("nothing is connected any more")
   * is simply false whenever a UI journey ran before it in the same process — which is what a full
   * `:app:connectedDebugAndroidTest` does. Measured: with `BrowseJourneyTest` ahead of it,
   * `MuPlaybackService` LINE fell from 29/31 to 22/31 and its coverage floor failed, while the test
   * itself stayed green because it re-connects afterwards and never asserted the service had gone.
   */
  fun playbackConnection(): PlaybackConnection

  /**
   * The **application's own** singleton [SleepTimerController] — the one the book screen's view
   * model calls `start` on, and the one `MuPlaybackService` attaches to its player.
   *
   * There is no other way to ask the question the test that uses this asks. Every other test of
   * this class hands it a player itself, which is precisely how it shipped with `attach` called
   * from nowhere: a controller given a player passes, and production was never giving it one.
   * Reached from the running graph, the object under test is the object the app has.
   */
  fun sleepTimerController(): SleepTimerController

  /**
   * The **application's own** singleton [ShakeSensor] — the one `MuPlaybackService` starts and
   * stops as the sleep timer comes and goes.
   *
   * Here for the same reason [sleepTimerController] is, and against the same defect one seam
   * further out: `ShakeSensor` had a complete instrumented suite of its own in which the test
   * called `start` itself, and nothing in any `src/main` ever did — so the gesture was dead in the
   * shipped app while every test of it was green. Reached from the running graph, the object under
   * test is the object the app has.
   */
  fun shakeSensor(): ShakeSensor
}
