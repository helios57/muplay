package io.github.helios57.muplay.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.helios57.muplay.database.MirrorMapper
import io.github.helios57.muplay.database.MuPlayDatabase
import io.github.helios57.muplay.database.OfflineRepository
import io.github.helios57.muplay.database.entity.AlbumEntity
import io.github.helios57.muplay.database.entity.LibraryEntity
import io.github.helios57.muplay.model.LibraryRole
import io.github.helios57.muplay.model.Playlist
import io.github.helios57.muplay.model.Song
import java.io.File
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Saved playlists, end to end against the real container: a saved song's bytes land in the offline
 * cache, and playback's own data source then serves them **with the network refused** -- which is
 * the only observation that means "plays offline". Counting requests would not do: the streaming
 * cache below could satisfy a count with no offline cache at all.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class OfflineDownloadsTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private lateinit var db: MuPlayDatabase
  private lateinit var dir: File
  private lateinit var streaming: Cache
  private lateinit var offline: Cache
  private lateinit var credentials: File
  private lateinit var tracks: List<Song>

  private val noNetwork = Call.Factory { throw IOException("this test has no network") }

  @Before
  fun setUp(): Unit = runBlocking {
    tracks = RealTrackBytes.musicTracks().take(2)
    check(tracks.size == 2) { "the seeded music library must hold two mp3 tracks" }
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    db.libraryDao().mergeFromServer(listOf(LibraryEntity(tracks[0].libraryId, "Music", LibraryRole.MUSIC)))
    db.browseDao().replaceLibraryContents(
      libraryId = tracks[0].libraryId,
      artists = emptyList(),
      albums = tracks.mapNotNull { it.albumId }.distinct().map { id ->
        AlbumEntity(
          id = id, libraryId = tracks[0].libraryId, artistId = null, name = id, artistName = null,
          coverArtId = null, songCount = 0, durationSeconds = 0, sortName = id,
        )
      },
      songs = tracks.map(MirrorMapper::songEntity),
    )
    dir = File(context.cacheDir, "offline-test-${System.nanoTime()}")
    val database = StandaloneDatabaseProvider(context)
    streaming = MediaCache.create(context, File(dir, "streaming"), database = database)
    offline = MediaCache.createOffline(context, database, File(dir, "offline"))
  }

  @After
  fun tearDown() {
    if (::streaming.isInitialized) streaming.release()
    if (::offline.isInitialized) offline.release()
    if (::dir.isInitialized) dir.deleteRecursively()
    if (::db.isInitialized) db.close()
    if (::credentials.isInitialized) credentials.delete()
  }

  private fun downloads(callFactory: Call.Factory): OfflineDownloads {
    val (provider, file) = fixedSubsonicSourceProvider(context, RealTrackBytes.source())
    credentials = file
    return OfflineDownloads(
      OfflineRepository(db.offlineDao(), Clock.systemUTC()),
      provider,
      MuPlayDataSourceFactory(callFactory, streaming, offline),
      offline,
    )
  }

  private val playlist = Playlist("pl", "For the train", 2, 10, "admin", null)

  /** What playback reads for [song], through the shipped chain, with every request refused. */
  private fun playOffline(song: Song): ByteArray {
    val source = MuPlayDataSourceFactory(noNetwork, streaming, offline).create().createDataSource()
    val spec = DataSpec.Builder()
      .setUri("http://localhost:4533/rest/stream?id=${song.id}".toUri())
      .setKey(song.id)
      .build()
    source.open(spec)
    return try {
      DataSourceUtil.readToEnd(source)
    } finally {
      source.close()
    }
  }

  @Test
  fun aSavedPlaylistPlaysWithTheNetworkRefused(): Unit = runBlocking {
    val downloads = downloads(OkHttpClient())

    downloads.save(playlist, tracks)
    val saved = withTimeout(60_000) { downloads.saved().first { it.singleOrNull()?.isComplete == true } }

    assertThat(saved.single().downloaded).isEqualTo(2)
    assertThat(downloads.state.value).isEqualTo(DownloadActivity.Idle)
    tracks.forEach { song ->
      assertThat(playOffline(song)).describedAs(song.title).isEqualTo(RealTrackBytes.bytesOf(song))
    }
  }

  @Test
  fun anUnsavedSongIsNotServedOffline(): Unit = runBlocking {
    // The control for the test above: without the download, the same read must fail, or the test
    // above proves nothing about the offline cache.
    assertThatThrownBy { playOffline(tracks[0]) }.isInstanceOf(IOException::class.java)
  }

  @Test
  fun removingThePlaylistDeletesItsAudio(): Unit = runBlocking {
    val downloads = downloads(OkHttpClient())
    downloads.save(playlist, tracks)
    withTimeout(60_000) { downloads.saved().first { it.singleOrNull()?.isComplete == true } }

    downloads.remove(playlist.id)

    assertThat(offline.keys).isEmpty()
    assertThat(downloads.saved().first()).isEmpty()
    assertThatThrownBy { playOffline(tracks[0]) }.isInstanceOf(IOException::class.java)
  }

  @Test
  fun anUnreachableServerStallsAndSaysSoRatherThanMarkingAnything(): Unit = runBlocking {
    val downloads = downloads(noNetwork)

    downloads.save(playlist, tracks)
    val stalled = withTimeout(30_000) {
      downloads.state.first { it is DownloadActivity.Stalled }
    }

    assertThat((stalled as DownloadActivity.Stalled).reason).contains("no network")
    assertThat(downloads.saved().first().single().downloaded).isZero
  }

  @Test
  fun signingOutDeletesEverySavedSong(): Unit = runBlocking {
    val downloads = downloads(OkHttpClient())
    downloads.save(playlist, tracks)
    withTimeout(60_000) { downloads.saved().first { it.singleOrNull()?.isComplete == true } }

    downloads.clearAudio()

    assertThat(offline.keys).isEmpty()
    assertThat(offline.getCachedBytes(tracks[0].id, 0, C.LENGTH_UNSET.toLong())).isZero
  }
}
