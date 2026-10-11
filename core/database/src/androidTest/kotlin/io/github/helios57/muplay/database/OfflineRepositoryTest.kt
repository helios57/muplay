package io.github.helios57.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.helios57.muplay.database.entity.AlbumEntity
import io.github.helios57.muplay.database.entity.LibraryEntity
import io.github.helios57.muplay.database.entity.SongEntity
import io.github.helios57.muplay.model.LibraryRole
import io.github.helios57.muplay.model.Playlist
import io.github.helios57.muplay.model.Song
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline bookkeeping, over the real schema: which playlists are kept, in what order, which
 * songs still need their audio, and which audio nothing needs any more.
 */
@RunWith(AndroidJUnit4::class)
class OfflineRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var repository: OfflineRepository

  private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

  @Before
  fun setUp(): Unit = runBlocking {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    repository = OfflineRepository(db.offlineDao(), clock)
    db.libraryDao().mergeFromServer(listOf(LibraryEntity(1, "Music", LibraryRole.UNASSIGNED)))
    db.browseDao().replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(
        AlbumEntity(
          id = "al", libraryId = 1, artistId = null, name = "Album", artistName = "Artist",
          coverArtId = null, songCount = 4, durationSeconds = 0, sortName = "album",
        ),
      ),
      songs = (1..4).map { n ->
        SongEntity(
          id = "s$n", libraryId = 1, albumId = "al", artistId = null, title = "Song $n",
          albumName = "Album", artistName = "Artist", trackNumber = n, discNumber = 1,
          durationSeconds = 10 * n, suffix = "mp3", coverArtId = null, sortTitle = "song $n",
        )
      },
    )
  }

  @After
  fun tearDown() = db.close()

  private fun playlist(id: String, name: String) =
    Playlist(id = id, name = name, songCount = 0, durationSeconds = 0, owner = "me", coverArtId = null)

  private fun songs(vararg ids: String): List<Song> = runBlocking {
    ids.map { id -> MirrorMapper.song(db.browseDao().songsByIds(listOf(id)).single()) }
  }

  @Test
  fun aSavedPlaylistKeepsItsOrderAndCountsWhatIsDownloaded(): Unit = runBlocking {
    repository.save(playlist("p", "Night"), songs("s3", "s1", "s2"))

    assertThat(repository.songs("p").map { it.id }).containsExactly("s3", "s1", "s2")
    assertThat(repository.pending().map { it.id }).containsExactlyInAnyOrder("s1", "s2", "s3")

    repository.markDownloaded("s1")
    val saved = repository.observe().first().single()
    assertThat(saved.playlist.name).isEqualTo("Night")
    assertThat(saved.playlist.songCount).isEqualTo(3)
    assertThat(saved.playlist.durationSeconds).isEqualTo(60)
    assertThat(saved.total).isEqualTo(3)
    assertThat(saved.downloaded).isEqualTo(1)
    assertThat(saved.isComplete).isFalse
    assertThat(repository.pending().map { it.id }).containsExactlyInAnyOrder("s2", "s3")
  }

  @Test
  fun aSongInTwoPlaylistsIsDownloadedOnceAndKeptUntilBothAreGone(): Unit = runBlocking {
    repository.save(playlist("a", "A"), songs("s1", "s2"))
    repository.save(playlist("b", "B"), songs("s2", "s3"))
    repository.markDownloaded("s2")

    assertThat(repository.observe().first().map { it.downloaded }).containsExactly(1, 1)
    // Removing A frees s1 only: s2 is still B's.
    assertThat(repository.remove("a")).containsExactly("s1")
    assertThat(repository.remove("b")).containsExactlyInAnyOrder("s2", "s3")
    assertThat(repository.observe().first()).isEmpty()
    assertThat(repository.markDownloaded("s3")).describedAs("nothing holds s3 any more").isFalse
  }

  @Test
  fun savingAgainReplacesTheOrderAndFreesSongsThePlaylistNoLongerHas(): Unit = runBlocking {
    repository.save(playlist("p", "P"), songs("s1", "s2"))
    repository.markDownloaded("s1")

    val freed = repository.save(playlist("p", "P"), songs("s2", "s4"))

    assertThat(freed).containsExactly("s1")
    assertThat(repository.songs("p").map { it.id }).containsExactly("s2", "s4")
    assertThat(repository.downloadedIds()).isEmpty()
  }

  @Test
  fun aSongTheServerNoLongerHasIsNotCountedAgainstThePlaylist(): Unit = runBlocking {
    repository.save(playlist("p", "P"), songs("s1", "s2"))
    db.browseDao().replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = emptyList(),
      songs = emptyList(),
    )

    val saved = repository.observe().first().single()
    assertThat(saved.total).isZero
    assertThat(repository.songs("p")).isEmpty()
  }

  @Test
  fun clearingForgetsEverything(): Unit = runBlocking {
    repository.save(playlist("p", "P"), songs("s1"))

    repository.clear()

    assertThat(repository.observe().first()).isEmpty()
    assertThat(repository.pending()).isEmpty()
    assertThat(repository.playlist("p")).isNull()
  }
}
