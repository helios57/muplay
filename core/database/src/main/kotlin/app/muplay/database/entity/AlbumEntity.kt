package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mirrored album. [libraryId] came from the `musicFolderId` of the request that fetched it —
 * no Subsonic response carries a library id — and every browse query filters on it.
 *
 * No foreign key to `artists`: the artist rows are derived from these albums, so a foreign key
 * would make insertion order load-bearing inside the reconcile transaction for no benefit. Room
 * would also then require the delete order to be exactly right, turning a data-quality question
 * into a constraint-violation crash.
 */
@Entity(
  tableName = "albums",
  indices = [Index("libraryId"), Index("artistId"), Index("sortName")],
)
data class AlbumEntity(
  @PrimaryKey val id: String,
  val libraryId: Int,
  val artistId: String?,
  val name: String,
  val artistName: String?,
  val coverArtId: String?,
  val songCount: Int,
  val durationSeconds: Int,
  val sortName: String,
)
