package app.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class StreamFormatTest {

  @Test
  fun `raw is the wire value raw and carries no bitrate`() {
    assertThat(StreamFormat.Raw.wireValue).isEqualTo("raw")
  }

  @Test
  fun `mp3 is the wire value mp3 and carries the bitrate it was given`() {
    // Two observations of the same field, so a constant cannot satisfy both.
    assertThat(StreamFormat.Mp3(96).maxBitRateKbps).isEqualTo(96)
    assertThat(StreamFormat.Mp3(320).maxBitRateKbps).isEqualTo(320)
    assertThat(StreamFormat.Mp3(96).wireValue).isEqualTo("mp3")
  }

  @Test
  fun `a bitrate outside the mp3 range is rejected at construction`() {
    assertThatIllegalArgumentException().isThrownBy { StreamFormat.Mp3(0) }
      .withMessageContaining("maxBitRateKbps")
    assertThatIllegalArgumentException().isThrownBy { StreamFormat.Mp3(321) }
      .withMessageContaining("maxBitRateKbps")
  }

  /**
   * Spec section 4: **never Opus**. With `format=raw` the bytes on the wire are whatever the file
   * is, so "never Opus" cannot be achieved by leaving a parameter off — it has to be an explicit
   * decision, and this is it.
   */
  @Test
  fun `an opus source is transcoded rather than streamed raw`() {
    assertThat(StreamFormat.forSuffix("opus", 192)).isEqualTo(StreamFormat.Mp3(192))
  }

  /**
   * `ogg` too, and this is the deliberate over-reach: an Ogg container may hold Vorbis (fine) or
   * Opus (forbidden), and the suffix cannot tell them apart. Transcoding an Ogg-Vorbis file that
   * did not need it costs bandwidth on a library almost nobody has; letting an Opus stream through
   * breaks Sonos in Plan 6 and hands ExoPlayer a stream Navidrome has mislabelled `audio/ogg`.
   * The trade is made here, on purpose, rather than discovered later.
   */
  @Test
  fun `an ogg source is transcoded because the suffix cannot rule out opus`() {
    assertThat(StreamFormat.forSuffix("ogg", 192)).isEqualTo(StreamFormat.Mp3(192))
  }

  @Test
  fun `the suffix is matched case-insensitively`() {
    // Navidrome sends lower case today. A mirror row is a String and nothing enforces that.
    assertThat(StreamFormat.forSuffix("OPUS", 192)).isEqualTo(StreamFormat.Mp3(192))
    assertThat(StreamFormat.forSuffix("Ogg", 192)).isEqualTo(StreamFormat.Mp3(192))
  }

  @Test
  fun `the transcode bitrate is the one the caller passed`() {
    // The argument's effect, proven by varying only it. Without this, `forSuffix` returning a
    // hardcoded Mp3(192) passes every other test in this class.
    assertThat(StreamFormat.forSuffix("opus", 64)).isEqualTo(StreamFormat.Mp3(64))
    assertThat(StreamFormat.forSuffix("opus", 256)).isEqualTo(StreamFormat.Mp3(256))
  }

  @Test
  fun `every other suffix streams raw`() {
    // The exact mapped list, not `allMatch`: `allMatch` over an empty list is vacuously true, and
    // a `forSuffix` that returned Raw for everything would also pass an `allMatch` written the
    // obvious way. Pairing this with the two transcode cases above is what makes both real.
    val suffixes = listOf("mp3", "flac", "m4a", "m4b", "aac", "wav", "wma", "MP3", "", null)

    assertThat(suffixes.map { StreamFormat.forSuffix(it, 192) })
      .containsExactly(*Array(suffixes.size) { StreamFormat.Raw })
  }

  @Test
  fun `the default transcode bitrate is a real number this project chose`() {
    assertThat(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS).isEqualTo(192)
  }
}
