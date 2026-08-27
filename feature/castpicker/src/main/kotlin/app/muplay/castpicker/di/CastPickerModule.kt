package app.muplay.castpicker.di

import app.muplay.castpicker.CastPickerScope
import app.muplay.castpicker.RendererDirectSection
import app.muplay.settings.SettingsSection
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Everything this module contributes to the graph, which is one section and the scope it writes on.
 *
 * **This file is the whole of the coupling between casting and settings.** Delete
 * `feature/castpicker/` and the `@IntoSet` below goes with it, the multibound set gets smaller, and
 * `:feature:settings` -- which names neither this module nor `:core:cast` -- keeps compiling. That
 * is Plan 6's definition-of-done item 5, expressed as a binding rather than as a promise.
 */
@Module
@InstallIn(SingletonComponent::class)
object CastPickerModule {

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
