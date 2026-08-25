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
 * **`isCarController` is wired to `false`, because Media3 1.11.0 has no car predicate to wire it
 * to.** Measured, not assumed: `MediaSession.ControllerInfo` in the resolved
 * `media3-session-1.11.0.aar` declares `getPackageName`, `getUid`, `getConnectionHints`,
 * `isTrusted` and `isPackageNameVerified`, and neither `isAutomotiveController` nor
 * `isAutomobileController` exists anywhere in that artifact (`grep -ri` over every class in it:
 * zero hits, including the string "automotive"). Spec section 7 names those predicates and is
 * therefore describing an API that does not exist -- Task 11's spec correction has to say so.
 *
 * So [BrowseSurfaces.CAR_PACKAGES] carries the whole car decision today. A car host Google adds
 * later, that does not appear in that list, receives the phone root -- which Android Auto truncates
 * to its four tabs. Degraded, not wrong. The argument stays in [BrowseSurfaces.of]'s signature, at
 * both of its values on the JVM tier, so that a real predicate (or
 * `PackageManager.FEATURE_AUTOMOTIVE`, which this class's own [context] could answer for an
 * Automotive OS build) is a one-line change here and no change at all there.
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
