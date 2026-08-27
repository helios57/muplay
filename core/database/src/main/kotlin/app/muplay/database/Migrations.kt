package app.muplay.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The project's first migration, and the reason `exportSchema = true` has been on since Plan 2
 * Task 1.
 *
 * **Every statement below is copied verbatim out of
 * `core/database/schemas/app.muplay.database.MuPlayDatabase/7.json`**, with the generator's own
 * table-name placeholder replaced by the literal table name. That is not a stylistic preference:
 * Room verifies at open time that the database a migration produced is identical to the schema it
 * generated -- column order, type affinity, `NOT NULL`, defaults, index names, all of it -- and
 * reports any difference as `Migration didn't properly handle` with a diff that is genuinely hard
 * to read. SQL written from memory gets this wrong roughly always.
 *
 * (Measured for the record: the three `createSql` strings the generator emitted for version 7
 * matched the plan's hand-written literals character for character, including the space before
 * the closing parenthesis of the foreign key clause. That is a happy accident of a simple schema,
 * not a licence to skip the copy -- the next migration will alter a table rather than add three.)
 *
 * **6 -> 7, not 4 -> 5.** The plan calls this "schema 5"; master was already at 6 when this task
 * began, because Plan 2 Task 6 added `sync_watermark` and Plan 3 Task 11 added three ReplayGain
 * columns to `songs`. The number a migration is named for is a measurement -- see
 * [MuPlayDatabase]'s own doc, which records the same correction being made a second time.
 *
 * An `@AutoMigration(from = 6, to = 7)` would also work here, and would be less code. It is
 * deliberately not used: an auto-migration is not reviewable, and this migration's job is to be
 * the one everyone reads before writing the second one.
 *
 * **This does not retire the escape hatch in `DataModule`.** Versions 1 through 6 still have no
 * `Migration` between them, so a device holding any of those still needs it; deleting the call
 * would turn "dropped" into `IllegalStateException: A migration from 2 to 7 was required but not
 * found`, which is not safer. `DESTRUCTIVE_MIGRATION_EXEMPTION.md` holds the list of what is still
 * owed, and `MigrationTest.theRealBuilderMigratesRatherThanDropping` is the test that proves the
 * builder consults this object *first* -- which is the entire reason a listener's positions
 * survive this bump.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `book_settings` (" +
        "`bookId` TEXT NOT NULL, `speed` REAL NOT NULL, `skipSilence` INTEGER NOT NULL, " +
        "PRIMARY KEY(`bookId`))",
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `chapter_scans` (" +
        "`mediaId` TEXT NOT NULL, `chapterCount` INTEGER NOT NULL, " +
        "`scannedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))",
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `chapters` (" +
        "`mediaId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, " +
        "`endMs` INTEGER NOT NULL, `title` TEXT, PRIMARY KEY(`mediaId`, `chapterIndex`), " +
        "FOREIGN KEY(`mediaId`) REFERENCES `chapter_scans`(`mediaId`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE )",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_mediaId` ON `chapters` (`mediaId`)")
  }
}
