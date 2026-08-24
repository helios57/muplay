package app.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [SearchResults.isEmpty] is the one piece of author-written behaviour among the six model types
 * Plan 2 Task 3 adds — every other one is a `data class` or an `enum class` with no body, whose
 * only executable code is compiler-generated.
 *
 * It is tested here, in the module that owns it, for the reason [SubsonicCredentialsTest] spells
 * out at length: JaCoCo measures coverage per module, so an assertion living in `:core:network`
 * exercises this property without contributing a single covered branch to `:core:model`'s own
 * execution data. `:core:network`'s `BrowseEndpointsTest` does assert `isEmpty` on a real
 * all-empty `search3` response, and that assertion is worth having — but it cannot gate this
 * class, and this module's floor table is `"CLASS"`-element precisely so an ungated class shows
 * up rather than being absorbed into an average.
 *
 * `isEmpty` is a three-way `&&`, which the compiler turns into six branches. A test that only
 * checked "all three empty" and "all three full" would cover two of them and leave the four that
 * matter — the cases where *one* list has results — untouched, which is exactly the shape of a
 * bug this property could have (`||` instead of `&&`, or a forgotten third clause). So each of
 * the three lists is exercised as the sole non-empty one.
 */
class SearchResultsTest {

  @Test
  fun `results with nothing in any list are empty`() {
    assertThat(SearchResults(emptyList(), emptyList(), emptyList()).isEmpty).isTrue
  }

  @Test
  fun `an artist alone is not empty`() {
    assertThat(SearchResults(listOf(ARTIST), emptyList(), emptyList()).isEmpty).isFalse
  }

  @Test
  fun `an album alone is not empty`() {
    assertThat(SearchResults(emptyList(), listOf(ALBUM), emptyList()).isEmpty).isFalse
  }

  @Test
  fun `a song alone is not empty`() {
    assertThat(SearchResults(emptyList(), emptyList(), listOf(SONG)).isEmpty).isFalse
  }

  @Test
  fun `results with all three populated are not empty`() {
    assertThat(SearchResults(listOf(ARTIST), listOf(ALBUM), listOf(SONG)).isEmpty).isFalse
  }

  private companion object {
    val ARTIST = Artist(id = "ar1", libraryId = 1, name = "Test Artist", coverArtId = null, albumCount = 1)

    val ALBUM = Album(
      id = "al1",
      libraryId = 1,
      name = "Test Album",
      artistId = "ar1",
      artistName = "Test Artist",
      coverArtId = null,
      songCount = 3,
      durationSeconds = 15,
    )

    val SONG = Song(
      id = "so1",
      libraryId = 1,
      title = "Track 1",
      albumId = "al1",
      albumName = "Test Album",
      artistId = "ar1",
      artistName = "Test Artist",
      trackNumber = 1,
      discNumber = null,
      durationSeconds = 5,
      suffix = "mp3",
      coverArtId = null,
    )
  }
}
