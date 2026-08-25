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

/**
 * Which [BrowseSurface] a connected client is.
 *
 * A pure function of four values so that the whole decision is testable without a car, a watch or
 * an Android runtime -- spec section 7's *"`isAutomotiveController` branching lets it be tested with
 * no car"*, made literal. `DefaultSurfaceResolver` in `:core:media` is the only place these four
 * values are read off a Media3 `ControllerInfo`, and it is one expression long.
 *
 * **This decides presentation, never authorisation.** Every argument except [ownPackageName]
 * describes whatever connected, and a `ControllerInfo`'s `packageName` is only as honest as the
 * binder that produced it, so nothing here may be read as "this caller is allowed to". It is safe
 * to run on an untrusted name for one structural reason, asserted by
 * `a caller we do not recognise gets the phone tree, whatever it claims to be`: the answer a liar
 * can reach is never wider than the one it already had. [BrowseSurface.PHONE] is the default *and*
 * the fullest tree; [BrowseSurface.CAR] and [BrowseSurface.WATCH] are reductions of it. Whoever
 * writes `onConnect` owns the other half of the question -- which controllers may connect at all --
 * and composes with this function rather than being replaced by it.
 */
object BrowseSurfaces {

  /**
   * The connection-hint key by which this app's own clients declare their surface.
   *
   * Namespaced, because connection hints are one shared `Bundle` handed to a session by whatever
   * connected: a key of `"surface"` would collide with any other library that had the same idea,
   * and a `Bundle` key collision is silent.
   */
  const val HINT_KEY: String = "app.muplay.browse.SURFACE"

  /** Used by `:app`'s Tier 2 browse journey. See Task 3's header for why this exists. */
  const val HINT_CAR: String = "car"

  /** Used by `:wear`'s `MediaBrowser`. Its package is this app's, so nothing else could tell. */
  const val HINT_WATCH: String = "watch"

  /**
   * Hosts that render a media browse tree in a car.
   *
   * A **backstop** for hosts Media3's own predicates do not know, never an override of one they do
   * -- `of` consults the predicate first. `com.google.android.projection.gearhead` is Android Auto
   * (projection from the phone); `com.android.car.media` and `com.android.car.carlauncher` are
   * Android Automotive OS; `com.google.android.gms.car` is the older projection host;
   * `com.google.android.apps.automotive.templates.host` is the templates host used on AAOS
   * headends. All five get the same tree, because what differs is the render, not the runtime.
   */
  val CAR_PACKAGES: Set<String> = setOf(
    "com.google.android.projection.gearhead",
    "com.google.android.gms.car",
    "com.android.car.media",
    "com.android.car.carlauncher",
    "com.google.android.apps.automotive.templates.host",
  )

  /** Wear OS's own bridged media surfaces -- the companion app and the media-session controller. */
  val WATCH_PACKAGES: Set<String> = setOf(
    "com.google.android.wearable.app",
    "com.google.android.wearable.media.sessions",
  )

  /**
   * The classification, in strict precedence order.
   *
   * 1. Media3's own answer, if it says car. Google maintains that list; this file does not.
   * 2. Our backstop package lists, matched **exactly** -- not by prefix and not case-insensitively.
   *    A prefix match is satisfiable by a repackaged app; a case-insensitive one treats a genuinely
   *    different package as the same one.
   * 3. A self-declared hint, and **only** from [ownPackageName]. From anyone else it is a request,
   *    not a declaration, and is ignored.
   * 4. Otherwise a phone: the fullest tree, which is also the right answer for the Assistant, for
   *    the system's media resumption and for a browser this app has never heard of.
   */
  fun of(
    packageName: String,
    ownPackageName: String,
    isCarController: Boolean,
    hintSurface: String?,
  ): BrowseSurface = when {
    isCarController -> BrowseSurface.CAR
    packageName in CAR_PACKAGES -> BrowseSurface.CAR
    packageName in WATCH_PACKAGES -> BrowseSurface.WATCH
    packageName == ownPackageName -> when (hintSurface) {
      HINT_CAR -> BrowseSurface.CAR
      HINT_WATCH -> BrowseSurface.WATCH
      else -> BrowseSurface.PHONE
    }
    else -> BrowseSurface.PHONE
  }
}
