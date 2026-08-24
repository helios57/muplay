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

  private fun song(id: String, title: String, libraryId: Int, albumId: String, track: Int?) =
    SongEntity(id, libraryId, albumId, "artist-1", title, "Test Album", "Test Artist", track, null, 5, "mp3", null, title.lowercase())

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
    seedTwoLibraries()

    assertThat(dao.observeSongs("album-1").first().map { it.title })
      .containsExactly("Track 1", "Track 2")
  }

  @Test
  fun replacingALibraryRemovesWhatTheServerNoLongerHas() = runTest {
    seedTwoLibraries()

    // The case a delta protocol cannot express at all: Subsonic never reports deletions, so the
    // only way to notice one is to replace the library wholesale with what the server just said.
    val result = dao.replaceLibraryContents(
      libraryId = 1,
      artists = listOf(artist("artist-1", "Test Artist", 1)),
      albums = listOf(album("album-1", "Test Album", 1)),
      songs = listOf(song("song-1", "Track 1", 1, "album-1", 1)),
    )

    assertThat(dao.observeSongs("album-1").first().map { it.id }).containsExactly("song-1")
    assertThat(result.songsBefore).isEqualTo(2)
    assertThat(result.songsAfter).isEqualTo(1)
  }

  @Test
  fun replacingOneLibraryLeavesTheOtherAlone() = runTest {
    seedTwoLibraries()

    dao.replaceLibraryContents(1, emptyList(), emptyList(), emptyList())

    assertThat(dao.observeAlbums(1).first()).isEmpty()
    assertThat(dao.observeAlbums(2).first().map { it.name }).containsExactly("Test Book")
    assertThat(dao.observeSongs("album-2").first()).hasSize(1)
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

  @Test
  fun observingAlbumsByArtistCrossesNoLibraryBoundary() = runTest {
    seedTwoLibraries()

    assertThat(dao.observeAlbumsByArtist("artist-2").first().map { it.name })
      .containsExactly("Test Book")
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

    assertThat(dao.observeAlbumsByArtist("artist-1").first().map { it.name })
      .containsExactly("Apple Album", "Mango Album", "Zebra Album")
  }
}
