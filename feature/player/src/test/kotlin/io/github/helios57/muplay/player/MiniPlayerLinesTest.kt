package io.github.helios57.muplay.player

import androidx.media3.common.MediaMetadata
import io.github.helios57.muplay.media.PlaybackState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What the mini player's two lines say. For music that is the track and the artist. For a book it
 * used to be the same -- and a book's file is called "Part 07" or "Track 12", so the bar showed a
 * file name and the book's own title nowhere.
 */
class MiniPlayerLinesTest {

  private val song = PlaybackState.NOTHING_PLAYING.copy(
    mediaId = "s1", title = "Track 1", artist = "Test Artist", albumTitle = "Test Album",
  )

  private val book = song.copy(
    title = "Part Two", artist = "Fourth Author", albumTitle = "Multi Part Book",
    mediaType = MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
  )

  @Test
  fun `music shows the track and the artist`() {
    assertThat(miniPlayerLines(song)).isEqualTo("Track 1" to "Test Artist")
  }

  @Test
  fun `a book shows the book first and the part it is in second`() {
    assertThat(miniPlayerLines(book)).isEqualTo("Multi Part Book" to "Part Two")
  }

  @Test
  fun `a one-file book whose file is named after it shows the author instead of the title twice`() {
    val single = book.copy(title = "Test Book", albumTitle = "Test Book", artist = "Test Author")

    assertThat(miniPlayerLines(single)).isEqualTo("Test Book" to "Test Author")
  }

  @Test
  fun `a book file with no album falls back to the file title and the author`() {
    assertThat(miniPlayerLines(book.copy(albumTitle = null))).isEqualTo("Part Two" to "Fourth Author")
  }
}
