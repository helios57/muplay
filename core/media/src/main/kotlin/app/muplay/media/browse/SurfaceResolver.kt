package app.muplay.media.browse

import android.content.Context
import androidx.media3.session.MediaSession
import app.muplay.model.browse.BrowseSurface
import app.muplay.model.browse.BrowseSurfaces
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Turns a connected controller into the [BrowseSurface] whose tree it should get.
 *
 * An interface with one method, injected into `MuPlayLibraryCallback`, for one reason: the
 * *decision* and the *extraction* have different testability. Keeping the decision behind this seam
 * means the callback's own behaviour -- which tree it builds, in which order, with which extras --
 * is exercisable at every surface from a real `MediaBrowser` and from a plain unit test alike,
 * without any of them needing a car or a watch.
 *
 * **A resolver answers "what should this controller see", never "may this controller connect".**
 * Every value it reads describes whatever connected, and a `ControllerInfo`'s `packageName` is only
 * as honest as the binder that produced it -- Media3 1.11.0 says so itself, with a separate
 * `isPackageNameVerified()` that this class does not consult. The connection policy is
 * `MediaSession.Callback.onConnect`'s to make; this composes with it and does not stand in for it.
 * See [BrowseSurfaces] for why classifying an unverified name is nonetheless safe: the surface a
 * liar can reach is never wider than the one it already had.
 */
fun interface SurfaceResolver {
  fun surfaceOf(browser: MediaSession.ControllerInfo): BrowseSurface
}

/**
 * The production resolver: the values off the controller, straight into [BrowseSurfaces.of].
 *
 * It is deliberately one expression long. Everything it hands on -- `packageName`,
 * `connectionHints`, and the whole of [BrowseSurfaces.of] -- is proven on the JVM in Task 3 and
 * over real Media3 IPC in Task 4, and `DefaultSurfaceResolverTest` in this module's instrumented
 * tier drives this class itself through a real `ControllerInfo`.
 *
 * ### `isCarController` is wired to `false`, and this is the corrected reason
 *
 * The predicate Task 3 went looking for is **not** on `ControllerInfo` -- that type declares
 * `getPackageName`, `getUid`, `getConnectionHints`, `isTrusted` and `isPackageNameVerified`, and
 * nothing else. Task 3 concluded from that that Media3 1.11.0 ships no car predicate at all, and
 * wrote so here. **That was wrong, and Task 4 measured it:** `MediaSession` itself declares
 * `public final boolean isAutomotiveController(ControllerInfo)` and
 * `public final boolean isAutoCompanionController(ControllerInfo)`, both delegating to
 * `MediaSessionImpl`. Read out of the shipped aar with `javap -c`.
 *
 * What they *do* is the reason nothing here changed. In 1.11.0 they are literal package-name
 * comparisons and nothing more:
 *
 *   * `isAutomotiveController` -> `com.android.car.media` or `com.android.car.carlauncher`
 *   * `isAutoCompanionController` -> `com.google.android.projection.gearhead`
 *
 * All three of those names are already in [BrowseSurfaces.CAR_PACKAGES], so consulting Media3 would
 * change **no classification for any input**. It would, on the other hand, cost this seam its
 * shape: `surfaceOf` takes a `ControllerInfo` and those predicates are instance methods on the
 * `MediaSession`, so threading them in means passing a session into the resolver -- a Media3 type
 * per call, for zero behaviour.
 *
 * So [BrowseSurfaces.CAR_PACKAGES] carries the whole car decision, and now demonstrably carries a
 * superset of Media3's own. A car host Google adds later, to those predicates but not to that list,
 * receives the phone root -- which Android Auto truncates to its four tabs. Degraded, not wrong.
 * The argument stays in [BrowseSurfaces.of]'s signature, at both of its values on the JVM tier, so
 * that wiring it (or `PackageManager.FEATURE_AUTOMOTIVE`, which this class's own [context] could
 * answer for an Automotive OS build) is a one-line change here and no change at all there.
 *
 * Spec section 7 spells the name `isAutomotiveController` on `ControllerInfo`; it is on
 * `MediaSession`. Task 11's spec correction should say that rather than "it does not exist".
 */
class DefaultSurfaceResolver @Inject constructor(
  @ApplicationContext private val context: Context,
) : SurfaceResolver {

  override fun surfaceOf(browser: MediaSession.ControllerInfo): BrowseSurface =
    BrowseSurfaces.of(
      packageName = browser.packageName,
      ownPackageName = context.packageName,
      // Media3 1.11.0 exposes no car predicate on ControllerInfo; CAR_PACKAGES carries the whole
      // decision. A car host Google adds later, and does not appear in that list, receives the
      // phone root -- which Android Auto truncates to its four tabs. Degraded, not wrong.
      isCarController = false,
      hintSurface = browser.connectionHints.getString(BrowseSurfaces.HINT_KEY),
    )
}
