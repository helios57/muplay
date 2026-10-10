package io.github.helios57.muplay.database

import io.github.helios57.muplay.database.dao.BrowseDao
import io.github.helios57.muplay.database.dao.MirrorReplacement
import io.github.helios57.muplay.database.dao.SyncWatermarkDao
import io.github.helios57.muplay.network.SubsonicClient
import io.github.helios57.muplay.network.SubsonicErrorException
import io.github.helios57.muplay.network.SubsonicSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
  private val parallelism: Int = DEFAULT_PARALLELISM,
  private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {

  private val _progress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)

  /**
   * How far the sync currently running has got — [SyncProgress.Idle] when none is.
   *
   * A flow rather than something [syncIfStale] returns, because every value of it that matters is
   * true only *while* that call is still suspended. The browse screen draws its first-run progress
   * bar from this; see [SyncProgress] for why the counters are per library and why the states that
   * cannot count themselves say so instead of guessing.
   *
   * There is one of these per [SyncEngine], and `DataModule` provides the engine as a `@Singleton`,
   * so every screen observing it sees the same sync. Nothing here serialises two concurrent
   * [syncIfStale] calls, and nothing needs to: the only caller is `LibraryViewModel`, which starts
   * one from `init` and one per Refresh tap.
   */
  val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

  init {
    // F-3 in task-6-review.md: `fetchAllAlbums`'s "was that the last page" check compares the
    // batch it got back against this value, but `SubsonicClient.getAlbumList2` silently clamps
    // whatever it is sent to the same range. Left unguarded, a value above the clamp makes every
    // full page from the server look short (the reconcile then deletes everything past the
    // clamp from the mirror, silently and successfully) and a value below it never sees a short
    // page at all (every sync fails at `MAX_PAGES`). Both are now construction-time failures.
    require(albumPageSize in 1..SubsonicClient.MAX_ALBUM_LIST_PAGE) {
      "albumPageSize must be in 1..${SubsonicClient.MAX_ALBUM_LIST_PAGE} (SubsonicClient's own " +
        "clamp) -- got $albumPageSize"
    }
  }

  /**
   * Reconciles every library if — and only if — the server's `lastScan` has moved since the last
   * committed reconcile.
   *
   * Never throws for an expected failure: a coroutine started by a ViewModel would turn one into
   * a crash rather than into something a screen can render, so everything except cancellation
   * becomes [SyncState.Failed].
   */
  /**
   * One sync at a time. A sign-in can ask for one while the start-up sync is still running, and
   * two reconciles interleaving their per-library transactions would each commit a mirror the
   * other is halfway through replacing. The second caller waits and then asks the server again,
   * which after the first has finished is almost always "up to date".
   */
  private val running = Mutex()

  suspend fun syncIfStale(): SyncState = running.withLock { syncOnce() }

  private suspend fun syncOnce(): SyncState = try {
    // Before the first round trip: `sourceProvider.current()` and `getScanStatus` are themselves a
    // wait the user is looking at, and an up-to-date sync never gets past them.
    _progress.value = SyncProgress.Preparing
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
  } finally {
    // `finally`, so a failure and a cancellation clear it too. A progress value left behind is a
    // bar frozen part-way for the life of the process, saying "still working" over a sync that
    // has already given up and reported why -- see
    // `SyncEngineTest.aFailedSyncLeavesNoProgressRunningBehindIt`.
    _progress.value = SyncProgress.Idle
  }

  private suspend fun reconcile(source: SubsonicSource, watermark: String?): SyncState {
    // Libraries first: a library added on the server since the last sync has to exist locally
    // before there is anything to reconcile into, and `mergeFromServer` leaves existing roles
    // alone.
    libraryRepository.refreshFromServer()

    val reader = LibraryReader(
      source = source.asLibraryReadSource(),
      pageSize = albumPageSize,
      parallelism = parallelism,
      retryDelaysMs = retryDelaysMs,
      onProgress = { _progress.value = it },
    )
    val results = mutableMapOf<Int, MirrorReplacement>()
    val refused = mutableMapOf<Int, SubsonicErrorException>()
    var failure: Exception? = null
    for (libraryId in libraryRepository.allIds()) {
      try {
        // One transaction per library: a failure reconciling the audiobook library must not be
        // able to empty the music library, and a library is the unit the user actually reasons
        // about.
        val contents = reader.read(libraryId)
        results[libraryId] = browseDao.replaceLibraryContents(
          libraryId = libraryId,
          artists = MirrorMapper.artistEntities(contents.albums),
          albums = contents.albums.map(MirrorMapper::albumEntity),
          songs = contents.songs.map(MirrorMapper::songEntity),
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: SubsonicErrorException) {
        // **One library this account may not read is not a failed sync.** Navidrome answers code
        // 70 ("Library 2 not found or not accessible") for a library the user has not been
        // granted, and the first version let that one answer fail every library -- an account
        // restricted to some libraries could never finish a first sync at all. The others are
        // read and committed; this one's mirror is left exactly as it was.
        if (e.code in ACCESS_REFUSED_CODES) refused[libraryId] = e else failure = failure ?: e
      } catch (e: Exception) {
        // Keep going: the libraries after this one can still be read, and what is read is
        // committed. The sync is still reported failed, and the watermark not advanced, so the
        // next one retries.
        failure = failure ?: e
      }
    }

    failure?.let { return SyncState.Failed(it) }
    // Last, and only now. Advancing the watermark before the transactions commit would mean a
    // failed sync is never retried and the mirror stays permanently stale. A null watermark is
    // deliberately not stored -- see `SyncDecision.Reconcile`. A refused library does not hold it
    // back: asking again gets the same refusal until somebody changes the account on the server.
    watermark?.let { watermarkDao.store(it) }
    return SyncState.Synced(results, refused = refused.mapValues { it.value.message })
  }

  companion object {
    /** 200 pages of 500 is 100,000 albums — far past any real library, and not infinity. */
    const val MAX_PAGES = LibraryReader.MAX_PAGES

    /**
     * Requests in flight at once when a library has to be read album by album. Six is what a
     * browser allows per host: enough to hide a phone's round-trip time, few enough that a
     * Raspberry Pi running Navidrome does not notice.
     */
    const val DEFAULT_PARALLELISM = 6

    /** A dropped connection is retried twice, after one second and then three. */
    val DEFAULT_RETRY_DELAYS_MS = listOf(1_000L, 3_000L)

    /** 50 "not authorized", 70 "not found" -- which is what a library not shared with you is. */
    private val ACCESS_REFUSED_CODES = setOf(50, 70)
  }
}
