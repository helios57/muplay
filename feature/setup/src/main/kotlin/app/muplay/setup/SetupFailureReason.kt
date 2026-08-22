package app.muplay.setup

/**
 * Why [SetupUiState.Failure] happened, typed so the UI can render a genuinely different message
 * for each — see [SetupViewModel.connect] for exactly which caught exception maps to which
 * member.
 *
 * Two of the three mirror the line [app.muplay.network.SubsonicException] itself already draws
 * between "the server answered, on purpose" and "we could not even ask": [Rejected] covers both
 * members of that sealed hierarchy (a Subsonic-level error such as wrong credentials, or a bad
 * HTTP status) — the server said something. [Unreachable] covers everything that is structurally
 * not a `SubsonicException` — a dead socket, a timeout, an unparseable body — where nothing came
 * back to interpret at all. [InvalidUrl] is the one case with no network attempt behind it at all:
 * the server URL failed client-side validation before [SetupViewModel.connect] ever called
 * `ping`.
 */
sealed interface SetupFailureReason {

  /** The server URL was blank or not a well-formed `http`/`https` URL. No network call was made. */
  data object InvalidUrl : SetupFailureReason

  /**
   * The server answered, on purpose, and the answer was not success — a Subsonic-level error
   * (e.g. wrong credentials, [app.muplay.network.SubsonicErrorException.code] `40`) or an
   * unsuccessful HTTP status ([app.muplay.network.SubsonicHttpException.status]). [code] carries
   * whichever of those two numbers applies; [detail] is the exception's own message, where one
   * exists.
   */
  data class Rejected(val code: Int, val detail: String?) : SetupFailureReason

  /**
   * No usable answer came back at all — a transport failure (no connection, a timeout) or an
   * unparseable response. Structurally not a [app.muplay.network.SubsonicException] (see that
   * type's own documentation), so it cannot carry a Subsonic error code or HTTP status the way
   * [Rejected] does.
   */
  data object Unreachable : SetupFailureReason
}
