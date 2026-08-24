package app.muplay.database

import app.muplay.database.entity.ArtistEntity
import app.muplay.model.Album
import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MirrorMapperTest {

  private fun album(
    id: String,
    name: String,
    artistId: String? = "artist-1",
    artistName: String? = "Test Artist",
    coverArtId: String? = "al-$id",
    libraryId: Int = 1,
  ) = Album(
    id = id,
    libraryId = libraryId,
    name = name,
    artistId = artistId,
    artistName = artistName,
    coverArtId = coverArtId,
    songCount = 3,
    durationSeconds = 15,
  )

  @Test
  fun `the sort key is case-insensitive and trimmed`() {
    assertThat(MirrorMapper.sortKey("  The Wall ")).isEqualTo("the wall")
    assertThat(MirrorMapper.sortKey("ABBA")).isEqualTo(MirrorMapper.sortKey("abba"))
  }

  @Test
  fun `the sort key deliberately keeps leading articles`() {
    // Navidrome has its own per-server `ignoredArticles` list ("The El La Los Las Le Les Os As O
    // A" on the pinned container) and this plan never fetches it. Stripping articles with a
    // hardcoded English list would sort a German or French library wrongly and silently, so the
    // honest choice is not to strip at all until the server's own list is read.
    assertThat(MirrorMapper.sortKey("The Wall")).isEqualTo("the wall")
  }

  @Test
  fun `an album maps to an entity with its stamped library id intact`() {
    val entity = MirrorMapper.albumEntity(album("a1", "Test Album", libraryId = 2))

    assertThat(entity.id).isEqualTo("a1")
    assertThat(entity.libraryId).isEqualTo(2)
    assertThat(entity.name).isEqualTo("Test Album")
    assertThat(entity.artistId).isEqualTo("artist-1")
    assertThat(entity.artistName).isEqualTo("Test Artist")
    assertThat(entity.sortName).isEqualTo("test album")
    assertThat(entity.songCount).isEqualTo(3)
    assertThat(entity.durationSeconds).isEqualTo(15)
  }

  @Test
  fun `a song round-trips through its entity unchanged`() {
    val song = Song(
      id = "s1",
      libraryId = 2,
      title = "Track 1",
      albumId = "a1",
      albumName = "Test Album",
      artistId = "artist-1",
      artistName = "Test Artist",
      trackNumber = 1,
      discNumber = null,
      durationSeconds = 5,
      suffix = "mp3",
      coverArtId = "al-a1_0",
    )

    assertThat(MirrorMapper.song(MirrorMapper.songEntity(song))).isEqualTo(song)
  }

  @Test
  fun `artists are derived from the albums of one library`() {
    val artists = MirrorMapper.artistEntities(
      listOf(
        album("a2", "Second", artistId = "artist-1", artistName = "Test Artist"),
        album("a1", "First", artistId = "artist-1", artistName = "Test Artist"),
        album("b1", "Other", artistId = "artist-2", artistName = "Other Artist"),
      ),
    )

    assertThat(artists.map { it.id }).containsExactlyInAnyOrder("artist-1", "artist-2")
    val first = artists.single { it.id == "artist-1" }
    assertThat(first.name).isEqualTo("Test Artist")
    assertThat(first.albumCount).isEqualTo(2)
    // Borrowed from the artist's first album by sort key ("first" < "second"), and documented as
    // derived -- AlbumID3 carries no artist image and this plan never calls getArtists.
    assertThat(first.coverArtId).isEqualTo("al-a1")
    assertThat(first.sortName).isEqualTo("test artist")
  }

  @Test
  fun `an album with no artist id contributes no artist but is not dropped`() {
    // A real possibility: `artistId` is optional on AlbumID3. Inventing an artist row keyed by
    // name would create a second "Various Artists" every time the name differed by a space.
    val artists = MirrorMapper.artistEntities(
      listOf(album("a1", "Orphan", artistId = null, artistName = null)),
    )

    assertThat(artists).isEmpty()
    assertThat(MirrorMapper.albumEntity(album("a1", "Orphan", artistId = null, artistName = null)).artistId)
      .isNull()
  }

  @Test
  fun `a derived artist takes its library id from its albums`() {
    val artists = MirrorMapper.artistEntities(listOf(album("a1", "First", libraryId = 9)))

    assertThat(artists.single().libraryId).isEqualTo(9)
  }

  @Test
  fun `an album with a null artist name still yields an artist when it has an id`() {
    // `artistId` present, `artist` absent is spec-legal. Falling back to the id keeps the row
    // addressable instead of producing a blank line in the artist list.
    val artists = MirrorMapper.artistEntities(
      listOf(album("a1", "First", artistId = "artist-1", artistName = null)),
    )

    assertThat(artists.single().name).isEqualTo("artist-1")
  }

  @Test
  fun `a derived artist's borrowed cover art skips albums that have none of their own`() {
    // `firstNotNullOfOrNull` has to keep looking past a null before it can borrow from the next
    // album -- every other test in this file gives every album a cover art id, which never
    // exercises that "keep looking" step at all (measured: 2 of 4 branches missed on this exact
    // line before this test existed).
    val artists = MirrorMapper.artistEntities(
      listOf(
        album("a1", "First", coverArtId = null),
        album("a2", "Second", coverArtId = "al-a2"),
      ),
    )

    assertThat(artists.single().coverArtId).isEqualTo("al-a2")
  }

  @Test
  fun `a derived artist has no cover art when none of its albums do`() {
    val artists = MirrorMapper.artistEntities(
      listOf(album("a1", "First", coverArtId = null)),
    )

    assertThat(artists.single().coverArtId).isNull()
  }

  // `MirrorMapper.album(entity)` and `.artist(entity)` -- the reverse direction `BrowseRepository`
  // uses to map every DAO row back to a domain model -- had no JVM test at all: `MirrorMapperTest`
  // exercised the forward direction and the `song` round trip, but nothing here ever called
  // either reverse function, so both measured 0/17 LINE from this file alone (only reachable
  // through `BrowseRepositoryTest`, an instrumented-only suite). The brief requires this class's
  // floor to stay JVM-measurable, so these two close that gap here rather than leaving it to the
  // emulator tier.

  @Test
  fun `an album round-trips through its entity unchanged`() {
    val original = album("a1", "Test Album")

    assertThat(MirrorMapper.album(MirrorMapper.albumEntity(original))).isEqualTo(original)
  }

  @Test
  fun `an artist entity maps to its domain model field by field`() {
    val entity = ArtistEntity(
      id = "artist-1",
      libraryId = 3,
      name = "Test Artist",
      coverArtId = "art-1",
      albumCount = 5,
      sortName = "test artist",
    )

    val artist = MirrorMapper.artist(entity)

    assertThat(artist.id).isEqualTo("artist-1")
    assertThat(artist.libraryId).isEqualTo(3)
    assertThat(artist.name).isEqualTo("Test Artist")
    assertThat(artist.coverArtId).isEqualTo("art-1")
    assertThat(artist.albumCount).isEqualTo(5)
  }
}
