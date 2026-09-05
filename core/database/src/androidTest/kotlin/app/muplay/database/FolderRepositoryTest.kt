package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.dao.BrowseDao
import app.muplay.database.entity.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Folder browsing against a real SQLite database.
 *
 * `FolderPathsTest` holds the string arithmetic on the fast tier and this holds the half it
 * cannot reach: that the `LIKE` prefix query and its `ESCAPE` clause select the rows the arithmetic
 * assumes, that `path IS NOT NULL` keeps a pre-version-8 row out of the root rather than putting it
 * in it, and that the whole thing is scoped to one library.
 *
 * The corpus below is deliberately adversarial in three specific ways, each of which is a real
 * defect rather than a hypothetical: a folder whose name is a **prefix of another** ("Fourth" and
 * "Fourth Author"), a folder whose name contains a **SQL wildcard** ("50% Off"), and a song in a
 * **second library** at a path that also exists in the first.
 */
@RunWith(AndroidJUnit4::class)
class FolderRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: BrowseDao
  private lateinit var repository: FolderRepository

  private companion object {
    const val MUSIC = 1
    const val BOOKS = 2
  }

  private fun song(id: String, libraryId: Int, path: String?, title: String = id) = SongEntity(
    id = id,
    libraryId = libraryId,
    albumId = "al-$id",
    artistId = "ar-$id",
    title = title,
    albumName = "An Album",
    artistName = "A Band",
    trackNumber = 1,
    discNumber = 1,
    durationSeconds = 100,
    suffix = "mp3",
    coverArtId = null,
    sortTitle = MirrorMapper.sortKey(title),
    path = path,
  )

  @Before
  fun setUp() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    dao = db.browseDao()
    repository = FolderRepository(dao)
    dao.replaceLibraryContents(
      libraryId = MUSIC,
      artists = emptyList(),
      albums = emptyList(),
      songs = listOf(
        song("m1", MUSIC, "Fourth Author/Multi Part Book/01 - Part One.mp3", "Part One"),
        song("m2", MUSIC, "Fourth Author/Multi Part Book/02 - Part Two.mp3", "Part Two"),
        song("m3", MUSIC, "Fourth Author/Single/01 - Alone.mp3", "Alone"),
        // A folder whose name is a prefix of another folder's. A LIKE without the separator, or a
        // startsWith, puts this one's track inside "Fourth Author".
        song("m4", MUSIC, "Fourth/Only.mp3", "Only"),
        // A folder named with a SQL wildcard. Unescaped, its pattern matches both of the above.
        song("m5", MUSIC, "50% Off/Bargain.mp3", "Bargain"),
        // The decoy that makes the escaping assertion able to fail. An unescaped "50% Off/%" is a
        // pattern whose `%` matches the empty string, so it selects this folder too -- and without
        // this row the escaped and unescaped queries return the same answer and the test below
        // passes over the defect it names.
        song("m8", MUSIC, "50 Off/Full Price.mp3", "Full Price"),
        song("m6", MUSIC, "Loose Track.mp3", "Loose"),
        // Mirrored before schema version 8: no path yet, and no reconcile since.
        song("m7", MUSIC, null, "Pathless"),
      ),
    )
    dao.replaceLibraryContents(
      libraryId = BOOKS,
      artists = emptyList(),
      albums = emptyList(),
      // Same path as a music track. Only `libraryId` separates them.
      songs = listOf(song("b1", BOOKS, "Fourth Author/Multi Part Book/01 - Part One.mp3", "Book")),
    )
  }

  @After
  fun tearDown() {
    if (::db.isInitialized) db.close()
  }

  @Test
  fun theRootListsTopLevelFoldersAndTheTracksBesideThem(): Unit = runTest {
    val listing = repository.listing(MUSIC, "").first()

    assertThat(listing.folders.map { it.name })
      .containsExactly("50 Off", "50% Off", "Fourth", "Fourth Author")
    assertThat(listing.tracks.map { it.title }).containsExactly("Loose")
  }

  @Test
  fun aFolderWhoseNameStartsAnotherFolderIsNotSweptIntoIt(): Unit = runTest {
    // "Fourth" holds one track. "Fourth Author" holds three and no tracks of its own. A prefix
    // test that forgets the separator gives "Fourth" all four, and shuffling it plays a
    // neighbouring author's book.
    val fourth = repository.listing(MUSIC, "Fourth").first()
    assertThat(fourth.tracks.map { it.title }).containsExactly("Only")
    assertThat(fourth.folders).isEmpty()

    val author = repository.listing(MUSIC, "Fourth Author").first()
    assertThat(author.tracks).isEmpty()
    assertThat(author.folders.map { it.name }).containsExactly("Multi Part Book", "Single")
    assertThat(author.folders.single { it.name == "Multi Part Book" }.trackCount).isEqualTo(2)
  }

  @Test
  fun aFolderNamedWithASqlWildcardMatchesOnlyItself(): Unit = runTest {
    // Without `ESCAPE '\'` in the query -- or without the escaping in `likePatternFor` -- the
    // pattern "50% Off/%" matches every path in the library, and this folder shows all of it.
    val listing = repository.listing(MUSIC, "50% Off").first()

    assertThat(listing.tracks.map { it.title }).containsExactly("Bargain")
    assertThat(listing.folders).isEmpty()
    // Measured against SQLite rather than reasoned about: the unescaped pattern returns
    // ["50 Off/Full Price.mp3", "50% Off/Bargain.mp3"], the escaped one only the second.
    assertThat(repository.listing(MUSIC, "50 Off").first().tracks.map { it.title })
      .containsExactly("Full Price")
  }

  @Test
  fun aSongMirroredBeforeItHadAPathIsNotPlacedAtTheRoot(): Unit = runTest {
    // The alternative reading of a null path -- "no folder, therefore the root" -- would show a
    // track the user cannot find by browsing and would put it in every folder shuffle.
    val listing = repository.listing(MUSIC, "").first()

    assertThat(listing.tracks.map { it.title }).doesNotContain("Pathless")
    assertThat(repository.songsUnder(MUSIC, "").map { it.title }).doesNotContain("Pathless")
  }

  @Test
  fun everyFolderQueryIsScopedToOneLibrary(): Unit = runTest {
    // The book library holds a song at a path the music library also has. Nothing but `libraryId`
    // separates them, and an unscoped query would put an audiobook chapter in a music shuffle --
    // the exact leak `BrowseDao.songIdsInLibrary` exists to prevent one layer up.
    val music = repository.listing(MUSIC, "Fourth Author/Multi Part Book").first()
    assertThat(music.tracks.map { it.id }).containsExactly("m1", "m2")

    val books = repository.listing(BOOKS, "Fourth Author/Multi Part Book").first()
    assertThat(books.tracks.map { it.id }).containsExactly("b1")

    assertThat(repository.songsUnder(BOOKS, "").map { it.id }).containsExactly("b1")
  }

  @Test
  fun shufflingAFolderDrawsFromEverySubfolderBeneathIt(): Unit = runTest {
    // "Fourth Author" holds no files of its own, so a per-directory implementation returns nothing
    // here and the feature the user asked for -- shuffle a folder *and all its subfolders* -- is
    // silently a no-op on exactly the folders worth shuffling.
    val under = repository.songsUnder(MUSIC, "Fourth Author")

    assertThat(under.map { it.id }).containsExactly("m1", "m2", "m3")
    // ...and the count shown beside the folder is the same number, from the same rows.
    val root = repository.listing(MUSIC, "").first()
    assertThat(root.folders.single { it.name == "Fourth Author" }.trackCount).isEqualTo(under.size)
  }

  @Test
  fun theRootShuffleIsTheWholeLibraryThatHasPaths(): Unit = runTest {
    // Path order, which is SQLite's BINARY collation: a space (32) sorts before a slash (47),
    // so "Fourth Author/..." precedes "Fourth/...". Measured, not assumed.
    assertThat(repository.songsUnder(MUSIC, "").map { it.id })
      .containsExactly("m8", "m5", "m1", "m2", "m3", "m4", "m6")
  }

  @Test
  fun theTracksInAFolderCarryTheirPathBackOut(): Unit = runTest {
    // `MirrorMapper` maps the column both ways or the folder screen cannot descend from a row it
    // is showing. An identity round trip through the mirror is what makes that safe to rely on.
    val listing = repository.listing(MUSIC, "Fourth/").first()

    assertThat(listing.tracks.single().path).isEqualTo("Fourth/Only.mp3")
  }

  @Test
  fun onlySongsWithAPathAreCounted(): Unit = runTest {
    // Zero here means "this mirror predates schema version 8", which the folder screen renders
    // differently from "this library has no folders". Eight music songs, one of them pathless.
    assertThat(repository.pathedSongCount(MUSIC).first()).isEqualTo(7)
    assertThat(repository.pathedSongCount(BOOKS).first()).isEqualTo(1)
  }
}
