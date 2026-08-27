package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.muplay.database.entity.ChapterEntity
import app.muplay.database.entity.ChapterScanEntity

/**
 * The chapter cache: what a probe of one file's `moov` atom found, and the record that the probe
 * happened at all.
 *
 * `abstract class`, not `interface`, because [store] is a `@Transaction` with a body. Same shape as
 * Plan 2's `BrowseDao.replaceLibraryContents`.
 */
@Dao
abstract class ChapterDao {

  /**
   * Records one probe of one file: the scan marker first (the chapters' foreign-key parent), the
   * previous chapters gone, then the new ones.
   *
   * Ordering inside the transaction matters. Inserting chapters before the scan row violates the
   * foreign key; deleting after inserting removes what was just written.
   *
   * The scan row is written **unconditionally**, including for a file that turned out to have no
   * chapters at all. That is the whole point of `chapter_scans`: an empty `find` is ambiguous
   * between "no chapters" and "never looked", and the ambiguous reading re-probes the common case
   * over HTTP forever.
   */
  @Transaction
  open suspend fun store(mediaId: String, chapters: List<ChapterEntity>, scannedAtEpochMs: Long) {
    upsertScan(
      ChapterScanEntity(
        mediaId = mediaId,
        chapterCount = chapters.size,
        scannedAtEpochMs = scannedAtEpochMs,
      ),
    )
    deleteChapters(mediaId)
    insertChapters(chapters)
  }

  @Query("SELECT * FROM chapters WHERE mediaId = :mediaId ORDER BY chapterIndex ASC")
  abstract suspend fun find(mediaId: String): List<ChapterEntity>

  @Query("SELECT * FROM chapter_scans WHERE mediaId = :mediaId")
  abstract suspend fun findScan(mediaId: String): ChapterScanEntity?

  /** Cascades to `chapters`. */
  @Query("DELETE FROM chapter_scans WHERE mediaId = :mediaId")
  abstract suspend fun clear(mediaId: String)

  @Upsert
  protected abstract suspend fun upsertScan(scan: ChapterScanEntity)

  @Query("DELETE FROM chapters WHERE mediaId = :mediaId")
  protected abstract suspend fun deleteChapters(mediaId: String)

  @Insert
  protected abstract suspend fun insertChapters(chapters: List<ChapterEntity>)
}
