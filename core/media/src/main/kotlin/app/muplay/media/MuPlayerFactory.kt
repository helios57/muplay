package app.muplay.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The only place in this project an `ExoPlayer` is constructed.
 *
 * "Only" is not a convention here, it is the point of the type, and it is enforced twice.
 * `PlayerConstructionTest` (JVM tier) fails if a second `ExoPlayer.Builder(` appears anywhere in
 * this module's sources, and `media3-exoplayer` is an `implementation` dependency of `:core:media`
 * -- checked against the resolved POMs, `media3-session` does not depend on it -- so no other module
 * can even name the type. A feature module that *can* build an `ExoPlayer` eventually does, and then
 * there are two players in the process, one of them not the one holding the media session.
 *
 * ### Why a factory rather than a `@Provides @Singleton ExoPlayer`
 *
 * A hard Media3 requirement rather than a preference: an `ExoPlayer` binds to the `Looper` of the
 * thread that built it, and every subsequent access must come from that thread. Hilt would construct
 * a singleton on whichever thread first asked for it. [MuPlaybackService.onCreate] runs on the main
 * thread, so building it there is the only way to be sure.
 *
 * ### The one line that is silent when it is missing
 *
 * `.setLoadErrorHandlingPolicy(loadErrorPolicy)` hangs off the **`MediaSource.Factory`**, not off
 * `ExoPlayer.Builder` -- which has no such setter at all in Media3 1.11.0, checked against the
 * resolved artifact. Forget it and nothing breaks loudly: the player quietly keeps
 * `DefaultLoadErrorHandlingPolicy`'s three retries inside five seconds,
 * `NavidromeLoadErrorHandlingPolicyTest` and `StreamRetryPolicyTest` stay green, and the 429
 * handling this module exists for is simply absent from the running app.
 *
 * The other argument this constructor forwards, `dataSourceFactory`, is observed by
 * `MuPlayDataSourceFactoryTest.theRequestIsIssuedByTheInjectedCallFactoryAndNotOneBuiltInside`: the
 * client it is built on stamps a header nothing else sends, so a factory that ignored the argument
 * and built an identical one internally loses it. A `User-Agent` assertion cannot see that defect,
 * because the replacement would send the same `User-Agent`. `context` is not observable and honestly
 * so -- it is `ExoPlayer.Builder`'s sole positional argument, so there is no way to drop it that
 * compiles, and the only substitution available in a process (`context.applicationContext`) is
 * behaviourally identical.
 *
 * And the test that proves the *policy* wired counts requests rather than inspecting objects:
 * `MuPlayDataSourceFactoryTest.aRefusalBudgetThatRunsOutSurfacesAsAPlayerError` asserts
 * `StreamRetryPolicy.MAX_RETRIES + 1` = 6 requests reached the server, where Media3's own default
 * would produce 4. Its neighbour `twoRefusalsWithHttp429DoNotKillThePlayback` is **not** that
 * evidence and must not be read as it: two retries are inside Media3's default budget too, so it is
 * green either way. Both of those tests now build their player through *this* function, which is
 * what makes their wiring the production wiring rather than a copy of it.
 *
 * ### Adding to the player later
 *
 * A future collaborator -- a resume policy, an audio-focus configuration, a cache -- arrives as
 * another constructor parameter here, applied inside [create]. It must not arrive as a second
 * construction site somewhere else; that is exactly what `PlayerConstructionTest` refuses.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn`: Media3's `@UnstableApi` is an
// `androidx.annotation.RequiresOptIn`, which the Kotlin compiler does not enforce at all -- Android
// Lint's `UnsafeOptInUsageError` does, and `check` runs lint, so a file like this one compiles clean
// and fails the build much later. `ExoPlayer`, `ExoPlayer.Builder` and `DefaultMediaSourceFactory`
// are all annotated. Opting in here rather than marking this class `@UnstableApi` itself: that would
// propagate the requirement to every consumer, and the point of this module is that
// `androidx.media3.exoplayer` stops at its boundary.
@OptIn(UnstableApi::class)
class MuPlayerFactory @Inject constructor(
  @ApplicationContext private val context: Context,
  private val dataSourceFactory: MuPlayDataSourceFactory,
  private val loadErrorPolicy: NavidromeLoadErrorHandlingPolicy,
) {

  fun create(): ExoPlayer =
    ExoPlayer.Builder(context)
      .setMediaSourceFactory(
        DefaultMediaSourceFactory(dataSourceFactory.create())
          .setLoadErrorHandlingPolicy(loadErrorPolicy),
      )
      .build()
}
