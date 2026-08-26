package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One chapter atom of one file, cached so it is parsed once rather than fetched over HTTP on every
 * screen open.
 *
 * The primary key is `(mediaId, chapterIndex)`, so a re-store of the same file overwrites rather
 * than duplicating, and `chapterIndex` is what every read orders by. SQLite promises nothing about
 * row order without an `ORDER BY`, and on a four-row table it very often *happens* to return
 * insertion order -- which is exactly how a missing `ORDER BY` ships and how a book ends up
 * playing its epilogue third.
 *
 * The foreign key onto `chapter_scans` makes forgetting a file one delete. Room requires an index
 * on a foreign key's child column and warns rather than failing when it is missing, so it is
 * declared explicitly here.
 */
@Entity(
  tableName = "chapters",
  primaryKeys = ["mediaId", "chapterIndex"],
  foreignKeys = [
    ForeignKey(
      entity = ChapterScanEntity::class,
      parentColumns = ["mediaId"],
      childColumns = ["mediaId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("mediaId")],
)
data class ChapterEntity(
  val mediaId: String,
  val chapterIndex: Int,
  val startMs: Long,
  val endMs: Long,
  val title: String?,
)
