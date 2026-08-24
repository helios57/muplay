package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.dao.BrowseDao
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.ArtistEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not in the brief. `BrowseRepository`'s seven public methods are each a straight-line delegation
 * to `BrowseDao` (or, for [BrowseRepository.coverArtUrl], to `SubsonicSourceProvider`) plus a
 * `MirrorMapper` call -- exactly the shape Task 4's `LibraryRepository` had two live defects in: a
 * delegating method that discards its argument and hardcodes a value passes every test that never
 * observes the argument at more than one value.
 *
 * Every test below varies **exactly one** argument of the method it targets and holds every other
 * argument (and every other row in the mirror) fixed, so the only way the assertion can pass is if
 * that argument genuinely reached the DAO (or the source). The real, in-memory-Room `BrowseDao` is
 * used rather than a hand-written fake -- `BrowseDaoTest` already establishes that the DAO itself
 * discriminates correctly on every argument, so reusing it here isolates what this file is
 * actually testing: `BrowseRepository`'s own delegation, not SQLite's.
 */
@RunWith(AndroidJUnit4::class)
class BrowseRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: BrowseDao
  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private lateinit var source: FakeSubsonicSource
  private lateinit var repository: BrowseRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    dao = db.browseDao()
    file = File(context.filesDir, "browse-repo-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    credentialStore = CredentialStore(dataStore)
    source = FakeSubsonicSource()
    repository = BrowseRepository(
      browseDao = dao,
      sourceProvider = SubsonicSourceProvider(
        credentialStore = credentialStore,
        factory = SubsonicSourceFactory { source },
      ),
    )
  }

  @After
  fun tearDown() = runTest {
    credentialStore.clear()
    file.delete()
    db.close()
  }

  private fun artist(id: String, name: String, libraryId: Int) =
    ArtistEntity(id, libraryId, name, coverArtId = null, albumCount = 1, sortName = name.lowercase())

  private fun album(id: String, name: String, libraryId: Int, artistId: String? = "artist-1") =
    AlbumEntity(id, libraryId, artistId, name, "Test Artist", null, 1, 5, name.lowercase())

  private fun song(id: String, title: String, libraryId: Int, albumId: String?, track: Int? = 1) =
    SongEntity(id, libraryId, albumId, "artist-1", title, "Test Album", "Test Artist", track, null, 5, "mp3", null, title.lowercase())

  private suspend fun signIn() =
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))

  // ---- artists(libraryId) --------------------------------------------------------------------

  @Test
  fun artistsPassesTheLibraryIdThroughToTheDao() = runTest {
    dao.replaceLibraryContents(1, listOf(artist("a1", "Library One Artist", 1)), emptyList(), emptyList())
    dao.replaceLibraryContents(2, listOf(artist("a2", "Library Two Artist", 2)), emptyList(), emptyList())

    assertThat(repository.artists(1).first().map { it.name }).containsExactly("Library One Artist")
    assertThat(repository.artists(2).first().map { it.name }).containsExactly("Library Two Artist")
  }

  // ---- albums(libraryId) ---------------------------------------------------------------------

  @Test
  fun albumsPassesTheLibraryIdThroughToTheDao() = runTest {
    dao.replaceLibraryContents(1, emptyList(), listOf(album("al1", "Library One Album", 1)), emptyList())
    dao.replaceLibraryContents(2, emptyList(), listOf(album("al2", "Library Two Album", 2)), emptyList())

    assertThat(repository.albums(1).first().map { it.name }).containsExactly("Library One Album")
    assertThat(repository.albums(2).first().map { it.name }).containsExactly("Library Two Album")
  }

  // ---- albumsByArtist(artistId) ---------------------------------------------------------------

  @Test
  fun albumsByArtistPassesTheArtistIdThroughToTheDao() = runTest {
    dao.replaceLibraryContents(
      1,
      emptyList(),
      listOf(
        album("al1", "First Artist's Album", 1, artistId = "artist-a"),
        album("al2", "Second Artist's Album", 1, artistId = "artist-b"),
      ),
      emptyList(),
    )

    assertThat(repository.albumsByArtist("artist-a").first().map { it.name })
      .containsExactly("First Artist's Album")
    assertThat(repository.albumsByArtist("artist-b").first().map { it.name })
      .containsExactly("Second Artist's Album")
  }

  // ---- songs(albumId) ------------------------------------------------------------------------

  @Test
  fun songsPassesTheAlbumIdThroughToTheDao() = runTest {
    dao.replaceLibraryContents(
      1,
      emptyList(),
      emptyList(),
      listOf(
        song("s1", "First Album's Song", 1, albumId = "al1"),
        song("s2", "Second Album's Song", 1, albumId = "al2"),
      ),
    )

    assertThat(repository.songs("al1").first().map { it.title }).containsExactly("First Album's Song")
    assertThat(repository.songs("al2").first().map { it.title }).containsExactly("Second Album's Song")
  }

  // ---- album(albumId) ------------------------------------------------------------------------

  @Test
  fun albumPassesTheAlbumIdThroughToTheDao() = runTest {
    dao.replaceLibraryContents(
      1,
      emptyList(),
      listOf(album("al1", "First Album", 1), album("al2", "Second Album", 1)),
      emptyList(),
    )

    assertThat(repository.album("al1")?.name).isEqualTo("First Album")
    assertThat(repository.album("al2")?.name).isEqualTo("Second Album")
  }

  @Test
  fun albumReturnsNullForAnUnknownId() = runTest {
    assertThat(repository.album("does-not-exist")).isNull()
  }

  // ---- search(libraryId, query, limit) --------------------------------------------------------

  @Test
  fun searchPassesTheLibraryIdThroughToTheDao() = runTest {
    dao.replaceLibraryContents(1, emptyList(), emptyList(), listOf(song("s1", "Shared Title", 1, albumId = "al1")))
    dao.replaceLibraryContents(2, emptyList(), emptyList(), listOf(song("s2", "Shared Title", 2, albumId = "al2")))

    assertThat(repository.search(1, "Shared", 10).songs.map { it.id }).containsExactly("s1")
    assertThat(repository.search(2, "Shared", 10).songs.map { it.id }).containsExactly("s2")
  }

  @Test
  fun searchPassesTheQueryThroughToTheDao() = runTest {
    dao.replaceLibraryContents(
      1,
      emptyList(),
      emptyList(),
      listOf(song("s1", "Alpha Track", 1, albumId = "al1"), song("s2", "Beta Track", 1, albumId = "al1")),
    )

    assertThat(repository.search(1, "Alpha", 10).songs.map { it.title }).containsExactly("Alpha Track")
    assertThat(repository.search(1, "Beta", 10).songs.map { it.title }).containsExactly("Beta Track")
  }

  @Test
  fun searchPassesTheLimitThroughToTheDao() = runTest {
    dao.replaceLibraryContents(
      1,
      emptyList(),
      emptyList(),
      listOf(song("s1", "Track One", 1, albumId = "al1"), song("s2", "Track Two", 1, albumId = "al1")),
    )

    assertThat(repository.search(1, "Track", 1).songs).hasSize(1)
    assertThat(repository.search(1, "Track", 10).songs).hasSize(2)
  }

  /**
   * The one piece of real logic `search` carries beyond delegation: the caller's own `%`/`_` must
   * match literally. Without the `ESCAPE '\'` clause (or if this method stopped escaping), the
   * LIKE pattern for query `"50%"` would be `"%50%%"`, which matches the unrelated decoy row too.
   */
  @Test
  fun searchEscapesTheCallersOwnWildcards() = runTest {
    // A decoy chosen so it only distinguishes escaped from unescaped: query "A%Z", unescaped,
    // becomes the LIKE pattern "%A%Z%" -- which, because a bare "%" matches any run of
    // characters including none, also matches "AxyzZ Marker" (A ... Z with something between).
    // Escaped correctly it becomes "%A\%Z%" ESCAPE '\\', which requires the literal substring
    // "A%Z" and matches only the first row. `"50% Off"` / `"Totally unrelated"` (the fixture this
    // replaced) could not actually tell the two behaviours apart: neither pattern shape changes
    // which of *that* pair matches, so the mutation this test exists to catch (deleting the
    // three `.replace(...)` calls) passed it silently -- caught only by attacking the assertion
    // directly, per this task's own standard.
    dao.replaceLibraryContents(
      1,
      emptyList(),
      emptyList(),
      listOf(
        song("s1", "A%Z Marker", 1, albumId = "al1"),
        song("s2", "AxyzZ Marker", 1, albumId = "al1"),
      ),
    )

    assertThat(repository.search(1, "A%Z", 10).songs.map { it.title }).containsExactly("A%Z Marker")
  }

  @Test
  fun searchOnABlankQueryReturnsEmptyWithoutMatchingEverything() = runTest {
    dao.replaceLibraryContents(1, emptyList(), emptyList(), listOf(song("s1", "Anything", 1, albumId = "al1")))

    val result = repository.search(1, "   ", 10)

    assertThat(result.songs).isEmpty()
    assertThat(result.albums).isEmpty()
    assertThat(result.artists).isEmpty()
  }

  // ---- coverArtUrl(coverArtId, sizePx) ---------------------------------------------------------

  @Test
  fun coverArtUrlPassesTheCoverArtIdThroughToTheSource() = runTest {
    signIn()

    assertThat(repository.coverArtUrl("art-one", null)).contains("id=art-one")
    assertThat(repository.coverArtUrl("art-two", null)).contains("id=art-two")
  }

  @Test
  fun coverArtUrlPassesTheSizeThroughToTheSource() = runTest {
    signIn()

    assertThat(repository.coverArtUrl("art-1", 128)).contains("size=128")
    assertThat(repository.coverArtUrl("art-1", 256)).contains("size=256")
    assertThat(repository.coverArtUrl("art-1", null)).doesNotContain("size=")
  }
}
