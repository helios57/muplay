package app.muplay.cast.session

/**
 * Why a cast session can no longer play.
 *
 * Four kinds and not a string, because the consumer has to *branch* on them and each arm is a
 * different thing to tell a user and a different `PlaybackException.ERROR_CODE_*` for the Media3
 * player that wraps this session (Task 9):
 *
 * - [RENDERER_REFUSED] -- the speaker answered and said no. It is reachable; it will not play
 *   **this**. Retrying the same track will fail the same way.
 * - [RENDERER_UNREACHABLE] -- the speaker stopped answering at all, for
 *   [CastSession.LOST_AFTER_FAILURES] consecutive polls. This is the one that pairs with
 *   [CastSessionState.Lost] and with resuming locally.
 * - [UNROUTABLE] -- there is no way to get bytes to it. Nothing was ever played.
 * - [UNEXPECTED] -- an `IOException` that is none of the above. Never silently ignored.
 */
enum class CastFailureKind {
  RENDERER_REFUSED,
  RENDERER_UNREACHABLE,
  UNROUTABLE,
  UNEXPECTED,
}

/**
 * A failure a user can be shown.
 *
 * [message] is built by [CastSession] and is **guaranteed not to carry a stream URL**: an upstream
 * Navidrome URL holds the user's Subsonic `u`, `t` and `s` parameters, and a failure message is
 * exactly the string that ends up in a log line, a snackbar and a bug report. See
 * [CastSession.send]'s KDoc, which records why the one check in this subsystem that would
 * otherwise embed one is not made at runtime at all.
 */
data class CastFailure(val kind: CastFailureKind, val message: String)

/**
 * What a cast session is doing, for the session manager above it (Task 9) rather than for a player.
 *
 * Deliberately *not* the same type as [CastPlayback]. A player asks "am I ready, where am I"
 * several times a second; a session manager asks "is this session still a thing" and needs to act
 * exactly once when the answer changes. [CastSession] emits a state here only when it differs from
 * the last one.
 */
sealed interface CastSessionState {

  /** Nothing is cast. Also where a released session ends up. */
  data object Idle : CastSessionState

  /** Told to play, but the renderer has not yet reported that it is. */
  data class Connecting(val deviceName: String) : CastSessionState

  /** The renderer itself reported `PLAYING`. Not "we sent Play and it returned 200". */
  data class Playing(val deviceName: String) : CastSessionState

  /** The session ended because something refused. [reason] is user-facing and URL-free. */
  data class Failed(val deviceName: String, val reason: String) : CastSessionState

  /**
   * The renderer stopped answering mid-stream.
   *
   * [positionMs] is the **last position the renderer actually reported**, and it is the whole
   * reason this is a distinct state rather than a [Failed] with a network message: Task 9 resumes
   * locally from it, so a zero here would silently send a listener back to the start of a track --
   * or, for a book, to the start of the book.
   *
   * [mediaId] is `null` only when the queue was empty, which cannot happen while something was
   * playing; it is nullable so this type does not lie about a case its own constructor cannot rule
   * out.
   */
  data class Lost(val deviceName: String, val positionMs: Long, val mediaId: String?) : CastSessionState
}
