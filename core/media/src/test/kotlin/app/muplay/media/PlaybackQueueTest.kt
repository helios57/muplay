package app.muplay.media

import app.muplay.model.Song
import java.lang.reflect.Modifier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class PlaybackQueueTest {

  private fun song(id: String) = Song(
    id = id,
    libraryId = 1,
    title = "Title $id",
    albumId = "album",
    albumName = "Album",
    artistId = "artist",
    artistName = "Artist",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
  )

  @Test
  fun `a queue holds the songs it was given in the order it was given them`() {
    val queue = PlaybackQueue.of(listOf(song("a"), song("b"), song("c")))

    assertThat(queue.songs.map { it.id }).containsExactly("a", "b", "c")
    assertThat(queue.size).isEqualTo(3)
  }

  @Test
  fun `the start index is the one the caller asked for`() {
    // Two observations. A `startIndex` hardcoded to 0 -- the obvious accident -- passes the first
    // of these and fails the second, which is the entire reason both are here.
    assertThat(PlaybackQueue.of(listOf(song("a"), song("b")), startIndex = 0).startIndex).isZero
    assertThat(PlaybackQueue.of(listOf(song("a"), song("b")), startIndex = 1).startIndex).isEqualTo(1)
  }

  @Test
  fun `songAt returns the song at that index`() {
    val queue = PlaybackQueue.of(listOf(song("a"), song("b"), song("c")))

    assertThat(queue.songAt(0).id).isEqualTo("a")
    assertThat(queue.songAt(2).id).isEqualTo("c")
  }

  @Test
  fun `an empty queue is rejected`() {
    // "Play nothing" is not a request a caller can make by accident and have silently succeed:
    // an empty setMediaItems leaves the session in a state where the notification shows a track
    // that is not there.
    assertThatIllegalArgumentException()
      .isThrownBy { PlaybackQueue.of(emptyList()) }
      .withMessageContaining("empty")
  }

  @Test
  fun `a start index outside the queue is rejected`() {
    assertThatIllegalArgumentException()
      .isThrownBy { PlaybackQueue.of(listOf(song("a")), startIndex = 1) }
      .withMessageContaining("startIndex")
    assertThatIllegalArgumentException()
      .isThrownBy { PlaybackQueue.of(listOf(song("a")), startIndex = -1) }
      .withMessageContaining("startIndex")
  }

  /**
   * Spec section 3's core architectural decision, asserted structurally rather than trusted to a
   * comment: **the queue is a list of pointers, and progress is a property of the item.**
   *
   * A `positionMs` on this type would be the single global "now playing position" that every other
   * player has and that the next thing played overwrites — the exact reason a user cannot listen
   * to music between two audiobook sessions without losing their place. `startIndex` is *not* a
   * position: it names an item, which is queue membership, not progress.
   *
   * `declaredFields` rather than Kotlin reflection, so this needs no `kotlin-reflect` dependency.
   * A new property therefore fails this test with a message that says what to do instead: put it
   * on `media_progress`, keyed by the media id.
   *
   * **Instance** fields, not every declared field: a Kotlin `companion object` compiles to a
   * `public static final PlaybackQueue$Companion Companion` field on the class, and that field is
   * *not* marked synthetic, so a `filterNot { it.isSynthetic }` alone reports `["Companion",
   * "songs", "startIndex"]` and this test fails against a correct implementation. Filtering
   * `static` is also the honest expression of what is being asserted -- state a queue *carries* is
   * per-instance, and a `positionMs` property would be exactly that, so the mutation this test
   * exists to catch is still caught (measured: `queue/position-field` in `ci/mutation-probes.sh`).
   */
  @Test
  fun `the queue carries no playback position of its own`() {
    val fields = PlaybackQueue::class.java.declaredFields
      .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
      .map { it.name }

    assertThat(fields)
      .describedAs(
        "PlaybackQueue must stay a list of pointers (spec section 3). Progress belongs on " +
          "media_progress, keyed by media id -- never on the queue.",
      )
      .containsExactlyInAnyOrder("songs", "startIndex")
  }
}
