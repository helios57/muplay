package io.github.helios57.muplay.database

import io.github.helios57.muplay.model.Album
import io.github.helios57.muplay.model.AlbumWithSongs
import io.github.helios57.muplay.model.Song
import io.github.helios57.muplay.network.SubsonicErrorException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [LibraryReader] is the part of a sync that talks to the server: list a library's albums, then
 * its songs. It used to make one `getAlbum` request per album, one after another, which on a real
 * library of a few thousand albums is most of an hour of a first run looking stuck.
 *
 * Every rule here is about the number and shape of requests, so the fake counts them.
 */
class LibraryReaderTest {

  private class FakeSource(
    private val albums: List<Album>,
    private val songs: List<Song>,
    private val bulk: Boolean = true,
  ) : LibraryReadSource {
    val albumListCalls = AtomicInteger()
    val songPageCalls = AtomicInteger()
    val getAlbumCalls = AtomicInteger()
    val getAlbumIds = mutableListOf<String>()
    var inFlight = AtomicInteger()
    var maxInFlight = 0
    var failNextGetAlbums = 0
    var failSongPagesWith: Exception? = null
    var transientSongPageFailures = 0

    override suspend fun albumPage(libraryId: Int, size: Int, offset: Int): List<Album> {
      albumListCalls.incrementAndGet()
      return albums.drop(offset).take(size)
    }

    override suspend fun songPage(libraryId: Int, size: Int, offset: Int): List<Song>? {
      if (!bulk) return null
      songPageCalls.incrementAndGet()
      failSongPagesWith?.let { throw it }
      if (transientSongPageFailures > 0) {
        transientSongPageFailures--
        throw IOException("connection reset")
      }
      return songs.drop(offset).take(size)
    }

    override suspend fun album(albumId: String, libraryId: Int): AlbumWithSongs {
      getAlbumCalls.incrementAndGet()
      synchronized(getAlbumIds) { getAlbumIds += albumId }
      val now = inFlight.incrementAndGet()
      synchronized(this) { maxInFlight = maxOf(maxInFlight, now) }
      try {
        delay(10)
        if (failNextGetAlbums > 0) {
          failNextGetAlbums--
          throw IOException("timeout")
        }
        return AlbumWithSongs(albums.first { it.id == albumId }, songs.filter { it.albumId == albumId })
      } finally {
        inFlight.decrementAndGet()
      }
    }
  }

  private fun album(id: String, songCount: Int) = Album(
    id = id, libraryId = 1, name = "Album $id", artistId = "ar", artistName = "Artist",
    coverArtId = null, songCount = songCount, durationSeconds = 0,
  )

  private fun song(id: String, albumId: String) = Song(
    id = id, libraryId = 1, title = "Song $id", albumId = albumId, albumName = "Album $albumId",
    artistId = "ar", artistName = "Artist", trackNumber = null, discNumber = null,
    durationSeconds = 60, suffix = "mp3", coverArtId = null,
  )

  /** [albumCount] albums of [perAlbum] songs each. */
  private fun library(albumCount: Int, perAlbum: Int): Pair<List<Album>, List<Song>> {
    val albums = (1..albumCount).map { album("a$it", perAlbum) }
    val songs = albums.flatMap { a -> (1..perAlbum).map { song("${a.id}-s$it", a.id) } }
    return albums to songs
  }

  private fun reader(source: LibraryReadSource, progress: (SyncProgress) -> Unit = {}) =
    LibraryReader(source, pageSize = 500, parallelism = 4, retryDelaysMs = listOf(0, 0), onProgress = progress)

  @Test
  fun `songs come in pages of five hundred, not one request per album`() = runTest {
    val (albums, songs) = library(albumCount = 300, perAlbum = 4)
    val source = FakeSource(albums, songs)

    val read = reader(source).read(libraryId = 1)

    assertThat(read.songs).hasSize(1200)
    // 1200 songs: pages at 0, 500, 1000 -- the third short, so it is the last.
    assertThat(source.songPageCalls.get()).isEqualTo(3)
    assertThat(source.getAlbumCalls.get()).isZero
    assertThat(read.albums).hasSize(300)
  }

  @Test
  fun `an album the bulk listing returned short is read on its own, and only that album`() = runTest {
    val (albums, songs) = library(albumCount = 10, perAlbum = 3)
    // The bulk listing misses one song of a5 -- a page boundary moving under a server rescan.
    val source = FakeSource(albums, songs.filterNot { it.id == "a5-s2" }).also {
      // ...but getAlbum knows the whole album.
    }
    val full = FakeSource(albums, songs)
    val combined = object : LibraryReadSource by source {
      override suspend fun album(albumId: String, libraryId: Int) = full.album(albumId, libraryId)
    }

    val read = reader(combined).read(libraryId = 1)

    assertThat(read.songs.map { it.id }).contains("a5-s2").hasSize(30).doesNotHaveDuplicates()
    assertThat(full.getAlbumIds).containsExactly("a5")
  }

  @Test
  fun `a song listed twice across pages is kept once`() = runTest {
    val (albums, songs) = library(albumCount = 2, perAlbum = 2)
    val source = FakeSource(albums, songs + songs.first())

    val read = reader(source).read(libraryId = 1)

    assertThat(read.songs.map { it.id }).doesNotHaveDuplicates().hasSize(4)
  }

  @Test
  fun `a song whose album the listing did not return is left out rather than orphaned`() = runTest {
    val (albums, songs) = library(albumCount = 2, perAlbum = 2)
    val source = FakeSource(albums, songs + song("stray", albumId = "gone"))

    val read = reader(source).read(libraryId = 1)

    assertThat(read.songs.map { it.id }).doesNotContain("stray")
  }

  @Test
  fun `a source that cannot list songs in bulk is read album by album, several at once`() = runTest {
    val (albums, songs) = library(albumCount = 20, perAlbum = 2)
    val source = FakeSource(albums, songs, bulk = false)

    val read = reader(source).read(libraryId = 1)

    assertThat(read.songs).hasSize(40)
    assertThat(source.getAlbumCalls.get()).isEqualTo(20)
    assertThat(source.maxInFlight).isGreaterThan(1).isLessThanOrEqualTo(4)
  }

  @Test
  fun `the album-by-album fallback keeps the server's album order`() = runTest {
    val (albums, songs) = library(albumCount = 12, perAlbum = 1)
    val source = FakeSource(albums, songs, bulk = false)

    val read = reader(source).read(libraryId = 1)

    assertThat(read.songs.map { it.albumId }).containsExactlyElementsOf(albums.map { it.id })
  }

  @Test
  fun `a transient transport failure is retried instead of failing the whole sync`() = runTest {
    val (albums, songs) = library(albumCount = 3, perAlbum = 1)
    val source = FakeSource(albums, songs).apply { transientSongPageFailures = 2 }

    val read = reader(source).read(libraryId = 1)

    assertThat(read.songs).hasSize(3)
    assertThat(source.songPageCalls.get()).isEqualTo(3)
  }

  @Test
  fun `a transport failure that outlasts the retries is still a failure`() = runTest {
    val (albums, songs) = library(albumCount = 3, perAlbum = 1)
    val source = FakeSource(albums, songs).apply { transientSongPageFailures = 99 }

    assertThatThrownBy { kotlinx.coroutines.runBlocking { reader(source).read(libraryId = 1) } }
      .isInstanceOf(IOException::class.java)
  }

  @Test
  fun `a server's refusal is not retried, because asking again gets the same answer`() = runTest {
    val (albums, songs) = library(albumCount = 3, perAlbum = 1)
    val source = FakeSource(albums, songs).apply {
      failSongPagesWith = SubsonicErrorException(70, "Library 1 not found or not accessible")
    }

    assertThatThrownBy { kotlinx.coroutines.runBlocking { reader(source).read(libraryId = 1) } }
      .isInstanceOf(SubsonicErrorException::class.java)
    assertThat(source.songPageCalls.get()).isEqualTo(1)
  }

  @Test
  fun `progress counts songs against the total the album listing promised`() = runTest {
    val (albums, songs) = library(albumCount = 300, perAlbum = 4)
    val seen = mutableListOf<SyncProgress>()

    reader(FakeSource(albums, songs)) { seen += it }.read(libraryId = 1)

    val reading = seen.filterIsInstance<SyncProgress.Reading>()
    assertThat(reading.map { it.unit }).containsOnly(SyncProgress.Counted.SONGS)
    assertThat(reading.map { it.done }).containsSubsequence(500, 1000, 1200)
    assertThat(reading.map { it.total }).containsOnly(1200)
  }

  @Test
  fun `the fallback counts albums, which is what it is actually reading`() = runTest {
    val (albums, songs) = library(albumCount = 5, perAlbum = 1)
    val seen = mutableListOf<SyncProgress>()

    reader(FakeSource(albums, songs, bulk = false)) { seen += it }.read(libraryId = 1)

    val reading = seen.filterIsInstance<SyncProgress.Reading>()
    assertThat(reading.map { it.unit }).containsOnly(SyncProgress.Counted.ALBUMS)
    assertThat(reading.last()).isEqualTo(SyncProgress.Reading(5, 5, SyncProgress.Counted.ALBUMS))
  }

  @Test
  fun `an empty library is read in one album request and one song request`() = runTest {
    val source = FakeSource(emptyList(), emptyList())

    val read = reader(source).read(libraryId = 1)

    assertThat(read.albums).isEmpty()
    assertThat(read.songs).isEmpty()
    assertThat(source.albumListCalls.get()).isEqualTo(1)
    assertThat(source.songPageCalls.get()).isLessThanOrEqualTo(1)
  }
}
