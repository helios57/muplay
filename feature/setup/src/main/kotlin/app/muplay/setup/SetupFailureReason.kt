package app.muplay.setup

/**
 * Why [SetupUiState.Failure] happened, typed so the UI can render a genuinely different message
 * for each — see [SetupViewModel.connect] for exactly which caught exception maps to which
 * member.
 *
 * Two of the four mirror the line [app.muplay.network.SubsonicException] itself already draws
 * between "the server answered, on purpose" and "we could not even ask": [Rejected] covers both
 * members of that sealed hierarchy (a Subsonic-level error such as wrong credentials, or a bad
 * HTTP status) — the server said something. [Unreachable] covers everything that is structurally
 * not a `SubsonicException` — a dead socket, a timeout, an unparseable body — where nothing came
 * back to interpret at all. [InvalidUrl] is the one case with no network attempt behind it at all:
 * the server URL failed client-side validation before [SetupViewModel.connect] ever called
 * `ping`. [CleartextForbidden] is the fourth, and it is a refusal by the *platform* rather than by
 * either endpoint — see its own note for why it is read off the thrown exception and not off the
 * scheme.
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

  /**
   * The URL was `http` and the platform refused to send it — [host] is the host from the URL the
   * user typed.
   *
   * Distinct from [Unreachable], and the distinction is the whole point: nothing was wrong with
   * the URL or the network, so *"check the URL and your connection"* sends a self-hoster to debug
   * two things that are both fine. Navidrome's own default is `http://host:4533` and this app's
   * release build forbids cleartext to a remote host, so this is the **most likely first-run
   * failure for the audience this app is for**.
   *
   * It is recognised from `java.net.UnknownServiceException`, which is what Android throws for a
   * blocked cleartext request, rather than from the scheme before the call. That ordering is
   * load-bearing: the platform permits cleartext to `localhost` regardless of
   * `cleartextTrafficPermitted` (measured — see CLAUDE.md), so a pre-check on `http` would reject
   * `http://localhost:4533`, which really does work and is how a release build is driven against
   * a local server. Letting the platform decide means this fires exactly when it actually blocked
   * something.
   */
  data class CleartextForbidden(val host: String) : SetupFailureReason
}

/**
 * The user-facing message for [this] reason. `internal`, not `private` to `SetupScreen.kt`: this
 * is a plain `when` with no Compose or Android dependency, so it belongs beside the
 * type it maps and is tested directly on the JVM (see `SetupFailureReasonTest`) rather than left
 * for Task 8's emulator journey — that tier is for the branching that genuinely needs Compose to
 * exercise (see [SetupUiState]'s own rendering in `SetupScreen`), not for this.
 */
internal fun SetupFailureReason.toMessage(): String = when (this) {
  SetupFailureReason.InvalidUrl -> "Enter a valid server URL, e.g. https://music.example.com."
  is SetupFailureReason.Rejected -> "Could not sign in" + (detail?.let { ": $it" } ?: " (server error $code).")
  SetupFailureReason.Unreachable -> "Could not reach the server. Check the URL and your connection."
  is SetupFailureReason.CleartextForbidden ->
    "This build will not send your password over plain http to $host. " +
      "Use an https:// address — a reverse proxy or a VPN such as Tailscale is the usual way."
}
