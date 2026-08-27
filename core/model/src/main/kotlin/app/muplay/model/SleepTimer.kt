package app.muplay.model

/**
 * What the listener asked the sleep timer to do.
 *
 * [UntilPosition] rather than an "end of chapter" case: the *caller* knows where the chapter ends
 * (`BookTimeline`), and giving the timer chapter knowledge would mean a second component deciding
 * what a chapter is. One mechanism, one owner.
 *
 * The `mediaId` on [UntilPosition] is not decoration. A position with no item attached is a
 * position in whatever happens to be playing when the tick fires, so a book that advanced to its
 * next file would keep counting toward a millisecond mark that belongs to the file before it --
 * silently, and only on multi-file books.
 */
sealed interface SleepTimerRequest {
  data class Duration(val millis: Long) : SleepTimerRequest

  data class UntilPosition(val mediaId: String, val positionMs: Long) : SleepTimerRequest
}

/**
 * What the sleep timer is doing, as the UI sees it.
 *
 * [Running.isFading] is carried rather than derived by the UI: the fade length is the controller's
 * (`fadeMs`), and a screen that recomputed `remainingMs <= 20_000` would be a second copy of a
 * number only one of the two owns.
 */
sealed interface SleepTimerState {
  data object Off : SleepTimerState

  data class Running(
    val remainingMs: Long,
    val untilEndOfChapter: Boolean,
    val isFading: Boolean,
  ) : SleepTimerState
}
