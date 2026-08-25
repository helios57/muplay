package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShuffleRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private lateinit var source: FakeSubsonicSource
  private lateinit var repository: ShuffleRepository

  private fun song(id: String, title: String, libraryId: Int) = Song(
    id = id,
    libraryId = libraryId,
    title = title,
    albumId = "album-$libraryId",
    albumName = "Album $libraryId",
    artistId = "artist-$libraryId",
    artistName = "Artist $libraryId",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
  )

  private fun songEntity(id: String, title: String, libraryId: Int) = SongEntity(
    id = id,
    libraryId = libraryId,
    albumId = "album-$libraryId",
    artistId = "artist-$libraryId",
    title = title,
    albumName = "Album $libraryId",
    artistName = "Artist $libraryId",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
    sortTitle = title.lowercase(),
  )

  private fun albumEntity(libraryId: Int) = AlbumEntity(
    id = "album-$libraryId",
    libraryId = libraryId,
    artistId = "artist-$libraryId",
    name = "Album $libraryId",
    artistName = "Artist $libraryId",
    coverArtId = null,
    songCount = 1,
    durationSeconds = 5,
    sortName = "album $libraryId",
  )

  @Before
  fun setUp() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    file = File(context.filesDir, "shuffle-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    credentialStore = CredentialStore(dataStore)
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))
    source = FakeSubsonicSource()

    // A mirror that agrees library 1 holds two music tracks and library 2 one audiobook chapter.
    db.browseDao().replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(albumEntity(1)),
      songs = listOf(songEntity("song-1", "Track 1", 1), songEntity("song-2", "Track 2", 1)),
    )
    db.browseDao().replaceLibraryContents(
      libraryId = 2,
      artists = emptyList(),
      albums = listOf(albumEntity(2)),
      songs = listOf(songEntity("chapter-1", "Chapter 1", 2)),
    )

    repository = ShuffleRepository(
      browseDao = db.browseDao(),
      sourceProvider = SubsonicSourceProvider(credentialStore, SubsonicSourceFactory { source }),
    )
  }

  @After
  fun tearDown() = runTest {
    credentialStore.clear()
    file.delete()
    db.close()
  }

  @Test
  fun aScopedShuffleReturnsThatLibrarysSongs() = runTest {
    source.randomSongsByLibrary = mapOf(
      1 to listOf(song("song-1", "Track 1", 1), song("song-2", "Track 2", 1)),
    )

    val result = repository.shuffle(libraryId = 1, requestedSize = 10)

    assertThat(result.songs.map { it.title }).containsExactlyInAnyOrder("Track 1", "Track 2")
    assertThat(result.discardedOutOfScope).isZero
  }

  /**
   * The defence of last resort, and the reason this repository does more than forward a call: if
   * the server's own scoping ever failed — a regression, a proxy rewriting a query string, a
   * `musicFolderId` that arrived unparseable — the mirror still knows which library each track is
   * in, and an audiobook chapter is dropped rather than played.
   */
  @Test
  fun aSongFromAnotherLibraryIsDroppedAndCounted() = runTest {
    source.randomSongsByLibrary = mapOf(
      1 to listOf(
        song("song-1", "Track 1", 1),
        // The server "leaked" an audiobook chapter into a music shuffle.
        song("chapter-1", "Chapter 1", 1),
      ),
    )

    val result = repository.shuffle(libraryId = 1, requestedSize = 10)

    assertThat(result.songs.map { it.title }).containsExactly("Track 1")
    assertThat(result.discardedOutOfScope).isEqualTo(1)
  }

  @Test
  fun aSongTheMirrorHasNeverSeenIsDropped() = runTest {
    // A track added on the server since the last sync. Dropping it makes the shuffle one track
    // short, which is a non-event; keeping it would mean trusting a claim the mirror cannot
    // check, which is the whole failure mode this guard exists for.
    source.randomSongsByLibrary = mapOf(
      1 to listOf(song("song-1", "Track 1", 1), song("brand-new", "Brand New", 1)),
    )

    val result = repository.shuffle(libraryId = 1, requestedSize = 10)

    assertThat(result.songs.map { it.id }).containsExactly("song-1")
    assertThat(result.discardedOutOfScope).isEqualTo(1)
  }

  @Test
  fun theRequestedSizeReachesTheServerUnchangedWhenItIsInRange() = runTest {
    source.randomSongsByLibrary = mapOf(1 to listOf(song("song-1", "Track 1", 1)))

    repository.shuffle(libraryId = 1, requestedSize = 25)

    assertThat(source.callLog).contains("getRandomSongs(1, size=25)")
  }

  @Test
  fun theScopeReachesTheServerAsTheLibraryAsked() = runTest {
    source.randomSongsByLibrary = mapOf(2 to listOf(song("chapter-1", "Chapter 1", 2)))

    repository.shuffle(libraryId = 2, requestedSize = 10)

    // The one parameter the whole feature depends on, asserted at this layer too: the repository
    // must not "helpfully" widen or default it.
    assertThat(source.callLog).contains("getRandomSongs(2, size=10)")
  }

  @Test
  fun anEmptyServerResponseIsAnEmptyResultRatherThanAnError() = runTest {
    source.randomSongsByLibrary = emptyMap()

    val result = repository.shuffle(libraryId = 1, requestedSize = 10)

    assertThat(result.songs).isEmpty()
    assertThat(result.discardedOutOfScope).isZero
  }
}
