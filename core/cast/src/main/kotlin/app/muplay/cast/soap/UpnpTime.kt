package app.muplay.cast.soap

import java.util.Locale

/**
 * The `H:MM:SS` clock UPnP uses for positions, durations and seek targets.
 *
 * Every value here is a **position**, so the failure mode is a silent wrong answer -- the class
 * this project treats as the worst there is. The spec records the same shape of mistake elsewhere:
 * `createBookmark.position` in milliseconds against `bookmarkPosition` in seconds, where "getting
 * that backwards puts every resume out by 1000x".
 */
object UpnpTime {

  /** What a renderer sends for a field it does not implement. `AbsTime` is usually this. */
  const val NOT_IMPLEMENTED: String = "NOT_IMPLEMENTED"

  private val CLOCK = Regex("""^(\d{1,3}):([0-5]\d):([0-5]\d)(?:\.(\d{1,3}))?$""")

  /**
   * Milliseconds, or `null` when the value is not a clock this client will act on.
   *
   * `null` rather than `0`, because they are different facts: `NOT_IMPLEMENTED` means "I do not
   * know", and a player that read it as zero would drag the seek bar back to the start once a
   * second.
   */
  fun parseClock(value: String?): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty() || text == NOT_IMPLEMENTED) return null
    val match = CLOCK.matchEntire(text) ?: return null
    val (hours, minutes, seconds, fraction) = match.destructured
    val millis = when (fraction.length) {
      0 -> 0L
      // ".5" is five hundred milliseconds, ".50" is five hundred, ".500" is five hundred.
      1 -> fraction.toLong() * 100
      2 -> fraction.toLong() * 10
      else -> fraction.toLong()
    }
    return hours.toLong() * 3_600_000 + minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + millis
  }

  /**
   * `H:MM:SS`, the form Sonos accepts as a `REL_TIME` seek target.
   *
   * **Truncating**, not rounding: rounding 4999 ms up seeks past where the user asked, and a seek
   * that overshoots is a seek the user has to correct. Negative input formats as zero rather than
   * as a negative clock, because `Player.currentPosition` arithmetic can go negative and a device
   * answers `711 Illegal seek target` to a clock with a minus sign in it.
   */
  fun formatClock(millis: Long): String {
    val total = (millis.coerceAtLeast(0L)) / 1_000
    return String.format(Locale.ROOT, "%d:%02d:%02d", total / 3_600, (total / 60) % 60, total % 60)
  }

  /** `H:MM:SS.mmm`, the form DIDL-Lite's `res@duration` conventionally carries. */
  fun formatDuration(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val total = safe / 1_000
    return String.format(
      Locale.ROOT,
      "%d:%02d:%02d.%03d",
      total / 3_600, (total / 60) % 60, total % 60, safe % 1_000,
    )
  }
}
