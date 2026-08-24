package app.muplay.database.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import app.muplay.database.MuPlayDatabase
import app.muplay.database.dao.LibraryDao
import app.muplay.database.dao.MediaProgressDao
import app.muplay.network.DefaultSubsonicSourceFactory
import app.muplay.network.SubsonicSourceFactory
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

}
