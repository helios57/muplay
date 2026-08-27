package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The transcoded-seek decision, on the fast tier, because it has no Android type in it.
 *
 * Every argument is observed at **two** disjoint values wherever two exist -- this project's
 * standing rule, and the reason `an offset hardcoded to 5` cannot pass anything here.
 */
class TranscodeSeekTest {

  @Test
  fun `a raw stream seeks in place, whatever the server supports`() {
    // Task 1 proved live that `format=raw` honours `Range` with a byte-exact 206. Re-issuing a URI
    // for that would throw away a working seek and buy a round trip with it.
    assertThat(TranscodeSeek.methodFor("raw", serverSupportsTranscodeOffset = true, 5_000L))
      .isEqualTo(SeekMethod.InPlace)
    assertThat(TranscodeSeek.methodFor("raw", serverSupportsTranscodeOffset = false, 5_000L))
      .isEqualTo(SeekMethod.InPlace)
  }

  @Test
  fun `a transcode on a server that supports the extension is re-issued at the offset`() {
    // Two targets, so an offset hardcoded to either one passes exactly one of these.
    assertThat(TranscodeSeek.methodFor("mp3", serverSupportsTranscodeOffset = true, 5_000L))
      .isEqualTo(SeekMethod.ReissueWithOffset(5))
    assertThat(TranscodeSeek.methodFor("mp3", serverSupportsTranscodeOffset = true, 137_400L))
      .isEqualTo(SeekMethod.ReissueWithOffset(137))
  }

  @Test
  fun `the offset floors rather than rounds`() {
    // Flooring is not a rounding preference. The server starts the transcode at or before the
    // second asked for, so the listener never loses audio they asked to hear; rounding up would
    // clip the first word of a sentence and there would be nothing to see. 5_999 rounds to 6 and
    // floors to 5, so the two rules are distinguishable here and nowhere else in this file.
    assertThat(TranscodeSeek.methodFor("mp3", true, 5_999L)).isEqualTo(SeekMethod.ReissueWithOffset(5))
    assertThat(TranscodeSeek.methodFor("mp3", true, 6_000L)).isEqualTo(SeekMethod.ReissueWithOffset(6))
  }

  @Test
  fun `a negative target is clamped rather than sent`() {
    // `seekTo(-1)` is a legal call on a Media3 Player. `timeOffset=-1` is a server-side surprise.
    assertThat(TranscodeSeek.methodFor("mp3", true, -1L)).isEqualTo(SeekMethod.ReissueWithOffset(0))
    assertThat(TranscodeSeek.methodFor("mp3", true, -60_000L)).isEqualTo(SeekMethod.ReissueWithOffset(0))
  }

  @Test
  fun `a transcode on a server without the extension does not offer the seek at all`() {
    // Spec section 4 says unsupported features are silent no-ops, not errors. A silent no-op ON A
    // SEEK is a silent wrong answer, so the honest form of that rule here is to withdraw the
    // command and let the transport controls grey the bar out.
    assertThat(TranscodeSeek.methodFor("mp3", serverSupportsTranscodeOffset = false, 5_000L))
      .isEqualTo(SeekMethod.NotOffered)
    // At a second target, so "returns NotOffered for everything" is not what is being observed.
    assertThat(TranscodeSeek.methodFor("mp3", serverSupportsTranscodeOffset = false, 0L))
      .isEqualTo(SeekMethod.NotOffered)
  }

  @Test
  fun `an item whose format is unknown seeks in place`() {
    // A `MediaItem` this app did not build -- a resumed session restored by the system, say --
    // carries no format extra. In place is the conservative answer: it is what every player did
    // before this task, and it is right for the raw streams that are the overwhelming majority.
    assertThat(TranscodeSeek.methodFor("", serverSupportsTranscodeOffset = true, 5_000L))
      .isEqualTo(SeekMethod.InPlace)
    // And a format nobody in this project can ask for is not treated as a transcode either.
    assertThat(TranscodeSeek.methodFor("opus", serverSupportsTranscodeOffset = false, 5_000L))
      .isEqualTo(SeekMethod.InPlace)
  }

  /**
   * The wire value is compared against `StreamFormat`'s own constant, not a literal.
   *
   * Worth its own observation because the failure is silent in a specific way: a `methodFor` that
   * compared against `"MP3"`, or against `"raw"` inverted, would send every transcode down the
   * in-place path and every raw stream down the re-issue path -- and both mistakes are invisible
   * to a test that only ever passes one of the two strings.
   */
  @Test
  fun `the two wire values this client can ask for are the two this decision distinguishes`() {
    assertThat(TranscodeSeek.methodFor(app.muplay.model.StreamFormat.Raw.wireValue, true, 5_000L))
      .isEqualTo(SeekMethod.InPlace)
    assertThat(TranscodeSeek.methodFor(app.muplay.model.StreamFormat.Mp3.WIRE_VALUE, true, 5_000L))
      .isEqualTo(SeekMethod.ReissueWithOffset(5))
  }

  /**
   * The cache key for a re-issued stream is not the bare track id, and this is the assertion that
   * says why in one line.
   *
   * `TrackIdCacheKeyFactory` files every request under `MediaItem.customCacheKey`, so an offset
   * stream carrying the track's own key is written **into the middle of the full track's cache
   * entry** -- and every later read of that track is served audio from the wrong place. That is the
   * same silent wrong answer the seek itself was fixed to remove, one layer down.
   */
  @Test
  fun `an offset stream is cached under its own key, and the top of the track under the plain id`() {
    assertThat(TranscodeSeek.cacheKeyFor("song-1", 20)).isEqualTo("song-1@20")
    // Two offsets, so a key that appended a constant passes neither.
    assertThat(TranscodeSeek.cacheKeyFor("song-1", 7)).isEqualTo("song-1@7")
    // ...and two ids, so a key that ignored the id passes neither.
    assertThat(TranscodeSeek.cacheKeyFor("chapter-14", 20)).isEqualTo("chapter-14@20")
    // Offset zero is the same audio as no offset at all (measured: 300369 bytes either way), so it
    // hits the entry the ordinary stream already filled rather than duplicating it.
    assertThat(TranscodeSeek.cacheKeyFor("song-1", 0)).isEqualTo("song-1")
    assertThat(TranscodeSeek.cacheKeyFor("song-1", -1)).isEqualTo("song-1")
  }
}
