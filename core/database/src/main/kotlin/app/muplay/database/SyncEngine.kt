package app.muplay.database

import app.muplay.database.dao.BrowseDao
import app.muplay.database.dao.MirrorReplacement
import app.muplay.database.dao.SyncWatermarkDao
import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.network.SubsonicSource
import kotlinx.coroutines.CancellationException

/**
 * Keeps the local mirror in step with the server.
 *
 * Not `@Inject constructor`: [albumPageSize] is a plain `Int` with no sensible Hilt binding, and
 * making it injectable would mean a qualifier and a `@Provides` for a number. `DataModule`
 * constructs this instead, which also lets a test pass a page size of 1 and exercise the paging
 * loop with two albums rather than 501.
 */
class SyncEngine(
  private val libraryRepository: LibraryRepository,
  private val browseDao: BrowseDao,
  private val watermarkDao: SyncWatermarkDao,
  private val sourceProvider: SubsonicSourceProvider,
  private val albumPageSize: Int,
) {

  /**
   * Reconciles every library if — and only if — the server's `lastScan` has moved since the last
   * committed reconcile.
   *
   * Never throws for an expected failure: a coroutine started by a ViewModel would turn one into
   * a crash rather than into something a screen can render, so everything except cancellation
   * becomes [SyncState.Failed].
   */
  suspend fun syncIfStale(): SyncState = try {
    val source = sourceProvider.current()
    when (val decision = SyncDecision.decide(watermarkDao.read(), source.getScanStatus())) {
      SyncDecision.UpToDate -> SyncState.UpToDate
      SyncDecision.ScanInProgress -> SyncState.ScanInProgress
      is SyncDecision.Reconcile -> reconcile(source, decision.watermark)
    }
  } catch (e: CancellationException) {
    // Cancelling the caller's scope is not a sync failure and must not be reported as one.
    throw e
  } catch (e: Exception) {
    SyncState.Failed(e)
  }

  private suspend fun reconcile(source: SubsonicSource, watermark: String?): SyncState {
    // Libraries first: a library added on the server since the last sync has to exist locally
    // before there is anything to reconcile into, and `mergeFromServer` leaves existing roles
    // alone.
    libraryRepository.refreshFromServer()

    val results = mutableMapOf<Int, MirrorReplacement>()
    for (libraryId in libraryRepository.allIds()) {
      results[libraryId] = reconcileLibrary(source, libraryId)
    }

    // Last, and only now. Advancing the watermark before the transactions commit would mean a
    // failed sync is never retried and the mirror stays permanently stale. A null watermark is
    // deliberately not stored -- see `SyncDecision.Reconcile`.
    watermark?.let { watermarkDao.store(it) }
    return SyncState.Synced(results)
  }

  /**
   * **The named decision on an empty fetch:** if [fetchAllAlbums] genuinely returns no albums for
   * [libraryId], this reconciles the library to empty anyway rather than skipping the write.
   *
   * `BrowseDao.replaceLibraryContents`'s own kdoc is explicit that it cannot tell "the library
   * truly emptied out on the server" apart from "the caller passed empty lists by mistake" — that
   * distinction is deferred to the caller, i.e. here. [fetchAllAlbums] only ever returns an empty
   * list two ways: [libraryId] genuinely has zero albums right now (Subsonic sends
   * `"albumList2": {}` for that, confirmed live — not an error), or something failed. A failure —
   * a network error, a non-2xx response, a malformed body — surfaces from [SubsonicSource] as a
   * thrown exception, not as a silently-empty list, and propagates out of [syncIfStale] to
   * [SyncState.Failed] before this function is ever called again with a bad watermark. So an
   * empty list reaching this point *is* the server's real answer, and this is the one caller
   * `replaceLibraryContents`'s own doc says must accept it: a library that has actually gone to
   * zero albums (every track removed, or the folder itself deleted on the server) is exactly what
   * a full reconcile exists to notice, since Subsonic has no delta primitive that would ever
   * report that deletion otherwise. Silently refusing to write here would mean a genuinely-emptied
   * library never reflects that locally, no matter how many times sync runs — the same
   * permanently-stale failure mode `SyncDecision`'s own doc warns about, just for one library
   * instead of the whole mirror. See `SyncEngineTest.aLibraryThatBecomesEmptyOnTheServerIsWipedLocally`.
   */
  private suspend fun reconcileLibrary(source: SubsonicSource, libraryId: Int): MirrorReplacement {
    val albums = fetchAllAlbums(source, libraryId)
    val songs = albums.flatMap { source.getAlbum(it.id, libraryId).songs }

    // One transaction per library: a failure reconciling the audiobook library must not be able
    // to empty the music library, and a library is the unit the user actually reasons about.
    return browseDao.replaceLibraryContents(
      libraryId = libraryId,
      artists = MirrorMapper.artistEntities(albums),
      albums = albums.map(MirrorMapper::albumEntity),
      songs = songs.map(MirrorMapper::songEntity),
    )
  }

  private suspend fun fetchAllAlbums(source: SubsonicSource, libraryId: Int): List<Album> {
    val albums = mutableListOf<Album>()
    var page = 0
    while (page < MAX_PAGES) {
      val batch = source.getAlbumList2(
        musicFolderId = libraryId,
        type = AlbumListType.ALPHABETICAL_BY_NAME,
        size = albumPageSize,
        offset = page * albumPageSize,
      )
      albums += batch
      // A short page is the last page. A past-the-end offset returns an empty list rather than an
      // error -- confirmed live, where the server sends `"albumList2": {}` with no album key.
      if (batch.size < albumPageSize) return albums
      page++
    }
    // A server that keeps returning full pages forever would otherwise spin here until the
    // process died. Failing loudly at a bound nothing real reaches is the lesser evil, and it
    // becomes SyncState.Failed rather than a hang the user cannot interpret.
    error("getAlbumList2 for library $libraryId did not terminate within $MAX_PAGES pages")
  }

  companion object {
    /** 200 pages of 500 is 100,000 albums — far past any real library, and not infinity. */
    const val MAX_PAGES = 200
  }
}
