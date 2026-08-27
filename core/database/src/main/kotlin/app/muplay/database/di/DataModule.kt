package app.muplay.database.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import app.muplay.database.AudiobookRepository
import app.muplay.database.Bookshelf
import app.muplay.database.CastPreferences
import app.muplay.database.LibraryRepository
import app.muplay.database.MIGRATION_6_7
import app.muplay.database.MuPlayDatabase
import app.muplay.database.SubsonicSourceProvider
import app.muplay.database.SyncEngine
import app.muplay.database.dao.AudiobookDao
import app.muplay.database.dao.BookSettingsDao
import app.muplay.database.dao.BrowseDao
import app.muplay.database.dao.ChapterDao
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
import java.time.Clock
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
      // Consulted FIRST, and that ordering is the whole point: Room looks for a migration path
      // before it considers the escape hatch below, so a device carrying the version-6 database
      // is migrated rather than emptied. Without this line the very next line silently deletes
      // every listener's book position -- the one thing this application exists to keep -- and no
      // migration test that is handed `MIGRATION_6_7` by name can see it happen.
      // `MigrationTest.theRealBuilderMigratesRatherThanDropping` is the one that can.
      .addMigrations(MIGRATION_6_7)
      // Pre-release only, and still needed. Versions 1 through 6 have no `Migration` between them
      // -- Plan 2 Tasks 4, 5 and 6 and Plan 3 Task 11 each bumped `version` and wrote none -- so a
      // developer's device (and the emulator that runs the required Tier 2 gate) must be allowed
      // to throw its mirror away and re-sync rather than refuse to open at all. Deleting this line
      // today does not make anything safer: those versions move from "dropped" to
      // `IllegalStateException: A migration from 2 to 7 was required but not found`.
      //
      // THIS LINE MUST BE REMOVED BEFORE THE FIRST RELEASE, and replaced with real `Migration`
      // objects verified against the exported schema JSON in `core/database/schemas/`. Shipping
      // it means every future schema change silently deletes a user's `media_progress` — every
      // audiobook position they have. `MIGRATION_6_7` is the first of those objects and the
      // pattern for the rest; `DESTRUCTIVE_MIGRATION_EXEMPTION.md` lists what is still owed.
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
  fun provideBookSettingsDao(database: MuPlayDatabase): BookSettingsDao = database.bookSettingsDao()

  @Provides
  fun provideChapterDao(database: MuPlayDatabase): ChapterDao = database.chapterDao()

  @Provides
  fun provideAudiobookDao(database: MuPlayDatabase): AudiobookDao = database.audiobookDao()

  /**
   * Global constraint: *"Inject a `Clock`; no direct wall-clock reads outside the injection
   * point."* This is that injection point.
   *
   * Declared **here**, in the lowest module that consumes it, rather than in `:core:media` where
   * Plan 3 Task 8 first needed it. `:core:media` depends on this module, so this binding is visible
   * to `ProgressWriter` and to every later consumer up there, while the reverse placement leaves
   * `AudiobookRepository` -- the first class in this module to take a `Clock` -- depending on a
   * binding declared above it. That resolves in `:app`, where one `SingletonComponent` is assembled
   * from every `@InstallIn` module on the classpath, so it looks like a non-problem; it is not one
   * below `:app`, where a `@HiltAndroidTest` in this module has no `MediaModule` on its classpath at
   * all. Moved by Plan 4 Task 4; `MediaModule` carries a note where it used to be.
   *
   * `java.time.Clock`, not `kotlinx-datetime`: `java.time` is native at `minSdk 26`,
   * `MediaProgressEntity.lastPlayedAtEpochMs` is already an epoch-millis `Long`, and a datetime
   * library plus a Room type converter would be bought for nothing.
   *
   * Unqualified, and there must stay exactly one of those: `IntegrationsDataModule` provides a
   * second `Clock` behind `@IntegrationsClock` because it is genuinely a different clock, and a
   * second *unqualified* one is a Hilt duplicate-binding failure.
   */
  @Provides
  @Singleton
  fun provideClock(): Clock = Clock.systemUTC()

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
     * Repointed by Plan 4 Task 4, which is the whole of the change Plan 5 Task 4 designed this seam
     * for: `MirrorBookshelf` and its `BookProgress` are deleted, [AudiobookRepository] answers the
     * same four questions from the same tables, and no browse decision moved. `@Singleton` here
     * matches the implementation's own scope rather than adding one, so the bookshelf the tree reads
     * and the repository a screen injects are one object and one cache.
     */
    @Binds
    @Singleton
    fun bindBookshelf(impl: AudiobookRepository): Bookshelf
  }
}
