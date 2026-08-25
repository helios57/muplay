package app.muplay.media

import app.muplay.model.Song
import java.lang.reflect.Modifier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class PlaybackQueueTest {

  // [trackNumber] is a parameter, not the constant it started as, for the reason the order test
  // below spells out: a fixture in which every song carries the same track number makes
  // `songs.sortedBy { it.trackNumber }` a stable no-op, so the queue's most plausible accidental
  // reordering would be invisible to an order-sensitive assertion.
  private fun song(id: String, trackNumber: Int? = 1) = Song(
    id = id,
    libraryId = 1,
    title = "Title $id",
    albumId = "album",
    albumName = "Album",
    artistId = "artist",
    artistName = "Artist",
    trackNumber = trackNumber,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
  )

  /**
   * **The fixture is deliberately non-monotone in all three of the keys a queue could be sorted
   * by**, and that is the entire point of it.
   *
   * `containsExactly` is order-sensitive, so an ascending `a, b, c` fixture catches a *reversal*
   * -- and nothing else. Every plausible sort is an identity on it: ids ascend, `"Title $id"`
   * ascends with them, and with one shared track number `sortedBy { it.trackNumber }` is a stable
   * no-op. So `PlaybackQueue(songs.sortedBy { … }, startIndex)` passed this file at every one of
   * those keys.
   *
   * The third is not a hypothetical mutation. *"Sort the queue by track number for album
   * playback"* is a change someone makes on purpose, and applied here it would silently destroy
   * library-scoped shuffle -- this project's headline feature -- by re-sorting a deliberately
   * random order back into track order. `c(3), a(1), b(2)` is non-monotone by id, by title and by
   * track number at once, so all three sorts now redden this test (probes
   * `queue/songs-sorted-by-id` and `queue/songs-sorted-by-track-number`).
   */
  @Test
  fun `a queue holds the songs it was given in the order it was given them`() {
    val queue = PlaybackQueue.of(
      listOf(song("c", trackNumber = 3), song("a", trackNumber = 1), song("b", trackNumber = 2)),
    )

    assertThat(queue.songs.map { it.id }).containsExactly("c", "a", "b")
    assertThat(queue.size).isEqualTo(3)
    // A second, disjoint observation of `size`. With only the line above it, `get() = 3` passes --
    // the one-observation defect this project has shipped four times. A single-song queue is also
    // the shape every "play just this track" call makes, so it is not a contrived fixture.
    assertThat(PlaybackQueue.of(listOf(song("only"))).size).isEqualTo(1)
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
   * **The `Companion` handle, not every static field.** A Kotlin `companion object` compiles to a
   * `public static final PlaybackQueue$Companion Companion` field on the class, and that field is
   * *not* marked synthetic (verified: its access flags are `ACC_PUBLIC|ACC_STATIC|ACC_FINAL`), so
   * a `filterNot { it.isSynthetic }` alone reports `["Companion", "songs", "startIndex"]` and this
   * test fails against a correct implementation. Some filter is therefore *required*.
   *
   * A blanket `Modifier.isStatic` was that filter and it opened a hole big enough to drive the
   * defect through: Kotlin puts a companion's **property backing fields** as static fields on the
   * containing class, so `companion object { var positionMs = 0L }` -- the exact global mutable
   * "now playing position" this test exists to forbid, in its worst form, shared by every queue
   * that ever exists -- was filtered out and this test stayed green. Naming the one field that has
   * to be excluded closes it (probe `queue/companion-position-field`), and if Kotlin ever renames
   * that handle this test fails loudly rather than widening.
   */
  @Test
  fun `the queue carries no playback position of its own`() {
    val fields = PlaybackQueue::class.java.declaredFields
      .filterNot { it.isSynthetic || (Modifier.isStatic(it.modifiers) && it.name == "Companion") }
      .map { it.name }

    assertThat(fields)
      .describedAs(
        "PlaybackQueue must stay a list of pointers (spec section 3). Progress belongs on " +
          "media_progress, keyed by media id -- never on the queue.",
      )
      .containsExactlyInAnyOrder("songs", "startIndex")
  }
}
