package app.muplay.media

/**
 * Which `MediaController`s the playback session accepts, as a decision over two plain values.
 *
 * A separate object with no Media3 type in its signature, for the reason [StreamRetryPolicy],
 * [TaskRemovalPolicy] and [ResumePolicy] are separate objects: the *rule* is then gated by the fast
 * tier, and what is left in `MuPlaybackService` is an adapter that reads two properties off a
 * `ControllerInfo`. A security rule that can only be exercised by starting a service on a device is
 * a security rule that gets exercised once.
 *
 * ### What it is for
 *
 * `core/media/src/main/AndroidManifest.xml` exports the service, because `exported="true"` plus the
 * `MediaSessionService` intent-filter is the only way Android Auto, Wear, Assistant and the system
 * media controls can find it. Media3's `MediaSession.Callback` accepts **every** connection by
 * default -- verified in the bytecode of `media3-session-1.11.0.aar`, where the default
 * `onConnectAsync` is `Futures.immediateFuture(new AcceptedResultBuilder(session, controller)
 * .build())` with no condition of any kind.
 *
 * What a connected controller can read is the point. `MediaMetadata.toBundle()` writes
 * `FIELD_ARTWORK_URI`, and this app's artwork URI is an authenticated Subsonic URL carrying `u`,
 * `s=salt` and `t=md5(password+salt)` -- a **non-expiring password-equivalent** granting the full
 * Subsonic API as the user. Media3 hands an untrusted controller
 * `ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS`, which is
 * `Player.Commands.Builder().addAllReadOnlyCommands()` -- it withholds transport control and grants
 * metadata reads. So without a gate, *any* locally installed app that declares
 * `<queries><intent><action android:name="androidx.media3.session.MediaSessionService"/></intent>
 * </queries>` obtains that credential with no runtime permission, no notification-listener grant and
 * no prompt.
 *
 * ### The rule, and why it is the platform's own definition rather than a package allow-list
 *
 * [accepts] takes Media3's `ControllerInfo.isTrusted()`, which `MediaSessionStub` computes as
 * `MediaSessionManager.isTrustedForMediaControl(remoteUserInfo)` -- read off the bytecode, not
 * assumed. That platform predicate is true when the caller's uid is the system's, **or the caller's
 * uid is this process's own**, or the caller holds `android.permission.STATUS_BAR_SERVICE` or
 * `android.permission.MEDIA_CONTENT_CONTROL`, or the user has enabled it as a notification listener.
 *
 * A hand-written allow-list of package names (`com.google.android.projection.gearhead`, …) was the
 * obvious alternative and is worse in both directions: it goes stale against every OEM head unit and
 * every rename, and it is a second copy of a decision the platform already publishes. It would also
 * be *less* strict where it matters -- the platform predicate consults the calling uid and pid for
 * the permission checks, which no list of names can.
 *
 * **What this costs Android Auto and Wear (Plan 5): nothing.** Auto's projection host and the Wear
 * companion hold `MEDIA_CONTENT_CONTROL`, SystemUI holds `STATUS_BAR_SERVICE`, and this app's own
 * `PlaybackConnection` shares this process's uid. Every one of them is trusted by the predicate
 * above. And a controller this rule rejects would have been given read-only commands by Media3
 * anyway -- so nothing that could *control* playback loses the ability to.
 *
 * ### What it does not close, stated plainly
 *
 * The platform `MediaSession` is a separate surface. `MediaSessionLegacyStub` mirrors the same
 * metadata onto it (`METADATA_KEY_ART_URI`, `METADATA_KEY_DISPLAY_ICON_URI`), and any app the user
 * has granted notification-listener access reads that directly, without ever calling
 * [MuPlaybackService]. This gate therefore moves the exposure from *"any local app, no permission"*
 * down to *"an app the user granted notification-listener access, or one holding
 * MEDIA_CONTENT_CONTROL"* -- the tier a media app cannot get below. Removing the credential from the
 * metadata altogether (carrying a coverArt **id** and resolving it in the bitmap loader, which Plan
 * 5's `BrowseNode` already does) is the fix that closes it, and it is tracked separately.
 */
object ControllerAccessPolicy {

  /**
   * Media3's placeholder package name for a caller the platform could not attribute.
   *
   * Copied from `MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME` rather than referenced,
   * so this object carries no Media3 type and the fast tier can reach it.
   * `ControllerAccessGateTest.thePlatformLegacyPackageNameThisPolicyCarriesIsMedia3sOwn` holds the
   * copy to Media3's own constant on the device tier, so the two cannot drift.
   *
   * **Why it is accepted rather than refused.** `minSdk` is 26, and below API 28 every transport
   * command that arrives through the platform session -- a headset button, a Bluetooth AVRCP
   * command, the lock-screen controls -- reaches `MediaSessionLegacyStub` through
   * `MediaSessionCompat.getCurrentControllerInfo()`, which falls back to
   * `RemoteUserInfo(LEGACY_CONTROLLER, -1, -1)` whenever the framework's `getCallingPackage()` is
   * empty. `isTrustedForMediaControl` then looks that name up in the package manager, does not find
   * it, and answers `false`. A gate that refused it would silently kill headset and Bluetooth
   * controls on API 26 and 27 -- on devices this project's emulator (API 36) cannot reproduce.
   *
   * It is not a hole a remote caller can climb through. `MediaSessionStub.connect` runs
   * `SessionUtil.checkPackageValidity(context, request.packageName, Binder.getCallingUid())` and
   * drops the connection outright when the claimed package does not belong to the calling uid, and
   * no package with this name exists at all. A legacy `MediaBrowserServiceCompat` client arrives
   * with the real package name and uid the browser service resolved. The only thing that can present
   * this name is Media3 itself, on behalf of the platform session.
   */
  const val PLATFORM_LEGACY_CONTROLLER_PACKAGE = "android.media.session.MediaController"

  /**
   * Whether a controller identifying itself as [controllerPackageName], which the platform judged
   * [isTrustedForMediaControl], may connect.
   *
   * Both arms fail in opposite, visible directions and both are driven by
   * `ControllerAccessPolicyTest`: refusing a trusted controller takes Android Auto, Wear and the
   * system media controls off the session; accepting an untrusted one hands a replayable Subsonic
   * credential to any app on the device.
   */
  fun accepts(controllerPackageName: String, isTrustedForMediaControl: Boolean): Boolean =
    isTrustedForMediaControl || controllerPackageName == PLATFORM_LEGACY_CONTROLLER_PACKAGE
}
