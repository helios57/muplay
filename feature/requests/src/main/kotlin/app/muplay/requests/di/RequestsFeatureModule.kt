package app.muplay.requests.di

import app.muplay.integrations.IntegrationCredentialStore
import app.muplay.integrations.bindery.BinderySourceFactory
import app.muplay.integrations.lidarr.LidarrSourceFactory
import app.muplay.requests.ConnectionProbe
import app.muplay.requests.IntegrationCredentialEraser
import app.muplay.requests.IntegrationCredentialWriter
import app.muplay.requests.IntegrationsSection
import app.muplay.settings.SettingsSection
import app.muplay.requests.observeConnection
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * This feature's three seams, wired to the real store and the real clients.
 *
 * **Every binding here is one SAM conversion wide, and that shape is a coverage decision.** This
 * module has no `src/androidTest` -- Task 10 is JVM-only, and two `ConventionTest` rules derive the
 * emulator job's and the fast tier's module lists from the tree, so adding one would silently
 * require a workflow edit -- which means nothing here can ever be executed by a test. A `@Provides`
 * returning a multi-line object expression would therefore be un-gated wiring, exactly the 0/1 shape
 * `:integrations:lidarr`, `:integrations:bindery` and `:integrations:requests` each found a real
 * defect in. Keeping each body to one call means the thing that *could* be wrong -- which client the
 * probe builds, which store the writer writes to -- is a single expression a reader can check, and
 * the logic behind it (`observeConnection`) is an ordinary function the fast tier drives over two
 * fake factories.
 *
 * Note what is **not** here: no `RequestsRepository` binding. That class is `@Singleton` with an
 * `@Inject` constructor and Hilt builds it itself; `RequestsViewModel` takes it directly rather than
 * behind a seam, for the reason that view model's own documentation gives.
 */
@Module
@InstallIn(SingletonComponent::class)
object RequestsFeatureModule {

  /**
   * Writes a credential, sealed under this service's own Keystore alias.
   *
   * Reading the configured set is deliberately *not* here: `:integrations:requests`' own
   * `ConfiguredServices` is already bound to the same store for `RequestsRepository`, so the list
   * the settings screen shows and the map the repository polls with are one read and cannot
   * disagree.
   */
  @Provides
  @Singleton
  fun provideCredentialWriter(store: IntegrationCredentialStore): IntegrationCredentialWriter =
    IntegrationCredentialWriter { credentials -> store.save(credentials) }

  @Provides
  @Singleton
  fun provideCredentialEraser(store: IntegrationCredentialStore): IntegrationCredentialEraser =
    IntegrationCredentialEraser { service -> store.clear(service) }

  /**
   * The connection probe, over the two real client factories.
   *
   * `LidarrSourceFactory` and `BinderySourceFactory` rather than `RequestsRepository`, because a
   * connection check runs against credentials the user has just typed and has **not** saved -- and
   * everything on the repository is built around the store's contents.
   */
  @Provides
  @Singleton
  fun provideConnectionProbe(
    lidarr: LidarrSourceFactory,
    bindery: BinderySourceFactory,
  ): ConnectionProbe = ConnectionProbe { credentials -> observeConnection(credentials, lidarr, bindery) }

  /**
   * The interface bindings this module contributes.
   *
   * A nested `interface` rather than a `@Provides` on the object above, for this project's measured
   * coverage reason: an `@Binds` method is `abstract` and compiles to no executable line.
   * `CastPickerModule`, `MediaModule` and `DataModule` all carry the same shape.
   */
  @Module
  @InstallIn(SingletonComponent::class)
  interface Bindings {

    /**
     * **The one line that puts this whole feature in front of a user.**
     *
     * `@IntoSet` and not a reference from the settings screen: that screen must not know this class
     * exists, or removing Plan 7 would mean editing it. Deleting `feature/requests/` deletes this
     * binding with it, the multibound set gets smaller, and the settings screen loses a row without
     * noticing.
     */
    @Binds
    @IntoSet
    fun bindIntegrationsSection(section: IntegrationsSection): SettingsSection
  }
}
