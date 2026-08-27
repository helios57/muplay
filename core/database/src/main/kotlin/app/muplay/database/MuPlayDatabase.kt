package app.muplay.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.muplay.database.dao.BookSettingsDao
import app.muplay.database.dao.BrowseDao
import app.muplay.database.dao.ChapterDao
import app.muplay.database.dao.LibraryDao
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.dao.SyncWatermarkDao
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.ArtistEntity
import app.muplay.database.entity.BookSettingsEntity
import app.muplay.database.entity.ChapterEntity
import app.muplay.database.entity.ChapterScanEntity
import app.muplay.database.entity.LibraryEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import app.muplay.database.entity.SyncWatermarkEntity

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
 * never exported. Task 6 takes it to version 5 by adding `sync_watermark`, the sync engine's
 * single-row `lastScan` checkpoint. Plan 3 Task 11 takes it to version 6 by adding three nullable
 * `REAL` columns to `songs` -- the file's own ReplayGain, mirrored because the player needs it
 * before a track has ever been played and `media_progress` therefore cannot carry it.
 *
 * (The brief for that task said "version 4 -> 5". It was written before Task 6's `sync_watermark`
 * landed; master was already at 5. The number a version bump moves *from* is a measurement, not a
 * plan value -- read the file. Plan 4 Task 2 met the same thing again and this paragraph is why it
 * cost nothing: its brief said "schema 5", master was at 6, and the migration is named for the
 * measurement.)
 *
 * **Plan 4 Task 2 takes it to version 7 -- `book_settings`, `chapter_scans` and `chapters` -- and
 * is the first version bump in this project with a real [MIGRATION_6_7] behind it.** Every earlier
 * step still relies on the escape hatch in `DataModule`, which is why that hatch is still there
 * and why removing it is not this task's to do; see `DESTRUCTIVE_MIGRATION_EXEMPTION.md` for the
 * list of what is still owed. From here on, a task that adds or reshapes a table writes a
 * `Migration` and commits the schema JSON alongside it -- there is now one to copy.
 */
@Database(
  entities = [
    MediaProgressEntity::class,
    LibraryEntity::class,
    ArtistEntity::class,
    AlbumEntity::class,
    SongEntity::class,
    SyncWatermarkEntity::class,
    BookSettingsEntity::class,
    ChapterScanEntity::class,
    ChapterEntity::class,
  ],
  version = 7,
  exportSchema = true,
)
abstract class MuPlayDatabase : RoomDatabase() {

  abstract fun mediaProgressDao(): MediaProgressDao

  abstract fun libraryDao(): LibraryDao

  abstract fun browseDao(): BrowseDao

  abstract fun syncWatermarkDao(): SyncWatermarkDao

  abstract fun bookSettingsDao(): BookSettingsDao

  abstract fun chapterDao(): ChapterDao

  companion object {
    const val DATABASE_NAME = "muplay.db"
  }
}
