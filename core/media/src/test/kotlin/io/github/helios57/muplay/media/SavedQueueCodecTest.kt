package io.github.helios57.muplay.media

import io.github.helios57.muplay.model.ReplayGain
import io.github.helios57.muplay.model.Song
import io.github.helios57.muplay.model.SongRating
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The play queue's file format. The queue used to live only inside the player, so the system
 * reclaiming a paused service -- which Media3 lets happen after about ten minutes -- took it with
 * it, and the app reopened on "Nothing playing". The file is what survives that.
 */
class SavedQueueCodecTest {

  private val full = Song(
    id = "tr-1", libraryId = 3, title = "Ünïcödé \"quoted\" title", albumId = "al-1",
    albumName = "Album", artistId = "ar-1", artistName = "Artist", trackNumber = 4, discNumber = 2,
    durationSeconds = 245, suffix = "flac", coverArtId = "al-1_0",
    replayGain = ReplayGain(trackGainDb = -6.5f, albumGainDb = -7.25f, peakAmplitude = 0.98f),
    path = "Artist/Album/04.flac", rating = SongRating.Promoted,
  )

  private val sparse = Song(
    id = "tr-2", libraryId = 1, title = "Untagged", albumId = null, albumName = null,
    artistId = null, artistName = null, trackNumber = null, discNumber = null,
    durationSeconds = 0, suffix = null, coverArtId = null,
  )

  private val queue = SavedQueue(
    songs = listOf(full, sparse, full.copy(id = "tr-3")),
    currentIndex = 1,
    positionMs = 93_500,
    repeatMode = 2,
    shuffle = true,
  )

  @Test
  fun `a queue survives a round trip through its file format exactly`() {
    assertThat(SavedQueueCodec.decode(SavedQueueCodec.encode(queue))).isEqualTo(queue)
  }

  @Test
  fun `the same track twice in a queue is two entries, not one`() {
    val twice = queue.copy(songs = listOf(full, full), currentIndex = 1)

    assertThat(SavedQueueCodec.decode(SavedQueueCodec.encode(twice))?.songs).hasSize(2)
  }

  @Test
  fun `nothing that could authenticate a request is written`() {
    // Stream URLs carry `u`, `t` and `s`, which are password equivalents. The file holds ids and
    // metadata only; the URL is rebuilt from the stored credentials when the queue is restored.
    val text = SavedQueueCodec.encode(queue)

    assertThat(text).doesNotContain("http").doesNotContain("t=").doesNotContain("s=")
  }

  @Test
  fun `a file this version cannot read is no queue, not a crash`() {
    assertThat(SavedQueueCodec.decode("")).isNull()
    assertThat(SavedQueueCodec.decode("{not json")).isNull()
    assertThat(SavedQueueCodec.decode("""{"version":99,"songs":[]}""")).isNull()
    assertThat(SavedQueueCodec.decode("""{"version":1}""")).isNull()
  }

  @Test
  fun `an index outside the saved songs is clamped rather than refused`() {
    // A file written half-way through a queue edit, or by a future version that trimmed songs.
    val text = SavedQueueCodec.encode(queue).replace("\"currentIndex\":1", "\"currentIndex\":40")

    assertThat(SavedQueueCodec.decode(text)?.currentIndex).isEqualTo(2)
  }

  @Test
  fun `an empty queue is not worth restoring`() {
    val text = SavedQueueCodec.encode(queue).replace(Regex("\"songs\":\\[.*]"), "\"songs\":[]}")

    assertThat(SavedQueueCodec.decode(text)).isNull()
  }

  @Test
  fun `one unreadable song is skipped and the rest of the queue kept`() {
    val text = SavedQueueCodec.encode(queue.copy(songs = listOf(full, sparse), currentIndex = 0))
      .replaceFirst("\"id\":\"tr-1\"", "\"id\":17")

    val decoded = SavedQueueCodec.decode(text)

    assertThat(decoded?.songs?.map { it.id }).containsExactly("tr-2")
    assertThat(decoded?.currentIndex).isEqualTo(0)
  }
}
