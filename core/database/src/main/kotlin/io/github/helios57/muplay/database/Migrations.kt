package io.github.helios57.muplay.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The project's first migration, and the reason `exportSchema = true` has been on since Plan 2
 * Task 1.
 *
 * **Every statement below is copied verbatim out of
 * `core/database/schemas/io.github.helios57.muplay.database.MuPlayDatabase/7.json`**, with the generator's own
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

/**
 * 7 -> 8: `songs.path`, and a cleared sync watermark.
 *
 * The column is what folder browsing reads. The `DELETE` is what makes it arrive.
 *
 * `SyncEngine` reconciles only when the server's `lastScan` differs from the watermark this
 * database last stored, so an upgraded install would keep a mirror of songs whose `path` is null
 * until the server happened to rescan -- which on a settled library may be months, or never. The
 * Folders screen would be empty, the library plainly is not, and nothing anywhere would report a
 * fault. Deleting the watermark makes `SyncDecision.decide(stored = null, ...)` return `Reconcile`
 * on the very next poll, which is the one and only thing that fills the new column.
 *
 * That costs one full re-fetch of the mirror per install, once. The mirror is a cache of the
 * server and a reconcile deletes and re-inserts it wholesale anyway; nothing durable lives there.
 * In particular `media_progress` is a different table that no reconcile touches, so no listener's
 * book position is at risk here -- which is the question to ask of any migration in this file.
 *
 * The `ALTER TABLE` is *not* copied from a generated `createSql`, because there is no such string
 * for a column addition: SQLite's `ADD COLUMN` has no counterpart in a `CREATE TABLE`. What
 * [MIGRATION_6_7]'s doc demands instead is that the result validate against
 * `core/database/schemas/io.github.helios57.muplay.database.MuPlayDatabase/8.json`, and
 * `MigrationTest`'s `runMigrationsAndValidate` is what checks it. `TEXT` with no `NOT NULL` and no
 * default is what Room generates for a `String?` with a Kotlin default of null; a `DEFAULT ''`
 * here would validate *and* be wrong, because `""` is a prefix that matches every path.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `songs` ADD COLUMN `path` TEXT")
    db.execSQL("DELETE FROM `sync_watermark`")
  }
}

/**
 * 8 -> 9: `songs.userRating`, and a cleared sync watermark.
 *
 * The column is what the thumbs read. The `DELETE` is what makes it arrive, for exactly the reason
 * [MIGRATION_7_8] gives -- `SyncEngine` reconciles only when the server's `lastScan` differs from
 * the stored watermark, so on a settled library an upgraded install would show every track as
 * unrated until the server happened to rescan, which may be never. Ratings the listener set in
 * another client would simply be invisible, and nothing would report a fault.
 *
 * Measured before writing this, rather than assumed: Navidrome returns `userRating` inline on the
 * `song` children of `getAlbum`, so the ordinary reconcile fills this column with no extra request.
 * That is the whole reason a rating can live in the mirror at all.
 *
 * `INTEGER NOT NULL DEFAULT 0` is what Room generates for an `Int` with a Kotlin default of `0`,
 * and 0 is `SongRating.Neutral` -- so an existing row's pre-migration state and "no thumb" are the
 * same value, which is the honest reading until the reconcile the `DELETE` forces has run.
 *
 * As with every migration in this file: `media_progress` is a different table that no reconcile
 * touches, so no listener's book position is at risk here.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `songs` ADD COLUMN `userRating` INTEGER NOT NULL DEFAULT 0")
    db.execSQL("DELETE FROM `sync_watermark`")
  }
}
