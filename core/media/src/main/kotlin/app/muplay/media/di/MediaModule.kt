package app.muplay.media.di

import app.muplay.media.NeverResume
import app.muplay.media.ResumePolicy
import app.muplay.media.browse.DefaultSurfaceResolver
import app.muplay.media.browse.SurfaceResolver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.OkHttpClient

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

  /**
   * Plan 3 resumes nothing -- spec section 3's stated behaviour for music: *"Only books get resume
   * treatment. Music restarts from 0."* [NeverResume] is that behaviour and not a placeholder.
   *
   * Plan 4 replaces **this binding** with a policy that answers from an in-memory snapshot of
   * `media_progress`, and changes nothing else: `MuPlayer` already consults whatever is bound here
   * on every one of its six `setMediaItem(s)` overloads.
   */
  @Provides
  @Singleton
  fun provideResumePolicy(): ResumePolicy = NeverResume

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
  }
}
