package app.muplay.player

import app.muplay.media.QueueItem
import app.muplay.media.QueueSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The queue screen's own decisions, on the fast tier.
 *
 * Everything the screen needs to know that is not a plain field copy is decided here: which row is
 * playing, which arrows are enabled at the ends of the list, and what a row is keyed by. They are
 * one pure function over a [QueueSnapshot] for the reason `playerUiState` is: a decision inside a
 * `@Composable` can only be checked on a device, and this project's device tier is the one that
 * disappears.
 */
class QueueUiStateTest {

  private fun snapshotOf(currentIndex: Int, vararg titles: String) = QueueSnapshot(
    items = titles.map { QueueItem(mediaId = "id-$it", title = it, artist = "Test Artist") },
    currentIndex = currentIndex,
  )

  @Test
  fun `an empty queue has nothing to show`() {
    assertThat(queueUiState(QueueSnapshot.EMPTY)).isEqualTo(QueueUiState.Empty)
  }

  @Test
  fun `every queued item becomes a row, in queue order`() {
    val state = queueUiState(snapshotOf(0, "First", "Second", "Third"))

    assertThat(state).isInstanceOf(QueueUiState.Content::class.java)
    assertThat((state as QueueUiState.Content).rows.map { it.title })
      .containsExactly("First", "Second", "Third")
  }

  @Test
  fun `the row the player is on is the one marked as playing`() {
    val rows = contentOf(snapshotOf(1, "First", "Second", "Third")).rows

    assertThat(rows.map { it.isCurrent }).containsExactly(false, true, false)
  }

  @Test
  fun `a current index past the end of the queue marks no row as playing`() {
    val rows = contentOf(snapshotOf(7, "First", "Second")).rows

    assertThat(rows.map { it.isCurrent }).containsExactly(false, false)
  }

  @Test
  fun `the first row cannot move up and the last cannot move down`() {
    val rows = contentOf(snapshotOf(0, "First", "Second", "Third")).rows

    assertThat(rows.map { it.canMoveUp }).containsExactly(false, true, true)
    assertThat(rows.map { it.canMoveDown }).containsExactly(true, true, false)
  }

  @Test
  fun `a queue of one can be removed but has nowhere to move`() {
    val rows = contentOf(snapshotOf(0, "Alone")).rows

    assertThat(rows.single().canMoveUp).isFalse()
    assertThat(rows.single().canMoveDown).isFalse()
  }

  @Test
  fun `a row carries the index the player knows it by, not its position among visible rows`() {
    val rows = contentOf(snapshotOf(0, "First", "Second", "Third")).rows

    assertThat(rows.map { it.index }).containsExactly(0, 1, 2)
  }

  /**
   * The same song queued twice is two rows, and they are two *different* rows to Compose.
   *
   * A `LazyColumn` keyed on `mediaId` alone throws `IllegalArgumentException: Key "id-Repeat" was
   * already used` the moment a user adds one track twice -- which is an ordinary thing to do to a
   * queue, and not something any other list in this app can meet, because a library never shows one
   * album twice.
   */
  @Test
  fun `the same song queued twice gets two distinct keys`() {
    val rows = contentOf(snapshotOf(0, "Repeat", "Repeat")).rows

    assertThat(rows.map { it.key }).doesNotHaveDuplicates()
  }

  private fun contentOf(snapshot: QueueSnapshot) = queueUiState(snapshot) as QueueUiState.Content

  // ---- what the header says, and where the list opens ------------------------------------------
  //
  // Both read the same `currentPosition`, which is why it is a field and not two independent
  // searches through `rows`: a header that says "2 of 3" while the list opens on row 5 is worse
  // than either mistake alone.

  @Test
  fun `the header counts the playing row from one`() {
    assertThat(contentOf(snapshotOf(1, "First", "Second", "Third")).summary).isEqualTo("2 of 3")
  }

  @Test
  fun `a queue with nothing playing is counted rather than positioned`() {
    assertThat(contentOf(snapshotOf(7, "First", "Second")).summary).isEqualTo("2 tracks")
  }

  @Test
  fun `one track with nothing playing is a track and not a tracks`() {
    assertThat(contentOf(snapshotOf(7, "Only")).summary).isEqualTo("1 track")
  }

  @Test
  fun `the list opens on the row that is playing`() {
    assertThat(contentOf(snapshotOf(2, "First", "Second", "Third")).currentPosition).isEqualTo(2)
  }

  @Test
  fun `a queue with nothing playing opens at the top`() {
    assertThat(contentOf(snapshotOf(7, "First", "Second")).currentPosition).isNull()
  }

}
