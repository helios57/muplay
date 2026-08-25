package app.muplay.database.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import app.muplay.database.Bookshelf
import app.muplay.database.CastPreferences
import app.muplay.database.LibraryRepository
import app.muplay.database.MirrorBookshelf
import app.muplay.database.MuPlayDatabase
import app.muplay.database.SubsonicSourceProvider
import app.muplay.database.SyncEngine
import app.muplay.database.dao.BrowseDao
import app.muplay.database.dao.LibraryDao
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.dao.SyncWatermarkDao
import app.muplay.network.DefaultSubsonicSourceFactory
import app.muplay.network.SubsonicClient
import app.muplay.network.SubsonicSourceFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The one place the data layer's object graph is described. Every repository in this module
 * takes its collaborators through an `@Inject constructor`, so this module only has to provide
 * the things that are not themselves constructor-injectable: the Room database (built from the
 * application `Context`) and the DAOs it hands out.
 *
 * This is the module that gives Hilt its first real participants — see Task 1's ruling in the
 * plan for why it earns its place now rather than coming out.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): MuPlayDatabase =
    Room.databaseBuilder(context, MuPlayDatabase::class.java, MuPlayDatabase.DATABASE_NAME)
      // Pre-release only. Every task in this plan that adds a table bumps `version` and writes no
      // migration, so a developer's device (and the emulator that runs the required Tier 2 gate)
      // must be allowed to throw its mirror away and re-sync — the mirror is a cache of the
      // server, and re-fetching it costs one sync.
      //
      // THIS LINE MUST BE REMOVED BEFORE THE FIRST RELEASE, and replaced with real `Migration`
      // objects verified against the exported schema JSON in `core/database/schemas/`. Shipping
      // it means every future schema change silently deletes a user's `media_progress` — every
      // audiobook position they have.
      .fallbackToDestructiveMigration(dropAllTables = true)
      .build()

  @Provides
  fun provideMediaProgressDao(database: MuPlayDatabase): MediaProgressDao =
    database.mediaProgressDao()

  @Provides
  fun provideLibraryDao(database: MuPlayDatabase): LibraryDao = database.libraryDao()

  @Provides
  fun provideBrowseDao(database: MuPlayDatabase): BrowseDao = database.browseDao()

  @Provides
  fun provideSyncWatermarkDao(database: MuPlayDatabase): SyncWatermarkDao =
    database.syncWatermarkDao()

  @Provides
  @Singleton
  fun provideSyncEngine(
    libraryRepository: LibraryRepository,
    browseDao: BrowseDao,
    watermarkDao: SyncWatermarkDao,
    sourceProvider: SubsonicSourceProvider,
  ): SyncEngine = SyncEngine(
    libraryRepository = libraryRepository,
    browseDao = browseDao,
    watermarkDao = watermarkDao,
    sourceProvider = sourceProvider,
    albumPageSize = SubsonicClient.MAX_ALBUM_LIST_PAGE,
  )

  /**
   * `:core:network` is a plain Kotlin/JVM module with no Hilt and no Android dependency, and it
   * stays that way — this is where its factory enters the graph.
   */
  @Provides
  fun provideSubsonicSourceFactory(): SubsonicSourceFactory = DefaultSubsonicSourceFactory

  /**
   * One DataStore instance per process for this file. DataStore throws
   * `IllegalStateException: There are multiple DataStores active for the same file` if a second
   * one is created for the same path, so this being `@Singleton` is a correctness requirement,
   * not a performance choice.
   */
  @Provides
  @Singleton
  fun provideCredentialDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create {
      File(context.filesDir, "credentials.preferences_pb")
    }

  /**
   * The cast subsystem's own DataStore, over its own **file**.
   *
   * Not the unqualified binding above, and the difference is not cosmetic: that one holds the
   * Navidrome password and `CredentialStore.clear()` empties the whole file on sign-out. A
   * `RendererStore` reading from it would forget every remembered speaker when a user signed out,
   * and the symptom -- an empty "not answering" list -- reads as a discovery bug. See
   * `CastPreferences`' own documentation for why a qualifier alone would not have been enough.
   *
   * `@Singleton` for the same correctness reason as the provider above: DataStore refuses a second
   * instance over one file.
   */
  @Provides
  @Singleton
  @CastPreferences
  fun provideCastDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create {
      File(context.filesDir, "cast.preferences_pb")
    }

  /**
   * The interface bindings this layer contributes.
   *
   * A nested `interface` rather than a `@Provides fun bind(impl: X): X = impl` on the object above,
   * for a coverage reason measured on this project: an `@Binds` method is `abstract` and compiles
   * to no executable line at all, whereas the `@Provides` form adds one line to [DataModule] that
   * only Hilt's own graph can execute -- which would quietly drag that class's floor below its
   * minimum on JVM-only data. `MediaModule` carries the same nested-interface shape for the same
   * reason.
   */
  @Module
  @InstallIn(SingletonComponent::class)
  interface Bindings {

    /**
     * Plan 5 Task 4's temporary bookshelf. **This is the one line Plan 4 Task 4 repoints** when its
     * `AudiobookRepository` lands -- see [Bookshelf]'s own provenance note.
     */
    @Binds
    @Singleton
    fun bindBookshelf(impl: MirrorBookshelf): Bookshelf
  }
}
