package app.muplay.book

import app.muplay.model.ResumePoint
import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which file a book starts on — the decision Task 6 moved out of the resume policy and into the
 * caller, because the caller is the only party that knows what the listener asked for.
 */
class StartIndexTest {

  private fun song(id: String) = Song(
    id = id, libraryId = 2, title = id, albumId = "book", albumName = "Book", artistId = null,
    artistName = "Author", trackNumber = null, discNumber = null, durationSeconds = 5,
    suffix = "mp3", coverArtId = null,
  )

  private val files = listOf(song("p1"), song("p2"), song("p3"))

  @Test
  fun `resuming starts on the file the listener was in`() {
    // Two different resume points, two different indices. With one, "the resume point's index" and
    // "1" are the same number.
    assertThat(startIndexFor(files, ResumePoint("p2", 3_500L, 900L))).isEqualTo(1)
    assertThat(startIndexFor(files, ResumePoint("p3", 500L, 900L))).isEqualTo(2)
  }

  @Test
  fun `a book nobody has opened starts on its first file`() {
    assertThat(startIndexFor(files, resumeAt = null)).isZero
  }

  @Test
  fun `a resume point naming a file that is no longer in the book starts at the beginning`() {
    // A server rescan can remove a file. `indexOf` returning -1 reaches `setMediaItems` as a
    // start index, and an out-of-range index there is a crash rather than a fallback.
    assertThat(startIndexFor(files, ResumePoint("deleted", 3_500L, 900L))).isZero
  }

  @Test
  fun `playing a specific file starts on that file`() {
    assertThat(startIndexFor(files, mediaId = "p1")).isZero
    assertThat(startIndexFor(files, mediaId = "p3")).isEqualTo(2)
  }

  @Test
  fun `playing a file that is not in the book starts at the beginning rather than out of range`() {
    assertThat(startIndexFor(files, mediaId = "elsewhere")).isZero
  }

  @Test
  fun `an empty book has no start index to get wrong`() {
    assertThat(startIndexFor(emptyList(), ResumePoint("p2", 1L, 1L))).isZero
    assertThat(startIndexFor(emptyList(), mediaId = "p2")).isZero
  }
}
