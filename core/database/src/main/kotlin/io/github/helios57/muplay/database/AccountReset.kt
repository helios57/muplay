package io.github.helios57.muplay.database

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Something held on this phone that belongs to one signed-in account and must not survive it --
 * a persisted play queue, downloaded files. Bound into a set, so the module that owns the data
 * owns forgetting it, and [AccountReset] does not have to know every one of them.
 */
fun interface AccountScopedData {
  suspend fun clear()
}

/**
 * **Everything signing out forgets, in one place.**
 *
 * Sign-out used to be `CredentialStore.clear()` and nothing else. The next account then inherited
 * the previous one's library mirror -- libraries it may not be allowed to read among them -- and
 * the previous server's sync watermark, which can make the new account's first sync decide there
 * is nothing to do. Both are about the *account*, not the phone, so both go.
 *
 * What stays is what the sign-out confirmation promises: audiobook positions (`media_progress`),
 * per-book settings and the chapter cache, which are keyed by track id and are the listener's own.
 *
 * Credentials go **first**: they are what the user was told is gone, and a failure further down
 * must not leave a password behind. Each [AccountScopedData] is cleared even if another throws.
 */
@Singleton
class AccountReset @Inject constructor(
  private val credentialStore: CredentialStore,
  private val database: MuPlayDatabase,
  private val scoped: Set<@JvmSuppressWildcards AccountScopedData>,
) {

  suspend fun signOut() {
    credentialStore.clear()
    database.withTransaction {
      database.syncWatermarkDao().clear()
      database.browseDao().clearMirror()
      database.libraryDao().deleteAll()
    }
    for (store in scoped) {
      // Swallowed, not rethrown: sign-out runs in a fire-and-forget scope whose only job left is
      // to land on setup, and an exception there would crash the app with the password already
      // gone. A store that failed to clear is cleared again by the next sign-out, and none of
      // them holds anything the next account can reach without credentials.
      try {
        store.clear()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
      }
    }
  }
}
