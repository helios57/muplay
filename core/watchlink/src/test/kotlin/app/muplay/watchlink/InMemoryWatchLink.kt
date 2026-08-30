package app.muplay.watchlink

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A [WatchLink] that goes nowhere.
 *
 * Spec section 10's rung 4 -- *"only where the real thing cannot run"* -- and the real thing needs
 * two physically paired devices, which no CI runner has. Hand-written, with no mock framework
 * anywhere near it, and it records what was published so a test can assert on the **arguments**, not
 * merely that publishing happened.
 *
 * ### Why it lives here and not in `:core:testing`
 *
 * Plan 5 Task 10's listing put it in `:core:testing`. That module is `muplay.jvm.library` -- a plain
 * Kotlin JVM project -- and this one is an Android library, because [DataLayerWatchLink] needs
 * `@ApplicationContext` and `play-services-wearable`. A JVM project cannot depend on an Android
 * library at all, so the placement is not a cycle to break but an impossibility, and `testFixtures`
 * is not the escape either: AGP gates a library's test-fixtures source set behind
 * `android { testFixtures { enable = true } }`, which `ConventionTest`'s *no module configures
 * android or kotlin blocks directly* refuses.
 *
 * So it lives in this module's own `src/test`, which is the only consumer there is. One copy, one
 * place -- the listing's own instruction was *"decide once and record it; a fake that exists in two
 * places is a fake that disagrees with itself"*.
 */
class InMemoryWatchLink : WatchLink {

  private val _published = mutableListOf<WatchSyncPayload>()
  val published: List<WatchSyncPayload> get() = _published.toList()

  /**
   * `replay = 1` so a test may [deliver] before the collector [WatchSyncEngine.start] launches has
   * actually started collecting -- which, on a `StandardTestDispatcher`, is the normal case rather
   * than a race. It is also what the real Data Layer does: a data item is state, not an event, and a
   * peer that connects late gets the current one.
   */
  private val delivered = MutableSharedFlow<WatchSyncPayload>(replay = 1, extraBufferCapacity = 8)

  override suspend fun publish(payload: WatchSyncPayload) {
    _published += payload
  }

  override fun incoming(): Flow<WatchSyncPayload> = delivered.asSharedFlow()

  /** Pretends the peer published [payload]. */
  suspend fun deliver(payload: WatchSyncPayload) {
    delivered.emit(payload)
  }
}
