package app.muplay.cast.control

import app.muplay.cast.soap.UpnpTime

/**
 * `CurrentTransportState`, from the `AVTransport:1` service template.
 *
 * `PAUSED_PLAYBACK` and `PAUSED_RECORDING` collapse into one member because MuPlay never records
 * and the difference is meaningless to it. Everything else is kept distinct, and in particular
 * [UNKNOWN] is **not** folded into [STOPPED]: Task 8 treats `STOPPED` after `PLAYING` as "the
 * track ended, advance", and a parse failure that read as `STOPPED` would skip a track every time
 * a renderer sent something this enum had not seen.
 */
enum class TransportState {
  STOPPED, PLAYING, TRANSITIONING, PAUSED, RECORDING, NO_MEDIA, UNKNOWN;

  companion object {
    fun fromWire(value: String?): TransportState = when (value.orEmpty().trim().uppercase()) {
      "STOPPED" -> STOPPED
      "PLAYING" -> PLAYING
      "TRANSITIONING" -> TRANSITIONING
      "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PAUSED
      "RECORDING" -> RECORDING
      "NO_MEDIA_PRESENT" -> NO_MEDIA
      else -> UNKNOWN
    }
  }
}

/**
 * `GetTransportInfo`'s answer.
 *
 * [hasError] is `CurrentTransportStatus`, which is a **second** state variable and not a state: a
 * renderer that could not fetch or decode what it was given reports `ERROR_OCCURRED` there while
 * `CurrentTransportState` reads an ordinary `STOPPED`. Reading only the state would turn "the
 * speaker refused these bytes" into "the track finished", which is silence with no explanation --
 * so Task 8 turns this flag into a player error instead.
 */
data class TransportInfo(val state: TransportState, val hasError: Boolean) {

  companion object {
    /** The one value of `CurrentTransportStatus` that is not `OK`, per the `AVTransport:1` template. */
    const val STATUS_ERROR_OCCURRED: String = "ERROR_OCCURRED"

    /**
     * `GetTransportInfo`'s two out-arguments, as this app reads them.
     *
     * A pure function on the two strings rather than a body inside [UpnpRenderer], for the reason
     * [TransportState.fromWire] is one: every arm of it -- a renderer that omits the status
     * entirely, one that spells it in lower case, one that pads it -- is then reachable from a
     * test that needs no socket at all, instead of needing a fake that can be persuaded to answer
     * each shape.
     */
    fun fromWire(state: String?, status: String?): TransportInfo = TransportInfo(
      state = TransportState.fromWire(state),
      // An absent status is NOT an error. A renderer that omits the argument has said nothing,
      // and reading silence as a failure would tear down a session over a firmware quirk.
      hasError = STATUS_ERROR_OCCURRED.equals(status.orEmpty().trim(), ignoreCase = true),
    )
  }
}

/** `GetPositionInfo`'s answer, in units this app uses. */
data class PositionInfo(val positionMs: Long?, val durationMs: Long?, val trackUri: String?) {

  /**
   * The speaker this one is **following in a Sonos group**, or `null` when it is playing its own.
   *
   * A grouped Sonos that is not the coordinator reports `TrackURI = x-rincon:RINCON_<uuid>`, which
   * is Sonos's way of saying "I play whatever that speaker plays". `SetAVTransportURI` on it is
   * accepted and does nothing audible, so a cast to it succeeds at every layer and plays nowhere.
   *
   * The scheme is stated **once**, here, and [UpnpRenderer.setUri] asks this property rather than
   * carrying a second copy of the prefix. Two statements of one rule is one chance for them to
   * drift, and this project has paid for that shape more than once.
   */
  val followedCoordinator: String? get() = trackUri?.takeIf { it.startsWith(FOLLOW_SCHEME) }

  val isFollowingAnotherSpeaker: Boolean get() = followedCoordinator != null

  companion object {
    /** Sonos's way of saying "I play whatever that speaker plays". */
    const val FOLLOW_SCHEME: String = "x-rincon:"

    /**
     * `GetPositionInfo`'s three interesting out-arguments, as this app reads them.
     *
     * Pure, for [TransportInfo.fromWire]'s reason. The blank check on the URI is the one that
     * needs it: a renderer with nothing loaded answers `<TrackURI></TrackURI>`, which is an empty
     * string and not an absent argument, and reading it as a URI would give Task 8 a track
     * identity of `""` to compare against the one it queued.
     */
    fun fromWire(position: String?, duration: String?, trackUri: String?): PositionInfo = PositionInfo(
      // `null`, not `0`, when the device says NOT_IMPLEMENTED -- see UpnpTime.parseClock.
      positionMs = UpnpTime.parseClock(position),
      durationMs = UpnpTime.parseClock(duration),
      trackUri = trackUri?.takeIf { it.isNotBlank() },
    )
  }
}
