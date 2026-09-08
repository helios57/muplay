package io.github.helios57.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [SongRating]: the three-state thumb this app draws, over the five-state star Subsonic stores.
 *
 * The mapping is deliberately **narrow** in one direction and total in the other, and that
 * asymmetry is the whole subject of this class.
 */
class SongRatingTest {

  @Test
  fun `the two values this app writes are the two the thumbs mean`() {
    assertThat(SongRating.ofUserRating(5)).isEqualTo(SongRating.Promoted)
    assertThat(SongRating.ofUserRating(1)).isEqualTo(SongRating.Demoted)
  }

  @Test
  fun `an unrated song is neutral whether the server omits the field or sends a zero`() {
    // Measured against the CI Navidrome: `userRating` is **absent** from a `Child` that has never
    // been rated, and `setRating(rating = 0)` clears it back to absent rather than storing a zero.
    // Both reach this function, because a client that has just cleared a rating holds the 0 it
    // sent while the next fetch will hold a null.
    assertThat(SongRating.ofUserRating(null)).isEqualTo(SongRating.Neutral)
    assertThat(SongRating.ofUserRating(0)).isEqualTo(SongRating.Neutral)
  }

  @Test
  fun `a two three or four star rating from another client is neutral rather than a thumb`() {
    // The interesting case, and the reason this is not a `>= 4` / `<= 2` split.
    //
    // Navidrome's own web UI writes any of 1..5, and this app gives two of those values
    // consequences a user cannot see from the star: a Demoted song is dropped from every shuffle.
    // Reading 2 as "never play this again" would silently ban a track somebody meant as "it is
    // fine, not a favourite" -- a destructive reading of an ambiguous input, applied to a library
    // the user did not rate with this app's thumbs at all.
    //
    // So only the two exact values this app writes carry thumb meaning. The cost is that a 4-star
    // song is not boosted; that is a rating this app never produces, and under-reacting to it is
    // the harmless direction.
    assertThat(listOf(2, 3, 4).map(SongRating::ofUserRating))
      .containsExactly(SongRating.Neutral, SongRating.Neutral, SongRating.Neutral)
  }

  @Test
  fun `a rating outside the star range is neutral rather than an error`() {
    // Nothing should ever send these. A browse list that threw on one malformed row would lose the
    // whole screen over a value that only decides how often a track is shuffled.
    assertThat(listOf(-1, 6, Int.MAX_VALUE, Int.MIN_VALUE).map(SongRating::ofUserRating))
      .containsOnly(SongRating.Neutral)
  }

  @Test
  fun `each thumb round trips through the number the server is sent`() {
    // `userRating` is what `setRating` puts on the wire, so this pins the wire values as much as
    // the mapping: clearing is a 0, not an omitted parameter, because Subsonic has no "unset"
    // verb.
    assertThat(SongRating.Promoted.userRating).isEqualTo(5)
    assertThat(SongRating.Demoted.userRating).isEqualTo(1)
    assertThat(SongRating.Neutral.userRating).isEqualTo(0)

    assertThat(SongRating.entries.map { SongRating.ofUserRating(it.userRating) })
      .containsExactlyElementsOf(SongRating.entries)
  }

  @Test
  fun `tapping the thumb a song already has clears it back to neutral`() {
    // The toggle rule, here rather than in the ViewModel because it is the same rule for both
    // thumbs and a screen that re-derived it would get one of them wrong. A second tap on a lit
    // thumb is the only way back to Neutral: there is no third control.
    assertThat(SongRating.Promoted.toggledTo(SongRating.Promoted)).isEqualTo(SongRating.Neutral)
    assertThat(SongRating.Demoted.toggledTo(SongRating.Demoted)).isEqualTo(SongRating.Neutral)

    // Tapping the *other* thumb switches outright rather than clearing, so promoting a demoted
    // song takes one tap and not two.
    assertThat(SongRating.Demoted.toggledTo(SongRating.Promoted)).isEqualTo(SongRating.Promoted)
    assertThat(SongRating.Promoted.toggledTo(SongRating.Demoted)).isEqualTo(SongRating.Demoted)
    assertThat(SongRating.Neutral.toggledTo(SongRating.Promoted)).isEqualTo(SongRating.Promoted)
    assertThat(SongRating.Neutral.toggledTo(SongRating.Demoted)).isEqualTo(SongRating.Demoted)
  }
}
