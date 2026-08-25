package app.muplay.model.browse

/**
 * The extras a car head unit reads off a browse item, as plain values.
 *
 * **Literal strings, not `androidx.media3.session.MediaConstants` references.** These keys are a
 * contract with Android Auto -- software this project does not own and no compiler checks against.
 * Media3 mirrors them under constant names that have moved between versions; pinning the strings
 * here means a rename upstream is a compile error somewhere else rather than a silent change to
 * what a car receives, and `BrowseExtrasTest` asserts each one as a literal.
 *
 * A `Map`, not a `Bundle`, so that *what goes in the extras* is decided in a pure-Kotlin module and
 * tested on the JVM. `BrowseItems.bundleOf` does the one Android-shaped step, on a device, because
 * `android.os.Bundle` is an unimplemented stub off one.
 */
object BrowseExtras {

  /** Told to the host once, on the root: this app sets content style hints at all. */
  const val CONTENT_STYLE_SUPPORTED: String = "android.media.browse.CONTENT_STYLE_SUPPORTED"

  /** How this item's **browsable** children should be laid out. */
  const val CONTENT_STYLE_BROWSABLE: String = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"

  /** How this item's **playable** children should be laid out. */
  const val CONTENT_STYLE_PLAYABLE: String = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

  /** Whether this item has been played, and how far. Drawn as a progress pip in the car. */
  const val COMPLETION_STATUS: String =
    "androidx.media.MediaBrowserCompat.extras.COMPLETION_STATUS"

  /** How far, as a `Double` in `0.0..1.0`. Read only when the status is partially played. */
  const val COMPLETION_PERCENTAGE: String =
    "androidx.media.MediaBrowserCompat.extras.COMPLETION_PERCENTAGE"

  const val STYLE_LIST: Int = 1
  const val STYLE_GRID: Int = 2

  const val STATUS_NOT_PLAYED: Int = 0
  const val STATUS_PARTIALLY_PLAYED: Int = 1
  const val STATUS_FULLY_PLAYED: Int = 2

  /**
   * The extras for one node.
   *
   * Style hints only on a browsable node, because they describe that node's *children* and a leaf
   * has none. A percentage only on a partially-played item, because that is the only state in which
   * Android Auto reads it -- sending `1.0` alongside `FULLY_PLAYED` is redundant, and sending `0.0`
   * alongside `NOT_PLAYED` draws an empty progress pip on every unheard book.
   */
  fun forNode(node: BrowseNode): Map<String, Any> = buildMap {
    if (node.isBrowsable) {
      put(CONTENT_STYLE_BROWSABLE, styleValue(node.childStyle))
      put(CONTENT_STYLE_PLAYABLE, STYLE_LIST)
    }
    node.completion?.let { completion ->
      put(COMPLETION_STATUS, statusValue(completion.status))
      if (completion.status == BrowseCompletionStatus.PARTIALLY_PLAYED) {
        put(COMPLETION_PERCENTAGE, completion.fraction)
      }
    }
  }

  /** The extras on the root item -- the host's default for everything below it. */
  fun forRoot(surface: BrowseSurface): Map<String, Any> = mapOf(
    CONTENT_STYLE_SUPPORTED to true,
    CONTENT_STYLE_BROWSABLE to styleValue(surface.browsableStyle),
    CONTENT_STYLE_PLAYABLE to STYLE_LIST,
  )

  private fun styleValue(style: BrowseStyle): Int = when (style) {
    BrowseStyle.LIST -> STYLE_LIST
    BrowseStyle.GRID -> STYLE_GRID
  }

  private fun statusValue(status: BrowseCompletionStatus): Int = when (status) {
    BrowseCompletionStatus.NOT_PLAYED -> STATUS_NOT_PLAYED
    BrowseCompletionStatus.PARTIALLY_PLAYED -> STATUS_PARTIALLY_PLAYED
    BrowseCompletionStatus.FULLY_PLAYED -> STATUS_FULLY_PLAYED
  }
}
