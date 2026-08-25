package app.muplay.model.browse

/**
 * Which of the three kinds of client is asking for the tree.
 *
 * The values are *kinds of screen*, not *kinds of device*: Android Auto (a phone projecting to a
 * head unit) and Android Automotive OS (the app running natively in the car) are both [CAR],
 * because what differs is the render and the distraction limit, not the runtime.
 */
enum class BrowseSurface(
  /** How many books the Continue shelf offers before it stops. */
  val continueLimit: Int,
  /** The layout hint this surface's browsable tabs carry for their children. */
  val browsableStyle: BrowseStyle,
) {
  /**
   * A car head unit. Four root tabs, a generous Continue shelf because a drive is long, and a grid
   * of cover art because recognising a cover is faster than reading a title at speed.
   */
  CAR(continueLimit = 8, browsableStyle = BrowseStyle.GRID),

  /**
   * A watch. Three root tabs and a short Continue shelf: every extra row is another crown scroll,
   * and a two-column grid of 40 px covers on a 45 mm screen is unreadable.
   */
  WATCH(continueLimit = 5, browsableStyle = BrowseStyle.LIST),

  /**
   * A phone, the system's media resumption, the Assistant, or any other browser. No four-tab
   * render and no distraction limit, so this is the surface that exposes the flat track list and
   * the per-library scoping.
   */
  PHONE(continueLimit = 25, browsableStyle = BrowseStyle.GRID),
  ;

  companion object {
    /**
     * Android Auto renders the root's children as tabs and shows at most this many. A fifth is
     * dropped by the host **silently**, which is why this is a named constant with a test on it
     * rather than a comment above a list.
     */
    const val MAX_CAR_ROOT_TABS: Int = 4
  }
}
