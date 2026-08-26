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
  fun `a follower is recognised by its scheme and nothing else is`() {
    // `x-rincon:` is stated once, in `PositionInfo`, and this is where the two directions of that
    // one statement are held. `x-rincon-stream:` -- a real Sonos scheme for a line-in source --
    // is deliberately in the negative list: it is NOT a group follower, and a `contains` check
    // would call it one.
    val uris = listOf(
      "x-rincon:RINCON_000E58ABCDEF01400",
      "http://192.168.1.9:8080/media/t.mp3",
      "x-file-cifs://server/music/t.mp3",
      "",
      null,
    )

    assertThat(uris.map { PositionInfo(0L, 0L, it).isFollowingAnotherSpeaker })
      .containsExactly(true, false, false, false, false)
  }
}
