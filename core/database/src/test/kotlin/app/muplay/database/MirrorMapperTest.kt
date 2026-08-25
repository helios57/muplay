package app.muplay.database

import app.muplay.database.entity.ArtistEntity
import app.muplay.model.Album
import app.muplay.model.ReplayGain
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
      replayGain = ReplayGain(trackGainDb = -6.5f, albumGainDb = -3.25f, peakAmplitude = 0.5f),
    )

    assertThat(MirrorMapper.song(MirrorMapper.songEntity(song))).isEqualTo(song)
    // The three gain columns are `null` on an entity by default, so a `songEntity` that simply
    // never set them still satisfies a round trip of an *untagged* song. Asserted on the entity's
    // own columns, at three values none of which is any other's, so no single hardcode reaches
    // all three -- and this is the first of two disjoint observations of each.
    val entity = MirrorMapper.songEntity(song)
    assertThat(entity.replayGainTrackDb).isEqualTo(-6.5f)
    assertThat(entity.replayGainAlbumDb).isEqualTo(-3.25f)
    assertThat(entity.replayGainPeak).isEqualTo(0.5f)
    // N2-3: sortTitle is write-only -- song(entity) never reads it back, so the round trip above
    // cannot see it at all. Before this line it was observed nowhere in the whole repo. First of
    // two disjoint observations.
    assertThat(MirrorMapper.songEntity(song).sortTitle).isEqualTo("track 1")
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
    // N-2/M: the only other test that asserts a derived artist's libraryId uses 9, so a
    // hardcoded "9" in artistEntities' `libraryId = ordered.first().libraryId` line would still
    // pass that test. This is the second, disjoint value (the default libraryId = 1) that makes
    // the hardcode fail somewhere.
    assertThat(first.libraryId).isEqualTo(1)
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
    val artists = MirrorMapper.artistEntities(
      listOf(album("a1", "First", artistName = "Solo Artist", libraryId = 9)),
    )

    assertThat(artists.single().libraryId).isEqualTo(9)
    // N-2/L: every other test in this file derives a two-album artist (albumCount = 2), so a
    // hardcoded "2" in `albumCount = ordered.size` would still pass them all. This one-album
    // fixture gives a second, disjoint observation.
    assertThat(artists.single().albumCount).isEqualTo(1)
    // N2-3: sortName is write-only -- artist(entity) never reads it back -- so no round trip can
    // reach it. Observed here at "solo artist", disjoint from "test artist" (the only other place
    // this field is asserted, in "artists are derived from the albums of one library" above) --
    // a hardcoded literal from either test would fail the other.
    assertThat(artists.single().sortName).isEqualTo("solo artist")
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

  // N-2/N-6: every forward and reverse function above was exercised by exactly one fixture, so
  // hardcoding any single field it assigns to that fixture's own literal value was undetectable
  // -- confirmed live by a review that mutated eight forward fields and four reverse fields, one
  // at a time, and found every one MISSED. `discNumber` was the worst of these: observed only as
  // `null` anywhere in this repo, on both tiers, so a `discNumber = null` hardcode in either
  // direction was invisible everywhere. The tests below each use a second fixture whose every
  // field differs from every other fixture's -- not just from the one it is paired with -- so a
  // hardcode to *any* literal already in use elsewhere in this file still fails here.

  @Test
  fun `a second song, every field disjoint from the first, still round-trips`() {
    val song = Song(
      id = "s2",
      libraryId = 4,
      title = "Chapter Seven",
      albumId = "a9",
      albumName = "Distant Album",
      artistId = "artist-9",
      artistName = "Someone Else",
      trackNumber = 7,
      discNumber = 2,
      durationSeconds = 777,
      suffix = "m4b",
      coverArtId = "cover-zzz",
      replayGain = ReplayGain(trackGainDb = 2.75f, albumGainDb = -11.5f, peakAmplitude = 0.875f),
    )

    assertThat(MirrorMapper.song(MirrorMapper.songEntity(song))).isEqualTo(song)
    // The second, disjoint observation of all three gain columns.
    val entity = MirrorMapper.songEntity(song)
    assertThat(entity.replayGainTrackDb).isEqualTo(2.75f)
    assertThat(entity.replayGainAlbumDb).isEqualTo(-11.5f)
    assertThat(entity.replayGainPeak).isEqualTo(0.875f)
    // N2-3: second, disjoint sortTitle observation.
    assertThat(MirrorMapper.songEntity(song).sortTitle).isEqualTo("chapter seven")
  }

  /**
   * The absence rule, both ways.
   *
   * An untagged file mirrors as three `null` columns and must come back as *no decision at all* --
   * not as a `ReplayGain(null, null, null)`, which is the same fact spelled as an object and would
   * make every caller ask two questions instead of one. It is also what keeps the round trip above
   * an identity for the untagged case, which is every file in a library nobody has tagged.
   */
  @Test
  fun `an untagged song round-trips as no replay gain rather than as an empty one`() {
    val untagged = song(replayGain = null)

    val entity = MirrorMapper.songEntity(untagged)

    assertThat(entity.replayGainTrackDb).isNull()
    assertThat(entity.replayGainAlbumDb).isNull()
    assertThat(entity.replayGainPeak).isNull()
    assertThat(MirrorMapper.song(entity)).isEqualTo(untagged)
    assertThat(MirrorMapper.song(entity).replayGain).isNull()
  }

  /**
   * A file tagged by an album-oriented tool: an album gain, no track gain. The reverse mapping's
   * guard is `both gains absent`, not `track gain absent`, and the difference between those two is
   * every album-tagged library there is.
   */
  @Test
  fun `a song with only an album gain still round-trips as a decision`() {
    val albumOnly = song(replayGain = ReplayGain(null, -7.5f, 0.6f))

    val roundTripped = MirrorMapper.song(MirrorMapper.songEntity(albumOnly))

    assertThat(roundTripped).isEqualTo(albumOnly)
    assertThat(checkNotNull(roundTripped.replayGain).albumGainDb).isEqualTo(-7.5f)
  }

  /** A gain of exactly zero is a decision the file made, and `0.0f` is not `null`. */
  @Test
  fun `a zero gain survives the mirror as a decision`() {
    val zeroed = song(replayGain = ReplayGain(0.0f, null, null))

    val roundTripped = MirrorMapper.song(MirrorMapper.songEntity(zeroed))

    assertThat(roundTripped).isEqualTo(zeroed)
    assertThat(checkNotNull(roundTripped.replayGain).trackGainDb).isEqualTo(0.0f)
  }

  private fun song(replayGain: ReplayGain?): Song = Song(
    id = "s-gain",
    libraryId = 3,
    title = "Quiet Track",
    albumId = "a-gain",
    albumName = "Gain Album",
    artistId = "artist-3",
    artistName = "Test Artist",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
    replayGain = replayGain,
  )

  @Test
  fun `a second album, every field disjoint from the first, still round-trips`() {
    val second = Album(
      id = "a-second",
      libraryId = 6,
      name = "A Completely Different Album",
      artistId = "artist-77",
      artistName = "Some Other Artist",
      coverArtId = "cover-second",
      songCount = 21,
      durationSeconds = 543,
    )

    assertThat(MirrorMapper.album(MirrorMapper.albumEntity(second))).isEqualTo(second)
    // N2-3: sortName is write-only -- album(entity) never reads it back, so the round trip above
    // cannot see it. Second, disjoint observation ("test album" is the only other one, in "an
    // album maps to an entity with its stamped library id intact").
    assertThat(MirrorMapper.albumEntity(second).sortName).isEqualTo("a completely different album")
  }

  @Test
  fun `a second artist entity, every field disjoint from the first, maps field by field`() {
    val entity = ArtistEntity(
      id = "artist-second",
      libraryId = 11,
      name = "Second Test Artist",
      coverArtId = "cover-second-artist",
      albumCount = 42,
      sortName = "second test artist",
    )

    val artist = MirrorMapper.artist(entity)

    assertThat(artist.id).isEqualTo("artist-second")
    assertThat(artist.libraryId).isEqualTo(11)
    assertThat(artist.name).isEqualTo("Second Test Artist")
    assertThat(artist.coverArtId).isEqualTo("cover-second-artist")
    assertThat(artist.albumCount).isEqualTo(42)
  }

  // N-9: the LIKE-pattern construction BrowseRepository.search used to build inline (trim, the
  // blank short-circuit, and %/_ escaping) moved here so it is JVM-testable rather than needing
  // an emulator and a decoy row to prove what it does. Every case below asserts the exact pattern
  // string, which is strictly stronger than the instrumented decoy-row tests it replaces: no
  // fixture pair can be accidentally non-discriminating when the assertion is the literal output.

  @Test
  fun `searchPattern wraps a plain query in wildcards`() {
    assertThat(MirrorMapper.searchPattern("beatles")).isEqualTo("%beatles%")
  }

  @Test
  fun `searchPattern trims surrounding whitespace before building the pattern`() {
    // N-7: dropping this trim was undetectable everywhere else in the repo -- the only
    // whitespace fixture in BrowseRepositoryTest was all-whitespace, whose pattern matches
    // nothing either way. A trailing space is what a soft keyboard inserts after autocomplete.
    assertThat(MirrorMapper.searchPattern("  beatles  ")).isEqualTo("%beatles%")
  }

  @Test
  fun `searchPattern is null for a blank query`() {
    assertThat(MirrorMapper.searchPattern("")).isNull()
    assertThat(MirrorMapper.searchPattern("   ")).isNull()
  }

  @Test
  fun `searchPattern escapes the caller's own percent and underscore`() {
    assertThat(MirrorMapper.searchPattern("50%")).isEqualTo("%50\\%%")
    assertThat(MirrorMapper.searchPattern("a_b")).isEqualTo("%a\\_b%")
  }

  @Test
  fun `searchPattern escapes a literal backslash before escaping percent or underscore`() {
    // Ordering matters: escaping "%" or "_" first would double-escape the backslash it just
    // inserted. A query containing a literal backslash is the case that catches the two
    // `.replace(...)` calls running in the wrong order.
    assertThat(MirrorMapper.searchPattern("a\\b")).isEqualTo("%a\\\\b%")
  }
}
