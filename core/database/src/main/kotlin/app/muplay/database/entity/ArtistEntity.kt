package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mirrored artist, **derived from the albums of one library** rather than fetched.
 *
 * `getArtists` and `getIndexes` are the two commands the spec says never to use to enforce a
 * scope, and `AlbumID3` already carries `artistId` and `artist` — so the artist list is a
 * `groupBy` over albums this client already has, with no extra request and no scoping hazard.
 *
 * [coverArtId] is therefore **borrowed** from the artist's first album by [sortName]: no artist
 * image is available without `getArtist`/`getArtistInfo2`. It is a real cover of the right
 * artist, not a placeholder.
 *
 * [libraryId] is the stamped scope of the request that produced the albums. The same artist
 * appearing in two libraries produces two rows only if the server gives them different ids; if it
 * gives them the same id, the later reconcile wins, which is correct for a browse mirror and is
 * why this table is never the source of truth for anything but display.
 */
@Entity(
  tableName = "artists",
  indices = [Index("libraryId"), Index("sortName")],
)
data class ArtistEntity(
  @PrimaryKey val id: String,
  val libraryId: Int,
  val name: String,
  val coverArtId: String?,
  val albumCount: Int,
  val sortName: String,
)
