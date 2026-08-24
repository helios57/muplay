package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.dao.BrowseDao
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.ArtistEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowseDaoTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: BrowseDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    dao = db.browseDao()
  }

  @After
  fun tearDown() = db.close()

  private fun artist(id: String, name: String, libraryId: Int) =
    ArtistEntity(id, libraryId, name, coverArtId = null, albumCount = 1, sortName = name.lowercase())

  private fun album(id: String, name: String, libraryId: Int, artistId: String? = "artist-1") =
    AlbumEntity(id, libraryId, artistId, name, "Test Artist", null, 1, 5, name.lowercase())

  private fun song(id: String, title: String, libraryId: Int, albumId: String, track: Int?, disc: Int? = null) =
    SongEntity(id, libraryId, albumId, "artist-1", title, "Test Album", "Test Artist", track, disc, 5, "mp3", null, title.lowercase())

  private suspend fun seedTwoLibraries() {
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = listOf(artist("artist-1", "Test Artist", 1)),
      albums = listOf(album("album-1", "Test Album", 1)),
      songs = listOf(
        song("song-2", "Track 2", 1, "album-1", 2),
        song("song-1", "Track 1", 1, "album-1", 1),
      ),
    )
    dao.replaceLibraryContents(
      libraryId = 2,
      artists = listOf(artist("artist-2", "Test Author", 2)),
      albums = listOf(album("album-2", "Test Book", 2, artistId = "artist-2")),
      songs = listOf(song("song-3", "Test Book", 2, "album-2", null)),
    )
  }

  @Test
  fun everyBrowseQueryIsScopedToOneLibrary() = runTest {
    seedTwoLibraries()

    // The assertion the whole application rests on, at the storage layer: asking for library 1
    // returns nothing from library 2. Everything above this -- browse, search, shuffle -- is only
    // as scoped as this is.
    assertThat(dao.observeAlbums(1).first().map { it.name }).containsExactly("Test Album")
    assertThat(dao.observeAlbums(2).first().map { it.name }).containsExactly("Test Book")
    assertThat(dao.observeArtists(1).first().map { it.name }).containsExactly("Test Artist")
    assertThat(dao.observeArtists(2).first().map { it.name }).containsExactly("Test Author")
  }

  @Test
  fun songsComeBackInDiscThenTrackOrder() = runTest {
    // Every song fixture elsewhere in this file hardcodes discNumber = null, which makes
    // COALESCE(discNumber, 0) the constant 0 for every row and leaves only track order proven --
    // this test's own name promised more than the old fixture could observe. Three songs across
    // two discs, inserted in an order that disagrees with the wanted result on both terms: disc 2
    // track 1 must sort after disc 1 track 2, and disc 1's own two tracks must sort by track
    // number within the disc.
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = emptyList(),
      songs = listOf(
        song("song-d2t1", "Disc 2 Track 1", 1, "album-1", track = 1, disc = 2),
        song("song-d1t2", "Disc 1 Track 2", 1, "album-1", track = 2, disc = 1),
        song("song-d1t1", "Disc 1 Track 1", 1, "album-1", track = 1, disc = 1),
      ),
    )

    assertThat(dao.observeSongs("album-1").first().map { it.id })
      .containsExactly("song-d1t1", "song-d1t2", "song-d2t1")
  }

  /**
   * The tiebreak `observeSongs`'s `ORDER BY` falls back to when disc and track both agree --
   * `COALESCE(trackNumber, 0)` ties at 0 for two songs with no track number at all (a common shape
   * for audiobook chapters split by title alone), and only `sortTitle` then decides the order.
   */
  @Test
  fun observeSongsFallsBackToSortTitleWhenDiscAndTrackTie() = runTest {
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = emptyList(),
      songs = listOf(
        song("song-zeta", "Zeta Chapter", 1, "album-1", track = null, disc = null),
        song("song-alpha", "Alpha Chapter", 1, "album-1", track = null, disc = null),
      ),
    )

    assertThat(dao.observeSongs("album-1").first().map { it.id })
      .containsExactly("song-alpha", "song-zeta")
  }

  @Test
  fun replacingALibraryRemovesWhatTheServerNoLongerHas() = runTest {
    // Two artists and two albums up front -- not seedTwoLibraries()'s one of each -- so
    // artistsBefore/albumsBefore can differ from artistsAfter/albumsAfter and every one of
    // MirrorReplacement's six counters is actually exercised at two distinct values below.
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = listOf(artist("artist-1", "Test Artist", 1), artist("artist-ghost", "Ghost Artist", 1)),
      albums = listOf(album("album-1", "Test Album", 1), album("album-ghost", "Ghost Album", 1)),
      songs = listOf(
        song("song-2", "Track 2", 1, "album-1", 2),
        song("song-1", "Track 1", 1, "album-1", 1),
      ),
    )

    // The case a delta protocol cannot express at all: Subsonic never reports deletions, so the
    // only way to notice one is to replace the library wholesale with what the server just said.
    val result = dao.replaceLibraryContents(
      libraryId = 1,
      artists = listOf(artist("artist-1", "Test Artist", 1)),
      albums = listOf(album("album-1", "Test Album", 1)),
      songs = listOf(song("song-1", "Track 1", 1, "album-1", 1)),
    )

    assertThat(result.artistsBefore).isEqualTo(2)
    assertThat(result.artistsAfter).isEqualTo(1)
    assertThat(result.albumsBefore).isEqualTo(2)
    assertThat(result.albumsAfter).isEqualTo(1)
    assertThat(result.songsBefore).isEqualTo(2)
    assertThat(result.songsAfter).isEqualTo(1)
    // The counters could still lie about what actually landed in the tables -- checked directly,
    // not only through MirrorReplacement's own numbers. Ghost rows are gone entirely, which is
    // what catches a `deleteArtists` call deleted outright: that mutation leaves artist-ghost
    // sitting in the table forever, present in this list and in artistsAfter alike.
    assertThat(dao.observeArtists(1).first().map { it.id }).containsExactly("artist-1")
    assertThat(dao.observeAlbums(1).first().map { it.id }).containsExactly("album-1")
    assertThat(dao.observeSongs("album-1").first().map { it.id }).containsExactly("song-1")
  }

  @Test
  fun replaceLibraryContentsReportsAfterCountsMatchingWhatWasWritten() = runTest {
    val artists = listOf(artist("a1", "Artist One", 3), artist("a2", "Artist Two", 3))
    val albums = listOf(album("al1", "Album One", 3), album("al2", "Album Two", 3))
    val songs = listOf(song("s1", "Song One", 3, "al1", 1), song("s2", "Song Two", 3, "al1", 2))

    val result = dao.replaceLibraryContents(libraryId = 3, artists = artists, albums = albums, songs = songs)

    assertThat(result.artistsAfter).isEqualTo(artists.size)
    assertThat(result.albumsAfter).isEqualTo(albums.size)
    assertThat(result.songsAfter).isEqualTo(songs.size)
  }

  /**
   * N-5: `replaceLibraryContents`'s `libraryId` parameter scopes the deletes and the counts, but
   * the inserts write whatever `libraryId` each entity already carries -- nothing about Room or
   * SQLite checks the two agree. Demonstrated live during review: a mismatched batch silently
   * wrote rows into the wrong library, made the shuffle scope guard affirm an audiobook was a
   * music track, and reported that nothing had been written. This is the failure the mirror's
   * whole `libraryId` design exists to prevent, so it must be rejected loudly, before anything is
   * written -- not merely logged or silently corrected.
   */
  @Test
  fun replaceLibraryContentsRejectsARowScopedToADifferentLibrary() = runTest {
    val thrown = runCatching {
      dao.replaceLibraryContents(
        libraryId = 2,
        artists = listOf(artist("leaked-artist", "Leaked Artist", 1)),
        albums = listOf(album("leaked-album", "Leaked Album", 1)),
        songs = listOf(song("leaked-song", "Leaked Song", 1, "leaked-album", 1)),
      )
    }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    // Rejected before anything was written -- not a partial leak that happened to be rolled back.
    assertThat(dao.observeArtists(1).first()).isEmpty()
    assertThat(dao.observeAlbums(1).first()).isEmpty()
    assertThat(dao.observeArtists(2).first()).isEmpty()
    assertThat(dao.observeAlbums(2).first()).isEmpty()
  }

  @Test
  fun replaceLibraryContentsRejectsAMismatchOnAnyOneOfTheThreeLists() = runTest {
    // Each of the three require() checks is its own line; a mutation that deleted only one of
    // them would still pass the test above if it happened to target a different list. One
    // mismatched batch per list, each alone otherwise valid, proves all three fire independently.
    assertThat(
      runCatching {
        dao.replaceLibraryContents(1, listOf(artist("x", "X", 2)), emptyList(), emptyList())
      }.exceptionOrNull(),
    ).isInstanceOf(IllegalArgumentException::class.java)

    assertThat(
      runCatching {
        dao.replaceLibraryContents(1, emptyList(), listOf(album("x", "X", 2)), emptyList())
      }.exceptionOrNull(),
    ).isInstanceOf(IllegalArgumentException::class.java)

    assertThat(
      runCatching {
        dao.replaceLibraryContents(1, emptyList(), emptyList(), listOf(song("x", "X", 2, "al1", 1)))
      }.exceptionOrNull(),
    ).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun replacingOneLibraryLeavesTheOtherAlone() = runTest {
    seedTwoLibraries()

    dao.replaceLibraryContents(1, emptyList(), emptyList(), emptyList())

    assertThat(dao.observeAlbums(1).first()).isEmpty()
    assertThat(dao.observeArtists(1).first()).isEmpty()
    assertThat(dao.observeAlbums(2).first().map { it.name }).containsExactly("Test Book")
    assertThat(dao.observeArtists(2).first().map { it.name }).containsExactly("Test Author")
    assertThat(dao.observeSongs("album-2").first()).hasSize(1)
  }

  /**
   * The three album/artist/song lists a reconcile deletes are scoped by `libraryId` alone, so a
   * batch with **no** rows at all is a legitimate call (a library that genuinely emptied out on
   * the server) and must not throw. `all {}` on an empty list is vacuously true, which is why
   * this is its own test rather than trusted as a side effect of the other `require` tests above.
   */
  @Test
  fun replaceLibraryContentsAcceptsAWhollyEmptyBatch() = runTest {
    seedTwoLibraries()

    val result = dao.replaceLibraryContents(1, emptyList(), emptyList(), emptyList())

    assertThat(result.artistsAfter).isEqualTo(0)
    assertThat(dao.observeArtists(1).first()).isEmpty()
  }

  /**
   * Spec section 3, as an assertion: the queue is a list of pointers and progress is a property
   * of the item. A reconcile wipes and re-inserts the whole song table for a library; if that
   * could take a listener's position with it, the resume feature would silently break on the
   * first server rescan — which is exactly when nobody would connect the two events.
   */
  @Test
  fun reconcilingTheMirrorDoesNotTouchPlaybackProgress() = runTest {
    seedTwoLibraries()
    db.mediaProgressDao().upsert(
      MediaProgressEntity("song-3", 900_000L, false, 1_000L, 1.0f, false, 0f),
    )

    dao.replaceLibraryContents(2, emptyList(), emptyList(), emptyList())

    assertThat(db.mediaProgressDao().find("song-3")!!.positionMs).isEqualTo(900_000L)
  }

  @Test
  fun searchIsScopedAndCaseInsensitive() = runTest {
    seedTwoLibraries()

    assertThat(dao.searchSongs(1, "%track%", 10).map { it.title })
      .containsExactlyInAnyOrder("Track 1", "Track 2")
    assertThat(dao.searchSongs(2, "%track%", 10)).isEmpty()
    assertThat(dao.searchAlbums(1, "%album%", 10).map { it.name }).containsExactly("Test Album")
    assertThat(dao.searchArtists(2, "%author%", 10).map { it.name }).containsExactly("Test Author")
  }

  @Test
  fun searchRespectsItsLimit() = runTest {
    seedTwoLibraries()

    assertThat(dao.searchSongs(1, "%track%", 1)).hasSize(1)
  }

  /**
   * `searchIsScopedAndCaseInsensitive` above deliberately uses `containsExactlyInAnyOrder` for
   * its one two-row case, so none of the three search queries' `ORDER BY` was ever pinned. Three
   * matching rows each, inserted out of sort order, `containsExactly` this time.
   */
  @Test
  fun searchSongsOrdersBySortTitle() = runTest {
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = emptyList(),
      songs = listOf(
        song("song-zeta", "Zeta Track", 1, "album-1", 1),
        song("song-mango", "Mango Track", 1, "album-1", 2),
        song("song-alpha", "Alpha Track", 1, "album-1", 3),
      ),
    )

    assertThat(dao.searchSongs(1, "%track%", 10).map { it.id })
      .containsExactly("song-alpha", "song-mango", "song-zeta")
  }

  @Test
  fun searchArtistsOrdersBySortName() = runTest {
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = listOf(
        artist("artist-zeta", "Zeta Match", 1),
        artist("artist-mango", "Mango Match", 1),
        artist("artist-alpha", "Alpha Match", 1),
      ),
      albums = emptyList(),
      songs = emptyList(),
    )

    assertThat(dao.searchArtists(1, "%match%", 10).map { it.id })
      .containsExactly("artist-alpha", "artist-mango", "artist-zeta")
  }

  @Test
  fun searchAlbumsOrdersBySortName() = runTest {
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(
        album("album-zeta", "Zeta Match", 1),
        album("album-mango", "Mango Match", 1),
        album("album-alpha", "Alpha Match", 1),
      ),
      songs = emptyList(),
    )

    assertThat(dao.searchAlbums(1, "%match%", 10).map { it.id })
      .containsExactly("album-alpha", "album-mango", "album-zeta")
  }

  /**
   * The query that backs the shuffle scope guard. Given a set of song ids the server just
   * returned, it answers "which of these does the mirror agree are in this library" — which is
   * how a scope leak is caught locally even if the server's own scoping ever fails.
   */
  @Test
  fun songIdsInLibraryFiltersOutForeignAndUnknownIds() = runTest {
    seedTwoLibraries()

    val kept = dao.songIdsInLibrary(1, listOf("song-1", "song-3", "does-not-exist"))

    assertThat(kept).containsExactly("song-1")
  }

  /**
   * N-8: Navidrome artist ids are **global**, unlike `albumId` -- the same artist id genuinely
   * appears in two different libraries whenever the same person has both a music and an audiobook
   * credit. The previous version of this test seeded two *different* artists ("artist-1",
   * "artist-2") in the two libraries, so the crossing it is named for was unobservable by
   * construction: `observeAlbumsByArtist` had no `libraryId` to leak across in the first place.
   * The **same** artist id in both libraries is what makes the boundary real.
   */
  @Test
  fun observingAlbumsByArtistCrossesNoLibraryBoundary() = runTest {
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = listOf(artist("shared-artist", "Shared Artist", 1)),
      albums = listOf(album("music-album", "Music Album", 1, artistId = "shared-artist")),
      songs = emptyList(),
    )
    dao.replaceLibraryContents(
      libraryId = 2,
      artists = listOf(artist("shared-artist", "Shared Artist", 2)),
      albums = listOf(album("audiobook-album", "Audiobook", 2, artistId = "shared-artist")),
      songs = emptyList(),
    )

    // Before the libraryId parameter: this call returned BOTH albums, so tapping the artist from
    // library 1's Artists tab listed the audiobook alongside the music.
    assertThat(dao.observeAlbumsByArtist(1, "shared-artist").first().map { it.name })
      .containsExactly("Music Album")
    assertThat(dao.observeAlbumsByArtist(2, "shared-artist").first().map { it.name })
      .containsExactly("Audiobook")
    // Before the composite (id, libraryId) primary key: reconciling library 2 re-stamped this
    // row's libraryId to 2 via INSERT ... REPLACE, so it vanished from library 1's Artists tab
    // even though library 1's own albums for it were untouched.
    assertThat(dao.observeArtists(1).first().map { it.name }).containsExactly("Shared Artist")
    assertThat(dao.observeArtists(2).first().map { it.name }).containsExactly("Shared Artist")
  }

  @Test
  fun findAlbumReturnsNullForAnUnknownId() = runTest {
    seedTwoLibraries()

    assertThat(dao.findAlbum("nope")).isNull()
    assertThat(dao.findAlbum("album-1")!!.name).isEqualTo("Test Album")
  }

  /**
   * `seedTwoLibraries()` above never puts more than one artist in a library, so
   * `observeArtists`'s `ORDER BY sortName` was unobservable by construction -- deleting the
   * clause would change nothing a test above could see. Three artists, inserted in an order that
   * disagrees with their sort order ("Zeta" first, "Alpha" last), makes the ordering an actual
   * property under test: `containsExactly` fails if the rows come back in insertion order, in
   * reverse, or in any order but the sorted one.
   */
  @Test
  fun observeArtistsOrdersBySortNameNotInsertionOrder() = runTest {
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = listOf(
        artist("artist-zeta", "Zeta Artist", 1),
        artist("artist-mango", "Mango Artist", 1),
        artist("artist-alpha", "Alpha Artist", 1),
      ),
      albums = emptyList(),
      songs = emptyList(),
    )

    assertThat(dao.observeArtists(1).first().map { it.name })
      .containsExactly("Alpha Artist", "Mango Artist", "Zeta Artist")
  }

  /**
   * The same defect, one query over: `observeAlbums` puts exactly one album in each library
   * everywhere else in this file, so `ORDER BY sortName` was never exercised with more than one
   * row. Three albums in one library, inserted out of sort order.
   */
  @Test
  fun observeAlbumsOrdersBySortNameNotInsertionOrder() = runTest {
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(
        album("album-zeta", "Zeta Album", 1),
        album("album-mango", "Mango Album", 1),
        album("album-alpha", "Alpha Album", 1),
      ),
      songs = emptyList(),
    )

    assertThat(dao.observeAlbums(1).first().map { it.name })
      .containsExactly("Alpha Album", "Mango Album", "Zeta Album")
  }

  /**
   * The same defect, one query over: `observeAlbumsByArtist` filters on `artistId`, so its
   * `ORDER BY sortName` needs more than one album *for the same artist* to be observable at all.
   * Three albums for one artist, inserted out of sort order, so `containsExactly` genuinely pins
   * the order rather than restating a single-element list.
   */
  @Test
  fun observeAlbumsByArtistOrdersBySortNameNotInsertionOrder() = runTest {
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = listOf(artist("artist-1", "Test Artist", 1)),
      albums = listOf(
        album("album-zebra", "Zebra Album", 1, artistId = "artist-1"),
        album("album-mango", "Mango Album", 1, artistId = "artist-1"),
        album("album-apple", "Apple Album", 1, artistId = "artist-1"),
      ),
      songs = emptyList(),
    )

    assertThat(dao.observeAlbumsByArtist(1, "artist-1").first().map { it.name })
      .containsExactly("Apple Album", "Mango Album", "Zebra Album")
  }
}
