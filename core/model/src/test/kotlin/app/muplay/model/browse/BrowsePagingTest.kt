package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The four lines that stand between a car's paging request and a `subList` that throws.
 *
 * Every assertion here observes the function at **two or more** arguments, because the defect this
 * class exists against is not "paging is wrong" but "paging is a constant": a `page` that ignored
 * its argument and always returned the first slice passes any single-page assertion.
 */
class BrowsePagingTest {

  @Test
  fun `pages divide the list and the last page is short`() {
    // Three observations of `page` in one assertion, so an implementation that ignored the argument
    // fails rather than being right once out of three.
    assertThat(listOf(0, 1, 2).map { BrowsePaging.page(ITEMS, page = it, pageSize = 2) })
      .containsExactly(listOf("a", "b"), listOf("c", "d"), listOf("e"))
  }

  @Test
  fun `a page past the end is empty rather than an error`() {
    assertThat(BrowsePaging.page(ITEMS, page = 3, pageSize = 2)).isEmpty()
    assertThat(BrowsePaging.page(emptyList<String>(), page = 0, pageSize = 2)).isEmpty()
  }

  @Test
  fun `a page size of Int MAX_VALUE returns everything and does not overflow`() {
    // The case that matters: `page * pageSize` at page 1 with this size is -2147483648 in Int
    // arithmetic, and `subList` with a negative index throws inside a ListenableFuture, where it
    // reaches a car as an unexplained empty list rather than as an error.
    assertThat(BrowsePaging.page(ITEMS, page = 0, pageSize = Int.MAX_VALUE)).isEqualTo(ITEMS)
    assertThat(BrowsePaging.page(ITEMS, page = 1, pageSize = Int.MAX_VALUE)).isEmpty()
    assertThat(BrowsePaging.page(ITEMS, page = 2, pageSize = Int.MAX_VALUE)).isEmpty()
  }

  @Test
  fun `a nonsensical page or size is empty rather than a crash`() {
    assertThat(
      listOf(
        BrowsePaging.page(ITEMS, page = -1, pageSize = 2),
        BrowsePaging.page(ITEMS, page = 0, pageSize = 0),
        BrowsePaging.page(ITEMS, page = 0, pageSize = -5),
      ),
    ).containsExactly(emptyList(), emptyList(), emptyList())
  }

  @Test
  fun `paging preserves order within a page`() {
    // A `page` implemented over a Set, or with `shuffled().take()`, passes every size assertion
    // above and fails this one.
    assertThat(BrowsePaging.page(ITEMS, page = 0, pageSize = 5))
      .containsExactly("a", "b", "c", "d", "e")
    // And the second page's contents, not merely its size: a slice taken from the wrong offset is
    // the same length as the right one.
    assertThat(BrowsePaging.page(ITEMS, page = 1, pageSize = 3)).containsExactly("d", "e")
  }

  @Test
  fun `a page larger than the list is the whole list rather than an out of bounds slice`() {
    assertThat(BrowsePaging.page(ITEMS, page = 0, pageSize = 500)).isEqualTo(ITEMS)
  }

  private companion object {
    val ITEMS = listOf("a", "b", "c", "d", "e")
  }
}
