package io.github.helios57.muplay.database

import io.github.helios57.muplay.network.SubsonicErrorException
import io.github.helios57.muplay.network.SubsonicHttpException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

/**
 * **Why a sync failed, typed, so a screen can say something true about it.**
 *
 * ### What went wrong without it
 *
 * [SyncState.Failed] carries a `Throwable`, and every consumer flattened it. `LibraryViewModel`
 * rendered one sentence for all of them:
 *
 * > Could not reach the server. Showing your last synced library.
 *
 * That sentence is wrong in four separate ways at once. It is wrong about the **cause** for a
 * changed password (the server answered, and said no), for a 502 from a reverse proxy (the proxy
 * answered), for a lapsed certificate, and for a release build refusing to send a password over
 * plain `http`. And its second clause is wrong about the **app** on a first run that never synced:
 * there is no last synced library to show, so it promises a fallback that is not there — the same
 * "message that is not true when it is shown" defect [SyncState] and `LibraryUiState.syncMessage`
 * both already carry warnings about.
 *
 * ### Where this lives, and why here
 *
 * In `:core:database`, beside [SyncState] and next to the two exceptions it names
 * ([NotConfiguredException], [EmptyLibraryListException]), because this module is where the
 * failure is produced and it is the lowest one that can see every type involved. `:feature:library`
 * gets these transitively but classifies nothing itself; it only chooses wording.
 *
 * No Android type appears in [of]'s signature, so the fast tier holds every branch — the same
 * split `PlaybackFailure.of` makes, for the same reason.
 */
sealed interface SyncFailure {

  /**
   * The server answered and refused the credentials — [code] is Subsonic's own error code, one of
   * the authentication codes in [AUTHENTICATION_CODES].
   */
  data class SignInRejected(val code: Int) : SyncFailure

  /**
   * Subsonic code 70: something asked for does not exist, **or is not shared with this account**.
   *
   * Navidrome 0.63.2 answers exactly this for a library the user has not been granted --
   * `Library 2 not found or not accessible` -- which is why it must not read as a bad password.
   * [detail] is the server's own message, kept because it is the only part that names the thing.
   */
  data class NotFound(val detail: String?) : SyncFailure

  /** Subsonic code 50: the account is signed in correctly and is not allowed to do this. */
  data class NotPermitted(val detail: String?) : SyncFailure

  /** Any other Subsonic error, with its [code] and the server's own [detail]. */
  data class ServerRefused(val code: Int, val detail: String?) : SyncFailure

  /** The server (or something in front of it) answered with an unsuccessful [status]. */
  data class ServerError(val status: Int) : SyncFailure

  /** Setup has never completed, so there are no credentials to sync with. */
  data object NotConfigured : SyncFailure

  /** The server answered, and reported no libraries at all. */
  data object NoLibraries : SyncFailure

  /** The platform refused to send the request over plain `http`. */
  data object CleartextForbidden : SyncFailure

  /** TLS could not be established — an expired, self-signed or otherwise untrusted certificate. */
  data object CertificateInvalid : SyncFailure

  /** Nothing came back at all: no connection, or a timeout. */
  data object Unreachable : SyncFailure

  /**
   * Something threw that nothing here classifies.
   *
   * Deliberately **not** folded into [Unreachable]. "Could not reach the server" is a claim, and
   * making that claim about an exception nobody has looked at is precisely how the defect this
   * type exists to fix came about.
   */
  data object Unknown : SyncFailure

  companion object {

    /**
     * 40 wrong credentials, 41 token auth unsupported for an LDAP user, 42 auth mechanism not
     * supported, 43 conflicting mechanisms, 44 invalid API key: the spec's authentication codes.
     * Every one of them means "sign in again"; no other code does.
     */
    val AUTHENTICATION_CODES = 40..44

    private const val NOT_AUTHORIZED = 50
    private const val NOT_FOUND = 70

    /**
     * Classifies [cause].
     *
     * Order matters twice. [UnknownServiceException] and [SSLException] are both `IOException`s,
     * so each must be tested before any general transport clause or it can never be reached — the
     * same trap `SetupViewModel.connect` meets with its own cleartext catch.
     */
    fun of(cause: Throwable): SyncFailure = when (cause) {
      is SubsonicErrorException -> when (cause.code) {
        in AUTHENTICATION_CODES -> SignInRejected(cause.code)
        NOT_AUTHORIZED -> NotPermitted(cause.message)
        NOT_FOUND -> NotFound(cause.message)
        else -> ServerRefused(cause.code, cause.message)
      }
      is SubsonicHttpException -> ServerError(cause.status)
      is NotConfiguredException -> NotConfigured
      is EmptyLibraryListException -> NoLibraries
      is UnknownServiceException -> CleartextForbidden
      is SSLException -> CertificateInvalid
      is java.io.IOException -> Unreachable
      else -> Unknown
    }
  }
}
