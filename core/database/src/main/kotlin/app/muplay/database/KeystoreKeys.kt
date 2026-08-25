package app.muplay.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The AES-GCM keys this application holds in the Android Keystore, addressed by alias.
 *
 * Extracted from [CredentialStore] when a second store needed the same plumbing. The keys never
 * leave the Keystore — [getOrCreate] and [find] return a handle, not key material — and none of
 * them is user-authentication-bound (`setUserAuthenticationRequired` is never called), because
 * background playback and background request polling must both work from a locked screen.
 *
 * One alias per *thing being protected*, never one shared alias: [delete] must be able to mean
 * "forget this one service" without touching another, and a shared key makes that impossible to
 * express. See `IntegrationCredentialStore`'s doc for the concrete failure a shared key produces.
 *
 * Every function here takes its alias as an argument and none of them is defaulted, deliberately:
 * an alias constant living in here would be a shared default that two callers could silently end
 * up agreeing on. The alias belongs to the store that owns the secret.
 */
object KeystoreKeys {

  private const val ANDROID_KEY_STORE = "AndroidKeyStore"
  private const val KEY_SIZE_BITS = 256

  /** Whether [alias] holds a key. Never creates one — see [find]. */
  fun exists(alias: String): Boolean = keyStore().containsAlias(alias)

  /**
   * The key at [alias], or `null` if there is none.
   *
   * Deliberately does not create. "No key" is a meaningful state for a caller — it means the same
   * thing as "nothing is stored", i.e. the user has to configure this again — and a `find` that
   * created on demand would turn a readable state into an unreadable blob's worth of confusion.
   */
  fun find(alias: String): SecretKey? {
    val keyStore = keyStore()
    if (!keyStore.containsAlias(alias)) return null
    return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
  }

  /** The key at [alias], generating a new AES-256-GCM one if there is none. */
  fun getOrCreate(alias: String): SecretKey = find(alias) ?: generate(alias)

  /**
   * Destroys the key at [alias], if there is one.
   *
   * Destroying the key, rather than only deleting the ciphertext, is what makes "forget this"
   * mean it: a key left behind still opens any surviving copy of the blob — a backup, a forensic
   * image — which is not what a user means by signing out.
   *
   * Deleting an alias that does not exist is not an error: clearing a service that was never
   * configured is an ordinary path, not an exceptional one.
   */
  fun delete(alias: String) {
    val keyStore = keyStore()
    if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
  }

  private fun generate(alias: String): SecretKey {
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
    generator.init(
      KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(KEY_SIZE_BITS)
        .build(),
    )
    return generator.generateKey()
  }

  private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
}
