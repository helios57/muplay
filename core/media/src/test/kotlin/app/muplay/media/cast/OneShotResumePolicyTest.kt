package app.muplay.media.cast

import app.muplay.media.ResumePolicy
import app.muplay.media.ResumeTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The handover's position, in the one place it can be driven without a device.
 *
 * A pure JVM test, because `ResumePolicy` takes media ids and an index and touches no Android type
 * at all -- which is the whole reason the playback core gave it that signature. Everything the
 * *device* suite can only observe once (that a handover lands on the right second) is decided here,
 * where every arm is reachable and each one is falsifiable in seconds.
 */
class OneShotResumePolicyTest {

  /**
   * A stand-in for whatever the resume plan binds. It answers a **distinctive** target so that "the
   * delegate was consulted" is distinguishable from "a default was returned" -- which a delegate
   * answering `ResumeTarget(requestedIndex, 0)` would not be.
   */
  private val delegate = ResumePolicy { _, requestedIndex -> ResumeTarget(requestedIndex, 99_000L) }

  @Test
  fun `with nothing armed, the delegate's answer is passed straight through`() {
    val policy = OneShotResumePolicy(delegate)

    assertThat(policy.resolve(listOf("a", "b", "c"), requestedIndex = 1))
      .isEqualTo(ResumeTarget(1, 99_000L))
  }

  @Test
  fun `an armed target wins over the delegate, once`() {
    val policy = OneShotResumePolicy(delegate)
    policy.armFor("b", ResumeTarget(startIndex = 1, startPositionMs = 42_000L))

    assertThat(policy.resolve(listOf("a", "b", "c"), requestedIndex = 0))
      .isEqualTo(ResumeTarget(1, 42_000L))
    // ...and then it is gone. A target that stuck would make every later `setMediaItems` -- a
    // shuffle, a new album, the next book -- start at 42 seconds.
    assertThat(policy.resolve(listOf("a", "b", "c"), requestedIndex = 0))
      .isEqualTo(ResumeTarget(0, 99_000L))
  }

  @Test
  fun `a second arming carries a second target`() {
    // Without this, `armFor` could store nothing, return a constant 42_000, and pass the test
    // above. Two observations of a value that is supposed to vary, at two different values.
    val policy = OneShotResumePolicy(delegate)

    policy.armFor("b", ResumeTarget(1, 42_000L))
    assertThat(policy.resolve(listOf("a", "b"), 0)).isEqualTo(ResumeTarget(1, 42_000L))

    policy.armFor("a", ResumeTarget(0, 7_000L))
    assertThat(policy.resolve(listOf("a", "b"), 1)).isEqualTo(ResumeTarget(0, 7_000L))
  }

  @Test
  fun `an armed target for a media id that is not in the new queue is discarded`() {
    // A handover whose queue changed between arming and setting. Applying the target anyway would
    // start an unrelated track 42 seconds in -- the silent-wrong-answer class, and the exact
    // failure this architecture exists to prevent for books.
    val policy = OneShotResumePolicy(delegate)
    policy.armFor("gone", ResumeTarget(0, 42_000L))

    assertThat(policy.resolve(listOf("a", "b", "c"), requestedIndex = 2))
      .isEqualTo(ResumeTarget(2, 99_000L))
  }

  @Test
  fun `an armed target that misses its queue is still spent`() {
    // The other half of the discard, and it is not implied by the test above: a decorator that
    // returned the delegate's answer *without consuming* the armed target would pass that one and
    // then apply the stale target to the very next queue -- which, on a handover whose queue
    // changed, is the album the listener chose next.
    val policy = OneShotResumePolicy(delegate)
    policy.armFor("gone", ResumeTarget(0, 42_000L))

    policy.resolve(listOf("a", "b"), requestedIndex = 0)

    assertThat(policy.resolve(listOf("gone", "b"), requestedIndex = 1))
      .isEqualTo(ResumeTarget(1, 99_000L))
  }

  @Test
  fun `the armed index is corrected to where the media id actually is in the new queue`() {
    // The queue can be reordered by a handover -- a shuffle is regenerated, an album is re-fetched.
    // The media id is the identity; the index is not.
    val policy = OneShotResumePolicy(delegate)
    policy.armFor("c", ResumeTarget(startIndex = 0, startPositionMs = 42_000L))

    assertThat(policy.resolve(listOf("a", "b", "c"), requestedIndex = 0))
      .isEqualTo(ResumeTarget(2, 42_000L))
  }

  @Test
  fun `disarming leaves the delegate in charge`() {
    val policy = OneShotResumePolicy(delegate)
    policy.armFor("a", ResumeTarget(0, 42_000L))

    policy.disarm()

    assertThat(policy.resolve(listOf("a"), 0)).isEqualTo(ResumeTarget(0, 99_000L))
  }

  @Test
  fun `the delegate is consulted with the arguments it was given`() {
    // The decorator must not rewrite what it forwards. Two observations, because a decorator that
    // passed a constant index would satisfy one of them.
    val seen = mutableListOf<Pair<List<String>, Int>>()
    val recording = ResumePolicy { ids, index -> seen += ids to index; ResumeTarget(index, 0L) }

    OneShotResumePolicy(recording).resolve(listOf("a", "b"), 1)
    OneShotResumePolicy(recording).resolve(listOf("x"), 0)

    assertThat(seen).containsExactly(listOf("a", "b") to 1, listOf("x") to 0)
  }

  @Test
  fun `an empty queue clears the armed target rather than keeping it for the next one`() {
    // `setMediaItems(emptyList())` is how a queue is cleared, and it goes through the same seam.
    // A decorator that skipped an empty list would hold its target across the clear and apply it to
    // whatever the listener chose next.
    val policy = OneShotResumePolicy(delegate)
    policy.armFor("a", ResumeTarget(0, 42_000L))

    assertThat(policy.resolve(emptyList(), requestedIndex = 0)).isEqualTo(ResumeTarget(0, 99_000L))

    assertThat(policy.resolve(listOf("a"), requestedIndex = 0)).isEqualTo(ResumeTarget(0, 99_000L))
  }
}
