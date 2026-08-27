package app.muplay.media

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A **JVM** test, because the policy takes media ids and an index and asks an [AudiobookItemSource]
 * -- no `MediaItem`, no `Uri`, no Room. That is why the source is a narrow `fun interface` rather
 * than [AudiobookSnapshot] itself: the decision that this whole application exists for is gated in
 * Tier 1, where a mutation costs seconds, and the Room plumbing is gated separately on a device by
 * `AudiobookSnapshotTest`.
 *
 * Everything here uses **two books and at least two positions**. With one book, *"resume book B at
 * P"* and *"resume at the only position there is"* are the same program.
 */
class AudiobookResumePolicyTest {

  private val now = 1_700_000_000_000L
  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

  private fun item(
    mediaId: String,
    bookId: String,
    positionMs: Long,
    agoMs: Long = 0L,
    finished: Boolean = false,
  ) = AudiobookItem(
    mediaId = mediaId,
    bookId = bookId,
    positionMs = positionMs,
    lastPlayedAtEpochMs = now - agoMs,
    isFinished = finished,
    speed = 1.0f,
    skipSilence = false,
  )

  private fun policy(vararg items: AudiobookItem): AudiobookResumePolicy {
    val byId = items.associateBy { it.mediaId }
    // A hand-written source, not a mock: this project bans mock frameworks, and a map is a
    // complete implementation of a one-method interface rather than a stand-in for one.
    return AudiobookResumePolicy({ mediaId -> byId[mediaId] }, clock)
  }

  @Test
  fun `a book resumes at the position stored for the item the caller asked for`() {
    // Two books, two positions, one policy. A policy returning "the stored position" without
    // looking at which item would pass either observation alone and fails this pair.
    val subject = policy(
      item("book-a-1", "book-a", positionMs = 12_345L),
      item("book-b-1", "book-b", positionMs = 60_000L),
    )

    assertThat(subject.resolve(listOf("book-a-1"), 0).startPositionMs).isEqualTo(12_345L)
    assertThat(subject.resolve(listOf("book-b-1"), 0).startPositionMs).isEqualTo(60_000L)
  }

  @Test
  fun `music is not resumed, however much progress it has`() {
    // Spec section 3: "Music restarts from 0 -- progress is still recorded, just not honoured on
    // prepare." Structural rather than conditional: a music item has no entry in the source at
    // all, so there is nothing to honour.
    val subject = policy(item("book-a-1", "book-a", positionMs = 12_345L))

    assertThat(subject.resolve(listOf("a-song"), 0).startPositionMs).isZero
    // ...and the mixed case, which is what a shuffle queue looks like: one known id and one not.
    // Index 0 names the *song*, so the book's 12 345 must not leak across from index 1.
    assertThat(subject.resolve(listOf("a-song", "book-a-1"), 0).startPositionMs).isZero
    // ...and the control on the same queue: index 1 names the book and does resume. Without this,
    // "music is not resumed" and "this queue resumes nothing" are the same observation.
    assertThat(subject.resolve(listOf("a-song", "book-a-1"), 1).startPositionMs).isEqualTo(12_345L)
  }

  @Test
  fun `the caller's index is honoured, and it selects which position is used`() {
    // The seam correction, asserted. The caller decides the index because it knows whether the
    // listener said "resume this book" or "play chapter 3"; the policy answers the position of
    // whatever the caller chose.
    val subject = policy(
      item("p1", "multi", positionMs = 1_000L),
      item("p2", "multi", positionMs = 3_500L),
      item("p3", "multi", positionMs = 500L),
    )
    val queue = listOf("p1", "p2", "p3")

    assertThat(subject.resolve(queue, 0)).isEqualTo(ResumeTarget(0, 1_000L))
    assertThat(subject.resolve(queue, 1)).isEqualTo(ResumeTarget(1, 3_500L))
    assertThat(subject.resolve(queue, 2)).isEqualTo(ResumeTarget(2, 500L))
  }

  @Test
  fun `the smart rewind is applied, and it depends on how long the book was away`() {
    // Same stored position, three away times, three answers. Without the second observation, "the
    // rewind is applied" and "the rewind is 5000" are the same claim.
    val recent = policy(item("b1", "b", positionMs = 60_000L, agoMs = 5_000L))
    val awhile = policy(item("b1", "b", positionMs = 60_000L, agoMs = 600_000L))
    val ages = policy(item("b1", "b", positionMs = 60_000L, agoMs = 30L * 86_400_000L))

    assertThat(recent.resolve(listOf("b1"), 0).startPositionMs).isEqualTo(60_000L)
    assertThat(awhile.resolve(listOf("b1"), 0).startPositionMs).isEqualTo(55_000L)
    assertThat(ages.resolve(listOf("b1"), 0).startPositionMs).isEqualTo(40_000L)
  }

  @Test
  fun `the away time is measured from the clock, not from a constant`() {
    // The other axis of the test above, and the one it cannot reach: it holds the clock fixed and
    // moves the row, so a policy that read `System.currentTimeMillis()` -- or subtracted a
    // hardcoded band -- agrees with it exactly. Here the ROW is fixed and the clock moves.
    val storedAt = now - 5_000L
    val item = AudiobookItem("b1", "b", 60_000L, storedAt, false, 1.0f, false)
    val source = AudiobookItemSource { id -> item.takeIf { id == "b1" } }

    val atStoreTime = AudiobookResumePolicy(source, fixed(storedAt + 1_000L))
    val tenMinutesLater = AudiobookResumePolicy(source, fixed(storedAt + 600_000L))

    assertThat(atStoreTime.resolve(listOf("b1"), 0).startPositionMs).isEqualTo(60_000L)
    assertThat(tenMinutesLater.resolve(listOf("b1"), 0).startPositionMs).isEqualTo(55_000L)
  }

  @Test
  fun `a finished item starts again from the beginning`() {
    // Otherwise pressing play on a book you finished drops you two seconds before the end, which
    // reads as "the resume is broken" rather than as "you finished this".
    val subject = policy(item("b1", "b", positionMs = 20_000L, finished = true))

    assertThat(subject.resolve(listOf("b1"), 0).startPositionMs).isZero
    // ...and the control: the same item unfinished does resume.
    assertThat(policy(item("b1", "b", positionMs = 20_000L)).resolve(listOf("b1"), 0).startPositionMs)
      .isEqualTo(20_000L)
  }

  @Test
  fun `an audiobook item nobody has played yet starts at zero`() {
    val subject = policy(item("b1", "b", positionMs = 0L))

    assertThat(subject.resolve(listOf("b1"), 0)).isEqualTo(ResumeTarget(0, 0L))
  }

  @Test
  fun `an index outside the queue does not throw`() {
    // `MediaController`s from other processes -- a car, a watch, a headset -- can and do send stale
    // indices. An exception thrown from inside `setMediaItems` takes the whole session down.
    val subject = policy(item("b1", "b", positionMs = 12_345L))

    assertThat(subject.resolve(listOf("b1"), requestedIndex = 7)).isEqualTo(ResumeTarget(0, 12_345L))
    assertThat(subject.resolve(listOf("b1"), requestedIndex = -3)).isEqualTo(ResumeTarget(0, 12_345L))
    assertThat(subject.resolve(emptyList(), requestedIndex = 0)).isEqualTo(ResumeTarget(0, 0L))
    // Clearing a queue is `setMediaItems(emptyList())` and reaches the policy like any other call;
    // `ResumePolicyTest` pins the same promise for `NeverResume`. A negative index on an empty
    // queue is the pair of them at once, and it is the input `coerceIn(0, size - 1)` would throw
    // an `IllegalArgumentException` on if the upper bound were not itself clamped.
    assertThat(subject.resolve(emptyList(), requestedIndex = -1)).isEqualTo(ResumeTarget(0, 0L))
  }

  @Test
  fun `a clock that moved backwards does not rewind wildly`() {
    // `agoMs` negative -- the row claims to have been written in the future. `SmartRewind` handles
    // it, and this asserts the policy actually routes through `SmartRewind` rather than
    // subtracting something of its own.
    val subject = policy(item("b1", "b", positionMs = 60_000L, agoMs = -86_400_000L))

    assertThat(subject.resolve(listOf("b1"), 0).startPositionMs).isEqualTo(60_000L)
  }

  @Test
  fun `a rewind never runs off the front of a file`() {
    // A position smaller than the band's rewind. `seekTo` with a negative is not behaviour a
    // listener should discover, and `SmartRewind.resumePositionMs` is what stops it -- this is the
    // assertion that the policy asks it rather than doing the subtraction itself.
    val subject = policy(item("b1", "b", positionMs = 1_500L, agoMs = 600_000L))

    assertThat(subject.resolve(listOf("b1"), 0).startPositionMs).isZero
  }

  @Test
  fun `the same question always gets the same answer`() {
    // A policy is asked again every time a queue is set, and Plan 6 sets the same queue onto a
    // second, remote player on a cast. An answer that depended on anything remembered between
    // calls would land the book somewhere else on the speaker than it did on the phone.
    val subject = policy(
      item("b1", "b", positionMs = 12_345L),
      item("c1", "c", positionMs = 60_000L),
    )

    val first = subject.resolve(listOf("b1"), 0)
    subject.resolve(listOf("c1"), 0)
    assertThat(subject.resolve(listOf("b1"), 0)).isEqualTo(first)
  }

  private fun fixed(epochMillis: Long): Clock =
    Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)
}
