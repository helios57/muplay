package app.muplay.setup

import kotlinx.coroutines.flow.Flow

/**
 * The connected server, as anything outside the credential store is allowed to see it.
 *
 * **There is no password on this type, and that is the whole point of it.** `CredentialStore`
 * exposes `SubsonicCredentials`, which carries the plaintext password because Subsonic token auth
 * needs it at request time -- so a settings section that collected that flow directly would hold a
 * live credential in composition state, for a screen that has no use for it. Two of this project's
 * recorded defects are a credential reaching somewhere it was never needed (`ProxyRegistry`'s
 * `toString`, `IntegrationsUiState`'s `keyText`), and both were leaks of exactly this shape: a
 * secret carried along because it happened to be in the object already.
 */
data class ServerIdentity(val baseUrl: String, val username: String)

/**
 * Reading who the app is connected as, and signing out.
 *
 * A seam for the reason `SetupViewModel` has [SetupCredentialSink]: the real store opens an
 * AndroidKeystore key, so anything naming it directly can only be exercised on a device.
 */
interface ServerAccount {

  /** The connected server, or `null` when nothing is stored. */
  val identity: Flow<ServerIdentity?>

  /**
   * Forgets the credentials and destroys the key that opens them.
   *
   * Library tags are **not** touched. They describe the server's libraries, not this session, and a
   * user signing back in to the same server should not have to tag everything again -- while a user
   * signing in to a *different* one has them replaced anyway, because `refreshFromServer` removes
   * libraries the server no longer reports.
   */
  suspend fun signOut()
}
