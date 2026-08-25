package app.muplay.model.browse

import kotlin.math.max

/**
 * The handful of strings the browse tree puts in front of a driver.
 *
 * English literals, not string resources, and that is a decision rather than an oversight:
 * `:core:model` is a pure-Kotlin module with no `Context` and no resource table, and moving these
 * into `:core:media` to reach one would move the *tree* there with them, which is what makes the
 * tree untestable (see Task 2's own header). Localisation, when it happens, belongs at the
 * `BrowseItems` boundary where a `Context` already exists -- and it is a whole-app concern that no
 * plan has yet taken on, so no string in this app is localised today.
 */
object BrowseText {

  /** What a subtitle says when the server gave no artist or author at all. */
  const val UNKNOWN_ARTIST: String = "Unknown artist"

  private const val MINUTE_MS = 60_000L
  private const val HOUR_MS = 3_600_000L

  /**
   * "12 h 34 min left", "59 min left", "under a minute left".
   *
   * Clamped at zero: `remainingMs` is a subtraction of two independently-sourced numbers (a
   * container's declared duration and a player's reported position), and Media3 reports positions
   * past a declared duration on streams whose duration was estimated. "-3 min left" is a worse
   * answer than "under a minute left".
   */
  fun remainingLabel(remainingMs: Long): String {
    val clamped = max(0L, remainingMs)
    val hours = clamped / HOUR_MS
    val minutes = (clamped % HOUR_MS) / MINUTE_MS
    return when {
      clamped < MINUTE_MS -> "under a minute left"
      hours == 0L -> "$minutes min left"
      minutes == 0L -> "$hours h left"
      else -> "$hours h $minutes min left"
    }
  }

  /** "no albums", "1 album", "13 albums". */
  fun albumCountLabel(count: Int): String = when (count) {
    0 -> "no albums"
    1 -> "1 album"
    else -> "$count albums"
  }

  /** "Part 2 of 3", from a zero-based [index]. */
  fun partLabel(index: Int, total: Int): String = "Part ${index + 1} of $total"
}
