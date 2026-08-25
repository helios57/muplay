package app.muplay.cast.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * RFC 7233 parsing and resolution, as a pure function, so the eleven cases in this task's table
 * are gated in Tier 1 without a socket.
 *
 * Every value below is observed at **two or more** arguments. `bytes=0-` is the request a naive
 * renderer sends first and is therefore the one most likely to be the only one tested -- and it
 * passes against an implementation that ignores the header entirely, which is why it appears here
 * only alongside offsets that do not.
 */
class RangeHeaderTest {

  @Test
  fun `an absent header is absent and not a malformed one`() {
    // Two different facts with two different answers: absent means "send the whole thing as 200",
    // and so does ignored -- but they arrive by different routes and a reader needs to see both.
    assertThat(RangeHeader.parse(null)).isEqualTo(RangeRequest.Absent)
    assertThat(RangeHeader.parse("")).isEqualTo(RangeRequest.Absent)
    assertThat(RangeHeader.parse("   ")).isEqualTo(RangeRequest.Absent)
  }

  @Test
  fun `a bounded range keeps both of its numbers`() {
    // Two observations of each end, so neither can be a constant.
    assertThat(RangeHeader.parse("bytes=100-199")).isEqualTo(RangeRequest.Bounded(100, 199))
    assertThat(RangeHeader.parse("bytes=0-0")).isEqualTo(RangeRequest.Bounded(0, 0))
    assertThat(RangeHeader.parse("bytes=512-1023")).isEqualTo(RangeRequest.Bounded(512, 1023))
  }

  @Test
  fun `an open-ended range has no last byte`() {
    assertThat(RangeHeader.parse("bytes=0-")).isEqualTo(RangeRequest.Bounded(0, null))
    assertThat(RangeHeader.parse("bytes=999-")).isEqualTo(RangeRequest.Bounded(999, null))
  }

  @Test
  fun `a suffix range names how many bytes from the end`() {
    assertThat(RangeHeader.parse("bytes=-500")).isEqualTo(RangeRequest.Suffix(500))
    assertThat(RangeHeader.parse("bytes=-1")).isEqualTo(RangeRequest.Suffix(1))
    assertThat(RangeHeader.parse("bytes=-0")).isEqualTo(RangeRequest.Suffix(0))
  }

  @Test
  fun `whitespace and case in the unit are tolerated`() {
    assertThat(RangeHeader.parse("bytes = 100 - 199")).isEqualTo(RangeRequest.Bounded(100, 199))
    assertThat(RangeHeader.parse("BYTES=100-199")).isEqualTo(RangeRequest.Bounded(100, 199))
    assertThat(RangeHeader.parse(" bytes=-500 ")).isEqualTo(RangeRequest.Suffix(500))
  }

  @Test
  fun `everything unparseable is Ignored, which is not the same as Unsatisfiable`() {
    // RFC 7233: a server MAY ignore a Range header it does not understand and answer 200. A
    // server that answered 416 here would refuse requests it should have served.
    listOf("bytes=abc", "bytes=", "items=0-10", "bytes=5-2", "bytes=-", "bytes=0-0,10-20", "0-10")
      .forEach { header ->
        assertThat(RangeHeader.parse(header))
          .describedAs("parse of %s", header)
          .isEqualTo(RangeRequest.Ignored)
      }
  }

  @Test
  fun `a number too large to hold is ignored rather than thrown out of the parser`() {
    // Every byte of a `Range` header comes from a device on the LAN that MuPlay did not write, and
    // this parser's contract is that it returns a decision for every string one can send. `toLong`
    // on either end raises NumberFormatException instead -- out of a parser, into a connection
    // thread, past the caller's `catch (IOException)`. Both ends, because they parse separately.
    assertThat(RangeHeader.parse("bytes=-99999999999999999999")).isEqualTo(RangeRequest.Ignored)
    assertThat(RangeHeader.parse("bytes=99999999999999999999-")).isEqualTo(RangeRequest.Ignored)
    assertThat(RangeHeader.parse("bytes=0-99999999999999999999")).isEqualTo(RangeRequest.Ignored)
  }

  @Test
  fun `an absent or ignored request resolves to the whole entity`() {
    assertThat(RangeHeader.resolve(RangeRequest.Absent, 1000)).isEqualTo(RangeResolution.Whole)
    assertThat(RangeHeader.resolve(RangeRequest.Ignored, 1000)).isEqualTo(RangeResolution.Whole)
  }

  @Test
  fun `a bounded range resolves to exactly those bytes`() {
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(100, 199), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(100, 199)))
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(0, null), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(0, 999)))
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(999, null), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(999, 999)))
  }

  @Test
  fun `a last byte past the end is clamped rather than refused`() {
    // RFC 7233: the last-byte-pos is clamped to the length. A renderer asking for more than exists
    // is asking for "the rest", and 416 there would stall playback near the end of every track.
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(0, 99_999), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(0, 999)))
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(900, 99_999), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(900, 999)))
  }

  @Test
  fun `a suffix range resolves from the end, and is clamped to the whole entity`() {
    assertThat(RangeHeader.resolve(RangeRequest.Suffix(500), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(500, 999)))
    assertThat(RangeHeader.resolve(RangeRequest.Suffix(1), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(999, 999)))
    assertThat(RangeHeader.resolve(RangeRequest.Suffix(5000), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(0, 999)))
  }

  @Test
  fun `a first byte at or past the end is unsatisfiable`() {
    // The boundary from both sides. `>= totalLength` against `> totalLength` is the classic
    // off-by-one, and it is the difference between 416 and a 206 that names no bytes.
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(999, null), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(999, 999)))
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(1000, null), 1000))
      .isEqualTo(RangeResolution.Unsatisfiable)
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(5000, 6000), 1000))
      .isEqualTo(RangeResolution.Unsatisfiable)
  }

  @Test
  fun `a suffix of zero bytes is unsatisfiable, not the whole entity`() {
    // `bytes=-0` names no bytes at all. Reading it as "no suffix, so everything" would hand a
    // renderer the start of the file when it asked for nothing.
    assertThat(RangeHeader.resolve(RangeRequest.Suffix(0), 1000)).isEqualTo(RangeResolution.Unsatisfiable)
  }

  @Test
  fun `a range against an empty entity is unsatisfiable`() {
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(0, null), 0)).isEqualTo(RangeResolution.Unsatisfiable)
    assertThat(RangeHeader.resolve(RangeRequest.Suffix(10), 0)).isEqualTo(RangeResolution.Unsatisfiable)
  }

  @Test
  fun `a byte range knows how long it is`() {
    // Off-by-one in `length` is off-by-one in `Content-Length`, which is a renderer waiting for a
    // byte that never arrives.
    assertThat(ByteRange(0, 999).length).isEqualTo(1000L)
    assertThat(ByteRange(100, 199).length).isEqualTo(100L)
    assertThat(ByteRange(999, 999).length).isEqualTo(1L)
  }
}
