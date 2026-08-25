package app.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class StreamFormatTest {

  /**
   * The Ogg family as the server this client talks to indexes it, written out here so that a
   * **missing member** of `StreamFormat.TRANSCODE_ONLY_SUFFIXES` is a red test rather than a
   * silence.
   *
   * That distinction is the whole reason this list exists. `oga` was absent from the policy set
   * and this class was 9/9 green with both of the `format/` mutation probes CAUGHT, because every
   * assertion named only suffixes the set already contained -- a test can only observe an
   * omission if something independent of the set says what the set must hold.
   *
   * The source is the pinned `deluan/navidrome:0.63.2` binary's own audio-extension table, read
   * out of the running container: `... m4a mp4 m4b m4p ogg oga aif asf mpp ac3 als wav raw mid`,
   * with `opus` and `flac` in the MIME table beside it. `oga` is the IANA-registered Ogg *audio*
   * extension and it sits directly beside `ogg`, so this server indexes a `.oga` file and reports
   * `suffix = "oga"` like any other. `mka` is not in that table at all and `webm` appears only as
   * a MIME type, never as an indexed audio extension -- so neither is in this list, and adding
   * either would be a guess rather than an observation.
   */
  private val oggFamilySuffixes = listOf("opus", "ogg", "oga")

  /**
   * Suffixes whose container cannot hold Opus, so `format=raw` is both correct and preferred.
   * `""` and `null` are here for the same reason: an unknown suffix streams raw on purpose.
   */
  private val rawSuffixes = listOf("mp3", "flac", "m4a", "m4b", "aac", "wav", "wma", "MP3", "", null)

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

  /**
   * The rule stated over the whole family at once, so that dropping any one member reddens this.
   *
   * `oga` is the member that was missing: `forSuffix("oga", 192)` returned `Raw`, `streamUrl`
   * sent `format=raw`, and an Ogg-Opus track reached the player as Opus mislabelled `audio/ogg`
   * -- the exact harm spec section 4's "never Opus" exists to prevent, and the one Plan 6's Sonos
   * renderer cannot decode. The per-suffix tests above are kept as named observations; this one
   * is the observation none of them could make.
   */
  @Test
  fun `every suffix the ogg container is indexed under is transcoded, in either case`() {
    val cases = oggFamilySuffixes.flatMap { listOf(it, it.uppercase()) }

    // A map rather than a loop of assertions: the failure names the suffix that came back wrong,
    // and one member missing from the policy set is one entry wrong, not a silent pass.
    assertThat(cases.associateWith { StreamFormat.forSuffix(it, 192) })
      .isEqualTo(cases.associateWith { StreamFormat.Mp3(192) })

    // The two lists in this class are a partition, not two independent opinions. The wrong way to
    // silence this test is to move a suffix into `rawSuffixes`; this line contradicts that edit.
    assertThat(rawSuffixes).doesNotContainAnyElementsOf(cases)
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
    assertThat(rawSuffixes.map { StreamFormat.forSuffix(it, 192) })
      .containsExactly(*Array(rawSuffixes.size) { StreamFormat.Raw })
  }

  @Test
  fun `the default transcode bitrate is a real number this project chose`() {
    assertThat(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS).isEqualTo(192)
  }
}
