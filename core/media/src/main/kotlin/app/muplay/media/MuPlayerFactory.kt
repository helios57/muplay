package app.muplay.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
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
 * construction site somewhere else; that is exactly what `PlayerConstructionTest` refuses. When a
 * test needs to reach *inside* the player rather than to configure it -- Task 7's PCM capture is
 * the first -- the answer is a parameter on [create] with a production default, for the same
 * reason: see that function's own note.
 *
 * ### The three lines that are silent in the other direction
 *
 * `setAudioAttributes(.., handleAudioFocus = true)`, `setHandleAudioBecomingNoisy(true)` and
 * `setWakeMode(C.WAKE_MODE_NETWORK)` are one builder call each, and dropping any of them is silent
 * in exactly the way the retry policy is: the app still plays, every unit test stays green, and the
 * defect only appears on a device that has something else happening on it -- a phone call played
 * over, an audiobook coming out of the phone's speaker the moment the headphones come out, or a
 * track that stalls once the screen has been off long enough for doze and WiFi power-save to bite.
 *
 * The third is the one a bench test can never reproduce, because the device under test is awake and
 * plugged in. `AudioFocusTest` therefore observes it the only way that is not a flag assertion: the
 * **power manager's own wake-lock registry**, read back through `dumpsys`, showing this process
 * holding `ExoPlayer:WakeLockManager` while it plays and giving it up when it pauses.
 *
 * The first is observed as *playback that stopped*, never as a flag that was set. The second cannot
 * be observed as a pause on any emulator this project has -- see that test's own note for the
 * measured reason -- and is observed as `ActivityManagerService`'s receiver registry instead.
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
  private val resumePolicy: ResumePolicy,
) {

  /**
   * **What the session is given, and the only thing outside this module that should ever be.**
   *
   * A [MuPlayer] rather than the `ExoPlayer` below: everything past this line sees a `Player` that
   * cannot be told where to start, because all six of its `setMediaItem(s)` overloads discard the
   * caller's position and ask [resumePolicy] instead. That is spec section 3's guarantee, and it is
   * structural rather than conventional -- a `MediaController` in a car reaches the session's
   * player, and the session's player is this one.
   *
   * The caller's **index** survives: [NeverResume] returns it unchanged, so `PlaybackLauncher`'s
   * `setMediaItems(items, queue.startIndex, 0L)` still starts on the track the user tapped. Only the
   * position is the policy's to choose.
   */
  fun create(): MuPlayer = MuPlayer(createExoPlayer(), resumePolicy)

  /**
   * The **raw** player, for the three device suites whose subject is an `ExoPlayer` behaviour
   * (audio focus, the cache key, gapless) and for [create] to wrap. [renderersFactory] defaults to
   * exactly what this player has always used.
   *
   * ### Why the parameter exists, since it is a seam a test asked for
   *
   * Plan 3 Task 7 measures gapless playback by capturing the PCM a real decoder produced, from
   * inside the audio pipeline: a `TeeAudioProcessor` in the `DefaultAudioSink`'s processor chain,
   * upstream of the `AudioTrack`. Media3 offers **no** way to reach that chain on an already-built
   * player -- no setter, no listener, nothing after construction. The only supported route is the
   * `RenderersFactory`, which is a *construction* argument.
   *
   * So the choice was between this parameter and `GaplessTest` assembling a player of its own. The
   * second is the one this class exists to prevent, and `PlayerConstructionTest` refuses it outright
   * -- a hand-built player would silently lose the 429 retry policy, which hangs off the media
   * source factory below and is silent when it is missing. A test measuring a player that is not
   * the one that ships is measuring a copy, and the copy is exactly what drifts.
   *
   * ### Why the default is not a behaviour change
   *
   * `ExoPlayer.Builder(context)` -- the single-argument form this used to call -- supplies
   * `DefaultRenderersFactory(context)` itself, from a lazy supplier. Passing the same object
   * explicitly is the same arrangement, constructed eagerly; the constructor stores a `Context` and
   * an extension-renderer mode and does nothing else. `MuPlaybackService` calls `create()` with no
   * argument and gets the player it always got.
   *
   * ### What keeps the seam honest
   *
   * The parameter is not observable from production -- both call shapes build a working player --
   * so what stops it drifting is the tier that uses it: `GaplessTest` passes a renderers factory
   * whose audio sink is tapped, and every frame it measures arrives through *this* function. A
   * `create` that ignored its argument would leave that suite with an empty capture and every
   * frame-count assertion in it red. Measured, not asserted from the armchair: see task-7b-report.md.
   */
  fun createExoPlayer(
    renderersFactory: RenderersFactory = DefaultRenderersFactory(context),
  ): ExoPlayer =
    ExoPlayer.Builder(context, renderersFactory)
      .setMediaSourceFactory(
        DefaultMediaSourceFactory(dataSourceFactory.create())
          .setLoadErrorHandlingPolicy(loadErrorPolicy),
      )
      // Music until the first item transition says otherwise -- [ContentTypeSwitcher] below keeps
      // it honest from then on. `handleAudioFocus = true` is what makes Media3 request focus, duck
      // for a navigation prompt and pause for a call, all of it without a line of focus code here.
      .setAudioAttributes(PlaybackAudioAttributes.of(MediaMetadata.MEDIA_TYPE_MUSIC), true)
      // Headphones unplugged, Bluetooth disconnected. Without this, yanking headphones plays an
      // audiobook out loud on a train.
      .setHandleAudioBecomingNoisy(true)
      // A partial wake lock **and** a WiFi lock, held only while actually playing. `WAKE_MODE_NETWORK`
      // rather than `WAKE_MODE_LOCAL` because every byte this app plays arrives over the network:
      // without the WiFi half, WiFi power-save with the screen off starves the loader and playback
      // stalls mid-track. Both are released the moment playback stops, by Media3, so this is not a
      // battery decision made once for the process.
      .setWakeMode(C.WAKE_MODE_NETWORK)
      .build()
      .also { player -> player.addListener(ContentTypeSwitcher(player)) }
}
