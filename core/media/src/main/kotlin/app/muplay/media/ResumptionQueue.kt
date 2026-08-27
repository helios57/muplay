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
    // A shelf row for a book whose files the mirror has not reached yet is a real state (see
    // `BrowseGraph`'s `bk-empty`), and `PlaybackQueue.of` refuses an empty queue by `require`.
    if (files.isEmpty()) return null
    val resumeAt = audiobookRepository.resumeFileId(book.bookId)
    // `coerceAtLeast(0)` covers both "no row yet" and "the row names a file this book no longer
    // has", which a re-sync that renamed a file really produces.
    val index = files.indexOfFirst { it.id == resumeAt }.coerceAtLeast(0)
    return PlaybackQueue.of(files, startIndex = index)
  }
}
