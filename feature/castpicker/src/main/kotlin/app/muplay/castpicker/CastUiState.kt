package app.muplay.castpicker

import app.muplay.cast.discovery.DiscoveryResult
import app.muplay.cast.session.CastSessionState

/**
 * One row in the picker.
 *
 * Every field comes from the [app.muplay.cast.discovery.CastDevice] it describes, and
 * `CastUiStateTest` observes each of them at more than one value across one list — a row whose
 * `isSonos` was a constant would otherwise render a Sonos badge on a Yamaha amplifier and pass any
 * "the list is not empty" assertion.
 *
 * **No URL.** `CastDevice` carries a `descriptionUrl` and two control URLs; none of them is here.
 * That is not because a device description URL is secret — it is not — but because this type is
 * what the composables render, and the rule this whole subsystem is built around is that nothing
 * reaches a label by accident.
 */
data class CastDeviceRow(
  val udn: String,
  val name: String,
  /** The model, when the device reported one. Two identical speakers differ only by their names. */
  val subtitle: String?,
  val isSonos: Boolean,
  val isConnected: Boolean,
)

sealed interface CastUiState {

  /** The sheet is closed. Nothing is being searched for; see [CastViewModel]. */
  data object Hidden : CastUiState

  /** Looking. Distinct from "looked and found nothing" — see [Devices]. */
  data object Searching : CastUiState

  data class Devices(
    val devices: List<CastDeviceRow>,
    /**
     * Remembered speakers that did not answer, **by name**, because a name is something a user can
     * act on and "0 devices found" is not.
     */
    val unreachable: List<String>,
    val connectedUdn: String?,
    /**
     * The connected speaker's own volume, 0–100, or `null` when nothing is connected — which is
     * also what makes the slider absent rather than inert.
     */
    val volumePercent: Int?,
  ) : CastUiState

  /**
   * A cast that will not work, with the sentence the user reads.
   *
   * [message] is the **rendered** sentence, not the protocol string it was classified from. That is
   * a deliberate narrowing: see [castFailure].
   */
  data class Failed(val deviceName: String, val message: String) : CastUiState
}

internal const val CAST_BUTTON_LABEL = "Cast"
internal const val CAST_SEARCHING_LABEL = "Looking for speakers…"
internal const val CAST_NO_DEVICES_LABEL = "No speakers found on this network"
internal const val CAST_DISCONNECT_LABEL = "Stop casting"
internal const val CAST_UNREACHABLE_SUFFIX = "not answering"
internal const val CAST_REFRESH_LABEL = "Search again"
internal const val CAST_RETRY_LABEL = "Try again"
internal const val CAST_TITLE = "Play on"
internal const val CAST_VOLUME_LABEL = "Speaker volume"

/**
 * The supporting line for a Sonos that reported no model name.
 *
 * The only thing `CastDeviceRow.isSonos` renders, and deliberately the only thing: a device that
 * *did* report a model shows that model, because "Sonos One" tells a user more than "Sonos" does.
 * `CastDevice.isSonos` is derived from two independent signals for Task 5's three vendor quirks, so
 * it is reliable enough to name a device by when the device named nothing itself.
 */
internal const val CAST_SONOS_FALLBACK_SUBTITLE = "Sonos speaker"

/**
 * Task 5: `AVTransport::Play` takes a `Speed` argument and every renderer accepts only `"1"`. A
 * book's stored playback speed therefore cannot be delivered to a speaker, so it is said out loud —
 * a setting that silently does nothing is worse than one that is refused.
 *
 * **Deliberately general, and this is the record of that decision.** Plan 4 landed a per-item speed
 * (`app.muplay.model.BookSettings.speed`, stored on the progress row), so the *number* exists. What
 * does not exist is a handle from here to *which* item is playing: this module has no
 * `MediaController` and no `PlaybackConnection` by design — see its build file — and
 * `CastSessionState` carries a device name, not a media id. Naming the number in this sentence
 * would mean giving the picker a media session, which is a larger decision than a sentence is worth
 * making on its own.
 */
internal const val CAST_SPEED_LIMIT_NOTICE =
  "A speaker plays at normal speed. Speed and silence-skipping settings apply on this phone only."

internal const val CAST_GROUPED_NOTICE = "Ungroup it in the Sonos app to cast to it."

/**
 * The three phrases this file classifies a failure by, and the one place they are written down.
 *
 * They are substrings of messages **another module composes**, which is a coupling worth being
 * loud about rather than hiding inside a `when`. `CastSessionState.Failed` carries a `String` and
 * no kind (`CastFailure.kind` exists but does not travel with the state), so a picker that wants to
 * say something better than the protocol string has exactly this to work with.
 *
 * `CastUiStateTest`'s `the phrases this file classifies on are the phrases the cast layer actually
 * produces` drives the real `RendererFollowsAnotherException` and the real `CastRouter` and asserts
 * each of these against what came out. That is what turns "these strings are still right" from a
 * hope into a failing test.
 */
internal object FailurePhrases {
  /** `app.muplay.cast.control.RendererFollowsAnotherException`'s message. */
  const val GROUPED = "is grouped with another"

  /** `CastRouter.confirm`, `UnroutableReason.PROXY_UNREACHABLE_AND_DIRECT_DISABLED`. */
  const val RENDERER_CANNOT_REACH_PHONE = "did not fetch anything from this phone"

  /** `CastRouter.candidate`, `UnroutableReason.NO_ROUTE_TO_RENDERER`. */
  const val PHONE_CANNOT_REACH_RENDERER = "cannot be reached:"
}

/**
 * What the cast layer knows, as what a user sees.
 *
 * A pure function with its own file — so that this, the part where a field silently becomes a
 * constant, is gated by a **BRANCH** floor on Tier 1, while the Composables take a LINE floor on the
 * device. The same split `SetupScreenKt` and `PlayerUiStateKt` use, for the same measured reason:
 * a pure function sharing a file-class with a `@Composable` measures its own branches drowned in
 * the Compose compiler's synthetic ones, and no floor can then reach it.
 */
internal fun castUiState(
  discovery: DiscoveryResult?,
  session: CastSessionState,
  connectedUdn: String? = null,
  volumePercent: Int? = null,
): CastUiState {
  // A failure outranks the list. A picker that renders both is one where the row a user just
  // tapped still looks tappable, with the reason it did not work somewhere above it.
  val failure = castFailure(session)
  return when {
    failure != null -> failure
    // `null` means no search has completed. An empty `DiscoveryResult` means one has, and found
    // nothing -- different facts, and collapsing them leaves a spinner running over an empty room.
    discovery == null -> CastUiState.Searching
    else -> CastUiState.Devices(
      devices = discovery.devices.map { device ->
        CastDeviceRow(
          udn = device.udn,
          name = device.friendlyName,
          subtitle = device.modelName,
          isSonos = device.isSonos,
          isConnected = device.udn == connectedUdn,
        )
      },
      unreachable = discovery.unreachable.map { it.friendlyName },
      connectedUdn = connectedUdn,
      // `null` unless something is actually connected: a slider over nothing is a control that
      // silently does nothing, which is the defect [CAST_SPEED_LIMIT_NOTICE] exists to refuse
      // elsewhere in this very file.
      volumePercent = volumePercent?.takeIf { connectedUdn != null },
    )
  }
}

/**
 * The name the session is connected to, or `null` when it is connected to nothing.
 *
 * Read by the cast **button**, which has to say what it is doing whether or not the sheet is open --
 * so it deliberately does not go through [castUiState], whose whole first decision is that a closed
 * picker is [CastUiState.Hidden].
 *
 * A failed or lost session is connected to nothing, and neither is `Idle`. Reporting a name for
 * those is the UI that keeps saying "Playing on Kitchen" long after the kitchen went quiet.
 */
internal fun castDeviceName(session: CastSessionState): String? = when (session) {
  is CastSessionState.Connecting -> session.deviceName
  is CastSessionState.Playing -> session.deviceName
  CastSessionState.Idle, is CastSessionState.Failed, is CastSessionState.Lost -> null
}

/**
 * Which discovered device the session is playing on, or `null`.
 *
 * Resolved **by name**, because that is the only identifier `CastSessionState` carries -- it is a
 * type built for a session manager, which needs a label, rather than for a list, which needs a key.
 * Two speakers sharing one friendly name resolve to whichever `RendererDirectory` sorted first;
 * that is a tie between two rows the user cannot tell apart either.
 */
internal fun connectedUdn(discovery: DiscoveryResult?, session: CastSessionState): String? {
  val name = castDeviceName(session) ?: return null
  // `?.devices.orEmpty()`, for the reason `CastViewModel.select` records: chaining a second `?.`
  // onto a non-null `List` emits a branch no input can select.
  return discovery?.devices.orEmpty().firstOrNull { it.friendlyName == name }?.udn
}

/**
 * The cast button's content description: [CAST_BUTTON_LABEL] alone when nothing is cast, and the
 * label plus the speaker's name when something is.
 *
 * A content description rather than visible text, so that a journey can assert **which** state the
 * button is in rather than only that a button exists -- and so that a user with TalkBack hears
 * where their audio is going, which is the same fact.
 */
internal fun castButtonDescription(connectedDeviceName: String?): String =
  if (connectedDeviceName == null) {
    CAST_BUTTON_LABEL
  } else {
    "$CAST_BUTTON_LABEL \u2014 $connectedDeviceName"
  }

/**
 * The failure a user reads, or `null` when nothing has failed.
 *
 * Four failures get four different sentences, because "something went wrong" is not information:
 *
 * | what happened | where it comes from |
 * |---|---|
 * | the speaker cannot reach the phone | `CastRouter.confirm`'s `Unroutable` (Task 7) |
 * | the phone cannot reach the speaker | `CastRouter.candidate`'s `Unroutable` (Task 7) |
 * | the speaker is grouped with another | `RendererFollowsAnotherException` (Task 5) |
 * | the speaker vanished mid-track | `CastSessionState.Lost` (Task 8) |
 *
 * **The classified arms discard the protocol string entirely**, and that is the security half of
 * this function as well as the legibility half. `CastFailure`'s own documentation guarantees the
 * reason is free of a stream URL -- an upstream Navidrome URL carries the user's Subsonic `u`, `t`
 * and `s`, and a failure message is exactly the string that reaches a snackbar and a bug report --
 * but a guarantee made in another module is one that can be edited. Everything this function
 * recognises is rewritten from the device's name and nothing else, so the only path that can carry
 * another module's text to a label is the last arm, which exists because a UPnP error code is
 * genuinely worth showing and inventing prose in its place would be worse.
 *
 * Returns a whole [CastUiState.Failed] rather than the plan's `castFailureMessage(): String?`, and
 * the reason is measured rather than stylistic: a `String?` leaves the caller to dig the device name
 * back out of the session, which needs a five-arm `when` whose three non-failure arms no test can
 * reach -- three permanently uncovered branches on a class whose floor is BRANCH.
 */
internal fun castFailure(session: CastSessionState): CastUiState.Failed? = when (session) {
  is CastSessionState.Lost -> CastUiState.Failed(
    session.deviceName,
    "${session.deviceName} stopped responding. Playback moved back to this phone.",
  )

  is CastSessionState.Failed -> CastUiState.Failed(
    session.deviceName,
    when {
      session.reason.contains(FailurePhrases.GROUPED) ->
        "${session.deviceName} is grouped with another speaker. $CAST_GROUPED_NOTICE"
      session.reason.contains(FailurePhrases.RENDERER_CANNOT_REACH_PHONE) ->
        "${session.deviceName} could not reach this phone \u2014 it is probably on a different network."
      session.reason.contains(FailurePhrases.PHONE_CANNOT_REACH_RENDERER) ->
        "This phone could not reach ${session.deviceName} \u2014 it is probably on a different network."
      else -> "${session.deviceName} could not play it: ${session.reason}"
    },
  )

  CastSessionState.Idle, is CastSessionState.Connecting, is CastSessionState.Playing -> null
}
