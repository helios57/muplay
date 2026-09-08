package io.github.helios57.muplay.database

import io.github.helios57.muplay.model.FolderNode

/**
 * Path arithmetic for browsing the library by folder.
 *
 * The server reports each song's path relative to the library root, `/`-separated
 * (`"Fourth Author/Multi Part Book/02 - Part Two.mp3"`). Everything the folder screens ask reduces
 * to two questions about a prefix — what folders are immediately below it, and what tracks lie
 * directly in it — so both live here as plain functions over strings. The database only has to
 * narrow the candidate paths; the decision is made on the fast tier where it can be tested.
 *
 * A prefix is a folder path with no trailing separator (`""` is the library root). Callers that
 * build prefixes by concatenation may leave a trailing one, so every entry point tolerates it.
 */
object FolderPaths {

  private const val SEPARATOR = '/'

  /**
   * Whether [path] lies anywhere beneath [prefix].
   *
   * Match is on whole segments. `"Fourth"` does not contain `"Fourth Author/b.mp3"`, which a
   * `startsWith` or a `LIKE 'Fourth%'` would say it does — and shuffling that folder would then
   * play a neighbouring one's tracks.
   */
  fun isUnder(prefix: String, path: String): Boolean {
    val normalised = normalise(prefix)
    if (normalised.isEmpty()) return true
    return path.startsWith(normalised + SEPARATOR)
  }

  /** The folders immediately below [prefix], in case-insensitive name order. */
  fun children(prefix: String, paths: List<String>): List<FolderNode> {
    val normalised = normalise(prefix)
    val start = if (normalised.isEmpty()) 0 else normalised.length + 1
    return paths
      .filter { isUnder(normalised, it) }
      .mapNotNull { path ->
        val next = path.indexOf(SEPARATOR, startIndex = start)
        if (next < 0) null else path.substring(start, next)
      }
      .groupingBy { it }
      .eachCount()
      .map { (name, count) ->
        FolderNode(
          name = name,
          path = if (normalised.isEmpty()) name else "$normalised$SEPARATOR$name",
          trackCount = count,
        )
      }
      .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
  }

  /**
   * Whether [path] is a track lying directly in [prefix], with no folder in between.
   *
   * A predicate on one path rather than a filter over a list, because the folder screen shows
   * whole songs: it filters rows, not strings, and a second copy of this rule written inline at
   * that call site is exactly how the count beside a folder and the list inside it drift apart.
   */
  fun isDirectlyIn(prefix: String, path: String): Boolean {
    val normalised = normalise(prefix)
    if (!isUnder(normalised, path)) return false
    val start = if (normalised.isEmpty()) 0 else normalised.length + 1
    return path.indexOf(SEPARATOR, startIndex = start) < 0
  }

  /** The tracks lying directly in [prefix], excluding anything in a folder below it. */
  fun tracksDirectlyIn(prefix: String, paths: List<String>): List<String> =
    paths.filter { isDirectlyIn(prefix, it) }.sorted()

  /** The folder above [path], or null if [path] is already the root. */
  fun parentOf(path: String): String? {
    val normalised = normalise(path)
    if (normalised.isEmpty()) return null
    val cut = normalised.lastIndexOf(SEPARATOR)
    return if (cut < 0) "" else normalised.substring(0, cut)
  }

  /**
   * The SQL `LIKE` pattern selecting every path beneath [prefix], for use with `ESCAPE '\\'`.
   *
   * The pattern ends at the separator (`"Fourth/%"`), so unlike a bare `startsWith` it cannot
   * reach a sibling whose name merely begins the same way. The user's own `%` and `_` are escaped
   * for the same reason `MirrorMapper.searchPattern` escapes them: a folder genuinely named
   * `"50% Off"` would otherwise be a wildcard, and shuffling it would play a neighbour's tracks.
   */
  fun likePatternFor(prefix: String): String {
    val normalised = normalise(prefix)
    if (normalised.isEmpty()) return "%"
    val escaped = normalised
      .replace("\\", "\\\\")
      .replace("%", "\\%")
      .replace("_", "\\_")
    return "$escaped$SEPARATOR%"
  }

  private fun normalise(prefix: String): String = prefix.trimEnd(SEPARATOR)
}
