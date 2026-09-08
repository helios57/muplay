package io.github.helios57.muplay.designsystem.component

import java.text.Normalizer

/**
 * One row of the A–Z bar, and the list position tapping it scrolls to.
 *
 * @property label the letter shown on the bar, or [NON_ALPHABETIC_BUCKET].
 * @property itemIndex the index in the list the bar was built from — an index into *that* list, so
 *   a caller whose `LazyColumn` has header items of its own has to offset it. Handing back a
 *   `LazyListState` index instead would make this function need to know the screen's layout.
 */
data class FastScrollBucket(val label: Char, val itemIndex: Int)

/**
 * The bucket every name that does not begin with a letter falls into.
 *
 * One row rather than one per digit and one per symbol: the bar has to fit down the side of a phone
 * screen beside 26 letters, and `1`, `2`, `(`, `'` and `…` as rows of their own is how it stops
 * fitting. It is also what users expect — Android's own contact and media indexes do the same.
 */
const val NON_ALPHABETIC_BUCKET: Char = '#'

/**
 * The A–Z index for a list already showing [labels] in [labels]' own order.
 *
 * **Derived from the list, never from an alphabet.** The bar offers only letters the list actually
 * contains, so a library of nothing but German albums does not get 26 rows of which 19 do nothing,
 * and tapping a row cannot land somewhere that has no such item.
 *
 * **And derived in the list's order rather than sorted.** These lists are sorted by the server's
 * collation, and this function's idea of alphabetical is not that one — CLAUDE.md records a
 * measured case where a space sorting below a slash reordered `songs.path` against every
 * expectation. Re-sorting here would build a bar that disagrees with the list under it, which is
 * the single failure a fast scroll cannot have: it is a promise about where a tap lands.
 *
 * A letter that appears, stops, and appears again therefore gets **one** entry, at its first
 * occurrence. That is a list this function was given out of order, and jumping to the first is both
 * the answer a user expects and the only one that keeps each letter on the bar once.
 */
fun fastScrollBuckets(labels: List<String>): List<FastScrollBucket> {
  val seen = mutableSetOf<Char>()
  val buckets = mutableListOf<FastScrollBucket>()
  labels.forEachIndexed { index, label ->
    val bucket = bucketOf(label)
    if (seen.add(bucket)) buckets += FastScrollBucket(bucket, index)
  }
  return buckets
}

/**
 * Which row of the bar [label] belongs to.
 *
 * The accent fold is `Normalizer.Form.NFD` plus dropping the combining marks it separates out, so
 * `Ä` becomes `A` and `É` becomes `E`. Without it a German or French library scatters its `A`s
 * across `A`, `Ä` and `Å`, which is three unhittable rows on a strip that has to fit 27.
 */
private fun bucketOf(label: String): Char {
  val first = label.trim().firstOrNull() ?: return NON_ALPHABETIC_BUCKET
  val folded = Normalizer.normalize(first.toString(), Normalizer.Form.NFD)
    .firstOrNull { !it.isDiacritic() }
    ?: return NON_ALPHABETIC_BUCKET
  return if (folded.isLetter()) folded.uppercaseChar() else NON_ALPHABETIC_BUCKET
}

/**
 * Whether [this] is one of the combining marks `NFD` splits an accented letter into.
 *
 * A set membership rather than three `||`ed comparisons, and the difference is not style. Written
 * as a chain, the two marks this repository's own fixtures never produce -- Latin decomposes only
 * into non-spacing marks; the other two are Indic vowel signs and enclosing circles -- are two
 * branch arms no test can reach, so the file measures 5 branches short forever and the floor over
 * it has to be written below what the code deserves. As a set it is one decision with both arms
 * taken by the accented and unaccented names the tests already use, and the *data* is still all
 * three marks, which is the part that has to be right.
 */
private fun Char.isDiacritic(): Boolean = Character.getType(this) in COMBINING_MARK_TYPES

private val COMBINING_MARK_TYPES: Set<Int> = setOf(
  Character.NON_SPACING_MARK.toInt(),
  Character.COMBINING_SPACING_MARK.toInt(),
  Character.ENCLOSING_MARK.toInt(),
)

/**
 * Which of [bucketCount] rows a finger at [y] on a bar of [heightPx] is on.
 *
 * Separate from the `Composable` that calls it because this is the whole feature: a fast scroll is
 * an arithmetic promise about where a tap lands, and an off-by-one here is invisible in a
 * screenshot and obvious to a thumb.
 *
 * Clamped rather than validated at both ends. A drag down the side of a phone runs past the bottom
 * of the bar as a matter of course, and both zero arguments are reachable in one frame — the first
 * pointer event can arrive before layout has measured the bar.
 */
fun bucketIndexAt(y: Float, heightPx: Int, bucketCount: Int): Int {
  if (heightPx <= 0 || bucketCount <= 0) return 0
  return ((y / heightPx) * bucketCount).toInt().coerceIn(0, bucketCount - 1)
}

/**
 * Whether an A–Z bar over a list of [itemCount] items with these [buckets] is worth drawing.
 *
 * Three ways it is not, and all three are things a real library does rather than hypotheticals:
 *
 * **The list already fits.** A bar is a strip of letters laid over content, so on a list the user
 * can see all of it is furniture covering the thing it claims to help reach. [FAST_SCROLL_MIN_ITEMS]
 * is roughly two screens of rows at this app's row height.
 *
 * **There is one letter on it.** One artist's discography, or a search narrow enough to leave a
 * single initial, derives exactly one bucket. A one-letter bar offers nowhere to go and still takes
 * the space.
 *
 * **The list is not in alphabetical order.** This is the rule that keeps the feature honest, and it
 * is checked rather than assumed because the answer differs per screen and changes when a query
 * changes. `M` on the bar is a promise that tapping it lands on the first thing beginning with M;
 * over a shelf ordered by how recently a book was opened, or a folder ordered by path, that promise
 * is false, and a fast scroll that lands somewhere arbitrary reads as a broken feature rather than
 * as a list that was never sorted. So the bar simply does not appear there, and no call site has to
 * remember to ask.
 *
 * Ascending is measured over the buckets rather than over the labels on purpose. The mirror sorts
 * on `name.trim().lowercase()` compared byte by byte, which is not this function's collation and
 * need not be: digits and punctuation land ahead of every letter, which is [NON_ALPHABETIC_BUCKET]
 * first and ascending; accented names land after `z`, and they fold into a bucket the bar already
 * has, so [fastScrollBuckets] drops them and the order survives.
 */
fun offersFastScroll(itemCount: Int, buckets: List<FastScrollBucket>): Boolean =
  itemCount >= FAST_SCROLL_MIN_ITEMS &&
    buckets.size >= 2 &&
    buckets.zipWithNext().all { (above, below) -> above.label < below.label }

/** Roughly two screens of rows: below this a list is scrolled by dragging it. */
const val FAST_SCROLL_MIN_ITEMS: Int = 20

/**
 * Where [bucket] is in a `LazyColumn` of [totalItems] rows whose last [blockSize] rows are the ones
 * the index was built from.
 *
 * [FastScrollBucket.itemIndex] counts the labels; `LazyListState.scrollToItem` counts every row,
 * headers included, and every screen that offers a fast scroll has headers — chips, a search field,
 * a shuffle button, a section heading. Reading the header count off the *list* rather than writing
 * it down at each call site is deliberate: those headers are conditional (a sync notice that comes
 * and goes, a shuffle result that appears on a tap), so a written-down count is right until the day
 * it silently is not, and the symptom is a jump that lands a few rows off with nothing to blame.
 *
 * Both ends are clamped because the index and the list are recomposed independently: for one frame
 * a bucket can name a row that a sync has removed or a search has filtered away, and `totalItems`
 * is 0 until the list has been laid out at all.
 */
fun listIndexOf(bucket: FastScrollBucket, totalItems: Int, blockSize: Int): Int {
  val firstOfBlock = (totalItems - blockSize).coerceAtLeast(0)
  return (firstOfBlock + bucket.itemIndex).coerceIn(0, (totalItems - 1).coerceAtLeast(0))
}
