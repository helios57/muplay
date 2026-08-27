package app.muplay.model.browse

/**
 * Which single node a *spoken* query plays.
 *
 * Distinct from search: a search returns a list for someone to look at, and this returns the one
 * thing to start now, for someone whose hands are on a steering wheel. Three tiers, each of which
 * has to beat the next, and a last resort that is never "nothing" -- a car that answers a spoken
 * request with "no results" has done nothing at all, and the app is what gets blamed.
 *
 * **The candidate list is the caller's to widen, and `BrowseTreeRepository.searchSelection` does.**
 * Handed only the rows a mirror `LIKE` search returned, tiers 2 and 3 would be unreachable: every
 * row in that list matched the query by construction, and a query matching nothing produces an
 * empty list rather than a list of non-matches. So the repository falls back to the whole shelf
 * when the search finds nothing, which is what makes [normalise] worth having -- `"Tail, Book!"`
 * is a query no `LIKE '%Tail, Book!%'` will ever match and an exact title match here.
 */
object PlayFromSearch {

  /**
   * The best of [nodes] for [query], or `null` when nothing in [nodes] can be played at all.
   *
   * Returns the element itself rather than anything derived from it: the caller expands
   * [BrowseNode.id] into a queue, so a node rebuilt from a title would be an id that names nothing.
   */
  fun pick(query: String, nodes: List<BrowseNode>): BrowseNode? = rank(query, nodes).firstOrNull()

  /**
   * Every playable node in [nodes], best first.
   *
   * [pick] is this list's head, and the list itself exists because the head is not always
   * *reachable*: an album row the mirror holds no files for is playable as a row and expands to
   * nothing, and answering a spoken request with silence because the best match happened to be that
   * row is the failure this whole object is written against. `BrowseTreeRepository.searchSelection`
   * walks the list and takes the first that really expands.
   *
   * Three tiers -- exact title, then containment, then everything else in the order it arrived --
   * and a **stable** sort, which is what makes "the first playable thing" a last resort rather than
   * a fourth rule: inside a tier the caller's order is the answer, and the caller's order is the
   * one a driver was already reading.
   */
  fun rank(query: String, nodes: List<BrowseNode>): List<BrowseNode> {
    val playable = nodes.filter(BrowseNode::isPlayable)
    if (query.isBlank()) return playable
    val wanted = normalise(query)
    return playable.sortedBy { tierOf(normalise(it.title), wanted) }
  }

  private fun tierOf(title: String, wanted: String): Int = when {
    title == wanted -> EXACT
    title.contains(wanted) -> CONTAINS
    else -> ANYTHING_PLAYABLE
  }

  private const val EXACT = 0
  private const val CONTAINS = 1
  private const val ANYTHING_PLAYABLE = 2

  /**
   * Lower case, no punctuation, single spaces.
   *
   * This is what a speech recogniser hands over -- no capitals, no commas, and sometimes a stray
   * double space where it hesitated -- so comparing raw strings would fail on the most common input
   * there is. Digits survive, because "part 2" is a thing a listener says out loud.
   */
  fun normalise(text: String): String =
    text.lowercase()
      .map { if (it.isLetterOrDigit()) it else ' ' }
      .joinToString("")
      .split(" ")
      .filter(String::isNotEmpty)
      .joinToString(" ")
}
