package io.github.helios57.muplay.media

import io.github.helios57.muplay.database.AudiobookRepository
import io.github.helios57.muplay.database.BookFinish
import io.github.helios57.muplay.database.dao.BookSettingsDao
import io.github.helios57.muplay.database.dao.MediaProgressDao
import io.github.helios57.muplay.database.entity.BookSettingsEntity
import io.github.helios57.muplay.database.entity.MediaProgressEntity
import io.github.helios57.muplay.model.BookSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * One audiobook file, and everything the player needs to know about it, in memory.
 *
 * `speed` and `skipSilence` come from `book_settings` -- the **book's** grain -- and are carried on
 * the item because the player only ever knows a media id. `media_progress.speed` is deliberately
 * not consulted; see [BookSettings]'s own documentation for why that column is the wrong grain, and
 * `BookSettingsDao`'s for the same statement from the storage side.
 */
data class AudiobookItem(
  val mediaId: String,
  val bookId: String,
  val positionMs: Long,
  val lastPlayedAtEpochMs: Long,
  /**
   * Whether the **book** is finished -- `BookFinish`'s rule, the shelf's own -- not this file's row.
   * It is the same on every file of a book: pressing Resume on any part of a finished book is
   * "listen again", and the answer must not depend on which part the caller happened to name.
   */
  val isFinished: Boolean,
  val speed: Float,
  val skipSilence: Boolean,
)

/**
 * The one question [AudiobookResumePolicy] asks, and the whole of what it is allowed to ask.
 *
 * A `fun interface` rather than [AudiobookSnapshot] itself, and that is what puts the resume
 * decision on the **fast** tier: a `Map` standing in here is a complete implementation of a
 * one-method interface, not a stand-in for one, so `AudiobookResumePolicyTest` is a JVM test with
 * no Room, no Android and no emulator in it. The Room plumbing is gated separately, on a device,
 * by `AudiobookSnapshotTest`.
 *
 * It is also what lets `MediaModule.provideUndecoratedResumePolicy` be **called** by a JVM test --
 * see that provider, and `MediaModuleTest`'s assertion that the shipped binding really resumes.
 * A provider taking the concrete snapshot could only be exercised behind an emulator, and the plan
 * that wrote this task expected exactly that and recorded the binding as ungated.
 */
fun interface AudiobookItemSource {
  /** `null` means "not an audiobook", which is how music restarts from zero structurally. */
  fun itemFor(mediaId: String): AudiobookItem?
}

/**
 * An in-memory view of every audiobook file's position and settings, kept current by a Flow
 * collector.
 *
 * It exists because Plan 3's [ResumePolicy] contract forbids blocking: `MuPlayer` calls `resolve`
 * from `setMediaItems`, on the player's application thread, and a Room query there janks the UI.
 *
 * **Two traps, both of which produce the exact defect this application exists to fix:**
 *
 * 1. *A cold snapshot resumes nothing.* If the first `setMediaItems` beats the collector's first
 *    emission, every book starts at zero -- reproducing once a month on a slow device and never in
 *    a test that happens to warm it up first. [refresh] is a one-shot read the play path calls
 *    **before** building a queue; [awaitLoaded] is for a caller that genuinely has to wait.
 * 2. *A snapshot that knows about everything makes music resume.* The map is keyed off
 *    `AudiobookRepository.observeAudiobookItems()`, which is every file in a library the user
 *    tagged `AUDIOBOOKS` and nothing else -- so a music id has no entry and the policy has nothing
 *    to honour. Spec section 3's *"music restarts from 0"* is then structural rather than a branch
 *    somebody can delete.
 *
 * **[isLoaded] latches and never clears**, which is deliberate: it answers *"has this snapshot ever
 * had a real answer"*, not *"is it fresh"*. A caller that needs fresh calls [refresh].
 */
@Singleton
class AudiobookSnapshot @Inject constructor(
  private val audiobookRepository: AudiobookRepository,
  private val mediaProgressDao: MediaProgressDao,
  private val bookSettingsDao: BookSettingsDao,
) : AudiobookItemSource {

  /**
   * `@Volatile` because the writer is a coroutine on whatever dispatcher [start]'s scope carries
   * and the reader is the player's application thread. Replaced wholesale, never mutated: a reader
   * either sees the previous map or the next one, and never a half-built one.
   */
  @Volatile
  private var current: Map<String, AudiobookItem> = emptyMap()

  private var collector: Job? = null

  private val loaded = CompletableDeferred<Unit>()

  val isLoaded: Boolean get() = loaded.isCompleted

  /**
   * Starts the collector, once.
   *
   * Idempotent rather than "call it once and be careful": `MuPlaybackService.onCreate` runs again
   * after the service is destroyed and recreated, and a second collector over the same `@Singleton`
   * would be two writers racing for one field for the rest of the process's life.
   */
  fun start(scope: CoroutineScope) {
    if (collector != null) return
    collector = scope.launch {
      combine(
        audiobookRepository.observeAudiobookItems(),
        audiobookRepository.observeBookFileOrder(),
        mediaProgressDao.observeAll(),
        bookSettingsDao.observeAll(),
      ) { bookIds, order, progress, settings -> build(bookIds, order, progress, settings) }
        // **Not on the caller's dispatcher.** `MuPlaybackService.onCreate` starts this on its
        // main-thread-confined scope (an `ExoPlayer` binds to its creating thread's `Looper`, so
        // that scope has to be main), and the transform above rebuilds the whole map -- once per
        // `media_progress` write, which `ProgressWriter` performs every five seconds while
        // playback runs. On a large library that is a periodic main-thread stall nothing would
        // report. `flowOn` moves the rebuild and leaves `publish` -- one volatile field write --
        // where the collector was.
        .flowOn(Dispatchers.Default)
        .collect { publish(it) }
    }
  }

  fun stop() {
    collector?.cancel()
    collector = null
  }

  /**
   * A one-shot read, straight from Room. Called on the play path, so the answer a listener gets is
   * never the answer the collector had a second ago -- and so a queue set before the collector's
   * first emission still resumes.
   */
  suspend fun refresh() {
    publish(
      build(
        audiobookRepository.observeAudiobookItems().first(),
        audiobookRepository.observeBookFileOrder().first(),
        mediaProgressDao.observeAll().first(),
        bookSettingsDao.observeAll().first(),
      ),
    )
  }

  suspend fun awaitLoaded() = loaded.await()

  fun items(): Map<String, AudiobookItem> = current

  override fun itemFor(mediaId: String): AudiobookItem? = current[mediaId]

  private fun publish(next: Map<String, AudiobookItem>) {
    val previous = current
    current = next
    loaded.complete(Unit)
    // Only a *change of setting* is announced, never a republish: the progress ticker rewrites
    // `media_progress` every five seconds and each write rebuilds this map, so a signal on every
    // publish would make the speed controller re-apply the stored speed over one the listener had
    // just tapped on the player, before its own write-back had landed.
    if (next.any { (id, item) -> previous[id]?.let { it.speed != item.speed || it.skipSilence != item.skipSilence } == true }) {
      _settingsChanged.tryEmit(Unit)
    }
  }

  private val _settingsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  /**
   * Emits when a book's speed or silence-skipping changed in `book_settings` -- from the book
   * screen, with the player already running -- so the player can be told. See
   * `BookSpeedController.applyCurrent`.
   */
  val settingsChanged: SharedFlow<Unit> = _settingsChanged

  private fun build(
    bookIdByMediaId: Map<String, String>,
    fileOrderByBookId: Map<String, List<String>>,
    progress: List<MediaProgressEntity>,
    settings: List<BookSettingsEntity>,
  ): Map<String, AudiobookItem> {
    val progressById = progress.associateBy { it.mediaId }
    val settingsByBook = settings.associateBy { it.bookId }
    val finishedBooks = fileOrderByBookId.filterValues { BookFinish.isFinished(it, progressById) }.keys
    // Keyed off the audiobook item map, never off `media_progress`: a music row must not become an
    // entry here, and a book file with no progress row still needs its settings.
    return bookIdByMediaId.mapValues { (mediaId, bookId) ->
      val row = progressById[mediaId]
      val bookSettings = settingsByBook[bookId]
      AudiobookItem(
        mediaId = mediaId,
        bookId = bookId,
        positionMs = row?.positionMs ?: 0L,
        lastPlayedAtEpochMs = row?.lastPlayedAtEpochMs ?: 0L,
        isFinished = bookId in finishedBooks,
        // Clamped on the way out, the same way `AudiobookRepository.settings` clamps: a `NaN` or a
        // 40x from a hand-edited database reaches `ExoPlayer.setPlaybackSpeed`, which throws from
        // inside a listener callback and surfaces as playback dying with no message.
        speed = BookSettings.clampSpeed(bookSettings?.speed ?: BookSettings.DEFAULT_SPEED),
        skipSilence = bookSettings?.skipSilence ?: false,
      )
    }
  }
}
