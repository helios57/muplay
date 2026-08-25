package app.muplay.model.browse

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
   * A negative [remainingMs] is a real input, not a caller error: the value is a subtraction of two
   * independently-sourced numbers (a container's declared duration and a player's reported
   * position), and Media3 reports positions past a declared duration on streams whose duration was
   * estimated. "-3 min left" is a worse answer than "under a minute left".
   *
   * It is handled by the **order of the bands**, not by a clamp. The first arm's test is `<`, so
   * every negative value satisfies it and never reaches the arithmetic below. An earlier draft
   * opened with `max(0L, remainingMs)`; that clamp was measured to be unfalsifiable -- removing it
   * changed no output for any input, including `Long.MIN_VALUE` -- and this project does not ship
   * code no test can fail on. `BrowseTextTest` pins the negative case against the band order
   * instead, which is what actually decides it: hoist `hours == 0L` above this arm and `-1` starts
   * rendering as "0 min left".
   *
   * [BookSummary.remainingMs][app.muplay.model.BookSummary.remainingMs] clamps at zero on its own
   * account, where the clamp *is* observable. This function does not depend on that.
   */
  fun remainingLabel(remainingMs: Long): String {
    val hours = remainingMs / HOUR_MS
    val minutes = (remainingMs % HOUR_MS) / MINUTE_MS
    return when {
      remainingMs < MINUTE_MS -> "under a minute left"
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
