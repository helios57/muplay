package io.github.helios57.muplay.database

import io.github.helios57.muplay.model.Album
import io.github.helios57.muplay.model.AlbumListType
import io.github.helios57.muplay.model.AlbumWithSongs
import io.github.helios57.muplay.model.Song
import io.github.helios57.muplay.network.SubsonicException
import io.github.helios57.muplay.network.SubsonicSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The three requests a sync needs, narrowed from [SubsonicSource] so the reading rules can be held
 * on the fast tier by a fake that implements three methods instead of twenty.
 */
interface LibraryReadSource {
  suspend fun albumPage(libraryId: Int, size: Int, offset: Int): List<Album>

  /** Null when the source cannot list songs in bulk; see [SubsonicSource.songPage]. */
  suspend fun songPage(libraryId: Int, size: Int, offset: Int): List<Song>?

  suspend fun album(albumId: String, libraryId: Int): AlbumWithSongs
}

internal fun SubsonicSource.asLibraryReadSource(): LibraryReadSource = object : LibraryReadSource {
  override suspend fun albumPage(libraryId: Int, size: Int, offset: Int) =
    getAlbumList2(libraryId, AlbumListType.ALPHABETICAL_BY_NAME, size, offset)

  override suspend fun songPage(libraryId: Int, size: Int, offset: Int) =
    this@asLibraryReadSource.songPage(libraryId, size, offset)

  override suspend fun album(albumId: String, libraryId: Int) = getAlbum(albumId, libraryId)
}

/** Everything one library holds, as read from the server, ready for one mirror transaction. */
data class LibraryContents(val albums: List<Album>, val songs: List<Song>)

/**
 * **Reads one library from the server: its albums, then its songs.**
 *
 * ### Why it is shaped like this
 *
 * The first version read songs with one `getAlbum` per album, strictly one after another. A real
 * library of 3,000 albums is 3,000 round trips, and over a phone's connection to a home server
 * that was most of an hour of a first run showing a counter crawl -- which the owner reported as
 * "the import is stuck". Now:
 *
 * 1. **Songs come in pages of [pageSize]** from `search3` with an empty query (see
 *    [SubsonicSource.songPage]). 12,000 songs is 24 requests.
 * 2. **The album listing is the check.** Each album says how many songs it has; an album the bulk
 *    listing returned short (a rescan moving rows between two pages, a server whose search index
 *    lags) is read on its own with `getAlbum`. Only those albums -- a healthy library pays nothing.
 * 3. **A source that cannot list in bulk** is read album by album as before, but [parallelism]
 *    at once instead of one.
 *
 * A song whose album the listing did not return is dropped rather than mirrored: the browse
 * screens reach songs through albums, so it would be a row nothing can show, and the next sync
 * after the rescan finishes picks it up with its album.
 *
 * ### Retries
 *
 * A transport failure ([IOException]) is retried after each of [retryDelaysMs]; a long first sync
 * over Wi-Fi meets a dropped connection eventually, and failing 2,000 requests of work over one is
 * the wrong trade. A server's *answer* ([SubsonicException]) is never retried -- asking again gets
 * the same answer, and code 70 for a library this account may not read is exactly that.
 */
class LibraryReader(
  private val source: LibraryReadSource,
  private val pageSize: Int,
  private val parallelism: Int,
  private val retryDelaysMs: List<Long>,
  private val onProgress: (SyncProgress) -> Unit,
) {

  init {
    require(pageSize > 0) { "pageSize must be positive, got $pageSize" }
    require(parallelism > 0) { "parallelism must be positive, got $parallelism" }
  }

  suspend fun read(libraryId: Int): LibraryContents {
    val albums = listAlbums(libraryId)
    val songs = readSongsInBulk(libraryId, albums) ?: readAlbumByAlbum(libraryId, albums)
    return LibraryContents(albums, songs)
  }

  private suspend fun listAlbums(libraryId: Int): List<Album> {
    val albums = mutableListOf<Album>()
    var page = 0
    while (page < MAX_PAGES) {
      // How many have been listed, never how many remain: this loop learns it was on the last page
      // only by getting a short one back, so there is no honest denominator to publish here.
      onProgress(SyncProgress.Listing(found = albums.size))
      val batch = retrying { source.albumPage(libraryId, pageSize, page * pageSize) }
      albums += batch
      // A short page is the last page. A past-the-end offset returns an empty list rather than an
      // error -- confirmed live, where the server sends `"albumList2": {}` with no album key.
      if (batch.size < pageSize) return albums.distinctBy { it.id }
      page++
    }
    // A server that keeps returning full pages forever would otherwise spin here until the
    // process died. Failing loudly at a bound nothing real reaches is the lesser evil.
    error("getAlbumList2 for library $libraryId did not terminate within $MAX_PAGES pages")
  }

  private suspend fun readSongsInBulk(libraryId: Int, albums: List<Album>): List<Song>? {
    val expected = albums.sumOf { it.songCount }
    val byId = LinkedHashMap<String, Song>()
    var offset = 0
    var page = 0
    while (true) {
      if (page >= MAX_SONG_PAGES) error("search3 for library $libraryId did not terminate")
      val batch = retrying { source.songPage(libraryId, pageSize, offset) } ?: return null
      batch.forEach { byId.putIfAbsent(it.id, it) }
      offset += batch.size
      page++
      onProgress(
        SyncProgress.Reading(minOf(byId.size, expected), expected, SyncProgress.Counted.SONGS),
      )
      if (batch.size < pageSize) break
    }

    val known = albums.associateBy { it.id }
    val listed = byId.values.filter { it.albumId in known }.groupBy { it.albumId }
    val short = albums.filter { (listed[it.id]?.size ?: 0) < it.songCount }
    val refilled = readAlbums(libraryId, short, report = false).associate { it.album.id to it.songs }

    // Album order, then the server's own order within each album -- the same order the
    // album-by-album path produces, so which path ran is invisible to everything downstream.
    return albums.flatMap { album -> refilled[album.id] ?: listed[album.id].orEmpty() }
  }

  private suspend fun readAlbumByAlbum(libraryId: Int, albums: List<Album>): List<Song> =
    readAlbums(libraryId, albums, report = true).flatMap { it.songs }

  /** [albums]' songs, [parallelism] requests at a time, returned in [albums]' order. */
  private suspend fun readAlbums(
    libraryId: Int,
    albums: List<Album>,
    report: Boolean,
  ): List<AlbumWithSongs> = coroutineScope {
    if (albums.isEmpty()) return@coroutineScope emptyList()
    val permits = Semaphore(parallelism)
    val done = AtomicInteger()
    if (report) onProgress(SyncProgress.Reading(0, albums.size, SyncProgress.Counted.ALBUMS))
    albums.map { album ->
      async {
        permits.withPermit {
          retrying { source.album(album.id, libraryId) }.also {
            val finished = done.incrementAndGet()
            if (report) {
              onProgress(SyncProgress.Reading(finished, albums.size, SyncProgress.Counted.ALBUMS))
            }
          }
        }
      }
    }.awaitAll()
  }

  private suspend fun <T> retrying(block: suspend () -> T): T {
    var attempt = 0
    while (true) {
      try {
        return block()
      } catch (e: IOException) {
        if (attempt >= retryDelaysMs.size) throw e
        delay(retryDelaysMs[attempt])
        attempt++
      }
    }
  }

  companion object {
    const val MAX_PAGES = 200

    /** 500 pages of 500 is 250,000 songs: a bound nothing real reaches, so a loop cannot spin. */
    const val MAX_SONG_PAGES = 500
  }
}
