package app.muplay.database

import app.muplay.model.Song
import app.muplay.model.browse.BrowseSelection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The pure half of expansion: given a queue and the id that was tapped, which index does playback
 * start at.
 *
 * The rest of `expand` is repository plumbing over Room and is proven end to end by
 * `BrowsePlaybackTest` on a device; this is the arithmetic, and arithmetic belongs in the fast
 * tier.
 */
class BrowseExpansionTest {

  @Test
  fun `the start index is the tapped song's own position`() {
    // Three positions, three answers. A `startIndexOf` that returned 0 would pass a one-case test
    // and fail here on two of three -- and one that returned `songs.lastIndex`, or the position of
    // the *last* match, fails on the first two.
    assertThat(SONGS.map { BrowseTreeRepository.startIndexOf(SONGS, it.id) })
      .containsExactly(0, 1, 2)
  }

  @Test
  fun `the id is matched on identity and not on any other field`() {
    // Every song here shares an album, an artist and a duration, and two of them share a title.
    // The only thing that separates `tr-2` from `tr-3` is the id, so a match on title, on track
    // number or on position-in-list answers differently.
    assertThat(BrowseTreeRepository.startIndexOf(SAME_TITLE_SONGS, "tr-3")).isEqualTo(2)
    assertThat(BrowseTreeRepository.startIndexOf(SAME_TITLE_SONGS, "tr-2")).isEqualTo(1)
  }

  @Test
  fun `an id that is not in the list starts at the beginning rather than at minus one`() {
    // `indexOfFirst` returns -1, and `PlaybackQueue.of(songs, -1)` is an IllegalArgumentException
    // inside a ListenableFuture -- which reaches a car as an unexplained silence.
    assertThat(BrowseTreeRepository.startIndexOf(SONGS, "not-here")).isEqualTo(0)
    assertThat(BrowseTreeRepository.startIndexOf(emptyList(), "tr-1")).isEqualTo(0)
  }

  @Test
  fun `the empty selection is empty and starts at zero`() {
    // `BrowseSelection.EMPTY` is what a caller that must answer unconditionally hands back. Both
    // fields are asserted because either one alone is satisfied by a selection that would still
    // start a player somewhere.
    assertThat(BrowseSelection.EMPTY.songs).isEmpty()
    assertThat(BrowseSelection.EMPTY.startIndex).isEqualTo(0)
  }

  private companion object {
    fun song(id: String, track: Int, title: String = "Track $track") = Song(
      id = id,
      libraryId = 1,
      title = title,
      albumId = "al-a",
      albumName = "Test Album",
      artistId = "ar-1",
      artistName = "Test Artist",
      trackNumber = track,
      discNumber = 1,
      durationSeconds = 5,
      suffix = "mp3",
      coverArtId = "cov-a",
    )

    val SONGS = listOf(song("tr-1", 1), song("tr-2", 2), song("tr-3", 3))

    /** Two songs with the same title, so a match on anything but the id gets the wrong one. */
    val SAME_TITLE_SONGS =
      listOf(song("tr-1", 1), song("tr-2", 2, "Same"), song("tr-3", 3, "Same"))
  }
}
