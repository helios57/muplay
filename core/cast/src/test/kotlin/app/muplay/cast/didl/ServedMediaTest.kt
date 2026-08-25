package app.muplay.cast.didl

import app.muplay.model.StreamFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ServedMediaTest {

  @Test
  fun `a raw stream is served as the source format, suffix by suffix`() {
    // The exact mapped list, in order, rather than eleven separate assertions or an `allMatch`
    // that would be vacuously true on an empty input. A `ServedMedia.of` returning a constant
    // fails here on ten of eleven entries.
    val suffixes = listOf("mp3", "flac", "m4a", "m4b", "mp4", "aac", "wav", "wma", "aiff", "alac", "oga")

    assertThat(suffixes.map { ServedMedia.of(it, StreamFormat.Raw).mimeType }).containsExactly(
      "audio/mpeg",
      "audio/flac",
      "audio/mp4",
      "audio/mp4",
      "audio/mp4",
      "audio/aac",
      "audio/wav",
      "audio/x-ms-wma",
      "audio/aiff",
      "audio/mp4",
      "audio/mpeg",
    )
  }

  @Test
  fun `the file extension is the source suffix, lowercased, for a raw stream`() {
    // Separate from the MIME assertion because they are separate fields with separate failure
    // modes: a wrong MIME confuses a generic renderer, a wrong extension makes Sonos refuse.
    assertThat(ServedMedia.of("FLAC", StreamFormat.Raw).fileExtension).isEqualTo("flac")
    assertThat(ServedMedia.of("m4b", StreamFormat.Raw).fileExtension).isEqualTo("m4b")
    assertThat(ServedMedia.of("mp3", StreamFormat.Raw).fileExtension).isEqualTo("mp3")
  }

  /**
   * The invariant this whole type exists for.
   *
   * `StreamFormat.forSuffix("opus", ...)` returns `Mp3` (spec section 4, "Never Opus"), so
   * Navidrome transcodes and the bytes on the wire are MP3 -- whatever the source file was. A
   * `ServedMedia` derived from the suffix would promise Sonos `audio/ogg` and a `.opus` URL while
   * serving MP3, and Sonos would refuse the format it was promised.
   */
  @Test
  fun `a transcode is served as mp3, whatever the source file was`() {
    val transcoded = StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)

    // Four sources, one answer, each field observed. This is the assertion that closes spec
    // section 12's "Sonos rejects a served format" risk.
    listOf("opus", "ogg", "flac", null).forEach { suffix ->
      assertThat(ServedMedia.of(suffix, transcoded).mimeType)
        .describedAs("mime for suffix %s under a forced transcode", suffix)
        .isEqualTo("audio/mpeg")
      assertThat(ServedMedia.of(suffix, transcoded).fileExtension)
        .describedAs("extension for suffix %s under a forced transcode", suffix)
        .isEqualTo("mp3")
    }
  }

  @Test
  fun `an unknown suffix falls back to something a renderer will at least attempt`() {
    // `application/octet-stream` would be refused outright by Sonos; `audio/mpeg` with a `.mp3`
    // extension is the guess most likely to play, and the mirror very rarely lacks a suffix. The
    // fallback is a decision, so it is pinned rather than left to whatever the map returns.
    assertThat(ServedMedia.of("xyz", StreamFormat.Raw).mimeType).isEqualTo(ServedMedia.FALLBACK_MIME)
    assertThat(ServedMedia.of(null, StreamFormat.Raw).fileExtension).isEqualTo(ServedMedia.FALLBACK_EXTENSION)
    assertThat(ServedMedia.FALLBACK_MIME).isEqualTo("audio/mpeg")
    assertThat(ServedMedia.FALLBACK_EXTENSION).isEqualTo("mp3")
  }

  @Test
  fun `the suffix is matched case-insensitively`() {
    assertThat(ServedMedia.of("MP3", StreamFormat.Raw).mimeType).isEqualTo("audio/mpeg")
    assertThat(ServedMedia.of("Flac", StreamFormat.Raw).mimeType).isEqualTo("audio/flac")
  }

  @Test
  fun `protocolInfo names the mime type and declares byte-range seeking`() {
    // Two observations of the MIME position, so it cannot be a constant.
    assertThat(ServedMedia.of("mp3", StreamFormat.Raw).protocolInfo).isEqualTo(
      "http-get:*:audio/mpeg:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000",
    )
    assertThat(ServedMedia.of("flac", StreamFormat.Raw).protocolInfo).isEqualTo(
      "http-get:*:audio/flac:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000",
    )
  }

  /**
   * `DLNA.ORG_OP=01` is a **promise**: the low bit means byte-range seeking is supported, so a
   * renderer that reads it may issue `Range` requests and expect 206. Task 6's proxy owes that
   * promise, which is why the two are asserted against each other there.
   *
   * `DLNA.ORG_PN` is deliberately **absent**. A profile name identifies an exact encoding
   * (`MP3`, `MP3X`, `LPCM`, ...), a wrong one is a hard rejection on a strict renderer, and
   * Navidrome tells this client nothing precise enough to compute one. An absent PN means "work it
   * out from the bytes", which every renderer can do; a wrong PN means "no".
   */
  @Test
  fun `protocolInfo declares no dlna profile name, on purpose`() {
    assertThat(ServedMedia.of("mp3", StreamFormat.Raw).protocolInfo).doesNotContain("DLNA.ORG_PN")
    assertThat(ServedMedia.of("mp3", StreamFormat.Raw).protocolInfo).contains("DLNA.ORG_OP=01")
    assertThat(ServedMedia.DLNA_FLAGS).hasSize(32)
  }

  @Test
  fun `opus never reaches a renderer, by construction`() {
    // Not an assertion about a check, an assertion about the type system: `StreamFormat.forSuffix`
    // makes `opus` unrepresentable as a raw stream, so there is no path from an Opus file to an
    // `audio/opus` protocolInfo. Pinned here because this module is where the consequence lands.
    val format = StreamFormat.forSuffix("opus", StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)

    assertThat(format).isEqualTo(StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS))
    assertThat(ServedMedia.of("opus", format).mimeType).isEqualTo("audio/mpeg")
    assertThat(ServedMedia.of("opus", format).protocolInfo).doesNotContain("opus")
    assertThat(ServedMedia.of("opus", format).protocolInfo).doesNotContain("ogg")
  }
}
