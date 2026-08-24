package app.muplay.database

import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.ArtistEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.Song

/**
 * Domain models to mirror rows and back, plus the artist derivation.
 *
 * Deliberately a plain object with pure functions and no injected collaborators: it is the one
 * piece of this module's logic that needs no SQLite, so it is unit-tested on the JVM in Tier 1
 * rather than waiting 45 minutes for an emulator to say the same thing.
 */
object MirrorMapper {

  /**
   * The key rows are ordered by: trimmed and lower-cased, nothing else.
   *
   * Leading articles are **not** stripped. Navidrome publishes its own `ignoredArticles` list per
   * server ("The El La Los Las Le Les Os As O A" on the pinned container) and this plan never
   * fetches it; a hardcoded English list would mis-sort a German or French library silently,
   * which is worse than sorting "The Wall" under T.
   */
  fun sortKey(value: String): String = value.trim().lowercase()

  /**
   * The `LIKE` pattern for [query], or `null` if [query] is blank after trimming -- the caller's
   * signal to skip the DAO entirely rather than run a pattern that would match every row.
   *
   * The user's own `%` and `_` are escaped with a backslash so a query containing them matches
   * those characters literally instead of turning into a wildcard the user did not type; the
   * three `BrowseDao` search queries pair this with `ESCAPE '\\'`, without which the escaping
   * here is inert.
   */
  fun searchPattern(query: String): String? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null
    return "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
  }

  fun albumEntity(album: Album): AlbumEntity = AlbumEntity(
    id = album.id,
    libraryId = album.libraryId,
    artistId = album.artistId,
    name = album.name,
    artistName = album.artistName,
    coverArtId = album.coverArtId,
    songCount = album.songCount,
    durationSeconds = album.durationSeconds,
    sortName = sortKey(album.name),
  )

  fun album(entity: AlbumEntity): Album = Album(
    id = entity.id,
    libraryId = entity.libraryId,
    name = entity.name,
    artistId = entity.artistId,
    artistName = entity.artistName,
    coverArtId = entity.coverArtId,
    songCount = entity.songCount,
    durationSeconds = entity.durationSeconds,
  )

  fun songEntity(song: Song): SongEntity = SongEntity(
    id = song.id,
    libraryId = song.libraryId,
    albumId = song.albumId,
    artistId = song.artistId,
    title = song.title,
    albumName = song.albumName,
    artistName = song.artistName,
    trackNumber = song.trackNumber,
    discNumber = song.discNumber,
    durationSeconds = song.durationSeconds,
    suffix = song.suffix,
    coverArtId = song.coverArtId,
    sortTitle = sortKey(song.title),
  )

  fun song(entity: SongEntity): Song = Song(
    id = entity.id,
    libraryId = entity.libraryId,
    title = entity.title,
    albumId = entity.albumId,
    albumName = entity.albumName,
    artistId = entity.artistId,
    artistName = entity.artistName,
    trackNumber = entity.trackNumber,
    discNumber = entity.discNumber,
    durationSeconds = entity.durationSeconds,
    suffix = entity.suffix,
    coverArtId = entity.coverArtId,
  )

  fun artist(entity: ArtistEntity): Artist = Artist(
    id = entity.id,
    libraryId = entity.libraryId,
    name = entity.name,
    coverArtId = entity.coverArtId,
    albumCount = entity.albumCount,
  )

  /**
   * The artist rows implied by [albums]. Albums with no `artistId` contribute nothing — inventing
   * an artist keyed by name would create a second "Various Artists" the moment a name differed by
   * a space — but they are still stored as albums by the caller.
   */
  fun artistEntities(albums: List<Album>): List<ArtistEntity> =
    albums.filter { it.artistId != null }
      .groupBy { it.artistId!! }
      .map { (artistId, artistAlbums) ->
        val ordered = artistAlbums.sortedBy { sortKey(it.name) }
        val name = ordered.firstNotNullOfOrNull { it.artistName } ?: artistId
        ArtistEntity(
          id = artistId,
          libraryId = ordered.first().libraryId,
          name = name,
          coverArtId = ordered.firstNotNullOfOrNull { it.coverArtId },
          albumCount = ordered.size,
          sortName = sortKey(name),
        )
      }
}
