package app.muplay.media

import androidx.media3.common.PlaybackException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The error-code mapping, on the fast tier.
 *
 * Reachable here because [PlaybackFailure.of] takes an `Int?` and nothing else. That is not
 * fastidiousness: the first version of this file passed a `PlaybackException`, and all seven tests
 * died with *"Method elapsedRealtime in android.os.SystemClock not mocked"* -- that constructor
 * timestamps itself off `SystemClock`. The `ERROR_CODE_*` constants named below are `static final
 * int`s and are inlined at compile time, so referring to them loads no Android class.
 *
 * The same split `PlaybackState.durationMsOf` gets, and for the same reason: the decision is a pure
 * function, and the only thing that needs a device is the player that produces the input.
 */
class PlaybackFailureTest {

  private fun failureFor(errorCode: Int): PlaybackFailure? = PlaybackFailure.of(errorCode)

  @Test
  fun `no error is no failure`() {
    assertThat(PlaybackFailure.of(null)).isNull()
  }

  @Test
  fun `a connection that could not be opened is a connection problem`() {
    assertThat(failureFor(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
      .isEqualTo(PlaybackFailure.Connection)
  }

  @Test
  fun `a connection that died mid-track is a connection problem`() {
    assertThat(failureFor(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
      .isEqualTo(PlaybackFailure.Connection)
  }

  /**
   * **The two the ranges get wrong, and the reason [PlaybackFailure.of] names them.**
   *
   * Both live in Media3's 2000s "IO" decade, and neither is a network problem: a wrong password
   * and a track the server no longer has are things the user can act on, and "Couldn't reach your
   * server" would send them to check their wifi. Delete either arm and this pair goes red while
   * every other case in this file stays green.
   */
  @Test
  fun `a server that answered and refused is a server problem, not a network one`() {
    assertThat(failureFor(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
      .isEqualTo(PlaybackFailure.Server)
    assertThat(failureFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
      .isEqualTo(PlaybackFailure.Server)
  }

  /** A speaker that refused the stream. `UpnpPlayer` raises this one for a cast failure. */
  @Test
  fun `a remote renderer that refused is a server problem`() {
    assertThat(failureFor(PlaybackException.ERROR_CODE_REMOTE_ERROR))
      .isEqualTo(PlaybackFailure.Server)
  }

  @Test
  fun `a container that could not be parsed is unplayable`() {
    assertThat(failureFor(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
      .isEqualTo(PlaybackFailure.Unplayable)
  }

  @Test
  fun `a decoder that failed is unplayable`() {
    assertThat(failureFor(PlaybackException.ERROR_CODE_DECODING_FAILED))
      .isEqualTo(PlaybackFailure.Unplayable)
  }

  /**
   * Everything outside the three decades. `ERROR_CODE_UNSPECIFIED` is 1000, below the IO range, and
   * it is the code Media3 uses when it genuinely does not know -- so the message must not claim to
   * either.
   */
  @Test
  fun `anything else is unknown rather than guessed at`() {
    assertThat(failureFor(PlaybackException.ERROR_CODE_UNSPECIFIED))
      .isEqualTo(PlaybackFailure.Unknown)
    assertThat(failureFor(PlaybackException.ERROR_CODE_TIMEOUT))
      .isEqualTo(PlaybackFailure.Unknown)
  }

  /**
   * **No message may carry an error code, a URL, or exception text.**
   *
   * The reason is this app's own: a stream URL carries the Subsonic auth token, which is password
   * equivalent, and `PlaybackException.getMessage()` is developer text that can contain one. This
   * assertion is what makes "map to a fixed set of sentences" a rule rather than a habit -- add a
   * `"$this: ${error.message}"` to `message()` and it goes red.
   */
  @Test
  fun `every message is a plain sentence with nothing borrowed from the exception`() {
    assertThat(PlaybackFailure.entries.map { it.message() })
      .allSatisfy { message ->
        assertThat(message).endsWith(".")
        assertThat(message).doesNotContain("http")
        assertThat(message).doesNotContainPattern("\\d")
      }
  }

  /** Four distinct sentences. Two failures that read identically are one failure with two names. */
  @Test
  fun `no two failures say the same thing`() {
    assertThat(PlaybackFailure.entries.map { it.message() }.toSet())
      .hasSize(PlaybackFailure.entries.size)
  }
}
