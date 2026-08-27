package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which one thing a spoken query plays.
 *
 * Three tiers, and each is asserted **against a fixture where the other tiers would give a
 * different answer** -- otherwise "exact match wins" and "the first playable wins" are the same
 * observation and only one of them is being tested.
 *
 * The fixture is shaped so that every tier disagrees with every other: a browsable node first, a
 * partial-match node *before* the exact-match node, and "Book" contained in three of the four
 * titles.
 */
class PlayFromSearchTest {

  @Test
  fun `an exact title match wins over an earlier partial match`() {
    // "Book" is contained in three titles here and "Tail Book" is last. If tiering were reversed,
    // or absent, this would return "Multi Part Book".
    assertThat(PlayFromSearch.pick("Tail Book", NODES)?.title).isEqualTo("Tail Book")
  }

  @Test
  fun `a partial match wins over the first playable node`() {
    // "wizard" is nobody's whole title, so tier 1 cannot answer; tier 3 would answer with
    // "Multi Part Book". Only tier 2 gives this.
    assertThat(PlayFromSearch.pick("wizard", NODES)?.title).isEqualTo("A Wizard of Earthsea")
  }

  @Test
  fun `containment is on the node's title and not on the query`() {
    // The direction of `contains` is a real defect that a symmetric fixture cannot see: with the
    // operands swapped, `normalise(query).contains(normalise(title))` answers "Multi Part Book"
    // for both of these -- tier 2 never matches, so tier 3 does. Two observations, because the
    // first alone is also satisfied by a rule that never reaches tier 2 at all.
    assertThat(PlayFromSearch.pick("earthsea", NODES)?.title).isEqualTo("A Wizard of Earthsea")
    assertThat(PlayFromSearch.pick("part", NODES)?.title).isEqualTo("Multi Part Book")
  }

  @Test
  fun `a query that matches nothing still plays something`() {
    // A car that answers "no results" to a spoken request is a car that has done nothing. The
    // first playable node is the best available answer, and it is deliberately not the first node
    // in the fixture -- NODES starts with a browsable folder.
    assertThat(PlayFromSearch.pick("zzzz nothing", NODES)?.title).isEqualTo("Multi Part Book")
  }

  @Test
  fun `an empty query plays the first playable node`() {
    assertThat(listOf("", "   ").map { PlayFromSearch.pick(it, NODES)?.title })
      .containsExactly("Multi Part Book", "Multi Part Book")
  }

  @Test
  fun `a browsable-only node is never picked`() {
    // "Continue" is an exact title match for the first node in the fixture, and it is not playable.
    assertThat(PlayFromSearch.pick("Continue", NODES)?.title).isNotEqualTo("Continue")
    assertThat(PlayFromSearch.pick("Continue", NODES)?.isPlayable).isTrue
  }

  @Test
  fun `nothing playable at all yields null rather than a browsable node`() {
    assertThat(PlayFromSearch.pick("anything", listOf(NODES.first()))).isNull()
    assertThat(PlayFromSearch.pick("anything", emptyList())).isNull()
    // ...including for a blank query, which takes the other early-return branch.
    assertThat(PlayFromSearch.pick("", listOf(NODES.first()))).isNull()
  }

  @Test
  fun `the picked node keeps its own identity and is not rebuilt`() {
    // The return value is the element itself, so a caller can expand `it.id`. A `pick` that
    // returned a node reconstructed from the title alone would satisfy every title assertion in
    // this file and hand the caller an id that expands to nothing.
    assertThat(PlayFromSearch.pick("Tail Book", NODES)).isSameAs(NODES[3])
    assertThat(PlayFromSearch.pick("Tail Book", NODES)?.id).isEqualTo(BrowseId.Book("Tail Book"))
  }

  @Test
  fun `matching ignores case, punctuation and repeated spaces`() {
    // What a speech recogniser actually hands over: no capitals, no punctuation, stray spaces.
    assertThat(
      listOf("tail book", "TAIL BOOK", "  tail   book  ", "tail-book", "Tail, Book!")
        .map { PlayFromSearch.pick(it, NODES)?.title },
    ).containsExactly("Tail Book", "Tail Book", "Tail Book", "Tail Book", "Tail Book")
  }

  @Test
  fun `normalise is the exact transformation the tiers compare on`() {
    assertThat(
      listOf("Tail Book", "  TAIL,  book! ", "A Wizard of Earthsea").map(PlayFromSearch::normalise),
    ).containsExactly("tail book", "tail book", "a wizard of earthsea")
  }

  @Test
  fun `normalise keeps digits and collapses every kind of whitespace`() {
    // Digits survive because "part 2" is a thing a listener says; tabs and newlines collapse
    // because `isWhitespace` is what separates them from punctuation, and a rule that dropped
    // them would join two words into one.
    assertThat(listOf("Part 2", "a\tb\nc", "-", "").map(PlayFromSearch::normalise))
      .containsExactly("part 2", "a b c", "", "")
  }

  @Test
  fun `rank is every playable node best first, and pick is its head`() {
    // The list matters as much as its head: a node can be playable as a *row* and expand to
    // nothing (an album the mirror holds no files for), so the repository walks this order rather
    // than taking one answer. Asserted as an exact ordered list, because "the right nodes in some
    // order" is not the property a fallback walks.
    assertThat(PlayFromSearch.rank("Tail Book", NODES).map(BrowseNode::title))
      .containsExactly("Tail Book", "Multi Part Book", "A Wizard of Earthsea")
    assertThat(PlayFromSearch.rank("book", NODES).map(BrowseNode::title))
      .containsExactly("Multi Part Book", "Tail Book", "A Wizard of Earthsea")

    // The head is exactly what `pick` answers, for every query above and for the two early
    // returns -- so the two functions cannot drift into disagreeing.
    assertThat(listOf("Tail Book", "book", "wizard", "zzzz", "", "   ").map { query ->
      PlayFromSearch.pick(query, NODES) to PlayFromSearch.rank(query, NODES).firstOrNull()
    }).allSatisfy { (picked, head) -> assertThat(picked).isSameAs(head) }
  }

  @Test
  fun `ranking is stable, so within one tier the caller's order survives`() {
    // "the first playable thing" is a last resort, not a fourth rule: inside a tier the list stays
    // in the order the caller built it, which for a search result is books-before-music. A sort
    // that was not stable -- or one that re-sorted by title -- reverses this.
    val reversed = NODES.reversed()

    assertThat(PlayFromSearch.rank("zzzz", NODES).map(BrowseNode::title))
      .containsExactly("Multi Part Book", "A Wizard of Earthsea", "Tail Book")
    assertThat(PlayFromSearch.rank("zzzz", reversed).map(BrowseNode::title))
      .containsExactly("Tail Book", "A Wizard of Earthsea", "Multi Part Book")
  }

  @Test
  fun `rank drops browsable-only nodes entirely`() {
    // Not "sorts them last": a caller that walked the list looking for something to expand would
    // otherwise be handed a folder to play.
    assertThat(PlayFromSearch.rank("Continue", NODES).map(BrowseNode::isPlayable))
      .containsExactly(true, true, true)
    assertThat(PlayFromSearch.rank("anything", listOf(NODES.first()))).isEmpty()
  }

  private companion object {
    fun node(title: String, playable: Boolean) = BrowseNode(
      id = if (playable) BrowseId.Book(title) else BrowseId.Continue,
      title = title,
      isBrowsable = !playable,
      isPlayable = playable,
      mediaType = if (playable) BrowseMediaType.AUDIO_BOOK else BrowseMediaType.FOLDER_MIXED,
    )

    /**
     * Deliberately shaped so the three tiers disagree: a browsable node first, a partial-match
     * node before the exact-match node, and "Book" contained in three of the four titles.
     */
    val NODES = listOf(
      node("Continue", playable = false),
      node("Multi Part Book", playable = true),
      node("A Wizard of Earthsea", playable = true),
      node("Tail Book", playable = true),
    )
  }
}
