package app.muplay.book

import app.muplay.database.AudiobookRepository
import app.muplay.media.BookChapter
import app.muplay.media.BookFile
import app.muplay.media.BookTimeline
import app.muplay.media.ChapterRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One book's chapters, in one place.
 *
 * **It exists to have exactly one copy of this composition.** Both `BookViewModel` and
 * `BookPlayerViewModel` need a `List<BookChapter>` for the same book, and the four lines that
 * build one -- read the files, ask the chapter repository, convert `durationSeconds` to
 * milliseconds, hand both to [BookTimeline.of] -- are the kind of hand-written pair this
 * repository has repeatedly watched drift. Two copies would drift on the seconds-to-milliseconds
 * conversion first, and the symptom would be one screen's chapter boundaries a thousand times off
 * the other's.
 *
 * **It is deliberately ungated, and the same admission `BookPlaybackLauncher` carries applies.**
 * `AudiobookRepository` is Room and `ChapterRepository` is an HTTP `MetadataRetriever`, so nothing
 * on the JVM tier can reach either, and this project bans mock frameworks. So this class measures
 * LINE 0 and `warnUngatedClasses` names it on every run, which is the honest signal; a floor of
 * `0.00` would not be. Everything it *decides* is already gated one layer down --
 * `BookTimeline.of` has its own floor in `:core:media` -- and everything above it is behind
 * `BookSource`/`BookPlayerControls`, which is where the fast tier picks it up again.
 */
@Singleton
class BookTimelineReader @Inject constructor(
  private val audiobookRepository: AudiobookRepository,
  private val chapterRepository: ChapterRepository,
) {

  suspend fun timeline(bookId: String): List<BookChapter> {
    val files = audiobookRepository.files(bookId)
    return BookTimeline.of(
      files.map { BookFile(it.id, it.title, it.durationSeconds * 1_000L) },
      chapterRepository.chaptersFor(files),
    )
  }
}
