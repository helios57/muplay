package app.muplay.library

import app.muplay.database.SyncFailure

/**
 * **Why the album list is empty** — one of four genuinely different situations that used to render
 * one identical sentence.
 *
 * `LibraryScreen` printed `"Nothing here yet."` for a search with no match, for a first sync still
 * running, for a sync that failed against an empty mirror, and for a library that really is empty.
 * The third is the expensive one: a user who typed their server URL wrong saw an app that looked
 * like it worked and reported no music, with nothing anywhere naming the real problem.
 */
sealed interface LibraryEmptyReason {

  /** A search ran and matched nothing. [query] is what was searched for. */
  data class SearchNoMatch(val query: String) : LibraryEmptyReason

  /** A sync is in flight and the mirror has nothing in it yet. */
  data object Syncing : LibraryEmptyReason

  /** The mirror is empty because the sync that would have filled it failed. */
  data class SyncFailed(val failure: SyncFailure) : LibraryEmptyReason

  /** The sync succeeded and the library really has nothing in it. */
  data object Empty : LibraryEmptyReason
}

/** What the browse screen says when [this] is why its list is empty. */
internal fun LibraryEmptyReason.toMessage(): String = when (this) {
  is LibraryEmptyReason.SearchNoMatch -> "No albums match “$query”."
  LibraryEmptyReason.Syncing -> "Loading your library…"
  // Never "Nothing here yet": the library is not known to be empty, only unfetched.
  is LibraryEmptyReason.SyncFailed -> failure.describe()
  LibraryEmptyReason.Empty -> "Nothing here yet."
}

/**
 * **What the browse screen's banner has to report**, typed rather than pre-rendered.
 *
 * `LibraryViewModel.refresh` used to build the string itself, and flattened every
 * `SyncState.Failed` — a changed password, a 502 from a reverse proxy, a lapsed certificate, a
 * release build refusing plain `http`, and "setup never finished" — into:
 *
 * > Could not reach the server. Showing your last synced library.
 *
 * Both halves could be false at once. The first misnames every cause but a dead socket; the second
 * promises a fallback that does not exist on a first run, which is exactly the
 * message-that-is-not-true-when-shown defect [LibraryUiState.Content]'s own KDoc forbids. That is
 * why [toMessage] takes `hasMirror` rather than deciding on its own.
 */
sealed interface LibraryNotice {

  /** Nothing to say. */
  data object Idle : LibraryNotice

  /** A sync is running. */
  data object Syncing : LibraryNotice

  /** The server is mid-scan, so the mirror may be incomplete through no fault of this app. */
  data object ScanInProgress : LibraryNotice

  /** The last sync failed. */
  data class Failed(val failure: SyncFailure) : LibraryNotice

  /**
   * The last shuffle failed.
   *
   * Its own member rather than a flag: a shuffle that threw used to be swallowed into an empty
   * result, and an empty shuffle list renders identically to a shuffle nobody has tapped — so the
   * user tapped Shuffle, nothing happened, and nothing anywhere said why.
   */
  data class ShuffleFailed(val failure: SyncFailure) : LibraryNotice
}

/**
 * The banner text for [this], or `null` when there is nothing to say.
 *
 * @param hasMirror whether there is a previously synced library still on screen behind the
 *   failure. It decides only whether the message may offer it — see this type's own note.
 */
internal fun LibraryNotice.toMessage(hasMirror: Boolean): String? = when (this) {
  LibraryNotice.Idle -> null
  LibraryNotice.Syncing -> "Checking the server for changes…"
  LibraryNotice.ScanInProgress ->
    "The server is still scanning, so some albums may be missing. Tap $REFRESH_LABEL when it has finished."
  is LibraryNotice.Failed ->
    failure.describe() + if (hasMirror) " Showing your last synced library." else ""
  is LibraryNotice.ShuffleFailed -> "Shuffle could not reach the server. " + failure.describe()
}

/**
 * One sentence naming what actually went wrong, shared by the banner and the empty state.
 *
 * Each names the thing the user would have to change. None of them says "check your connection"
 * for a failure that is not about the connection — that phrasing sent a self-hoster to debug a
 * network that was working in four of these seven cases.
 */
private fun SyncFailure.describe(): String = when (this) {
  is SyncFailure.SignInRejected ->
    "Could not sign in — the server rejected your credentials. Sign in again from Settings."
  is SyncFailure.ServerError -> "The server answered with an error ($status)."
  SyncFailure.NotConfigured -> "No server is set up yet."
  SyncFailure.NoLibraries -> "The server reports no music libraries."
  SyncFailure.CleartextForbidden ->
    "This build will not send your password over plain http. Use an https:// address."
  SyncFailure.CertificateInvalid ->
    "The server's certificate could not be trusted — it may have expired or be self-signed."
  SyncFailure.Unreachable -> "Could not reach the server."
  SyncFailure.Unknown -> "The last sync did not finish."
}
