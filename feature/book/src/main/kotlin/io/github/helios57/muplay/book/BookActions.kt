package io.github.helios57.muplay.book

/** The book screen's main button, by where the listener is in the book. */
internal enum class BookPrimary(val label: String) {
  /** Never opened: there is nothing to resume, so the button says what it does. */
  Play(PLAY_BOOK_LABEL),
  Resume(RESUME_LABEL),
  /** Finished: `Resume` would replay the last seconds of the last file. This starts over. */
  ListenAgain(LISTEN_AGAIN_LABEL),
}

/** The overflow item that flips the finished flag by hand. */
internal enum class FinishToggle(val label: String) {
  MarkFinished(MARK_FINISHED_LABEL),
  MarkUnfinished(MARK_UNFINISHED_LABEL),
}

/**
 * What the book screen offers. It used to offer `Resume` and `Start from the beginning` whatever
 * the state: on a book never opened, both did the same thing, and on a finished book `Resume`
 * replayed the last seconds while one stray tap on the other button forgot the place with no way
 * back -- so starting over a book in progress now asks first.
 */
internal data class BookActions(
  val primary: BookPrimary,
  val offersStartOver: Boolean,
  val confirmsStartOver: Boolean,
  val finishToggle: FinishToggle,
)

internal fun bookActions(hasStarted: Boolean, isFinished: Boolean): BookActions = when {
  isFinished -> BookActions(BookPrimary.ListenAgain, false, false, FinishToggle.MarkUnfinished)
  hasStarted -> BookActions(BookPrimary.Resume, true, true, FinishToggle.MarkFinished)
  else -> BookActions(BookPrimary.Play, false, false, FinishToggle.MarkFinished)
}

/** "Finished" on a finished book, rather than "under a minute left" -- which reads as a nag. */
internal fun remainingLabel(remainingMs: Long, isFinished: Boolean): String =
  if (isFinished) FINISHED_LABEL else formatRemaining(remainingMs)
