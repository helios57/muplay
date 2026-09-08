package io.github.helios57.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.helios57.muplay.database.entity.AlbumEntity
import io.github.helios57.muplay.database.entity.SongEntity
import io.github.helios57.muplay.model.SongRating
import io.github.helios57.muplay.model.SubsonicCredentials
import io.github.helios57.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The thumbs end to end against a fake server that really keeps its playlists.
 *
 * `PromotedPlaylistTest` decides *what* each tap should do; this proves the decision is reached and
 * carried out, which is the half a pure function cannot cover: the order of the three writes, the
 * two reads promoting costs, and the toggle reading its "current" from the mirror rather than from
 * a caller's copy.
 *
 * The fake appends on `songIdToAdd` **unconditionally**, exactly as Navidrome was measured to, so
 * the duplicate this repository exists to avoid is reproducible here.
 */
@RunWith(AndroidJUnit4::class)
class RatingRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private lateinit var source: FakeSubsonicSource
  private lateinit var repository: RatingRepository

  @Before
  fun setUp() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    file = File(context.filesDir, "rating-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    credentialStore = CredentialStore(dataStore)
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "alice", "sesame"))
    source = FakeSubsonicSource()

    db.browseDao().replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(album()),
      songs = listOf(songEntity("song-1", "Track 1"), songEntity("song-2", "Track 2")),
    )

    repository = RatingRepository(
      browseDao = db.browseDao(),
      credentialStore = credentialStore,
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
  fun aThumbUpReachesTheServerAsAFiveAndLightsUpLocally() = runTest {
    val next = repository.rate("song-1", SongRating.Promoted)

    assertThat(next).isEqualTo(SongRating.Promoted)
    assertThat(source.callLog).contains("setRating(song-1, 5)")
    // The mirror, not just the return value: the screen reads the mirror, so a repository that
    // wrote only the server would show the thumb going out again on the next recomposition.
    assertThat(repository.ratingOf("song-1").first()).isEqualTo(SongRating.Promoted)
  }

  @Test
  fun aThumbDownReachesTheServerAsAOne() = runTest {
    val next = repository.rate("song-1", SongRating.Demoted)

    assertThat(next).isEqualTo(SongRating.Demoted)
    assertThat(source.callLog).contains("setRating(song-1, 1)")
    assertThat(repository.ratingOf("song-1").first()).isEqualTo(SongRating.Demoted)
  }

  @Test
  fun tappingTheSameThumbAgainClearsTheRatingWithAnExplicitZero() = runTest {
    // Subsonic has no "unset" verb, and an omitted `rating` is a malformed command rather than a
    // no-op -- so the toggle's second half is a `setRating(..., 0)` that has to actually be sent.
    repository.rate("song-1", SongRating.Promoted)

    val next = repository.rate("song-1", SongRating.Promoted)

    assertThat(next).isEqualTo(SongRating.Neutral)
    assertThat(source.callLog).contains("setRating(song-1, 0)")
    assertThat(repository.ratingOf("song-1").first()).isEqualTo(SongRating.Neutral)
  }

  @Test
  fun theOppositeThumbSwitchesOutrightRatherThanClearing() = runTest {
    repository.rate("song-1", SongRating.Promoted)

    val next = repository.rate("song-1", SongRating.Demoted)

    assertThat(next).isEqualTo(SongRating.Demoted)
    assertThat(repository.ratingOf("song-1").first()).isEqualTo(SongRating.Demoted)
  }

  @Test
  fun theFirstPromotionCreatesThePlaylistNamedForTheSignedInUser() = runTest {
    repository.rate("song-1", SongRating.Promoted)

    assertThat(source.callLog).contains("createPlaylist(promoted-alice, [song-1])")
    assertThat(source.playlists.values.single().songs.map { it.id }).containsExactly("song-1")
  }

  @Test
  fun aSecondPromotionAppendsToTheSamePlaylistRatherThanCreatingAnother() = runTest {
    repository.rate("song-1", SongRating.Promoted)

    repository.rate("song-2", SongRating.Promoted)

    assertThat(source.playlists).hasSize(1)
    assertThat(source.playlists.values.single().songs.map { it.id })
      .containsExactly("song-1", "song-2")
  }

  @Test
  fun promotingATrackThatIsAlreadyInThePlaylistAddsNoSecondCopy() = runTest {
    // The measured server behaviour this whole path is shaped around: `songIdToAdd` appends
    // unconditionally. Reaching this state needs the rating to disagree with the playlist, which
    // is what a rating set from another client does -- so it is reproduced here by clearing the
    // rating without touching the playlist, then promoting again.
    repository.rate("song-1", SongRating.Promoted)
    db.browseDao().setUserRating("song-1", SongRating.Neutral.userRating)

    repository.rate("song-1", SongRating.Promoted)

    assertThat(source.playlists.values.single().songs.map { it.id }).containsExactly("song-1")
    assertThat(source.callLog).doesNotContain("updatePlaylist(created-1, add=[song-1], remove=[])")
  }

  @Test
  fun clearingAPromotionTakesTheTrackOutOfThePlaylist() = runTest {
    repository.rate("song-1", SongRating.Promoted)
    repository.rate("song-2", SongRating.Promoted)

    repository.rate("song-1", SongRating.Promoted)

    assertThat(source.callLog).contains("updatePlaylist(created-1, add=[], remove=[0])")
    assertThat(source.playlists.values.single().songs.map { it.id }).containsExactly("song-2")
  }

  @Test
  fun aThumbDownTakesAPromotedTrackOutOfThePlaylistToo() = runTest {
    // Demoting is not the same tap as clearing, and it would be easy to handle only the clear:
    // `promoted = false` covers both, and the playlist must lose the track either way.
    repository.rate("song-1", SongRating.Promoted)

    repository.rate("song-1", SongRating.Demoted)

    assertThat(source.playlists.values.single().songs).isEmpty()
  }

  @Test
  fun aThumbDownWithNoPlaylistAtAllCreatesNothing() = runTest {
    repository.rate("song-1", SongRating.Demoted)

    assertThat(source.playlists).isEmpty()
    assertThat(source.callLog.filter { it.startsWith("createPlaylist") }).isEmpty()
  }

  @Test
  fun aRefusedServerWriteLeavesTheMirrorUntouched() = runTest {
    // The order that matters. A repository that lit the thumb first would leave the listener
    // looking at a rating the server never took, and the next reconcile would silently undo it.
    source.failWith = java.io.IOException("the server refused")

    runCatching { repository.rate("song-1", SongRating.Promoted) }

    assertThat(repository.ratingOf("song-1").first()).isEqualTo(SongRating.Neutral)
  }

  @Test
  fun aTrackTheMirrorHasNeverSeenIsStillRatableOnTheServer() = runTest {
    // The mirror is a cache and can be behind. The rating belongs to the server either way, so a
    // missing row must not stop the write -- it only means the thumb will not light until the
    // next reconcile, which is a lesser failure than refusing the tap.
    val next = repository.rate("not-mirrored", SongRating.Promoted)

    assertThat(next).isEqualTo(SongRating.Promoted)
    assertThat(source.callLog).contains("setRating(not-mirrored, 5)")
  }

  private fun songEntity(id: String, title: String) = SongEntity(
    id = id,
    libraryId = 1,
    albumId = "album-1",
    artistId = "artist-1",
    title = title,
    albumName = "Album 1",
    artistName = "Artist 1",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
    sortTitle = title.lowercase(),
  )

  private fun album() = AlbumEntity(
    id = "album-1",
    libraryId = 1,
    artistId = "artist-1",
    name = "Album 1",
    artistName = "Artist 1",
    coverArtId = null,
    songCount = 2,
    durationSeconds = 10,
    sortName = "album 1",
  )
}
