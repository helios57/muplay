package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The record that a file's chapters were *looked for*, whatever was found.
 *
 * Existence of this row, not the emptiness of `chapters`, is what makes "this file has no
 * chapters" a remembered answer. Most audiobook files in the world carry no chapter atoms at all
 * -- the corpus's own `Multi Part Book` is three of them -- so without this row the common case is
 * an HTTP round trip into the file's `moov` atom every time a screen opens.
 */
@Entity(tableName = "chapter_scans")
data class ChapterScanEntity(
  @PrimaryKey val mediaId: String,
  val chapterCount: Int,
  val scannedAtEpochMs: Long,
)
