package app.muplay.media

import app.muplay.database.AudiobookRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * What comes back when the system says *"carry on"*, with nothing playing.
 *
 * Android 13+ shows a resumption control in the notification shade after a reboot, and a headset or
 * lock-screen play button reaches a session whose player is empty. Media3's
 * `MediaSession.Callback.onPlaybackResumption` is the callback for it -- implemented by
 * `MuPlayLibraryCallback`, which is the one callback this app's session is built with -- and spec
 * section 10 assigns that callback to *"the plan that resumes"*.
 *
 * The answer is **the most recently heard unfinished book**, which is the top row of the shelf. Not
 * the most recent *anything*: pressing play after a reboot and getting a random song is a worse
 * answer than getting nothing, and the shelf's ordering already encodes what "carry on" means.
 *
 * The **position** is not decided here. This names the item; [AudiobookResumePolicy] supplies the
 * position when `MuPlayer` sets the items, exactly as on every other play path. Two places deciding
 * a position is how they come to disagree, and the whole of spec section 3 is that there is one.
 */
@Singleton
class ResumptionQueue @Inject constructor(
  private val audiobookRepository: AudiobookRepository,
) {

  suspend fun mostRecent(): PlaybackQueue? {
    // `bookshelf()` and not `books()`, which is the same derivation -- see that method. The filter
    // is on the *shelf's* own notion of started-and-unfinished, so "carry on" cannot disagree with
    // what the Continue shelf shows.
    val book = audiobookRepository.bookshelf().first()
      .firstOrNull { it.hasStarted && !it.isFinished }
      ?: return null
    val files = audiobookRepository.files(book.bookId)
    val resumeAt = audiobookRepository.resumeFileId(book.bookId)
    val index = files.indexOfFirst { it.id == resumeAt }

    // **One** guard, not two, and `null` rather than a `coerceAtLeast(0)`.
    //
    // `-1` covers both ways this can go wrong -- a shelf row whose files the mirror no longer holds
    // (a `replaceLibraryContents` between the two reads above), and a resume row naming a file this
    // book no longer has (a re-sync that renamed one). Neither is reachable from the repository as
    // it stands: a book with no files reports `positionMs == 0`, so `hasStarted` is false and the
    // filter above never yields it, and `resumeFileId` only ever names a file it read out of this
    // same book. It is therefore ONE dead arm rather than two, which is the trade this project
    // already records for `MuPlayer`'s and `StreamRetryPolicy`'s single unreachable branches.
    //
    // `coerceAtLeast(0)` was written first and is worse in both directions: it turns "the mirror
    // and the resume row disagree" into *"start this book from the top"*, silently, which is the
    // defect this plan exists to remove -- and it carries the same dead arm anyway.
    return if (index < 0) null else PlaybackQueue.of(files, startIndex = index)
  }
}
