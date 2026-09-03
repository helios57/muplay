package app.muplay.media

import androidx.media3.common.PlaybackException

/**
 * Why playback stopped, in terms a listener can act on.
 *
 * **Nothing surfaced a playback error before this type existed.** `PlaybackConnection` published
 * `isPlaying = false` and nothing else, so a track that failed to load looked exactly like a track
 * the user had paused -- and tapping play again called `play()` on a player Media3 had already put
 * into `STATE_IDLE`, which does nothing at all. The reported symptom is "the app just stops", and
 * there is no way for a user to tell a dead server from a mis-tap.
 *
 * A small closed set rather than the exception's own message: `PlaybackException.getMessage()` is
 * developer text ("Source error", "Unable to connect"), sometimes null, and occasionally carries a
 * URL -- and in this app a stream URL carries the auth token, which must never reach a screen or a
 * log. Mapping to a fixed set of sentences is what keeps that impossible rather than merely
 * unlikely.
 */
enum class PlaybackFailure {

  /** The server could not be reached, or the connection died mid-track. */
  Connection,

  /** The server answered, and refused. Usually credentials, sometimes a track that is gone. */
  Server,

  /** The file arrived and could not be decoded. */
  Unplayable,

  /** Something else. Kept distinct so the message never claims to know more than it does. */
  Unknown,
  ;

  /**
   * What the user is told, and each one names the thing they can actually do about it.
   *
   * No error code, no exception text: a code is not actionable, and the sentence has to work on a
   * mini player two lines high.
   */
  fun message(): String = when (this) {
    Connection -> "Couldn't reach your server."
    Server -> "Your server refused this track."
    Unplayable -> "This track couldn't be played."
    Unknown -> "Playback stopped unexpectedly."
  }

  companion object {

    /**
     * Maps Media3's error code to one of the four. `null` in, `null` out.
     *
     * **An `Int?`, not a `PlaybackException`,** and that is what makes this decision gateable on
     * the fast tier -- the same split `PlaybackState.durationMsOf` gets and for the same stated
     * reason. It is not merely a preference here: `PlaybackException`'s constructor calls
     * `SystemClock.elapsedRealtime()`, so a JVM test that built one to pass in died with *"Method
     * elapsedRealtime in android.os.SystemClock not mocked"* -- measured, and the reason this
     * signature changed. The `ERROR_CODE_*` constants below are `static final int`s, inlined by
     * the compiler, so naming them loads no Android class.
     *
     * The ranges are Media3's own decades -- `ERROR_CODE_IO_*` are 2000s, `ERROR_CODE_PARSING_*`
     * and `ERROR_CODE_DECODING_*` the 3000s and 4000s -- and they are read as ranges rather than
     * enumerated one constant at a time on purpose: the enumeration would be a list that goes
     * stale the next time Media3 adds a code, and the failure mode of a stale list here is a
     * specific error silently becoming [Unknown].
     *
     * The two that are called out by name are the two a user can act on: a bad password and a
     * missing file both arrive as `ERROR_CODE_IO_BAD_HTTP_STATUS`, and neither is a network
     * problem however much the 2000s range says otherwise.
     */
    fun of(errorCode: Int?): PlaybackFailure? = when (errorCode) {
      null -> null
      PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
      PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
      -> Server
      PlaybackException.ERROR_CODE_REMOTE_ERROR -> Server
      in IO_RANGE -> Connection
      in PARSING_AND_DECODING_RANGE -> Unplayable
      else -> Unknown
    }

    private val IO_RANGE = 2000..2999
    private val PARSING_AND_DECODING_RANGE = 3000..4999
  }
}
