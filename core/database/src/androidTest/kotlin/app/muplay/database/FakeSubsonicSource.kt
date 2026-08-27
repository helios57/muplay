package app.muplay.database

import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.model.AlbumWithSongs
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.SearchResults
import app.muplay.model.ServerCapabilities
import app.muplay.model.ServerInfo
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.network.SubsonicSource

/**
 * A hand-written fake, not a mock: no framework, no stubbing DSL, no verification API — a real
 * object with real fields whose behaviour is visible by reading it.
 *
 * It exists because of one requirement no real server can satisfy on demand: the sync engine must
 * not advance its watermark when a reconcile fails **part-way through**, and a live Navidrome
 * cannot be asked to fail on the fourth of seven calls. [failAfterCalls] does exactly that.
 * Everything else here is served out of plain maps, so a test's setup reads as data.
 *
 * Counting calls in [callLog] is deliberately not a verification API in disguise: the sync tests
 * assert on the *database's* contents, and the log exists so a failure message can say what the
 * engine actually asked for.
 */
class FakeSubsonicSource : SubsonicSource {

  var musicFolders: List<MusicLibrary> = emptyList()
  var scanStatus: ScanStatus = ScanStatus(isScanning = false, scannedCount = 0, lastScan = "s0")
  /** Albums per library id, in the order `getAlbumList2` should page through them. */
  var albumsByLibrary: Map<Int, List<Album>> = emptyMap()
  /** Songs per album id. */
  var songsByAlbum: Map<String, List<Song>> = emptyMap()
  var randomSongsByLibrary: Map<Int, List<Song>> = emptyMap()
  var searchResults: SearchResults = SearchResults(emptyList(), emptyList(), emptyList())

  /** After this many calls to any method, every further call throws. `null` disables it. */
  var failAfterCalls: Int? = null

  /**
   * When set, every further call throws exactly this -- unlike [failAfterCalls] (a forced
   * `IOException`, standing in for a network failure), this exists so a test can throw a specific
   * type, most importantly a real `CancellationException` (task-6-review.md F-6): no other path
   * in this fake can produce one, and `SyncEngine.syncIfStale`'s own cancellation clause needs a
   * genuine instance to prove it rethrows rather than being silently swallowed by the generic
   * `catch (e: Exception)` beneath it.
   */
  var failWith: Throwable? = null

  val callLog: MutableList<String> = mutableListOf()

  private fun record(call: String) {
    callLog += call
    failWith?.let { throw it }
    val limit = failAfterCalls
    if (limit != null && callLog.size > limit) {
      throw java.io.IOException("FakeSubsonicSource: forced failure after $limit calls")
    }
  }

  override suspend fun ping(): ServerInfo {
    record("ping")
    return ServerInfo("navidrome", "0.63.2", "1.16.1", isOpenSubsonic = true)
  }

  override suspend fun getMusicFolders(): List<MusicLibrary> {
    record("getMusicFolders")
    return musicFolders
  }

  override suspend fun getScanStatus(): ScanStatus {
    record("getScanStatus")
    return scanStatus
  }

  override suspend fun getAlbumList2(
    musicFolderId: Int,
    type: AlbumListType,
    size: Int,
    offset: Int,
  ): List<Album> {
    record("getAlbumList2($musicFolderId, offset=$offset, size=$size)")
    return albumsByLibrary[musicFolderId].orEmpty().drop(offset).take(size)
  }

  override suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs {
    record("getAlbum($albumId)")
    val album = albumsByLibrary[musicFolderId].orEmpty().first { it.id == albumId }
    return AlbumWithSongs(album, songsByAlbum[albumId].orEmpty())
  }

  override suspend fun search3(
    query: String,
    musicFolderId: Int,
    artistCount: Int,
    albumCount: Int,
    songCount: Int,
  ): SearchResults {
    record("search3($query, $musicFolderId)")
    return searchResults
  }

  override suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song> {
    record("getRandomSongs($musicFolderId, size=$size)")
    return randomSongsByLibrary[musicFolderId].orEmpty().take(size)
  }

  override fun coverArtUrl(coverArtId: String, sizePx: Int?): String =
    "https://fake.invalid/rest/getCoverArt?id=$coverArtId" + (sizePx?.let { "&size=$it" } ?: "")

  /**
   * A synthetic stream URL. Deliberately carries **no** `t`/`s`/`u`: a fake that produced a
   * realistic-looking credential would put one in a test's failure output and, sooner or later,
   * in a committed expectation. The real thing is `SubsonicClient.streamUrl`, and `StreamUrlTest`
   * is where its auth parameters are asserted.
   */
  override fun streamUrl(songId: String, format: StreamFormat, timeOffsetSeconds: Int?): String =
    "https://fake.invalid/rest/stream?id=$songId&format=${format.wireValue}" +
      (timeOffsetSeconds?.let { "&timeOffset=$it" } ?: "")

  /** Nothing in the sync engine negotiates capabilities; `TranscodeOffsetSupport` is the caller. */
  override suspend fun capabilities(): ServerCapabilities = error("not used by the sync suite")
}
