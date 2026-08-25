package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.muplay.model.SubsonicCredentials
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The server URL, username and password the app connects with, persisted across launches.
 *
 * The URL and username are stored in the clear — they are not secrets, and having them readable
 * makes a support question answerable. The **password** is sealed with an AES-GCM key that lives
 * in the Android Keystore and never leaves it; only the ciphertext reaches DataStore.
 *
 * Storing the password at all, rather than a hash, is forced by the protocol: Subsonic token
 * auth needs `md5(password + salt)` with a **fresh salt per request**, so the plaintext must be
 * recoverable at request time. There is no hashed-at-rest option to choose instead.
 *
 * The key is not user-authentication-bound (`setUserAuthenticationRequired` is never called):
 * background playback must work from a locked screen, which is the whole point of the feature it
 * serves.
 *
 * The Keystore plumbing itself lives in [KeystoreKeys], which this class used to hold inline. It
 * moved out when a second store (`IntegrationCredentialStore`, in `:integrations:core`) needed the
 * same create-or-fetch/exists/delete handling, so that the mechanism is shared rather than copied.
 * The dependency runs one way only -- that module depends on this one, never the reverse, which is
 * what keeps Plan 7 severable.
 * Nothing about *this* class's behaviour changed with it: the alias, the DataStore keys, the
 * public API and the "a missing key or an unopenable blob means nothing is stored" rule are all
 * exactly what they were, and `CredentialStoreTest` was not edited when the extraction happened.
 */
@Singleton
class CredentialStore @Inject constructor(
  private val dataStore: DataStore<Preferences>,
) {

  /** Emits the stored credentials, or `null` when nothing is stored or the blob cannot be opened. */
  val credentials: Flow<SubsonicCredentials?> = dataStore.data.map(::read)

  suspend fun save(credentials: SubsonicCredentials) {
    val sealed = KeystoreCipher.seal(KeystoreKeys.getOrCreate(KEY_ALIAS), credentials.password)
    dataStore.edit { preferences ->
      preferences[BASE_URL] = credentials.baseUrl
      preferences[USERNAME] = credentials.username
      preferences[SEALED_PASSWORD] = Base64.getEncoder().encodeToString(sealed)
    }
  }

  suspend fun load(): SubsonicCredentials? = credentials.first()

  /**
   * Forgets the credentials **and destroys the key**. Removing only the DataStore entry would
   * leave a key on the device that still opens any copy of the ciphertext — a backup, a forensic
   * image — which is not what "sign out" means to a user.
   */
  suspend fun clear() {
    dataStore.edit { it.clear() }
    KeystoreKeys.delete(KEY_ALIAS)
  }

  private fun read(preferences: Preferences): SubsonicCredentials? {
    val baseUrl = preferences[BASE_URL] ?: return null
    val username = preferences[USERNAME] ?: return null
    val sealed = preferences[SEALED_PASSWORD] ?: return null
    val key = KeystoreKeys.find(KEY_ALIAS) ?: return null
    // A blob that will not open is indistinguishable, to a caller, from nothing being stored:
    // both mean "you have to log in again". Surfacing a GeneralSecurityException from a Flow
    // collected by the UI would crash the screen instead.
    return runCatching { KeystoreCipher.open(key, Base64.getDecoder().decode(sealed)) }
      .map { password -> SubsonicCredentials(baseUrl, username, password) }
      .getOrNull()
  }

  companion object {
    /**
     * This app's oldest Keystore alias, and one that already exists on real devices. Renaming it
     * is a silent sign-out for every installed user, which is why `CredentialStoreTest` writes it
     * out as a literal rather than reading it from here.
     */
    private const val KEY_ALIAS = "app.muplay.credentials"

    private val BASE_URL = stringPreferencesKey("server_base_url")
    private val USERNAME = stringPreferencesKey("server_username")
    private val SEALED_PASSWORD = stringPreferencesKey("server_sealed_password")

    /** Whether the Keystore alias exists. Used by `CredentialStoreTest` to prove `clear()` means it. */
    fun keyExists(): Boolean = KeystoreKeys.exists(KEY_ALIAS)
  }
}
