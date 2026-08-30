package app.muplay.book

import app.muplay.database.AudiobookRepository
import app.muplay.media.AudiobookSnapshot
import app.muplay.media.PlaybackLauncher
import app.muplay.model.ResumePoint
import app.muplay.model.Song
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which file a book starts on.
 *
 * Task 6 moved this decision out of `ResumePolicy` deliberately: `resolve(mediaIds, requestedIndex)`
 * cannot tell "resume this book" from "play chapter 1 from the top", because both arrive as index
 * 0. The caller can, so the caller decides — and the policy still decides the *position*, which is
 * the guarantee the seam was built for.
 *
 * Out-of-range is folded to 0 rather than passed through: `indexOfFirst` returns -1 for a file a
 * server rescan removed, and -1 reaching `setMediaItems` is a crash rather than a fallback.
 * ([PlaybackLauncher] clamps as well, and that redundancy is deliberate — this function is the one
 * that is gated in Tier 1, and the clamp there is about a queue shrinking, not about a missing id.)
 */
internal fun startIndexFor(files: List<Song>, resumeAt: ResumePoint?): Int =
  files.indexOfFirst { it.id == resumeAt?.mediaId }.coerceAtLeast(0)

internal fun startIndexFor(files: List<Song>, mediaId: String): Int =
  files.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)

/**
 * The three things a listener can ask a book to do.
 *
 * Each one is a *start index* and nothing else. The position is not this class's to choose and
 * cannot be: `MuPlayer`'s seam discards the position argument of `setMediaItems` and asks
 * `ResumePolicy` instead. That is why "start from the beginning" below is expressed as **clearing
 * the progress** rather than as asking for zero — asking for zero would be answered with the
 * stored position, correctly.
 *
 * Every method refreshes [AudiobookSnapshot] **before** the queue is set. The policy reads memory,
 * by design (Plan 3 forbids a Room read on the player's application thread), and a stale snapshot
 * resumes at zero — silently, and only on the run where nothing warmed it.
 *
 * ### Why this delegates to [PlaybackLauncher] rather than driving a controller itself
 *
 * The plan's listing for this class injected `QueueRepository` and `PlaybackConnection` and
 * repeated `setMediaItems`/`prepare`/`play` here. That is a second copy of the sequence
 * [PlaybackLauncher] exists to be the only copy of, and the copy was already missing two things
 * that class documents at length: the `Handler(Looper.getMainLooper())` dispatcher every
 * `MediaController` call has to be made on, and the `TranscodeSeekSupport` negotiation that decides
 * whether seeking inside a transcoded stream works at all. Seeking is most of what an audiobook
 * player does, so silently dropping that negotiation for books specifically would have been the
 * worst possible place to drop it. What is genuinely this class's own is the *index*, and that is
 * all that is left here.
 */
@Singleton
class BookPlaybackLauncher @Inject constructor(
  private val audiobookRepository: AudiobookRepository,
  private val playbackLauncher: PlaybackLauncher,
  private val audiobookSnapshot: AudiobookSnapshot,
) {

  /** "Carry on" — the file the listener was last in. */
  suspend fun resume(bookId: String) {
    val files = audiobookRepository.files(bookId)
    play(files, startIndexFor(files, audiobookRepository.resumePoint(bookId)))
  }

  /** "Play *this* part" — the file the listener named, wherever the stored position is. */
  suspend fun playFile(bookId: String, mediaId: String) {
    val files = audiobookRepository.files(bookId)
    play(files, startIndexFor(files, mediaId))
  }

  /**
   * "Start again."
   *
   * Expressed by **clearing the book's progress**, not by asking for position 0 — the seam would
   * resolve the stored position straight back. Clearing first, then refreshing the snapshot, is
   * what makes the policy answer zero. It is also the more honest state: there is no position,
   * rather than a position that happens to be zero beside a `lastPlayedAt` claiming otherwise.
   */
  suspend fun restart(bookId: String) {
    audiobookRepository.restart(bookId)
    play(audiobookRepository.files(bookId), 0)
  }

  /**
   * No empty-list guard: `PlaybackLauncher.launchQueue` already answers `null` for an empty queue
   * rather than throwing, and that is gated by `PlaybackLauncherTest`. A second guard here would
   * be an untested branch duplicating a tested one.
   */
  private suspend fun play(files: List<Song>, startIndex: Int) {
    audiobookSnapshot.refresh()
    playbackLauncher.play(files, startIndex)
  }
}
