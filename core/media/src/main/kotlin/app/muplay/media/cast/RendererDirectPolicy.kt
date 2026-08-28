package app.muplay.media.cast

import app.muplay.database.CastSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * Whether a speaker may be handed the Navidrome stream URL itself, **asked now**.
 *
 * `CastRouter.confirm` takes a `() -> Boolean` for this and is called from a `Player`'s load path,
 * which cannot suspend; the stored answer lives in DataStore, which can only be read from a
 * coroutine. This interface is where those two facts meet, and it exists as a named type rather
 * than a bare `() -> Boolean` for one practical reason: Dagger cannot bind a Kotlin function type
 * without a qualifier, and a qualifier on a `Function0<Boolean>` is a binding whose meaning lives
 * entirely in its annotation. A named interface also gives this KDoc somewhere to live, and it
 * lets `MediaModuleTest` drive the shipped provider **in both directions** from the JVM tier --
 * which is what makes the router's refusal a Tier 1 assertion instead of an emulator-only one.
 *
 * It stays in `:core:media`, not `:core:cast`: `:core:cast` carries no Android type and no DI by
 * design, and the thing behind this answer is DataStore.
 */
fun interface RendererDirectPolicy {

  /**
   * The user's current answer.
   *
   * Called once per `CastRouter.confirm` that actually reaches the fallback -- i.e. after a
   * renderer has failed to fetch from the phone -- and never on the fast path.
   */
  fun isAllowed(): Boolean
}

/**
 * The shipped answer: whatever the user last chose in Settings, read at the moment it is asked.
 *
 * **Why not read it once and cache it.** This class and `CastRouter` are both `@Singleton`s, so a
 * value resolved at graph-construction time is resolved once per process. A user who turns the
 * switch on and immediately casts would then get the answer from before they touched it, with the
 * failure message naming a setting they had already changed -- the silent-wrong-answer shape this
 * plan is written against, reintroduced by the convenience of a singleton. `CastRouterTest`'s
 * `the renderer-direct setting is read when the fallback is taken, not when the router is built`
 * is the assertion; this class is the half that has to keep being true.
 *
 * **Why `runBlocking` is acceptable here, specifically.** `confirm` is already a blocking call: it
 * has just spent up to six seconds inside `MediaProxyServer.awaitRequest` waiting for a renderer
 * to fetch, on `Dispatchers.IO` (see `CastSession`). A DataStore read on the same thread, after
 * the file is warm, is microseconds. This is also the only place in the media layer that blocks on
 * a preference, and it is deliberately not a `Flow` collected into a cached field: a cache is a
 * second copy of the answer, and a second copy is a thing that can be stale exactly when it
 * matters.
 *
 * **Fail-closed** is inherited rather than restated: `CastSettings.allowRendererDirect` answers
 * `DEFAULT_ALLOW_RENDERER_DIRECT` -- which is `false` -- when the store cannot be read at all.
 * Nothing here catches anything, because a `runCatching { } ?: true` in this position is precisely
 * the mistake, and a `runCatching { } ?: false` would be a second, unasserted copy of a rule that
 * already has one.
 */
@Singleton
class StoredRendererDirectPolicy @Inject constructor(
  private val settings: CastSettings,
) : RendererDirectPolicy {

  override fun isAllowed(): Boolean = runBlocking { settings.allowRendererDirectNow() }
}
