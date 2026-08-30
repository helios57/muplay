package app.muplay.watchlink

import app.muplay.database.entity.MediaProgressEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Who wins when a phone and a watch disagree about where a book is.
 *
 * Every rule asserted **in both directions** -- remote newer *and* local newer -- because a merge
 * that always took the remote row passes every one-directional test there is and silently discards
 * whatever the user did on the device they are holding.
 */
class ProgressMergeTest {

  @Test
  fun `the newer row wins, whichever side it is on`() {
    val local = row("b-1", positionMs = 1_000, lastPlayedAtEpochMs = 100)
    val newerRemote = row("b-1", positionMs = 9_000, lastPlayedAtEpochMs = 200)
    val olderRemote = row("b-1", positionMs = 9_000, lastPlayedAtEpochMs = 50)

    assertThat(ProgressMerge.updates(listOf(local), listOf(newerRemote)))
      .containsExactly(newerRemote)
    assertThat(ProgressMerge.updates(listOf(local), listOf(olderRemote)))
      .isEmpty()
  }

  @Test
  fun `a tie is broken by the greater position, deterministically`() {
    // Two devices writing in the same millisecond is reachable: a batch apply writes many rows at
    // one clock read. An arbitrary tie-break makes the same row flip on every sync.
    val local = row("b-1", positionMs = 5_000, lastPlayedAtEpochMs = 100)
    val aheadRemote = row("b-1", positionMs = 6_000, lastPlayedAtEpochMs = 100)
    val behindRemote = row("b-1", positionMs = 4_000, lastPlayedAtEpochMs = 100)
    val identicalRemote = row("b-1", positionMs = 5_000, lastPlayedAtEpochMs = 100)

    assertThat(ProgressMerge.updates(listOf(local), listOf(aheadRemote))).containsExactly(aheadRemote)
    assertThat(ProgressMerge.updates(listOf(local), listOf(behindRemote))).isEmpty()
    assertThat(ProgressMerge.updates(listOf(local), listOf(identicalRemote))).isEmpty()
  }

  /**
   * The identity rule [ProgressMerge.winner] documents, asserted directly.
   *
   * `containsExactly` compares by `equals`, so the test above cannot tell "local won" from "an
   * equal-but-distinct remote row won" -- and the difference is a write on every sync, forever,
   * for a row nothing has changed. `isSameAs` is the only assertion that sees it.
   */
  @Test
  fun `an equal but distinct remote row is not a write`() {
    val local = row("b-1", positionMs = 5_000, lastPlayedAtEpochMs = 100)
    val identicalRemote = row("b-1", positionMs = 5_000, lastPlayedAtEpochMs = 100)

    assertThat(identicalRemote).isEqualTo(local).isNotSameAs(local)
    assertThat(ProgressMerge.winner(local, identicalRemote)).isSameAs(local)
  }

  @Test
  fun `a row this device has never seen is always taken`() {
    assertThat(ProgressMerge.updates(emptyList(), listOf(row("b-9", 3_000, 10))))
      .containsExactly(row("b-9", 3_000, 10))
  }

  @Test
  fun `a row the remote has never seen is left alone`() {
    // Deletions do not replicate -- see `ProgressMerge`'s own header. The local row survives because
    // the remote's silence is not evidence of anything.
    val local = row("b-1", 1_000, 100)
    assertThat(ProgressMerge.updates(listOf(local), emptyList())).isEmpty()
  }

  @Test
  fun `the winning row carries its own per-item settings, not the loser's`() {
    // speed, skipSilence and gainDb belong to the row (spec section 3), so they travel with the
    // winner rather than being merged field by field.
    val local = row("b-1", 1_000, 100).copy(speed = 1.0f, skipSilence = false, gainDb = 0f)
    val remote = row("b-1", 2_000, 200).copy(speed = 1.4f, skipSilence = true, gainDb = -3f)

    val updates = ProgressMerge.updates(listOf(local), listOf(remote))

    assertThat(updates.map { it.speed }).containsExactly(1.4f)
    assertThat(updates.map { it.skipSilence }).containsExactly(true)
    assertThat(updates.map { it.gainDb }).containsExactly(-3f)
  }

  @Test
  fun `many rows are merged independently and the result is exactly the ones that changed`() {
    // Mapped and compared as an exact list, so "some rows were returned" cannot pass for "the right
    // rows were returned".
    val local = listOf(row("a", 1_000, 100), row("b", 1_000, 300), row("c", 1_000, 100))
    val remote = listOf(row("a", 2_000, 200), row("b", 2_000, 200), row("d", 5_000, 500))

    assertThat(ProgressMerge.updates(local, remote).map { it.mediaId }).containsExactly("a", "d")
  }

  @Test
  fun `winner is symmetric with updates and never invents a row`() {
    val older = row("b-1", 1_000, 100)
    val newer = row("b-1", 2_000, 200)

    assertThat(ProgressMerge.winner(older, newer)).isEqualTo(newer)
    assertThat(ProgressMerge.winner(newer, older)).isEqualTo(newer)
  }

  /**
   * A row the remote has finished is still just a row: `isFinished` is carried by the winner and
   * decides nothing on its own. Asserted because it is the field a reader is most likely to assume
   * has a rule of its own -- it does not, and a merge that gave "finished" priority would drag a
   * listener back to the end of a book they had restarted on the other device.
   */
  @Test
  fun `isFinished is carried by the winner and is not itself a tie-break`() {
    val finishedButOlder = row("b-1", 30_000, 100).copy(isFinished = true)
    val unfinishedButNewer = row("b-1", 500, 200).copy(isFinished = false)

    assertThat(ProgressMerge.winner(finishedButOlder, unfinishedButNewer)).isSameAs(unfinishedButNewer)
    assertThat(ProgressMerge.winner(unfinishedButNewer, finishedButOlder)).isSameAs(unfinishedButNewer)
  }

  private companion object {
    fun row(mediaId: String, positionMs: Long, lastPlayedAtEpochMs: Long) = MediaProgressEntity(
      mediaId = mediaId,
      positionMs = positionMs,
      isFinished = false,
      lastPlayedAtEpochMs = lastPlayedAtEpochMs,
      speed = 1.0f,
      skipSilence = false,
      gainDb = 0f,
    )
  }
}
