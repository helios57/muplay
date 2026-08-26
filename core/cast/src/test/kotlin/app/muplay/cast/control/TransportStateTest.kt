package app.muplay.cast.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TransportStateTest {

  @Test
  fun `every wire value the AVTransport template defines maps to a state`() {
    // The exact mapped list, in order. One `isEqualTo` per value would leave a reader unable to
    // see which values are covered at all, and an `allMatch` would be vacuous on an empty input.
    val wire = listOf(
      "STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "PAUSED_RECORDING",
      "RECORDING", "NO_MEDIA_PRESENT",
    )

    assertThat(wire.map { TransportState.fromWire(it) }).containsExactly(
      TransportState.STOPPED,
      TransportState.PLAYING,
      TransportState.TRANSITIONING,
      TransportState.PAUSED,
      TransportState.PAUSED,
      TransportState.RECORDING,
      TransportState.NO_MEDIA,
    )
  }

  @Test
  fun `an unrecognised or missing value is UNKNOWN and not STOPPED`() {
    // The distinction matters in Task 8: `STOPPED` after `PLAYING` at the end of a track means
    // "advance to the next one", and a parse failure that read as `STOPPED` would skip a track
    // every time a renderer sent something unexpected.
    assertThat(TransportState.fromWire("SOMETHING_NEW")).isEqualTo(TransportState.UNKNOWN)
    assertThat(TransportState.fromWire(null)).isEqualTo(TransportState.UNKNOWN)
    assertThat(TransportState.fromWire("")).isEqualTo(TransportState.UNKNOWN)
  }

  @Test
  fun `the value is matched case-insensitively and trimmed`() {
    assertThat(TransportState.fromWire(" PLAYING ")).isEqualTo(TransportState.PLAYING)
    assertThat(TransportState.fromWire("playing")).isEqualTo(TransportState.PLAYING)
  }

  @Test
  fun `an absent transport status is not an error, and every spelling of the error one is`() {
    // Four observations of one boolean, because a `hasError` hardcoded either way passes a test
    // that only ever looks at one device. The absent case is the one that matters most: a renderer
    // that omits `CurrentTransportStatus` has said nothing, and reading silence as a failure would
    // tear down a session over a firmware quirk.
    val statuses = listOf(null, "OK", "ERROR_OCCURRED", " error_occurred ")

    assertThat(statuses.map { TransportInfo.fromWire("PLAYING", it).hasError })
      .containsExactly(false, false, true, true)
    // ...and the state half is not taken from the status half.
    assertThat(TransportInfo.fromWire("PLAYING", "ERROR_OCCURRED"))
      .isEqualTo(TransportInfo(TransportState.PLAYING, hasError = true))
    assertThat(TransportInfo.fromWire("STOPPED", "OK"))
      .isEqualTo(TransportInfo(TransportState.STOPPED, hasError = false))
  }

  @Test
  fun `position info reads three fields from three arguments, and a blank track uri is no uri`() {
    // Every field disjoint from the others, so none of them can be reading a neighbour. The blank
    // `TrackURI` is what a renderer with nothing loaded really sends -- an empty element, not an
    // absent argument -- and reading it as a URI would give Task 8 an identity of `""` to compare
    // against the one it queued.
    assertThat(PositionInfo.fromWire("0:01:23", "0:05:00", "http://10.0.0.2/a.mp3"))
      .isEqualTo(PositionInfo(83_000L, 300_000L, "http://10.0.0.2/a.mp3"))
    assertThat(PositionInfo.fromWire("0:00:07", "0:01:01", "   "))
      .isEqualTo(PositionInfo(7_000L, 61_000L, null))
    // `NOT_IMPLEMENTED` and an absent argument are null, never zero: a player that read them as
    // zero would drag the seek bar back to the start once a second.
    assertThat(PositionInfo.fromWire("NOT_IMPLEMENTED", null, null))
      .isEqualTo(PositionInfo(null, null, null))
  }

  @Test
  fun `a follower is recognised by its scheme and nothing else is`() {
    // `x-rincon:` is stated once, in `PositionInfo`, and this is where the two directions of that
    // one statement are held. `x-rincon-stream:` -- a real Sonos scheme for a line-in source --
    // is deliberately in the negative list: it is NOT a group follower, and a `contains` check
    // would call it one.
    val uris = listOf(
      "x-rincon:RINCON_000E58ABCDEF01400",
      "http://192.168.1.9:8080/media/t.mp3",
      // Two real Sonos `x-` schemes that are NOT group membership: a line-in or TV source, and a
      // file on an SMB share. A prefix loosened to `x-` -- the plausible way to get this wrong --
      // calls both of them followers and refuses to cast to a speaker that is perfectly free.
      "x-rincon-stream:RINCON_000E58ABCDEF01400",
      "x-file-cifs://server/music/t.mp3",
      "",
      null,
    )

    assertThat(uris.map { PositionInfo(0L, 0L, it).isFollowingAnotherSpeaker })
      .containsExactly(true, false, false, false, false, false)
    // ...and the coordinator is the URI itself, not a boolean dressed up as one: Task 10 puts it
    // in front of the user.
    assertThat(uris.map { PositionInfo(0L, 0L, it).followedCoordinator })
      .containsExactly("x-rincon:RINCON_000E58ABCDEF01400", null, null, null, null, null)
  }
}
