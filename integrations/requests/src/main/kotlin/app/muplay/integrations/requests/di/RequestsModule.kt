package app.muplay.integrations.requests.di

import app.muplay.database.BrowseRepository
import app.muplay.database.LibraryRepository
import app.muplay.database.SyncEngine
import app.muplay.integrations.IntegrationCredentialStore
import app.muplay.integrations.requests.AlbumSearch
import app.muplay.integrations.requests.ConfiguredServices
import app.muplay.integrations.requests.LibraryRoles
import app.muplay.integrations.requests.MirrorSync
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds this module's four ports to the real collaborators behind them.
 *
 * Every binding is one method reference wide, which is the whole point: the ports exist so a test
 * can make a specific call fail at a specific point, not to add a layer. Anything more than a
 * forward here would be logic that only the production graph ever runs.
 *
 * The three mirror bindings are **read-only** by construction — `syncIfStale`, `search` and
 * `idsWithRole` are the only three methods of Plan 2's data layer this plan can reach at all.
 */
@Module
@InstallIn(SingletonComponent::class)
object RequestsModule {

  @Provides
  @Singleton
  fun provideMirrorSync(engine: SyncEngine): MirrorSync = MirrorSync { engine.syncIfStale() }

  @Provides
  @Singleton
  fun provideAlbumSearch(browse: BrowseRepository): AlbumSearch =
    AlbumSearch { libraryId, query, limit -> browse.search(libraryId, query, limit) }

  @Provides
  @Singleton
  fun provideLibraryRoles(libraries: LibraryRepository): LibraryRoles =
    LibraryRoles { role -> libraries.idsWithRole(role) }

  @Provides
  @Singleton
  fun provideConfiguredServices(store: IntegrationCredentialStore): ConfiguredServices =
    ConfiguredServices { store.configured }
}
