package io.github.helios57.muplay.database

import io.github.helios57.muplay.model.Album
import io.github.helios57.muplay.model.AlbumListType
import io.github.helios57.muplay.model.AlbumWithSongs
import io.github.helios57.muplay.model.MusicLibrary
import io.github.helios57.muplay.model.Playlist
import io.github.helios57.muplay.model.PlaylistWithSongs
import io.github.helios57.muplay.model.ScanStatus
import io.github.helios57.muplay.model.SearchResults
import io.github.helios57.muplay.model.ServerCapabilities
import io.github.helios57.muplay.model.ServerInfo
import io.github.helios57.muplay.model.Song
import io.github.helios57.muplay.model.StreamFormat
import io.github.helios57.muplay.network.SubsonicSource

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

  /**
   * Run just before each call is answered, with the call's name.
   *
   * The only way to observe something that is true *during* a sync and false once it returns --
   * `SyncEngine.progress` being the case this was added for. Sampling after `syncIfStale()` has
   * returned can only ever see the final value, which is precisely the value a progress bar must
   * not be judged by.
   */
  var beforeCall: ((String) -> Unit)? = null

  private fun record(call: String) {
    beforeCall?.invoke(call)
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

  /**
   * The sync engine does not mirror playlists -- they are read live by their own repository, and
   * nothing in this suite exercises them. Returning empty rather than throwing keeps a future
   * caller from mistaking "the fake does not model this" for "the server has none"; the two are
   * different answers and only one of them is a defect worth a loud failure.
   */
  /** Playlists this fake server holds, by id. `RatingRepositoryTest` mutates it as the app writes. */
  var playlists: MutableMap<String, PlaylistWithSongs> = linkedMapOf()

  /** Ids handed out by [createPlaylist], in order, so a test can name the one it expects. */
  var nextPlaylistId: String = "created-1"

  override suspend fun getPlaylists(): List<Playlist> {
    record("getPlaylists()")
    return playlists.values.map { it.playlist }
  }

  override suspend fun getPlaylist(playlistId: String, musicFolderId: Int): PlaylistWithSongs {
    record("getPlaylist($playlistId)")
    return playlists[playlistId] ?: error("no such playlist: $playlistId")
  }

  /** Nothing in the sync engine negotiates capabilities; `TranscodeOffsetSupport` is the caller. */
  /**
   * The three rating writes, kept as a **working fake server** rather than as `error(..)` stubs.
   *
   * `RatingRepository`'s whole job is the sequence of requests it makes -- read the playlists, read
   * one playlist's entries, then create or add or remove -- and the defect it exists to prevent is a
   * duplicate entry, which is a property of the *server's* state after several taps. A recording
   * stub could assert the calls; only a fake that actually keeps the playlist can show that tapping
   * a thumb up twice leaves one copy.
   */
  override suspend fun setRating(songId: String, rating: Int) {
    record("setRating($songId, $rating)")
  }

  override suspend fun createPlaylist(name: String, songIds: List<String>): Playlist {
    record("createPlaylist($name, $songIds)")
    val playlist = Playlist(
      id = nextPlaylistId,
      name = name,
      songCount = songIds.size,
      durationSeconds = 0,
      owner = null,
      coverArtId = null,
    )
    playlists[playlist.id] = PlaylistWithSongs(playlist, songIds.map(::stubSong))
    return playlist
  }

  override suspend fun updatePlaylist(
    playlistId: String,
    songIdsToAdd: List<String>,
    songIndexesToRemove: List<Int>,
  ) {
    record("updatePlaylist($playlistId, add=$songIdsToAdd, remove=$songIndexesToRemove)")
    val existing = playlists[playlistId] ?: error("no such playlist: $playlistId")
    // Both halves of the measured server behaviour: the removals resolve against the list as it
    // was, and an add appends **unconditionally** -- adding a song the playlist already holds
    // leaves two copies. That second half is the whole reason `PromotedPlaylist` checks membership,
    // so a fake that de-duplicated here would make the bug untestable.
    val kept = existing.songs.filterIndexed { index, _ -> index !in songIndexesToRemove }
    playlists[playlistId] = existing.copy(songs = kept + songIdsToAdd.map(::stubSong))
  }

  private fun stubSong(id: String) = Song(
    id = id,
    libraryId = 1,
    title = id,
    albumId = null,
    albumName = null,
    artistId = null,
    artistName = null,
    trackNumber = null,
    discNumber = null,
    durationSeconds = 1,
    suffix = "mp3",
    coverArtId = null,
  )

  override suspend fun capabilities(): ServerCapabilities = error("not used by the sync suite")
}
