package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The strings a driver reads at 70 km/h.
 *
 * Every band is asserted at both sides of its boundary. A single sample per band cannot tell a
 * correct `when` cascade from one whose comparisons are all `<=` by mistake, and "1 h 0 min left"
 * versus "1 h left" is exactly the kind of difference that survives review and then reads badly on
 * a real screen.
 */
class BrowseTextTest {

  @Test
  fun `the remaining label names one band per magnitude, at both sides of every boundary`() {
    val inputs = listOf(
      0L,
      59_999L,
      60_000L,
      119_999L,
      3_599_999L,
      3_600_000L,
      3_660_000L,
      7_200_000L,
      45_296_000L,
    )

    assertThat(inputs.map(BrowseText::remainingLabel)).containsExactly(
      "under a minute left",
      "under a minute left",
      "1 min left",
      "1 min left",
      "59 min left",
      "1 h left",
      "1 h 1 min left",
      "2 h left",
      "12 h 34 min left",
    )
  }

  @Test
  fun `a negative remaining time is treated as none rather than rendered`() {
    // `remainingMs` is `durationMs - positionMs`, and a position past the end is reachable: Media3
    // reports a position beyond a container's declared duration on a stream whose duration was
    // estimated. "-3 min left" on a car screen is worse than "under a minute left".
    assertThat(BrowseText.remainingLabel(-1L)).isEqualTo("under a minute left")
  }

  @Test
  fun `the album count label is singular at exactly one and plural elsewhere`() {
    assertThat(listOf(0, 1, 2, 17).map(BrowseText::albumCountLabel))
      .containsExactly("no albums", "1 album", "2 albums", "17 albums")
  }

  @Test
  fun `the part label is one-based and carries the total`() {
    assertThat(listOf(0 to 3, 1 to 3, 2 to 3).map { BrowseText.partLabel(it.first, it.second) })
      .containsExactly("Part 1 of 3", "Part 2 of 3", "Part 3 of 3")
  }

  @Test
  fun `the unknown-artist placeholder is a real sentence, not an empty line`() {
    // Read by three call sites (an album with no `artistName`, a track with no `artistName`, a
    // book with no `author`), all of which assert against this constant rather than against a
    // literal, so the constant itself is the one place its value is pinned.
    assertThat(BrowseText.UNKNOWN_ARTIST).isEqualTo("Unknown artist")
  }
}
