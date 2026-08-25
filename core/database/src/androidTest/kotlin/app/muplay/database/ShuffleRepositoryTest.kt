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

    // A mirror that agrees library 1 holds three music tracks and library 2 one audiobook
    // chapter. The third music track (song-3) exists only so a test can have three surviving
    // songs to check the *order* of -- see theRepositoryPreservesTheServersOrder below.
    db.browseDao().replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(albumEntity(1)),
      songs = listOf(
        songEntity("song-1", "Track 1", 1),
        songEntity("song-2", "Track 2", 1),
        songEntity("song-3", "Track 3", 1),
      ),
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

  /**
   * Fix round 1, N-1 (HIGH): this test used to assert only `source.callLog` -- the *fetch's*
   * `libraryId` argument -- and never looked at `result` at all. That left the *guard's* own
   * `libraryId` argument (`browseDao.songIdsInLibrary(libraryId, ...)`) observed at exactly one
   * value across the whole suite: every other test that inspects `result` shuffles library 1, so
   * a hardcoded `1` inside the guard was indistinguishable from the real parameter and passed
   * 6/6 (see task-7-report.md). Asserting `result` here, at library 2, is what a hardcoded guard
   * cannot survive: with the guard pinned to `1`, `songIdsInLibrary(1, ["chapter-1"])` finds
   * nothing in library 1's mirror, so `chapter-1` -- which the mirror genuinely does place in
   * library 2 -- would be wrongly discarded as "out of scope".
   */
  @Test
  fun theScopeReachesTheServerAsTheLibraryAsked() = runTest {
    source.randomSongsByLibrary = mapOf(2 to listOf(song("chapter-1", "Chapter 1", 2)))

    val result = repository.shuffle(libraryId = 2, requestedSize = 10)

    // The one parameter the whole feature depends on, asserted at this layer too: the repository
    // must not "helpfully" widen or default it.
    assertThat(source.callLog).contains("getRandomSongs(2, size=10)")
    // The guard's own argument, not just the fetch's -- see this test's own doc.
    assertThat(result.songs.map { it.id }).containsExactly("chapter-1")
    assertThat(result.discardedOutOfScope).isZero
  }

  /**
   * The mirror image of `theScopeReachesTheServerAsTheLibraryAsked`: a song genuinely foreign to
   * library 2 (the mirror places `song-1` in library 1) must still be dropped when shuffling
   * library 2, the same way `aSongFromAnotherLibraryIsDroppedAndCounted` proves it for library 1.
   * Together the two prove the guard's `libraryId` argument at two disjoint values in both
   * directions -- kept for the *matching* library, dropped for the *foreign* one -- which a
   * hardcoded constant of either value cannot pass simultaneously.
   */
  @Test
  fun aSongForeignToTheSecondLibraryIsDroppedToo() = runTest {
    source.randomSongsByLibrary = mapOf(
      2 to listOf(
        song("chapter-1", "Chapter 1", 2),
        // The server "leaked" a music track into an audiobook-library shuffle.
        song("song-1", "Track 1", 2),
      ),
    )

    val result = repository.shuffle(libraryId = 2, requestedSize = 10)

    assertThat(result.songs.map { it.id }).containsExactly("chapter-1")
    assertThat(result.discardedOutOfScope).isEqualTo(1)
  }

  @Test
  fun anEmptyServerResponseIsAnEmptyResultRatherThanAnError() = runTest {
    source.randomSongsByLibrary = emptyMap()

    val result = repository.shuffle(libraryId = 1, requestedSize = 10)

    assertThat(result.songs).isEmpty()
    assertThat(result.discardedOutOfScope).isZero
  }

  /**
   * Fix round 1, N-6 (LOW): the state of *every* library before its first sync (Task 6) --
   * `songIdsInLibrary` finds nothing for any id, so every song the server returns is discarded,
   * not just the odd leaked one. Unlike `anEmptyServerResponseIsAnEmptyResultRatherThanAnError`
   * (the server returned nothing), here the server returns real songs and the *guard* discards
   * all of them -- `discardedOutOfScope` is the only thing that tells those two silences apart,
   * and until this test nothing observed it above 1.
   */
  @Test
  fun everySongIsDroppedWhenTheMirrorHasNeverSyncedThisLibrary() = runTest {
    source.randomSongsByLibrary = mapOf(
      3 to listOf(song("song-9", "Track 9", 3), song("song-10", "Track 10", 3)),
    )

    val result = repository.shuffle(libraryId = 3, requestedSize = 10)

    assertThat(result.songs).isEmpty()
    assertThat(result.discardedOutOfScope).isEqualTo(2)
  }

  /**
   * Fix round 1, N-2 (MEDIUM): nothing else in this suite has more than one surviving song to
   * order, so `containsExactlyInAnyOrder` elsewhere could never notice a `sortedBy` (or any other
   * reordering) inserted into `shuffle`. `filter` preserves the server's own order -- the play
   * order the user actually hears -- and this pins that directly: the fake returns the three
   * mirrored library-1 songs in a title order (`Track 3`, `Track 1`, `Track 2`) that a
   * title-sort would visibly rearrange, so `.sortedBy { it.title }` (or `{ it.id }`, which would
   * also reorder these three) fails this test while every other test in the suite stays green.
   */
  @Test
  fun theRepositoryPreservesTheServersOrderRatherThanSortingIt() = runTest {
    source.randomSongsByLibrary = mapOf(
      1 to listOf(song("song-3", "Track 3", 1), song("song-1", "Track 1", 1), song("song-2", "Track 2", 1)),
    )

    val result = repository.shuffle(libraryId = 1, requestedSize = 10)

    assertThat(result.songs.map { it.id }).containsExactly("song-3", "song-1", "song-2")
  }

  /**
   * Fix round 1, N-4 (LOW): the 500 cap lives in `SubsonicClient` (Task 3), one layer down from
   * here. `shuffle` neither rejects nor re-clamps a caller-supplied `requestedSize` above it --
   * clamping twice, at two different layers, would make "the number on the wire" and "the number
   * this repository forwarded" two different numbers to reason about. This pins that the
   * repository's own contract really is a pure passthrough, not an accidental one: 1000 reaches
   * the fake exactly as given, unclamped and unrejected, the same way `MAX_RANDOM_SONGS`
   * clamping happens only in `SubsonicClient.getRandomSongs`, asserted on the wire by
   * `BrowseEndpointsTest`.
   */
  @Test
  fun aRequestedSizeAbove500ReachesTheSourceUnclampedByThisRepository() = runTest {
    source.randomSongsByLibrary = mapOf(1 to listOf(song("song-1", "Track 1", 1)))

    repository.shuffle(libraryId = 1, requestedSize = 1000)

    assertThat(source.callLog).contains("getRandomSongs(1, size=1000)")
  }
}
