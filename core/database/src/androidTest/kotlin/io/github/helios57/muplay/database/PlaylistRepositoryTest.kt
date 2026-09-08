package io.github.helios57.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.helios57.muplay.database.entity.SongEntity
import io.github.helios57.muplay.model.Playlist
import io.github.helios57.muplay.model.PlaylistWithSongs
import io.github.helios57.muplay.model.Song
import io.github.helios57.muplay.model.SubsonicCredentials
import io.github.helios57.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `PlaylistRepository.librariesOf`: which libraries each playlist draws from.
 *
 * ### Why this has to exist at all
 *
 * `getPlaylists` ignores `musicFolderId` — measured twice against the CI Navidrome, most recently
 * with one playlist holding a music track and another holding an audiobook chapter, both of which
 * came back for both libraries. Neither the listing nor a `getPlaylist` entry names a library. So
 * "the playlists in this library" is not a question the server answers, and the only route is to
 * read each playlist's entries and look their ids up in the mirror.
 *
 * That costs one request per playlist, which is why half of these tests are about the cache rather
 * than about the answer.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private lateinit var source: FakeSubsonicSource
  private lateinit var repository: PlaylistRepository

  @Before
  fun setUp() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    source = FakeSubsonicSource()

    // Two libraries in the mirror, because a repository that answered "library 1" for everything
    // would pass every single-library test written against it.
    db.browseDao().replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = emptyList(),
      songs = listOf(songEntity("music-1", libraryId = 1), songEntity("music-2", libraryId = 1)),
    )
    db.browseDao().replaceLibraryContents(
      libraryId = 2,
      artists = emptyList(),
      albums = emptyList(),
      songs = listOf(songEntity("book-1", libraryId = 2)),
    )

    file = File(context.filesDir, "playlist-repository-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    credentialStore = CredentialStore(dataStore)
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "alice", "sesame"))
    repository = PlaylistRepository(
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
  fun aPlaylistIsPlacedInTheLibrariesItsEntriesAreMirroredIn() = runTest {
    server("music-only" to listOf("music-1", "music-2"), "book-only" to listOf("book-1"))

    val libraries = repository.librariesOf(source.getPlaylists())

    assertThat(libraries["music-only"]).containsExactly(1)
    assertThat(libraries["book-only"]).containsExactly(2)
  }

  @Test
  fun aPlaylistThatMixesLibrariesIsInBothOfThem() = runTest {
    // The case that rules out "take the first entry's library and stamp the playlist with it",
    // which is what `getPlaylist`'s own `musicFolderId` parameter invites.
    server("mixed" to listOf("music-1", "book-1"))

    assertThat(repository.librariesOf(source.getPlaylists())["mixed"])
      .containsExactlyInAnyOrder(1, 2)
  }

  @Test
  fun aPlaylistWhoseEntriesTheMirrorHasNeverSeenIsPlacedNowhereRatherThanGuessed() = runTest {
    // Deliberately empty, not "library 1". An empty set is what the screen reads as "unknown", and
    // an unknown playlist is shown under **every** library -- the same choice `playlist()` makes
    // for an unmirrored entry, and for the same reason: hiding a playlist the server says exists
    // is worse than showing one that may not belong here.
    server("unsynced" to listOf("never-seen-1", "never-seen-2"))

    assertThat(repository.librariesOf(source.getPlaylists())["unsynced"]).isEmpty()
  }

  @Test
  fun anEmptyPlaylistIsPlacedNowhereAndCostsExactlyOneRequestEver() = runTest {
    server("empty" to emptyList())

    repository.librariesOf(source.getPlaylists())
    val afterFirst = source.callLog.count { it.startsWith("getPlaylist(") }
    repository.librariesOf(source.getPlaylists())

    assertThat(repository.librariesOf(source.getPlaylists())["empty"]).isEmpty()
    // An empty playlist is genuinely, permanently unknown-libraried, so its emptiness is a real
    // answer and is cached. An empty answer for a playlist that *has* entries is not -- see below.
    assertThat(source.callLog.count { it.startsWith("getPlaylist(") }).isEqualTo(afterFirst)
  }

  @Test
  fun aSecondCallForAnUnchangedPlaylistAsksTheServerNothing() = runTest {
    server("music-only" to listOf("music-1"))
    val playlists = source.getPlaylists()
    repository.librariesOf(playlists)
    val afterFirst = source.callLog.count { it.startsWith("getPlaylist(") }

    val again = repository.librariesOf(playlists)

    assertThat(afterFirst).isOne()
    assertThat(source.callLog.count { it.startsWith("getPlaylist(") }).isEqualTo(afterFirst)
    assertThat(again["music-only"]).containsExactly(1)
  }

  @Test
  fun anEditedPlaylistIsReReadRatherThanServedFromTheStaleAnswer() = runTest {
    server("shifting" to listOf("music-1"))
    repository.librariesOf(source.getPlaylists())

    // Exactly what an edit looks like on the wire: same id, same name, a new `changed` stamp. The
    // count and the duration are deliberately left alone here, because they are what a weaker
    // cache key would have used and this test would then pass over a stale answer.
    source.playlists["shifting"] = PlaylistWithSongs(
      playlist = playlist("shifting", changed = "2026-09-08T21:00:00Z"),
      songs = listOf(stub("book-1")),
    )

    assertThat(repository.librariesOf(source.getPlaylists())["shifting"]).containsExactly(2)
  }

  @Test
  fun aPlaylistWhoseEntriesArriveLaterIsNotStuckOnItsEmptyAnswer() = runTest {
    // The cache's one asymmetry, and it is the difference between a filter that heals and one that
    // needs an app restart. "This playlist's entries are not in the mirror" is a statement about
    // the *mirror*, which the next sync changes without touching the playlist's `changed` stamp --
    // so it is an answer that must not be remembered.
    server("later" to listOf("arrives-later"))
    assertThat(repository.librariesOf(source.getPlaylists())["later"]).isEmpty()

    db.browseDao().replaceLibraryContents(
      libraryId = 2,
      artists = emptyList(),
      albums = emptyList(),
      songs = listOf(songEntity("arrives-later", libraryId = 2)),
    )

    assertThat(repository.librariesOf(source.getPlaylists())["later"]).containsExactly(2)
  }

  @Test
  fun onePlaylistTheServerRefusesDoesNotCostTheOthersTheirAnswer() = runTest {
    server("music-only" to listOf("music-1"))
    // A playlist the listing knows about and `getPlaylist` will not return -- deleted in another
    // client between the two calls, which is ordinary rather than exceptional on a live server.
    val listed = source.getPlaylists() + playlist("vanished")

    val libraries = repository.librariesOf(listed)

    assertThat(libraries["music-only"]).containsExactly(1)
    assertThat(libraries["vanished"]).isEmpty()
  }

  private fun server(vararg entries: Pair<String, List<String>>) {
    source.playlists = linkedMapOf()
    entries.forEach { (id, songIds) ->
      source.playlists[id] = PlaylistWithSongs(
        playlist = playlist(id, songCount = songIds.size),
        songs = songIds.map(::stub),
      )
    }
  }

  private fun playlist(id: String, songCount: Int = 1, changed: String? = "2026-09-08T20:00:00Z") =
    Playlist(
      id = id,
      name = id,
      songCount = songCount,
      durationSeconds = songCount * 5,
      owner = "alice",
      coverArtId = null,
      changed = changed,
    )

  private fun stub(id: String) = Song(
    id = id,
    libraryId = 0,
    title = id,
    albumId = null,
    albumName = null,
    artistId = null,
    artistName = null,
    trackNumber = null,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
  )

  private fun songEntity(id: String, libraryId: Int) = SongEntity(
    id = id,
    libraryId = libraryId,
    albumId = null,
    artistId = null,
    title = id,
    albumName = null,
    artistName = null,
    trackNumber = null,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
    sortTitle = id,
  )
}
