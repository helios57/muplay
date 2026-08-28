package app.muplay.media.di

import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.OkHttpProxyUpstream
import app.muplay.cast.proxy.ProxyRegistry
import app.muplay.cast.proxy.ProxyUpstream
import app.muplay.cast.route.CastRouter
import app.muplay.cast.soap.SoapClient
import app.muplay.media.AudiobookItemSource
import app.muplay.media.AudiobookResumePolicy
import app.muplay.media.AudiobookSnapshot
import app.muplay.media.NeverResume
import app.muplay.media.ResumePolicy
import app.muplay.media.TranscodeOffsetSupport
import app.muplay.media.TranscodeSeekSupport
import app.muplay.media.browse.DefaultSurfaceResolver
import app.muplay.media.browse.SurfaceResolver
import app.muplay.media.cast.OneShotResumePolicy
import app.muplay.media.cast.RendererDirectPolicy
import app.muplay.media.cast.StoredRendererDirectPolicy
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * The policy the cast decorator wraps.
 *
 * Whatever the resume plan binds as *the* `ResumePolicy` keeps its body and its provenance and
 * gains this annotation; nothing else about it changes. It exists so that exactly one **un**qualified
 * `ResumePolicy` is left in the graph and it is the decorator -- see [MediaModule]'s own block
 * comment for what the alternative costs.
 */
// `RUNTIME`, where [MediaHttpClient] next door is `BINARY`, and the difference is deliberate: the
// defect this qualifier guards against is SILENT (the wrong `ResumePolicy` left unqualified, the
// decorator armed and never consulted, the return leg resuming from zero), so `MediaModuleTest`
// asserts the shape of the binding by reflection -- and a `BINARY` annotation is invisible to
// reflection. `javax.inject`'s own specification asks qualifiers to be retained at runtime for
// exactly this kind of reason.
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class UndecoratedResumePolicy

/**
 * The scope every command to a speaker runs on. Never the main thread; see the provider.
 *
 * Qualified rather than binding a bare `CoroutineScope`, because an unqualified one is the kind of
 * binding a later module injects by accident and then cancels.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CastCommands

/**
 * The media layer's object graph.
 *
 * The `OkHttpClient` here is **not** the one `:core:network` uses, and that is deliberate rather
 * than an oversight. `:core:network` issues short JSON requests where a call timeout is a safety
 * net; this one reads a media body that is legitimately open for the length of a track, where a
 * call timeout is a guaranteed mid-song failure. Two clients with different, correct policies beats
 * one client with the union of both.
 *
 * The other `@Singleton` this layer contributes, `NavidromeLoadErrorHandlingPolicy`, is bound by
 * its own `@Inject` constructor and scoped on the class rather than provided from here — see that
 * class's own note. Only bindings that genuinely need a builder live in this module.
 *
 * **This object names no Android and no Media3 type, and that is load-bearing.** It is why
 * `ci/mutation-probes.sh` — a JVM-only runner — can reach the timeout decision at all, and why
 * `MediaModuleTest` can hold these four lines to a 1.0000 LINE floor on the *fast* tier rather
 * than behind the emulator. The media cache is a Media3 `Cache` built on a real `Context`, which
 * no JVM test can construct, so it lives in [MediaCacheModule] instead. Putting it here was
 * measured first: it took this class to 4/5 = 0.80 lines on JVM-only data and would have forced
 * the timeout gate onto Tier 2 or blunted it to 0.80. Same reasoning as `StreamRetryPolicy` being
 * a separate type from the Media3 adapter that consumes it.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

  @Provides
  @Singleton
  // Qualified, so "this is not `:core:network`'s client" is enforced rather than argued -- see
  // [MediaHttpClient]. An unqualified `Call.Factory` injection point now fails to compile instead
  // of quietly receiving the streaming client's four-minute-friendly timeouts.
  @MediaHttpClient
  fun provideMediaCallFactory(): Call.Factory =
    OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // How long a *read* may stall, not how long the whole body may take.
      .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // No `callTimeout`: the default is already "none", and setting one would cap the total
      // duration of a streaming read, i.e. cut off any track longer than the cap. Stated as a
      // comment because "we did not set it" and "we thought about it and must not set it" are
      // different facts, and only one of them survives a refactor.
      .build()

  // `provideClock` moved to `:core:database`'s `DataModule` (Plan 4 Task 4), where its reasoning
  // and its test now live: `AudiobookRepository` is the first class down there to take a `Clock`,
  // and a binding declared above its consumer breaks that module's Hilt tests while making this
  // module a build-time requirement of one that does not depend on it. Do not re-add it -- two
  // unqualified `Clock` bindings is a Hilt duplicate-binding failure, and `DataModule`'s is
  // visible here because `:core:media` depends on `:core:database`.

  // ---- the cast handover's bindings -----------------------------------------------------------
  //
  // Plan 6 Task 9. One contiguous block, and the first three entries are the whole feature: a
  // decorator that nothing injects is a decorator that does nothing, and it fails SILENTLY.
  //
  // `CastSessionManager` injects `OneShotResumePolicy` by concrete type and arms it; the local
  // player gets its policy from `MuPlayerFactory`'s injected, UNQUALIFIED `ResumePolicy`. Unless
  // those two are the same object the outbound leg still works by accident -- the remote player is
  // built holding the decorator -- while the **return** leg arms the decorator and then asks the
  // undecorated policy, which answers the ordinary resume and restarts the track from zero. Coming
  // back from a speaker having lost the listener's place is exactly the defect this task exists to
  // fix, and it would ship green.
  //
  // So the existing provider is RE-ANNOTATED rather than duplicated. Adding a second unqualified
  // `@Provides ResumePolicy` is a Hilt duplicate-binding failure, and that is the *good* outcome;
  // the bad one is two bindings where the wrong one wins.

  /**
   * **The line this whole project is for.**
   *
   * Plan 3 shipped [NeverResume] here -- spec section 3's stated behaviour for music, *"Only books
   * get resume treatment. Music restarts from 0"* -- and said the audiobook plan would replace this
   * one binding and change nothing else. This is that replacement, and nothing else did change:
   * the qualifier, the scope and the decorator below are exactly as Plan 6 Task 9 left them.
   *
   * [NeverResume] is **not** deleted. It remains the reference implementation of "no resume", it is
   * what a future non-audiobook policy starts from, and `ResumePolicyTest` is what keeps `resolve`'s
   * signature from growing a position parameter.
   *
   * ### Why this takes an [AudiobookItemSource] and not the snapshot
   *
   * So that a **JVM** test can call it. The plan for this task passed `AudiobookSnapshot` here and
   * recorded, honestly, that restoring `NeverResume` would then fail no test in this module at all
   * -- because every other test constructs the policy directly, and the snapshot needs Room. A
   * narrow `fun interface` costs nothing at the graph (`AudiobookSnapshot` is the only
   * implementation and is bound to it in [Bindings] below) and moves the single most important
   * binding in the application onto the fast tier: `MediaModuleTest` hands this a two-entry source
   * and asserts a real, non-zero resume comes back. `ci/mutation-probes.sh`'s `resume/module-*`
   * probes are the falsification.
   *
   * The `Clock` is `:core:database`'s `DataModule` binding -- see the note further up this file.
   * It is what makes the smart rewind depend on how long the book was away rather than on nothing.
   */
  @Provides
  @Singleton
  @UndecoratedResumePolicy
  fun provideUndecoratedResumePolicy(source: AudiobookItemSource, clock: Clock): ResumePolicy =
    AudiobookResumePolicy(source, clock)

  /**
   * `@Singleton` because [app.muplay.media.cast.CastSessionManager] arms *this instance* and the
   * local player must be asking *this instance*. An unscoped binding hands out two decorators over
   * one delegate, and the return leg then resumes from zero -- see the block comment above.
   */
  @Provides
  @Singleton
  fun provideOneShotResumePolicy(
    @UndecoratedResumePolicy delegate: ResumePolicy,
  ): OneShotResumePolicy = OneShotResumePolicy(delegate)

  /** The only unqualified `ResumePolicy` in the graph -- so `MuPlayerFactory` gets the decorator. */
  @Provides
  fun provideResumePolicy(oneShot: OneShotResumePolicy): ResumePolicy = oneShot


  /**
   * The tokens a renderer fetches media with, and nothing else holds them.
   *
   * Shared between the proxy that answers them and the router that mints them, which is why it is a
   * binding rather than a field on either.
   */
  @Provides
  @Singleton
  fun provideProxyRegistry(): ProxyRegistry = ProxyRegistry()

  /**
   * Navidrome, for the proxy's upstream fetch, on the media layer's own client.
   *
   * Deliberately the streaming client and not `:core:network`'s: this reads a media body that is
   * legitimately open for the length of a track, and a call timeout here is a guaranteed mid-song
   * failure for a speaker exactly as it is for the phone.
   */
  @Provides
  @Singleton
  fun provideProxyUpstream(@MediaHttpClient client: Call.Factory): ProxyUpstream =
    OkHttpProxyUpstream(client as OkHttpClient)

  /**
   * **Binds a listening socket as soon as it is created**, which is why nothing injects it
   * directly: `CastSessionManager` takes a `Provider<CastRouter>` so the first cast is the first
   * socket. It is started here rather than at the first cast so that "created" and "serving" cannot
   * come apart -- a proxy that exists and is not accepting is a route that fails its own proof.
   */
  @Provides
  @Singleton
  fun provideMediaProxyServer(upstream: ProxyUpstream, registry: ProxyRegistry): MediaProxyServer =
    MediaProxyServer(upstream, registry).also { it.start() }

  /**
   * `allowRendererDirect` is now **the setting**, not a hardcoded `false`.
   *
   * Handing a renderer the Navidrome stream URL hands it the user's Subsonic credentials -- `u`,
   * `t` and `s`, which are password equivalents and do not expire. Plan 6 Task 7 shipped that as a
   * constructor `Boolean` fixed at `false` with a comment saying *"until that setting exists the
   * answer is no"*. Task 12 built the setting: it is persisted in `:core:database`
   * (`CastSettings`), it is off until a user turns it on, and the three consequences of turning it
   * on are stated beside the switch in `:feature:castpicker`'s `RendererDirectSection`.
   *
   * **Passed as a function reference, not as a value read here.** `CastRouter` is a `@Singleton`
   * and so is this provider, so `allowRendererDirect = runBlocking { ... }` would resolve the
   * answer once, the first time anything in the app needed a router. A user who turned the switch
   * on and cast a second later would get the previous answer, silently, while the failure message
   * named the setting they had just changed. See [RendererDirectPolicy] and `CastRouterTest`'s
   * `the renderer-direct setting is read when the fallback is taken, not when the router is built`.
   *
   * The `sameSubnetFastPath` is left at its default of *never*, so **every** route is proved by
   * waiting for the renderer to fetch. Wiring the fast path needs the renderer's prefix length from
   * `ConnectivityManager`, and skipping the proof on a guess is the one change here that can make a
   * cast start and play nothing. Slower and right.
   */
  @Provides
  @Singleton
  fun provideCastRouter(
    proxy: MediaProxyServer,
    registry: ProxyRegistry,
    rendererDirect: RendererDirectPolicy,
  ): CastRouter = CastRouter(proxy, registry, allowRendererDirect = rendererDirect::isAllowed)

  @Provides
  @Singleton
  fun provideCastHttpClient(): CastHttpClient = CastHttpClient()

  @Provides
  @Singleton
  fun provideSoapClient(http: CastHttpClient): SoapClient = SoapClient(http)

  /**
   * Where a speaker is talked to.
   *
   * **Never the main thread**: every command is a blocking socket exchange and the poll runs
   * forever. `SupervisorJob` so that one session's failure does not cancel the scope the next one
   * would need, and `Dispatchers.IO` because that is what this work is -- `CastSession` already
   * hops to it for the route proof, and running the rest on `Default` would occupy a CPU-sized pool
   * with waiting.
   */
  @Provides
  @Singleton
  @CastCommands
  fun provideCastCommandScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private const val CONNECT_TIMEOUT_SECONDS = 15L
  private const val READ_TIMEOUT_SECONDS = 30L

  /**
   * The interface bindings this layer contributes.
   *
   * A nested `interface` rather than a `@Provides fun bind(impl: X): X = impl` on the object above,
   * and the reason is measured rather than stylistic: an `@Binds` method is `abstract` and compiles
   * to no executable line at all, whereas the `@Provides` form would add a **fifth** line to
   * [MediaModule] that only Hilt's graph -- and therefore only an emulator -- can execute. That
   * would take this object to 4/5 = 0.80 on JVM-only data and fail Tier 1's coverage gate on a
   * class nothing is wrong with, or force its floor onto Tier 2 and take the timeout decision with
   * it. See this object's own header for why keeping it JVM-reachable matters.
   */
  @Module
  @InstallIn(SingletonComponent::class)
  interface Bindings {

    /**
     * Plan 5 Task 3's resolver, bound by Plan 5 Task 4 -- its first consumer, and the reason this
     * binding did not exist until now.
     */
    @Binds
    @Singleton
    fun bindSurfaceResolver(impl: DefaultSurfaceResolver): SurfaceResolver

    /**
     * Plan 3 Task 12. `TranscodeOffsetSupport` is the `transcodeOffset` capability gate *and* the
     * thing that rebuilds an item's URI at an offset, because both need the same negotiated
     * `SubsonicSource` and `MuPlayer.seekTo` cannot suspend to fetch one.
     *
     * A binding rather than injecting the concrete class into `MuPlayerFactory`, so that `MuPlayer`
     * -- the seam -- names only the interface and the module's instrumented suites can hand it a
     * written-by-hand one.
     */
    @Binds
    @Singleton
    fun bindTranscodeSeekSupport(impl: TranscodeOffsetSupport): TranscodeSeekSupport

    /**
     * Plan 4 Task 6. The narrow question the resume policy asks, answered by the one class that can
     * answer it.
     *
     * Unscoped on purpose: [AudiobookSnapshot] is itself a `@Singleton`, so there is exactly one
     * instance whichever key it is reached through -- which matters, because
     * `MuPlaybackService.onCreate` starts the collector on the instance **it** injects and the
     * policy must be reading that one. A `@Singleton` here as well would be a second scoped entry
     * delegating to the same object: harmless, and a second thing to reason about.
     */
    @Binds
    fun bindAudiobookItemSource(impl: AudiobookSnapshot): AudiobookItemSource

    /**
     * Plan 6 Task 12. The renderer-direct setting, as the graph resolves it.
     *
     * `@Binds` rather than a `@Provides` line on the object above, and for this module's usual
     * measured reason: an `@Binds` method is `abstract` and compiles to no executable line, whereas
     * a `@Provides` form would add a sixteenth line to [MediaModule] that only a device can execute
     * -- taking a floor that is 15/15 from the JVM tier today and making it emulator-dependent.
     *
     * The seam is what lets `MediaModuleTest` drive [provideCastRouter] with the setting **on** as
     * well as off, on the JVM tier. Before it, the only test of the refusal was one direction of a
     * hardcoded `false`, which any constant satisfies.
     */
    @Binds
    @Singleton
    fun bindRendererDirectPolicy(impl: StoredRendererDirectPolicy): RendererDirectPolicy
  }
}
