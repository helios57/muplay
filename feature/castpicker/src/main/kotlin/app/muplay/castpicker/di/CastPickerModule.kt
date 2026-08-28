package app.muplay.castpicker.di

import androidx.media3.common.Player
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DatagramSsdpTransport
import app.muplay.cast.discovery.DescriptionFetcher
import app.muplay.cast.discovery.DiscoveryResult
import app.muplay.cast.discovery.RendererDirectory
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.session.CastSessionState
import app.muplay.castpicker.CastControl
import app.muplay.castpicker.CastDiscovery
import app.muplay.castpicker.CastPickerScope
import app.muplay.castpicker.RendererDirectSection
import app.muplay.media.cast.CastSessionManager
import app.muplay.model.RememberedRenderers
import app.muplay.settings.SettingsSection
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * The picker's two seams, wired to the real cast layer.
 *
 * Everything the picker touches is constructed here rather than injected as a concrete type, for
 * one reason each:
 *
 * * `RendererDirectory` is not in the object graph at all — it takes four collaborators, two of
 *   which are function types Hilt cannot bind, and nothing before this task needed one.
 * * `CastSessionManager` is, and is reached through [SessionCastControl] rather than injected into
 *   the view model directly, because its first statement in `castTo` is `Looper.getMainLooper()`
 *   and a JVM test of the view model would die there.
 *
 * **This file is also the whole of the coupling between casting and settings.** Delete
 * `feature/castpicker/` and the `@IntoSet` below goes with it, the multibound set gets smaller, and
 * `:feature:settings` -- which names neither this module nor `:core:cast` -- keeps compiling. That
 * is Plan 6's definition-of-done item 5, expressed as a binding rather than as a promise.
 */
@Module
@InstallIn(SingletonComponent::class)
object CastPickerModule {

  /**
   * One discovery pass, over the real UDP transport and the real remembered-renderer store.
   *
   * `@Singleton` because `RendererDirectory` writes the remembered list on every pass; two
   * instances would be two writers over one DataStore key, and the loser's list is whatever it
   * read before the winner wrote.
   *
   * No socket is bound by constructing this. `DatagramSsdpTransport` opens an ephemeral socket
   * inside each `search` call and closes it on the way out, so an app that never opens the picker
   * never opens a socket — the same property `MediaModule`'s `Provider<CastRouter>` buys for the
   * proxy.
   */
  @Provides
  @Singleton
  fun provideCastDiscovery(remembered: RememberedRenderers): CastDiscovery =
    DirectoryCastDiscovery(
      RendererDirectory(
        transport = DatagramSsdpTransport(),
        destinations = DatagramSsdpTransport::multicastDestinations,
        // Its own client, not `MediaModule`'s: this fetches a short XML document from a renderer,
        // where a call timeout is a safety net, and that one reads a media body that is
        // legitimately open for the length of a track, where a call timeout is a guaranteed
        // mid-song failure. Same split, same reasoning, as the two OkHttp clients this project
        // already keeps apart.
        http = DescriptionFetcher.overHttp(CastHttpClient()),
        remembered = remembered,
      ),
    )

  @Provides
  @Singleton
  fun provideCastControl(manager: CastSessionManager): CastControl = SessionCastControl(manager)

  /**
   * The scope the renderer-direct switch's writes run on.
   *
   * **Not `rememberCoroutineScope()`**, which is cancelled when the composable leaves composition:
   * a user who taps the switch and immediately navigates back would lose the write, and losing a
   * *turn it off* leaves a security decision in force that the user believes they revoked. See
   * `RendererDirectSection`'s own note.
   *
   * `SupervisorJob` so one failed write cannot cancel the scope the next one needs, and
   * `Dispatchers.Default` rather than `IO` because DataStore does its own IO dispatching -- the
   * work on this scope is a suspension and a few field writes.
   *
   * Qualified, because an unqualified `CoroutineScope` is the kind of binding two modules add
   * independently and Dagger then refuses; `:core:media`'s cast command scope is qualified for the
   * same reason.
   */
  @Provides
  @Singleton
  @CastPickerScope
  fun provideCastPickerScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * The interface bindings this module contributes.
   *
   * A nested `interface` rather than a `@Provides` on the object above, for this project's measured
   * coverage reason: an `@Binds` method is `abstract` and compiles to no executable line, whereas
   * the `@Provides` form would add a line to [CastPickerModule] that only Hilt's own graph can
   * execute. `MediaModule` and `DataModule` carry the same shape.
   */
  @Module
  @InstallIn(SingletonComponent::class)
  interface Bindings {

    /**
     * The one line that puts renderer-direct in front of a user.
     *
     * `@IntoSet` and not a direct reference from the settings screen: the screen must not know this
     * class exists, or removing casting would mean editing it.
     */
    @Binds
    @IntoSet
    fun bindRendererDirectSection(section: RendererDirectSection): SettingsSection
  }
}

/** [CastDiscovery] over the real [RendererDirectory]. */
internal class DirectoryCastDiscovery @Inject constructor(
  private val directory: RendererDirectory,
) : CastDiscovery {
  override suspend fun discover(): DiscoveryResult = directory.discover()
}

/**
 * [CastControl] over the real [CastSessionManager].
 *
 * The volume goes through `Player`, not through `UpnpRenderer`, and that is the point of routing it
 * here: `UpnpPlayer` reports `PLAYBACK_TYPE_REMOTE` and declares `COMMAND_SET_DEVICE_VOLUME`, so
 * the phone's own volume keys, the notification's volume row and this slider all end up in one
 * place. A second path straight to `RenderingControl` would be a second writer for one number.
 */
internal class SessionCastControl @Inject constructor(
  private val manager: CastSessionManager,
) : CastControl {

  override val state: StateFlow<CastSessionState> get() = manager.state

  override suspend fun castTo(device: CastDevice) = manager.castTo(device)

  override suspend fun stopCasting() = manager.stopCasting()

  /**
   * The remote player as a **`Player`**, and the widening is load-bearing rather than tidy.
   *
   * Media3's `@UnstableApi` is an `androidx.annotation.RequiresOptIn`, invisible to the Kotlin
   * compiler, and *which declaration a call resolves to* is what decides whether lint flags it. On
   * an `UpnpPlayer` receiver, `deviceVolume` and `setDeviceVolume` resolve to `SimpleBasePlayer`'s
   * overrides -- and `SimpleBasePlayer` is `@UnstableApi`, so both would fail `lintDebug` as
   * `UnsafeOptInUsageError` long after they compiled clean. Typed as `Player` they resolve to
   * `Player`'s own stable declarations, which is also the honest statement of what this class needs:
   * a player, not that player.
   */
  private val remote: Player? get() = manager.castPlayer

  /** `null` when nothing is cast, which is what makes the slider absent rather than inert. */
  override fun deviceVolumePercent(): Int? = remote?.deviceVolume

  override fun setDeviceVolumePercent(percent: Int) {
    // The two-argument form: the one-argument `setDeviceVolume(int)` is deprecated, and the flags
    // argument is where `FLAG_SHOW_UI` would go if this ever wanted the system volume panel.
    remote?.setDeviceVolume(percent, 0)
  }
}
