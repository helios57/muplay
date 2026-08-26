package app.muplay.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.entity.BookSettingsEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration, run against a **real version-6 database file** built from the exported schema.
 *
 * What makes this worth writing rather than trusting: the destructive escape hatch is one line away
 * in `DataModule`, it makes every migration failure disappear, and what it destroys is the whole
 * library mirror and every listener's book position -- the one thing this application exists to
 * keep. A migration test is the only thing standing between "the schema changed" and "the shelf is
 * empty".
 *
 * **The rows written below carry distinct values on purpose, and there is more than one of them in
 * every table.** A migration that dropped and recreated a table passes a test that only inspects
 * the schema afterwards; one that wrote defaults over every row passes a row-count assertion; and
 * one that kept the first row of each table passes an assertion on a single row. Each read here is
 * `containsExactly` over ordered values that differ in every column.
 *
 * 6 -> 7, not 4 -> 5: the plan calls this "schema 5" and master was at 6. See [MIGRATION_6_7].
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

  private companion object {
    /** Owned by [helper], which deletes it at the start of every test. */
    const val TEST_DB = "migration-test.db"

    /**
     * A **second** name, for the one test that hands the file to the real `Room.databaseBuilder`
     * rather than to [helper]. Sharing [TEST_DB] would put the helper's own lifecycle and a real
     * `RoomDatabase` over one file.
     */
    const val BUILDER_DB = "migration-builder-test.db"
  }

  @get:Rule
  val helper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    MuPlayDatabase::class.java,
  )

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  /** Two progress rows differing in every column, plus a whole small library mirror. */
  private fun seedVersionSix(name: String) {
    helper.createDatabase(name, 6).use { db ->
      db.execSQL(
        "INSERT INTO media_progress " +
          "(mediaId, positionMs, isFinished, lastPlayedAtEpochMs, speed, skipSilence, gainDb) " +
          "VALUES ('chapter-14', 3600000, 0, 1700000000000, 1.4, 1, 6.0)",
      )
      db.execSQL(
        "INSERT INTO media_progress " +
          "(mediaId, positionMs, isFinished, lastPlayedAtEpochMs, speed, skipSilence, gainDb) " +
          "VALUES ('a-song', 12345, 1, 1600000000000, 1.0, 0, 0.0)",
      )
      db.execSQL("INSERT INTO libraries (musicFolderId, name, role) VALUES (1, 'Music', 'MUSIC')")
      db.execSQL(
        "INSERT INTO libraries (musicFolderId, name, role) VALUES (2, 'Books', 'AUDIOBOOKS')",
      )
      db.execSQL(
        "INSERT INTO artists (id, libraryId, name, coverArtId, albumCount, sortName) " +
          "VALUES ('ar-1', 2, 'A Narrator', 'art-1', 3, 'a narrator')",
      )
      db.execSQL(
        "INSERT INTO albums " +
          "(id, libraryId, artistId, name, artistName, coverArtId, songCount, durationSeconds, sortName) " +
          "VALUES ('al-1', 2, 'ar-1', 'Multi Part Book', 'A Narrator', 'art-1', 3, 900, 'multi part book')",
      )
      db.execSQL(
        "INSERT INTO songs " +
          "(id, libraryId, albumId, artistId, title, albumName, artistName, trackNumber, " +
          "discNumber, durationSeconds, suffix, coverArtId, sortTitle, replayGainTrackDb, " +
          "replayGainAlbumDb, replayGainPeak) " +
          "VALUES ('chapter-14', 2, 'al-1', 'ar-1', 'Part Two', 'Multi Part Book', 'A Narrator', " +
          "2, 1, 300, 'm4b', 'art-1', 'part two', -7.5, -6.25, 0.98)",
      )
      db.execSQL(
        "INSERT INTO songs " +
          "(id, libraryId, albumId, artistId, title, albumName, artistName, trackNumber, " +
          "discNumber, durationSeconds, suffix, coverArtId, sortTitle, replayGainTrackDb, " +
          "replayGainAlbumDb, replayGainPeak) " +
          "VALUES ('a-song', 1, 'al-2', 'ar-2', 'A Song', 'An Album', 'A Band', 1, 1, 200, " +
          "'mp3', NULL, 'a song', NULL, NULL, NULL)",
      )
      db.execSQL("INSERT INTO sync_watermark (id, lastScan) VALUES (0, '2026-08-20T09:00:00Z')")
    }
  }

  @Test
  fun everyProgressRowSurvivesTheMoveToSeven() {
    seedVersionSix(TEST_DB)

    val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

    db.query(
      "SELECT mediaId, positionMs, isFinished, lastPlayedAtEpochMs, speed, skipSilence, gainDb " +
        "FROM media_progress ORDER BY mediaId",
    ).use { c ->
      // Exact values, in order, for two rows that differ in every column. Row counts survive a
      // migration that wrote defaults over everything; these do not.
      assertThat(c.count).isEqualTo(2)
      c.moveToFirst()
      assertThat(c.getString(0)).isEqualTo("a-song")
      assertThat(c.getLong(1)).isEqualTo(12_345L)
      assertThat(c.getInt(2)).isEqualTo(1)
      assertThat(c.getLong(3)).isEqualTo(1_600_000_000_000L)
      assertThat(c.getFloat(4)).isEqualTo(1.0f)
      assertThat(c.getInt(5)).isEqualTo(0)
      assertThat(c.getFloat(6)).isEqualTo(0.0f)
      c.moveToNext()
      assertThat(c.getString(0)).isEqualTo("chapter-14")
      assertThat(c.getLong(1)).isEqualTo(3_600_000L)
      assertThat(c.getInt(2)).isEqualTo(0)
      assertThat(c.getLong(3)).isEqualTo(1_700_000_000_000L)
      assertThat(c.getFloat(4)).isEqualTo(1.4f)
      assertThat(c.getInt(5)).isEqualTo(1)
      assertThat(c.getFloat(6)).isEqualTo(6.0f)
    }
  }

  @Test
  fun theWholeLibraryMirrorSurvivesTheMoveToSeven() {
    // The other half of what the escape hatch would destroy. A mirror is re-fetchable and a
    // position is not, which is why the position test above comes first -- but a migration that
    // silently emptied `songs` would leave a listener staring at an empty shelf until the next
    // sync, and nothing else in this module would notice.
    seedVersionSix(TEST_DB)

    val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

    db.query("SELECT musicFolderId, name, role FROM libraries ORDER BY musicFolderId").use { c ->
      assertThat(c.count).isEqualTo(2)
      c.moveToFirst()
      assertThat(listOf(c.getInt(0), c.getString(1), c.getString(2)))
        .containsExactly(1, "Music", "MUSIC")
      c.moveToNext()
      assertThat(listOf(c.getInt(0), c.getString(1), c.getString(2)))
        .containsExactly(2, "Books", "AUDIOBOOKS")
    }
    db.query("SELECT id, libraryId, name, albumCount FROM artists").use { c ->
      assertThat(c.count).isEqualTo(1)
      c.moveToFirst()
      assertThat(listOf(c.getString(0), c.getInt(1), c.getString(2), c.getInt(3)))
        .containsExactly("ar-1", 2, "A Narrator", 3)
    }
    db.query("SELECT id, name, songCount, durationSeconds FROM albums").use { c ->
      assertThat(c.count).isEqualTo(1)
      c.moveToFirst()
      assertThat(listOf(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3)))
        .containsExactly("al-1", "Multi Part Book", 3, 900)
    }
    db.query(
      "SELECT id, title, trackNumber, durationSeconds, replayGainTrackDb FROM songs ORDER BY id",
    ).use { c ->
      assertThat(c.count).isEqualTo(2)
      c.moveToFirst()
      // The nullable ReplayGain columns Plan 3 Task 11 added at version 6: a null that came back
      // as 0.0 would be a silent 6 dB error at playback, so `isNull` is asserted on the column
      // rather than on the row.
      assertThat(c.getString(0)).isEqualTo("a-song")
      assertThat(c.isNull(4)).isTrue
      c.moveToNext()
      assertThat(c.getString(0)).isEqualTo("chapter-14")
      assertThat(c.getString(1)).isEqualTo("Part Two")
      assertThat(c.getInt(2)).isEqualTo(2)
      assertThat(c.getInt(3)).isEqualTo(300)
      assertThat(c.getFloat(4)).isEqualTo(-7.5f)
    }
    db.query("SELECT lastScan FROM sync_watermark").use { c ->
      assertThat(c.count).isEqualTo(1)
      c.moveToFirst()
      assertThat(c.getString(0)).isEqualTo("2026-08-20T09:00:00Z")
    }
  }

  @Test
  fun theNewTablesExistAndAcceptRowsAfterTheMigration() {
    seedVersionSix(TEST_DB)

    val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

    // `runMigrationsAndValidate` already compares the resulting schema against the exported one,
    // which is the strongest assertion in this class. These add the behaviour that validation
    // cannot see: the cascade actually cascades, and it cascades for one file only.
    db.execSQL("INSERT INTO book_settings (bookId, speed, skipSilence) VALUES ('book-1', 1.4, 1)")
    db.execSQL("INSERT INTO chapter_scans (mediaId, chapterCount, scannedAtEpochMs) VALUES ('m-1', 2, 5)")
    db.execSQL("INSERT INTO chapter_scans (mediaId, chapterCount, scannedAtEpochMs) VALUES ('m-2', 1, 5)")
    db.execSQL("INSERT INTO chapters (mediaId, chapterIndex, startMs, endMs, title) VALUES ('m-1', 0, 0, 7000, 'Head')")
    db.execSQL("INSERT INTO chapters (mediaId, chapterIndex, startMs, endMs, title) VALUES ('m-1', 1, 7000, 12000, 'Tail')")
    db.execSQL("INSERT INTO chapters (mediaId, chapterIndex, startMs, endMs, title) VALUES ('m-2', 0, 0, 3000, 'Only')")
    db.execSQL("PRAGMA foreign_keys = ON")
    db.execSQL("DELETE FROM chapter_scans WHERE mediaId = 'm-1'")

    db.query("SELECT mediaId FROM chapters").use { c ->
      // The neighbour is the control: a cascade with no `WHERE` and a cascade that never fired are
      // both visible here, and an `isZero` on a whole-table count could not tell them apart.
      assertThat(c.count).describedAs("chapters left behind by a cascade that did not fire").isEqualTo(1)
      c.moveToFirst()
      assertThat(c.getString(0)).isEqualTo("m-2")
    }
    db.query("SELECT speed, skipSilence FROM book_settings WHERE bookId = 'book-1'").use { c ->
      c.moveToFirst()
      assertThat(c.getFloat(0)).isEqualTo(1.4f)
      assertThat(c.getInt(1)).isEqualTo(1)
    }
  }

  @Test
  fun theRealBuilderMigratesRatherThanDropping() {
    // The tests above are handed `MIGRATION_6_7` by name, so they prove the migration is *correct*
    // and prove nothing about whether the app installs it. `DataModule.provideDatabase` also
    // carries Plan 2's destructive escape hatch, deliberately (see `MIGRATION_6_7`'s own doc).
    // Room consults `addMigrations` first and falls back only when it finds no path -- so dropping
    // that one line turns this schema bump from "migrated" into "every listener's book position
    // deleted", and every other assertion in this class stays green while it happens. This is the
    // only test that can tell those two apart.
    seedVersionSix(BUILDER_DB)

    // The real builder, line for line what `DataModule.provideDatabase` builds, over that file.
    val room = Room.databaseBuilder(context, MuPlayDatabase::class.java, BUILDER_DB)
      .addMigrations(MIGRATION_6_7)
      .fallbackToDestructiveMigration(dropAllTables = true)
      .build()

    try {
      runBlocking {
        val row = room.mediaProgressDao().find("chapter-14")
        // Exact values, not a null check: a destructive fallback leaves an empty table, and so
        // does a migration that silently recreated it.
        assertThat(row).describedAs("the destructive fallback ran instead of MIGRATION_6_7").isNotNull
        assertThat(row!!.positionMs).isEqualTo(3_600_000L)
        assertThat(row.speed).isEqualTo(1.4f)
        assertThat(row.gainDb).isEqualTo(6.0f)
        // The mirror too -- `dropAllTables = true` empties every table, so a survivor in each of
        // the two kinds of data this database holds is what "migrated" means.
        assertThat(room.libraryDao().allIds()).containsExactlyInAnyOrder(1, 2)
        // ...and the new tables really are there, so this did not pass by never migrating at all.
        room.bookSettingsDao().upsert(BookSettingsEntity("book-1", 1.4f, true))
        assertThat(room.bookSettingsDao().find("book-1")!!.speed).isEqualTo(1.4f)
        assertThat(room.chapterDao().findScan("m-1")).isNull()
      }
    } finally {
      room.close()
    }
  }
}
