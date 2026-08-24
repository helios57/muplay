package app.muplay.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.muplay.model.SubsonicCredentials
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
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
 */
@Singleton
class CredentialStore @Inject constructor(
  private val dataStore: DataStore<Preferences>,
) {

  /** Emits the stored credentials, or `null` when nothing is stored or the blob cannot be opened. */
  val credentials: Flow<SubsonicCredentials?> = dataStore.data.map(::read)

  suspend fun save(credentials: SubsonicCredentials) {
    val sealed = KeystoreCipher.seal(secretKey(), credentials.password)
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
    val keyStore = androidKeyStore()
    if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
  }

  private fun read(preferences: Preferences): SubsonicCredentials? {
    val baseUrl = preferences[BASE_URL] ?: return null
    val username = preferences[USERNAME] ?: return null
    val sealed = preferences[SEALED_PASSWORD] ?: return null
    val keyStore = androidKeyStore()
    if (!keyStore.containsAlias(KEY_ALIAS)) return null
    val key = (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    // A blob that will not open is indistinguishable, to a caller, from nothing being stored:
    // both mean "you have to log in again". Surfacing a GeneralSecurityException from a Flow
    // collected by the UI would crash the screen instead.
    return runCatching { KeystoreCipher.open(key, Base64.getDecoder().decode(sealed)) }
      .map { password -> SubsonicCredentials(baseUrl, username, password) }
      .getOrNull()
  }

  private fun secretKey(): SecretKey {
    val keyStore = androidKeyStore()
    if (keyStore.containsAlias(KEY_ALIAS)) {
      return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
    generator.init(
      KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(KEY_SIZE_BITS)
        .build(),
    )
    return generator.generateKey()
  }

  companion object {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "app.muplay.credentials"
    private const val KEY_SIZE_BITS = 256

    private val BASE_URL = stringPreferencesKey("server_base_url")
    private val USERNAME = stringPreferencesKey("server_username")
    private val SEALED_PASSWORD = stringPreferencesKey("server_sealed_password")

    private fun androidKeyStore(): KeyStore =
      KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    /** Whether the Keystore alias exists. Used by `CredentialStoreTest` to prove `clear()` means it. */
    fun keyExists(): Boolean = androidKeyStore().containsAlias(KEY_ALIAS)
  }
}
