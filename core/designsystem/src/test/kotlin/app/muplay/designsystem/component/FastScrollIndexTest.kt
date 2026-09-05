package app.muplay.designsystem.component

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The A–Z index down the side of a long list: which letters it offers, and where each one jumps to.
 *
 * **The one invariant is that the index agrees with the list.** A fast scroll is a promise that
 * tapping `M` puts you at the first thing beginning with M, and every way of getting that wrong
 * looks identical from the outside — the list simply scrolls somewhere unhelpful and the user
 * concludes the feature is broken rather than that the sort is. So this is derived from the labels
 * the screen is actually showing, in the order it is showing them, and never from an alphabet
 * assumed in advance.
 */
class FastScrollIndexTest {

  @Test
  fun `a list with nothing in it offers no index`() {
    assertThat(fastScrollBuckets(emptyList())).isEmpty()
  }

  @Test
  fun `each letter points at the first item that starts with it`() {
    val labels = listOf("Abbey Road", "Aftermath", "Blue", "Kind of Blue", "Kid A")

    assertThat(fastScrollBuckets(labels))
      .containsExactly(
        FastScrollBucket('A', 0),
        FastScrollBucket('B', 2),
        FastScrollBucket('K', 3),
      )
  }

  @Test
  fun `a letter that comes back later does not get a second entry`() {
    // A "sorted by name" list is sorted by the *server's* collation, not by this function's idea of
    // one, and the two disagree over case, spaces and punctuation -- `songs.path`'s own ordering
    // note in CLAUDE.md records a measured example. An index with two `A`s in it would put two
    // identical letters on the bar and send a tap to whichever the loop reached last.
    val labels = listOf("Abbey Road", "Blue", "Aftermath")

    assertThat(fastScrollBuckets(labels))
      .containsExactly(FastScrollBucket('A', 0), FastScrollBucket('B', 1))
  }

  @Test
  fun `case is not a bucket of its own`() {
    val labels = listOf("abbey road", "Aftermath")

    assertThat(fastScrollBuckets(labels)).containsExactly(FastScrollBucket('A', 0))
  }

  @Test
  fun `an accent is not a bucket of its own`() {
    // "Ärzte" belongs under A. A bar with `Ä`, `Å` and `A` on it as three separate rows is both
    // wrong and, on a 27-row strip, unhittable.
    val labels = listOf("Ärzte", "Air", "Édith Piaf")

    assertThat(fastScrollBuckets(labels))
      .containsExactly(FastScrollBucket('A', 0), FastScrollBucket('E', 2))
  }

  @Test
  fun `everything that does not start with a letter shares one bucket`() {
    // Digits, quotes and brackets are one row on the bar and always the same one. Giving `1`, `2`
    // and `(` rows of their own is how a 27-row strip becomes a 40-row strip nobody can hit.
    val labels = listOf("10cc", "1975", "(What's the Story)", "Abbey Road")

    assertThat(fastScrollBuckets(labels))
      .containsExactly(FastScrollBucket(NON_ALPHABETIC_BUCKET, 0), FastScrollBucket('A', 3))
  }

  @Test
  fun `a name that is blank or only punctuation still has somewhere to go`() {
    // A track with no title is a real row on screen -- the mirror stores what the server sent --
    // and an index that threw on it would take the whole screen down with it.
    val labels = listOf("", "   ", "…", "Abbey Road")

    assertThat(fastScrollBuckets(labels))
      .containsExactly(FastScrollBucket(NON_ALPHABETIC_BUCKET, 0), FastScrollBucket('A', 3))
  }

  @Test
  fun `a name that is nothing but a combining mark still has somewhere to go`() {
    // The accent fold drops the marks `NFD` separates out, and a string that is *only* a mark has
    // nothing left after that. Reachable rather than theoretical: the mirror stores whatever the
    // server sent, and a tag written in a text field that lost its base letter looks exactly like
    // this. Returning early is what stops the fold from answering with a mark nobody can find on
    // an A-Z bar.
    val labels = listOf("\u0301", "Abbey Road")

    assertThat(fastScrollBuckets(labels))
      .containsExactly(FastScrollBucket(NON_ALPHABETIC_BUCKET, 0), FastScrollBucket('A', 1))
  }

  @Test
  fun `leading whitespace does not decide the bucket`() {
    val labels = listOf("  Abbey Road", "Blue")

    assertThat(fastScrollBuckets(labels))
      .containsExactly(FastScrollBucket('A', 0), FastScrollBucket('B', 1))
  }

  // ---- where a finger on the bar lands ----------------------------------------------------------

  @Test
  fun `the top of the bar is the first bucket and the bottom is the last`() {
    // The two ends are the taps that have to be exact: `A` and `Z` are what a thumb reaches for
    // without looking, and an off-by-one at either end makes the bar feel like it missed.
    assertThat(bucketIndexAt(y = 0f, heightPx = 400, bucketCount = 4)).isZero
    assertThat(bucketIndexAt(y = 399f, heightPx = 400, bucketCount = 4)).isEqualTo(3)
  }

  @Test
  fun `the bar is divided evenly between its buckets`() {
    assertThat(bucketIndexAt(y = 99f, heightPx = 400, bucketCount = 4)).isZero
    assertThat(bucketIndexAt(y = 100f, heightPx = 400, bucketCount = 4)).isEqualTo(1)
    assertThat(bucketIndexAt(y = 250f, heightPx = 400, bucketCount = 4)).isEqualTo(2)
  }

  @Test
  fun `a finger dragged off either end stays on the bar`() {
    // A drag does not stop at the bar's edge -- a thumb sliding down the side of a phone runs past
    // the bottom of it constantly -- and an unclamped index there is an out-of-bounds jump.
    assertThat(bucketIndexAt(y = -80f, heightPx = 400, bucketCount = 4)).isZero
    assertThat(bucketIndexAt(y = 4000f, heightPx = 400, bucketCount = 4)).isEqualTo(3)
  }

  @Test
  fun `a bar that has not been measured yet answers rather than dividing by zero`() {
    // The first pointer event can arrive in the same frame as the layout that measures the bar.
    assertThat(bucketIndexAt(y = 10f, heightPx = 0, bucketCount = 4)).isZero
    assertThat(bucketIndexAt(y = 10f, heightPx = 400, bucketCount = 0)).isZero
  }

  // ---- whether the bar earns its space at all ---------------------------------------------------

  @Test
  fun `a list short enough to scroll by hand gets no bar`() {
    // Both sides of the threshold, one item apart. A bar over a list that already fits on screen
    // is a strip of letters covering content the user can see, to reach content the user can see.
    assertThat(offersFastScroll(itemCount = 19, buckets = fastScrollBuckets(ascending(19)))).isFalse
    assertThat(offersFastScroll(itemCount = 20, buckets = fastScrollBuckets(ascending(20)))).isTrue
  }

  @Test
  fun `a bar with one letter on it is not a bar`() {
    // A long library whose every album begins with the same letter -- one artist's discography
    // filtered by a search, say. The index derives correctly and offers nowhere to go.
    val labels = List(40) { "The Fall $it" }

    assertThat(fastScrollBuckets(labels)).hasSize(1)
    assertThat(offersFastScroll(itemCount = labels.size, buckets = fastScrollBuckets(labels))).isFalse
  }

  @Test
  fun `a list that is not in alphabetical order gets no bar`() {
    // The rule that keeps this feature honest everywhere it is wired up. `M` on the bar means "the
    // first thing beginning with M", and on a list ordered by anything else -- a shelf ordered by
    // how recently a book was opened, a folder ordered by path -- that sentence is false. The bar
    // does not appear rather than appearing and lying, and no screen has to remember to ask.
    val labels = ascending(30).toMutableList().also { it.add(0, "Zoo Station") }

    assertThat(offersFastScroll(itemCount = labels.size, buckets = fastScrollBuckets(labels))).isFalse
  }

  @Test
  fun `a list that puts everything non-alphabetic first is in order`() {
    // The mirror sorts on `name.trim().lowercase()` compared byte by byte, which puts digits and
    // punctuation ahead of every letter. That is the common shape of a real library -- "10cc",
    // "1975", "(What's the Story)" -- and it must not be read as unsorted.
    val labels = listOf("10cc", "1975") + ascending(30)

    assertThat(offersFastScroll(itemCount = labels.size, buckets = fastScrollBuckets(labels))).isTrue
  }

  @Test
  fun `an accented name after the last z does not unsort the bar`() {
    // Same collation, other end: `lowercase()` leaves "\u00e4" above every ASCII letter, so a German or
    // Czech library trails its accented names after `z`. They fold into buckets the bar already
    // has, so they are skipped rather than appended -- which is what keeps the order ascending.
    val labels = ascending(30) + listOf("\u00c4rzte", "\u00c9dith Piaf")

    assertThat(fastScrollBuckets(labels).map { it.label }).doesNotHaveDuplicates()
    assertThat(offersFastScroll(itemCount = labels.size, buckets = fastScrollBuckets(labels))).isTrue
  }


  // ---- turning a bucket into a row of the LazyColumn --------------------------------------------

  @Test
  fun `a bucket in a list with a header above it points past the header`() {
    // A `LazyColumn` here is a header -- chips, a search field, a shuffle button -- and then the
    // block the index was built from. `itemIndex` counts the block; `scrollToItem` counts the whole
    // list, and getting that wrong scrolls to the right offset in the wrong list.
    assertThat(listIndexOf(FastScrollBucket('B', 1), totalItems = 8, blockSize = 4)).isEqualTo(5)
  }

  @Test
  fun `a list that is nothing but its block points at the bucket itself`() {
    assertThat(listIndexOf(FastScrollBucket('C', 2), totalItems = 4, blockSize = 4)).isEqualTo(2)
  }

  @Test
  fun `a list that has not been laid out yet is scrolled to its top`() {
    // `totalItemsCount` is 0 until the first layout, and a first pointer event can arrive in the
    // same frame. Answering 0 is what stops that becoming a scroll past the end of an empty list.
    assertThat(listIndexOf(FastScrollBucket('C', 2), totalItems = 0, blockSize = 4)).isZero
  }

  @Test
  fun `a bucket that would land past the end lands on the last row`() {
    // The index and the list are recomposed independently, so for one frame a bucket can name a
    // row the list no longer has -- a sync that removed albums, a search that narrowed them.
    assertThat(listIndexOf(FastScrollBucket('Z', 5), totalItems = 6, blockSize = 4)).isEqualTo(5)
  }

  /** [count] names whose first letters ascend, so only the property under test decides the answer. */
  private fun ascending(count: Int): List<String> = List(count) { "${'A' + it % 26} Album $it" }
}
