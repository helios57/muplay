package app.muplay.cast.control

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
    fun fromWire(value: String?): TransportState = when (value?.trim()?.uppercase()) {
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
 * [hasError] is `CurrentTransportStatus`, which is a **second** variable and not a state: a
 * renderer that could not fetch or decode what it was given reports `ERROR_OCCURRED` there while
 * `CurrentTransportState` reads an ordinary `STOPPED`. Reading only the state would turn "the
 * speaker refused these bytes" into "the track finished", which is silence with no explanation --
 * so Task 8 turns this flag into a player error instead.
 */
data class TransportInfo(val state: TransportState, val hasError: Boolean) {
  companion object {
    /** The one value of `CurrentTransportStatus` that is not `OK`, per the `AVTransport:1` template. */
    const val STATUS_ERROR_OCCURRED: String = "ERROR_OCCURRED"
  }
}

/** `GetPositionInfo`'s answer, in units this app uses. */
data class PositionInfo(val positionMs: Long?, val durationMs: Long?, val trackUri: String?) {
  /**
   * Whether this speaker is a **follower in a Sonos group**.
   *
   * A grouped Sonos that is not the coordinator reports `TrackURI = x-rincon:RINCON_<uuid>`, which
   * is Sonos's way of saying "I play whatever that speaker plays". `SetAVTransportURI` on it is
   * accepted and does nothing audible, so a cast to it succeeds at every layer and plays nowhere.
   *
   * The scheme is stated **once**, here, and [UpnpRenderer.setUri] asks this property rather than
   * carrying a second copy of the prefix. Two statements of one rule is one chance for them to
   * drift, and this project has paid for that shape more than once.
   */
  val isFollowingAnotherSpeaker: Boolean get() = trackUri?.startsWith(FOLLOW_SCHEME) == true

  companion object {
    /** Sonos's way of saying "I play whatever that speaker plays". */
    const val FOLLOW_SCHEME: String = "x-rincon:"
  }
}
