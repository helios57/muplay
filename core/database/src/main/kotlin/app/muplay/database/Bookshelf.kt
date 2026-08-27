package app.muplay.database

import app.muplay.model.BookSummary
import app.muplay.model.Song

/**
 * The audiobook shelf, as the browse tree needs to see it.
 *
 * **A seam, and it did its job.** Plan 4 Task 4's `AudiobookRepository` owns the bookshelf and had
 * not landed when Plan 5 Task 4 was implemented, so that task wrote a `MirrorBookshelf` behind this
 * interface and recorded that Plan 4 owned deleting or reconciling it. Plan 4 Task 4 has landed:
 * `MirrorBookshelf` and its `BookProgress` are **deleted**, [AudiobookRepository] implements this
 * interface, and `DataModule` binds it. The interface itself is unchanged, which is what made the
 * swap one `@Binds` line and no browse decision.
 *
 * It is kept rather than folded into `AudiobookRepository` because it is what stops the browse tree
 * from seeing the rest of that class. Four methods and no more: the tree asks "which books", "this
 * one book", "this book's files" and "which file was the listener in"; every other audiobook
 * question -- chapters, speed, silence skipping, and **at what second** to resume -- is deliberately
 * absent here rather than stubbed, and a tree that could see a position would eventually seek with
 * one.
 *
 * **The fourth method was three when Plan 5 Task 4 wrote this, and the correction is worth reading
 * rather than reverting.** That doc said "where to resume from" was Plan 4's, and half of it still
 * is: Plan 4's `AudiobookResumePolicy` owns the *position*. What Plan 5 Task 5 needs is the *file*,
 * because `ResumePolicy.resolve(mediaIds, requestedIndex)` cannot tell "play this book" from "play
 * chapter 1 from the top" -- the caller picks the index and the policy picks the position, which is
 * Plan 4's own seam correction. [resumeFileId] is that index's input and carries no offset at all.
 */
interface Bookshelf {

  /** Every book in every library the user tagged `AUDIOBOOKS`. Order is the caller's to impose. */
  suspend fun books(): List<BookSummary>

  /** One book, or `null` when no audiobook library holds it. */
  suspend fun book(bookId: String): BookSummary?

  /** One book's files, in disc/track order -- the same order `BrowseDao.observeSongs` imposes. */
  suspend fun files(bookId: String): List<Song>

  /**
   * The id of the file the listener was last in, or `null` when nothing has been written for this
   * book at all.
   *
   * A **file id**, never an offset. `BrowseTreeRepository.expand` turns it into a queue index; the
   * second within that file is `ResumePolicy`'s and reaches the player through `MuPlayer`, which is
   * the seam spec section 3 exists for.
   */
  suspend fun resumeFileId(bookId: String): String?
}
