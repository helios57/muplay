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
 *
 * **Primary key is `id` alone, deliberately not composite with `libraryId` the way
 * [ArtistEntity]'s is.** That composite key exists because Navidrome artist ids are *global*
 * (confirmed live, see `ArtistEntity`'s own doc). Albums are the opposite by Navidrome's own
 * design: its `Album` and `MediaFile` Go structs each carry their own `LibraryID` field (unlike
 * `Artist`, which carries none), and Navidrome's own `scanner_multilibrary_test.go` asserts this
 * directly — the same artist (e.g. "Jeff Beck") sharing one global artist id across two libraries
 * still gets **two distinct album records**, one per library, even for identical artist content.
 * A bare `id` primary key is therefore correct here, and `observeSongs(albumId)` needing no
 * `libraryId` of its own is correct for the matching reason: an `albumId` cannot itself span two
 * libraries, so it is already as scoped as `albumId` can be. (Investigated for fix round 2 of
 * Task 5's review, source-only — not re-verified against the pinned container, whose two fixture
 * libraries share no artist/album/song content to observe this against directly.)
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
