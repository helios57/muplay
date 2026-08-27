package app.muplay.model.browse

import app.muplay.model.Song
import java.lang.reflect.Modifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a playable browse id expands to.
 *
 * Two fields and no third, and that absence is the assertion worth writing: spec section 3 puts the
 * playback *position* behind `MuPlayer`'s seam so that no caller can choose one, and this type is
 * what a caller hands back. A `positionMs` here would be a way round that seam, and it would
 * compile.
 */
class BrowseSelectionTest {

  @Test
  fun `a selection carries a queue and an index and nothing else`() {
    // The declared *instance* properties, read off the class rather than off an instance: an
    // added `positionMs` fails here, which is the only place it would ever fail. Statics are
    // filtered out because the companion contributes `Companion` and `EMPTY`, neither of which is
    // a property of a selection.
    assertThat(
      BrowseSelection::class.java.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }
        .map { it.name },
    ).containsExactlyInAnyOrder("songs", "startIndex")
  }

  @Test
  fun `a selection holds the songs and index it was given`() {
    val selection = BrowseSelection(SONGS, startIndex = 2)

    assertThat(selection.songs.map { it.id }).containsExactly("tr-1", "tr-2", "tr-3")
    assertThat(selection.startIndex).isEqualTo(2)
  }

  @Test
  fun `the empty selection is empty and starts at zero`() {
    // Both fields, because either one alone is satisfied by a selection that would still start a
    // player somewhere. `EMPTY` is what a caller that must answer unconditionally hands back, and
    // `PlaybackQueue.of` refuses its songs, so it can never reach a player.
    assertThat(BrowseSelection.EMPTY.songs).isEmpty()
    assertThat(BrowseSelection.EMPTY.startIndex).isEqualTo(0)
  }

  private companion object {
    fun song(id: String, track: Int) = Song(
      id = id,
      libraryId = 1,
      title = "Track $track",
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
  }
}
