package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The rule `MuPlaybackService.LibraryCallback.onConnect` applies, gated by the fast tier.
 *
 * Every case below is a real caller of an exported `MediaSessionService`, and each one fails in its
 * own direction: refusing a trusted controller takes Android Auto, Wear, Assistant and the system
 * media controls off the session, and accepting an untrusted one hands a non-expiring Subsonic
 * credential to any app on the device (see [ControllerAccessPolicy] for the exact mechanism).
 *
 * The device tier proves the complementary half -- that the real callback really does refuse a real
 * `ControllerInfo` -- in `ControllerAccessGateTest`. Neither half is sufficient alone: this one
 * cannot see a callback that never consults the policy, and that one cannot enumerate the rule.
 */
class ControllerAccessPolicyTest {

  @Test
  fun `an app the platform does not trust with media control cannot connect`() {
    // The attack, in one line. A hostile app declares the MediaSessionService <queries> intent,
    // builds a SessionToken for app.muplay/app.muplay.media.MuPlaybackService and connects. It
    // holds no runtime permission and the user was never asked. Media3 would give it read-only
    // player commands -- which include reading `mediaMetadata`, i.e. the authenticated artwork URL.
    assertThat(
      ControllerAccessPolicy.accepts(
        controllerPackageName = "com.example.snooper",
        isTrustedForMediaControl = false,
      ),
    ).isFalse()
  }

  @Test
  fun `a controller the platform trusts with media control connects`() {
    // Android Auto's projection host, the Wear companion, Assistant and SystemUI all reach this
    // arm: `MediaSessionManager.isTrustedForMediaControl` answers true for MEDIA_CONTENT_CONTROL,
    // STATUS_BAR_SERVICE, the system uid, and an enabled notification listener. Plan 5 depends on
    // this arm being open.
    assertThat(
      ControllerAccessPolicy.accepts(
        controllerPackageName = "com.google.android.projection.gearhead",
        isTrustedForMediaControl = true,
      ),
    ).isTrue()
  }

  @Test
  fun `this app's own controller connects, and does so on the trusted arm`() {
    // `PlaybackConnection` runs in this process, and `isTrustedForMediaControl` answers true for
    // `uid == Process.myUid()` -- so the app's own controller needs no exception of its own. Asserted
    // because the alternative (a package-name carve-out for our own applicationId) is the obvious
    // thing to write and would be a second copy of a fact the platform already publishes.
    assertThat(
      ControllerAccessPolicy.accepts(
        controllerPackageName = "app.muplay",
        isTrustedForMediaControl = true,
      ),
    ).isTrue()
    // ...and the package name is not what carries it: the same name, untrusted, is refused. A
    // controller cannot claim to be us -- `MediaSessionStub.connect` drops any connection whose
    // claimed package does not belong to the calling uid -- but a rule that keyed on the name would
    // be relying on that instead of on the trust decision it says it makes.
    assertThat(
      ControllerAccessPolicy.accepts(
        controllerPackageName = "app.muplay",
        isTrustedForMediaControl = false,
      ),
    ).isFalse()
  }

  @Test
  fun `the platform's own unattributable legacy caller connects`() {
    // Below API 28 -- and `minSdk` is 26 -- every headset button, Bluetooth AVRCP command and
    // lock-screen transport control that reaches the session through `MediaSessionCompat` arrives
    // as this placeholder with uid and pid of -1 whenever the framework's `getCallingPackage()` is
    // empty. `isTrustedForMediaControl` looks the name up in the package manager, does not find it,
    // and answers false. Refusing it would silently kill hardware media controls on API 26 and 27,
    // on devices this project's emulator cannot reproduce.
    assertThat(
      ControllerAccessPolicy.accepts(
        controllerPackageName = ControllerAccessPolicy.PLATFORM_LEGACY_CONTROLLER_PACKAGE,
        isTrustedForMediaControl = false,
      ),
    ).isTrue()
  }

  @Test
  fun `the legacy carve-out is one exact name, not a family of them`() {
    // The carve-out is safe only because no installed package can present that name -- Media3
    // verifies a claimed package against the calling uid before a ControllerInfo is ever built. A
    // rule written as `startsWith("android.media")` or as a `contains` would extend the exemption to
    // names an app *can* be installed under, and nothing else in this suite would notice.
    listOf(
      "android.media.session.MediaControllerX",
      "android.media.session",
      "android.media.session.MediaController.evil",
      "com.evil.android.media.session.MediaController",
    ).forEach {
      assertThat(ControllerAccessPolicy.accepts(it, isTrustedForMediaControl = false))
        .describedAs(it)
        .isFalse()
    }
  }
}
