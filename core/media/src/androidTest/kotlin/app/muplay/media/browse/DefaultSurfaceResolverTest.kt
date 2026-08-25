package app.muplay.media.browse

import android.os.Bundle
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.browse.BrowseSurface
import app.muplay.model.browse.BrowseSurfaces
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The extraction half of the surface decision, driven through a **real**
 * `MediaSession.ControllerInfo`.
 *
 * Task 3's brief says a `ControllerInfo` "cannot be constructed by a test -- it is created by
 * Media3 when a controller connects", and treats `DefaultSurfaceResolver` as the one expression no
 * CI in this repository can observe. **In Media3 1.11.0 that is not true**, and it is worth being
 * exact about, because the whole seam was designed around it:
 * `MediaSession.ControllerInfo.createTestOnlyControllerInfo(packageName, pid, uid, libraryVersion,
 * interfaceVersion, isTrusted, connectionHints, isPackageNameVerified)` is a `public static`,
 * `@VisibleForTesting` factory in the shipped `media3-session-1.11.0.aar` (read out of the artifact
 * with `javap`, then exercised here). It needs a real `Bundle`, so it belongs on this tier and not
 * on the JVM one -- but it is not out of reach.
 *
 * What that buys: every value `DefaultSurfaceResolver` reads is now observed at two or more values
 * against the real type, so a resolver that hardcoded a package name, read the wrong `Bundle` key,
 * or ignored its `Context` fails here rather than on a head unit. What remains unobservable is
 * nothing at all -- `isCarController` is wired to a literal `false` precisely because Media3 1.11.0
 * ships no car predicate to read (verified by absence: neither `isAutomotiveController` nor
 * `isAutomobileController`, nor even the string "automotive", occurs anywhere in that aar).
 *
 * Note the package this app answers to here is **not** `app.muplay`: a library module's
 * instrumented tests are self-instrumenting, so `context.packageName` is `app.muplay.media.test`.
 * That is deliberate rather than incidental -- the tests below take the expected value from the
 * same `Context` the resolver is given, so a `DefaultSurfaceResolver` that hardcoded the shipping
 * application id instead of reading its `Context` would fail every hint assertion here.
 */
@RunWith(AndroidJUnit4::class)
class DefaultSurfaceResolverTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val resolver = DefaultSurfaceResolver(context)
  private val ownPackage: String = context.packageName

  @Test
  fun theControllersOwnPackageNameDecides() {
    // Two controllers, identical in every other respect, differing only in the name they connected
    // under. A resolver that passed a constant would return the same surface for both.
    val surfaces = listOf(
      "com.google.android.projection.gearhead",
      "com.google.android.wearable.media.sessions",
      "com.example.stranger",
    ).map { resolver.surfaceOf(controller(packageName = it)) }

    assertThat(surfaces)
      .containsExactly(BrowseSurface.CAR, BrowseSurface.WATCH, BrowseSurface.PHONE)
  }

  @Test
  fun theHintIsReadFromTheConnectionHintsUnderOurOwnKey() {
    // Same package -- ours -- three times, so only the Bundle differs. The fourth row puts the very
    // same value under a different key: a resolver that scanned the Bundle's values, or read a key
    // of "surface", would answer CAR for it.
    val surfaces = listOf(
      Bundle(),
      Bundle().apply { putString(BrowseSurfaces.HINT_KEY, BrowseSurfaces.HINT_CAR) },
      Bundle().apply { putString(BrowseSurfaces.HINT_KEY, BrowseSurfaces.HINT_WATCH) },
      Bundle().apply { putString("surface", BrowseSurfaces.HINT_CAR) },
    ).map { resolver.surfaceOf(controller(packageName = ownPackage, hints = it)) }

    assertThat(surfaces).containsExactly(
      BrowseSurface.PHONE,
      BrowseSurface.CAR,
      BrowseSurface.WATCH,
      BrowseSurface.PHONE,
    )
  }

  @Test
  fun theSameHintFromAnyOtherPackageIsRefused() {
    // The self-declaration rule, over real IPC types. `ownPackage + ".evil"` is the case that
    // matters: a prefix comparison anywhere in this path would hand a neighbouring package the
    // watch tree on nothing but its own say-so.
    val hints = Bundle().apply { putString(BrowseSurfaces.HINT_KEY, BrowseSurfaces.HINT_WATCH) }
    val surfaces = listOf(ownPackage, "$ownPackage.evil", "com.example.other", "app.muplay")
      .map { resolver.surfaceOf(controller(packageName = it, hints = hints)) }

    assertThat(surfaces).containsExactly(
      BrowseSurface.WATCH,
      BrowseSurface.PHONE,
      BrowseSurface.PHONE,
      // `app.muplay` is the *shipping* application id and is deliberately NOT this process's
      // package here. It is refused for the same reason every other stranger is, which is the
      // assertion that catches a resolver reading a constant instead of its Context.
      BrowseSurface.PHONE,
    )
  }

  @Test
  fun aTrustedVerifiedControllerIsClassifiedNoDifferentlyFromAnUnverifiedOne() {
    // Media3 1.11.0 carries `isTrusted` and `isPackageNameVerified` on `ControllerInfo`, and this
    // resolver consults NEITHER: it decides presentation, not authorisation. Pinned as behaviour
    // rather than left as prose, because the day someone wires the connection policy is the day
    // this distinction has to still be true -- a resolver that started refusing unverified callers
    // would be making a security decision in the wrong layer, and this test would go red.
    val hints = Bundle().apply { putString(BrowseSurfaces.HINT_KEY, BrowseSurfaces.HINT_WATCH) }
    val surfaces = listOf(true to true, true to false, false to true, false to false)
      .map { (trusted, verified) ->
        resolver.surfaceOf(
          controller(
            packageName = ownPackage,
            hints = hints,
            isTrusted = trusted,
            isPackageNameVerified = verified,
          ),
        )
      }

    assertThat(surfaces).isNotEmpty
      .allSatisfy { assertThat(it).isEqualTo(BrowseSurface.WATCH) }
  }

  /**
   * A real `MediaSession.ControllerInfo`, built through Media3's own `@VisibleForTesting` factory.
   * `pid`/`uid` are this process's own; `libraryVersion`/`interfaceVersion` are the values a
   * current Media3 controller connects with.
   */
  private fun controller(
    packageName: String,
    hints: Bundle = Bundle(),
    isTrusted: Boolean = false,
    isPackageNameVerified: Boolean = true,
  ): MediaSession.ControllerInfo = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
    packageName,
    android.os.Process.myPid(),
    android.os.Process.myUid(),
    /* libraryVersion = */ 2,
    /* interfaceVersion = */ 2,
    isTrusted,
    hints,
    isPackageNameVerified,
  )
}
