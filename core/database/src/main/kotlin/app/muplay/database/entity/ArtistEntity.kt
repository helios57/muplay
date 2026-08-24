package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.Index

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
 * [libraryId] is the stamped scope of the request that produced the albums. The primary key is
 * **`(id, libraryId)` together, not `id` alone** — Navidrome artist ids are global, so the same
 * artist genuinely does appear with the same id in two different libraries whenever the same
 * person has both a music and an audiobook credit. A bare `id` primary key made `INSERT ...
 * REPLACE` **move** that artist's row between libraries on every reconcile instead of each
 * library holding its own: whichever library synced last silently emptied the artist out of the
 * other library's Artists tab. The composite key makes the two rows independent, so reconciling
 * library 2 can never remove library 1's copy, and `observeAlbumsByArtist` additionally takes
 * `libraryId` for the same reason `artistId` alone cannot scope by library the way `albumId` can.
 */
@Entity(
  tableName = "artists",
  primaryKeys = ["id", "libraryId"],
  indices = [Index("libraryId"), Index("sortName")],
)
data class ArtistEntity(
  val id: String,
  val libraryId: Int,
  val name: String,
  val coverArtId: String?,
  val albumCount: Int,
  val sortName: String,
)
