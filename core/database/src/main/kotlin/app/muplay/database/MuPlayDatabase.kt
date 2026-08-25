package app.muplay.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.muplay.database.dao.BrowseDao
import app.muplay.database.dao.LibraryDao
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.ArtistEntity
import app.muplay.database.entity.LibraryEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity

/**
 * Version 1 through Task 3; Task 4 took it to version 2 by adding `libraries`; Task 5 took it to
 * version 3 by adding the library mirror (`artists`, `albums`, `songs`), then to version 4 in its
 * own fix round 2 when the `artists` primary key changed shape *inside* v3 (N2-1): a bare
 * `fallbackToDestructiveMigration` does not rescue an identity-hash mismatch at an *unchanged*
 * version number — verified on-device, it throws `IllegalStateException: Room cannot verify the
 * data integrity` instead of dropping and recreating the tables — so any change to an already-
 * exported schema needs its own version bump even pre-release, not just a new table. Nothing has
 * shipped, so every task in this plan that adds or reshapes a table bumps `version` and updates
 * this list rather than writing a migration. `exportSchema = true` (with the schema directory
 * wired up by `muplay.android.room`) is what makes the *first* post-release migration verifiable
 * — a migration test needs the previous schema JSON, and there is no way to recover one that was
 * never exported. Task 5 leaves this at version 4; Task 6 takes it to whatever comes next.
 */
@Database(
  entities = [
    MediaProgressEntity::class,
    LibraryEntity::class,
    ArtistEntity::class,
    AlbumEntity::class,
    SongEntity::class,
  ],
  version = 4,
  exportSchema = true,
)
abstract class MuPlayDatabase : RoomDatabase() {

  abstract fun mediaProgressDao(): MediaProgressDao

  abstract fun libraryDao(): LibraryDao

  abstract fun browseDao(): BrowseDao

  companion object {
    const val DATABASE_NAME = "muplay.db"
  }
}
