package app.muplay.media

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The attack `MuPlaybackService` exports, mounted against the real callback.
 *
 * `ControllerAccessPolicyTest` proves what the *rule* is; it cannot prove that
 * `MuPlaybackService.LibraryCallback` consults it, and until this file existed the callback
 * overrode nothing at all -- so Media3's default `onConnectAsync`
 * (`Futures.immediateFuture(new AcceptedResultBuilder(session, controllerInfo).build())`, verified
 * in the 1.11.0 bytecode) accepted every connection unconditionally. That is this project's recorded
 * "decision verified at a different layer than applied" defect, in the one place where the layer
 * that applies it is a security boundary.
 *
 * Instrumented rather than JVM, because `MediaSession.ControllerInfo` and `MediaSession` are
 * Android-backed: the `ControllerInfo` a session receives is built from a
 * `MediaSessionManager.RemoteUserInfo`, and there is no Robolectric in this project.
 *
 * ### Why it does not connect a second app instead
 *
 * The connection this test has to refuse comes from an app that is **not** this one. `:app`'s
 * instrumented suite runs inside `app.muplay`'s own process and uid, so every controller it can
 * build is trusted by construction (`isTrustedForMediaControl` answers true for
 * `uid == Process.myUid()`), and a second, hostile APK is not something a Gradle module's test can
 * install. Media3 ships `ControllerInfo.createTestOnlyControllerInfo` for exactly this: it builds
 * the object the session's own stub would build, with the package name, uid and trust flag the
 * platform would have computed. The values below are the ones a hostile third-party app produces.
 *
 * The complementary half -- that a *real* controller over real IPC still connects and plays -- is
 * `app.muplay.MuPlaybackServiceTest`, every test of which would go red if this gate refused too
 * much.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ControllerAccessGateTest {

  private val callback = MuPlaybackService.LibraryCallback()

  private lateinit var context: Context

  /**
   * A real `MediaSession`, because [MediaSession.Callback.onConnect] takes one.
   *
   * The accepted arm delegates to `super.onConnect(session, controller)` and the session is the
   * argument it needs; the refused arm never touches it. Built over a `SimpleBasePlayer` that does
   * nothing rather than over an `ExoPlayer`: `PlayerConstructionTest` forbids any second
   * `ExoPlayer.Builder` in this module, test sources included, and this suite's subject is who may
   * connect -- not what plays.
   */
  private lateinit var session: MediaSession

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      session = MediaSession.Builder(context, InertPlayer(Looper.getMainLooper()))
        .setId("controller-access-gate-test")
        .build()
    }
  }

  @After
  fun tearDown() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      session.player.release()
      session.release()
    }
  }

  @Test
  fun anAppThePlatformDoesNotTrustWithMediaControlIsRefused() {
    // A locally installed app with no permission of any kind. It declares
    // <queries><intent><action android:name="androidx.media3.session.MediaSessionService"/></intent>
    // </queries>, builds a SessionToken for app.muplay/app.muplay.media.MuPlaybackService and
    // connects. What it is after is `mediaMetadata.artworkUri` -- an authenticated Subsonic URL
    // carrying u, s=salt and t=md5(password+salt), which does not expire.
    //
    // `isAccepted`, read off the real ConnectionResult, is the whole assertion: Media3 hands an
    // accepted-but-untrusted controller DEFAULT_UNTRUSTED_PLAYER_COMMANDS, which is
    // `addAllReadOnlyCommands()` -- no transport control, and metadata reads.
    val hostile = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      /* packageName = */ "com.example.snooper",
      /* pid = */ Process.myPid() + 1,
      /* uid = */ Process.myUid() + 1,
      /* libraryVersion = */ 1,
      /* interfaceVersion = */ 1,
      /* isTrusted = */ false,
      /* connectionHints = */ Bundle.EMPTY,
      /* isPackageNameVerified = */ true,
    )

    assertThat(onMain { callback.onConnect(session, hostile) }.isAccepted)
      .describedAs("an untrusted third-party controller must not be accepted")
      .isFalse()
  }

  @Test
  fun aControllerThePlatformTrustsWithMediaControlIsAccepted() {
    // Android Auto's projection host, the Wear companion, Assistant and SystemUI all arrive on this
    // arm, and so does this app's own PlaybackConnection. Asserted with a package name that is not
    // this app's, so what is being read is the trust flag rather than an accidental self-exemption.
    val trusted = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      /* packageName = */ "com.google.android.projection.gearhead",
      /* pid = */ Process.myPid() + 1,
      /* uid = */ Process.myUid() + 1,
      /* libraryVersion = */ 1,
      /* interfaceVersion = */ 1,
      /* isTrusted = */ true,
      /* connectionHints = */ Bundle.EMPTY,
      /* isPackageNameVerified = */ true,
    )

    assertThat(onMain { callback.onConnect(session, trusted) }.isAccepted)
      .describedAs("Plan 5's Auto and Wear controllers depend on this arm staying open")
      .isTrue()
  }

  @Test
  fun theSameConnectionIsRefusedOrAcceptedOnTheTrustFlagAlone() {
    // The discriminating pair: one package name, two trust answers. A gate that keyed on anything
    // else -- a name prefix, the interface version, `isPackageNameVerified` -- satisfies one of the
    // two tests above and fails here.
    val name = "com.example.head.unit"
    val results = listOf(false, true).map { trusted ->
      val info = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        name,
        Process.myPid() + 1,
        Process.myUid() + 1,
        1,
        1,
        trusted,
        Bundle.EMPTY,
        true,
      )
      onMain { callback.onConnect(session, info) }.isAccepted
    }

    // `containsExactly`, not two separate assertions: an `anyMatch` over a gate that answered the
    // same thing twice would be vacuously satisfiable in one direction or the other.
    assertThat(results).containsExactly(false, true)
  }

  @Test
  fun thePlatformsOwnUnattributableLegacyCallerIsAccepted() {
    // Below API 28 this is what a headset button, a Bluetooth AVRCP command and the lock-screen
    // transport controls look like when the framework's getCallingPackage() is empty:
    // RemoteUserInfo(LEGACY_CONTROLLER, -1, -1), which isTrustedForMediaControl answers false for
    // because no package by that name exists. minSdk is 26, and this emulator is not.
    val platform = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME,
      /* pid = */ -1,
      /* uid = */ -1,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
      MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
      /* isTrusted = */ false,
      Bundle.EMPTY,
      /* isPackageNameVerified = */ false,
    )

    assertThat(onMain { callback.onConnect(session, platform) }.isAccepted)
      .describedAs("refusing this takes hardware media buttons off API 26 and 27")
      .isTrue()
  }

  @Test
  fun thePlatformLegacyPackageNameThisPolicyCarriesIsMedia3sOwn() {
    // `ControllerAccessPolicy` copies the constant rather than referencing it, so that the rule
    // carries no Media3 type and the fast tier can reach it. This is what stops the copy drifting:
    // if Media3 renames it, the carve-out above silently stops matching and hardware media buttons
    // on API 26/27 start being refused -- with every other test in this project still green.
    assertThat(ControllerAccessPolicy.PLATFORM_LEGACY_CONTROLLER_PACKAGE)
      .isEqualTo(MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME)
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  /**
   * A `Player` that holds no resources and plays nothing.
   *
   * `MediaSession.Builder` needs a `Player`, and this suite needs a `MediaSession` only as the
   * argument `onConnect` takes. A `SimpleBasePlayer` with the default `State` is the smallest thing
   * that satisfies the type -- and it is deliberately not an `ExoPlayer`, so this file does not
   * become the second construction site `PlayerConstructionTest` exists to prevent.
   */
  private class InertPlayer(looper: Looper) : SimpleBasePlayer(looper) {
    override fun getState(): State = State.Builder().build()
  }
}
