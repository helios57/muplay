package io.github.helios57.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.helios57.muplay.database.entity.AlbumEntity
import io.github.helios57.muplay.database.entity.SongEntity
import io.github.helios57.muplay.model.Song
import io.github.helios57.muplay.model.ShufflePlan
import io.github.helios57.muplay.model.SongRating
import io.github.helios57.muplay.model.SubsonicCredentials
import io.github.helios57.muplay.network.SubsonicSourceFactory
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

  private fun song(id: String, title: String, libraryId: Int, rating: SongRating = SongRating.Neutral) = Song(
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
    rating = rating,
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
    // songs to check the *order* of -- see theOrderIsRandomisedRatherThanFixed below.
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
  fun theRequestedSizeIsOverFetchedRatherThanForwardedUnchanged() = runTest {
    source.randomSongsByLibrary = mapOf(1 to listOf(song("song-1", "Track 1", 1)))

    repository.shuffle(libraryId = 1, requestedSize = 25)

    assertThat(source.callLog).contains("getRandomSongs(1, size=${ShufflePlan.poolSizeFor(25)})")
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
    assertThat(source.callLog).contains("getRandomSongs(2, size=${ShufflePlan.poolSizeFor(10)})")
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
   * The play order the listener hears is randomised here, and is not any fixed order.
   *
   * **This test used to assert that the server's order was preserved exactly** -- `filter` keeps
   * it, and a `sortedBy { it.title }` inserted into `shuffle` would have shown up as `Track 1,
   * Track 2, Track 3`. That property is gone: [ShufflePlan.queue] shuffles what it selects, because
   * selecting by weight and emitting in key order would put every promoted track at the front.
   *
   * So the assertion is now the one that survives: over many runs the same three songs come back in
   * more than one order, which a `sortedBy` **after** the plan fails and which the old fixture
   * (three songs whose title order differs from the server's) is still chosen to expose. A sort
   * *before* the plan is no longer detectable and no longer matters -- the plan re-randomises it.
   */
  @Test
  fun theOrderIsRandomisedRatherThanFixed() = runTest {
    source.randomSongsByLibrary = mapOf(
      1 to listOf(song("song-3", "Track 3", 1), song("song-1", "Track 1", 1), song("song-2", "Track 2", 1)),
    )

    val orders = (1..40).map { repository.shuffle(libraryId = 1, requestedSize = 10).songs.map { it.id } }

    // Every run returns all three -- nothing is dropped by randomising.
    assertThat(orders).allSatisfy { order ->
      assertThat(order).containsExactlyInAnyOrder("song-1", "song-2", "song-3")
    }
    // ...and not always in the same one. Three songs have six orders; forty draws miss a second
    // one with probability (1/6)^39, which is not a flake anybody will see.
    assertThat(orders.toSet()).hasSizeGreaterThan(1)
  }

  /**
   * The 500 cap lives in two places on purpose, and this pins the one that is *this* layer's.
   *
   * **This test used to assert the opposite** -- that a `requestedSize` of 1000 reached the source
   * unclamped, because clamping twice at two layers would make "the number on the wire" and "the
   * number this repository forwarded" two different numbers. That reasoning was correct while the
   * repository forwarded the size; it stopped being correct when the repository began asking for
   * [ShufflePlan.POOL_FACTOR] times the queue length, because the multiplication itself can exceed
   * what the protocol will return. The ceiling is now part of the arithmetic rather than a second
   * clamp on top of it: `SubsonicClient` still clamps the wire, and `BrowseEndpointsTest` still
   * proves it there.
   *
   * Kept as a rewritten test rather than deleted, because the old rationale reads perfectly
   * sensibly and the next person to shorten this method would restore it.
   */
  @Test
  fun aRequestedSizeAbove500AsksForTheProtocolCapRatherThanAMultipleOfIt() = runTest {
    source.randomSongsByLibrary = mapOf(1 to listOf(song("song-1", "Track 1", 1)))

    repository.shuffle(libraryId = 1, requestedSize = 1000)

    assertThat(source.callLog).contains("getRandomSongs(1, size=500)")
  }

  /**
   * The thumb down, at the layer that decides what plays.
   *
   * `ShufflePlanTest` proves the rule over a list; this proves the rule is *reached* -- a
   * repository that forgot to call [ShufflePlan.queue] returns the demoted song and every other
   * test in this class stays green, because no other test rates anything.
   */
  @Test
  fun aDemotedSongIsNeverInTheQueueHoweverManyTimesTheShuffleRuns() = runTest {
    source.randomSongsByLibrary = mapOf(
      1 to listOf(
        song("song-1", "Track 1", 1),
        song("song-2", "Track 2", 1, SongRating.Demoted),
        song("song-3", "Track 3", 1),
      ),
    )

    // Twenty runs rather than one: the pool is three songs and the queue asks for ten, so a
    // repository that skipped the plan entirely would return all three every single time -- but a
    // repository that merely *shuffled* would too, and only repetition distinguishes "removed"
    // from "unlucky" if the selection ever became probabilistic.
    repeat(20) {
      val result = repository.shuffle(libraryId = 1, requestedSize = 10)

      assertThat(result.songs.map { it.id }).containsExactlyInAnyOrder("song-1", "song-3")
      // Not counted as out of scope: the mirror agrees this song is in library 1. It was the
      // listener who removed it, and conflating the two would report a demotion as a sync fault.
      assertThat(result.discardedOutOfScope).isZero
    }
  }

}
