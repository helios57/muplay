package app.muplay.integrations

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.muplay.database.KeystoreCipher
import app.muplay.database.KeystoreKeys
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Where the optional integrations' credentials live.
 *
 * The **same mechanism** as `app.muplay.database.CredentialStore`, deliberately and not
 * coincidentally: an AES-GCM key held in the Android Keystore, ciphertext in DataStore, the base
 * URL in the clear because it is not a secret. A Lidarr API key can command a user's download
 * client and sits beside their Navidrome password on the same device; it does not get a weaker
 * store because the feature it serves is optional.
 *
 * **One Keystore alias per service.** With a shared key, [clear] on one service would either leave
 * a key behind that still opens the other's blob — so "forget this" would not mean it for the last
 * service removed — or destroy it, silently signing the user out of a service they did not ask to
 * forget. Neither failure is visible to a test that configures one service, which is why
 * `IntegrationCredentialStoreTest` never touches only one.
 *
 * Keys are not user-authentication-bound, for the same reason `CredentialStore`'s is not:
 * request-status polling runs in the background, from a locked screen.
 *
 * **The API key appears in exactly two places and nowhere else.** In memory, as
 * [IntegrationCredentials.Lidarr.apiKey]; and on disk, as AES-GCM ciphertext under this service's
 * own Keystore alias. It is never logged, never interpolated into a URL (the base URL is an
 * [IntegrationBaseUrl], whose only constructor strips query, fragment and userinfo), never in a
 * `toString()` (each member overrides it) and never in an exception message — every failure to
 * open a blob is turned into `null` here rather than thrown.
 */
@Singleton
class IntegrationCredentialStore @Inject constructor(
  @IntegrationPreferences private val dataStore: DataStore<Preferences>,
) {

  /**
   * Every configured service and its credentials, keyed by service.
   *
   * A service is *configured* only when its base URL, its secret and its Keystore key are all
   * present, the blob opens, and the stored URL still passes the cleartext policy. Anything less
   * reads as not configured — see `CredentialStore`'s own `read` for why an unopenable blob and a
   * missing one are the same fact to a caller.
   */
  val configured: Flow<Map<IntegrationService, IntegrationCredentials>> =
    dataStore.data.map { preferences ->
      IntegrationService.entries
        .mapNotNull { service -> read(preferences, service)?.let { service to it } }
        .toMap()
    }

  suspend fun load(service: IntegrationService): IntegrationCredentials? = configured.first()[service]

  suspend fun save(credentials: IntegrationCredentials) {
    val service = credentials.service
    val sealed = KeystoreCipher.seal(KeystoreKeys.getOrCreate(keyAlias(service)), secretOf(credentials))
    dataStore.edit { preferences ->
      preferences[baseUrlKey(service)] = credentials.baseUrl.value
      preferences[sealedSecretKey(service)] = Base64.getEncoder().encodeToString(sealed)
    }
  }

  /**
   * Forgets [service] entirely: its DataStore entries **and** its Keystore key.
   *
   * Only [service]'s. The other service's entries and key are untouched, which is the whole point
   * of the per-service alias — and note that this removes its own two keys rather than calling
   * `Preferences.clear()`, which would empty the file the other service is stored in too.
   */
  suspend fun clear(service: IntegrationService) {
    dataStore.edit { preferences ->
      preferences.remove(baseUrlKey(service))
      preferences.remove(sealedSecretKey(service))
    }
    KeystoreKeys.delete(keyAlias(service))
  }

  private fun read(preferences: Preferences, service: IntegrationService): IntegrationCredentials? {
    val rawUrl = preferences[baseUrlKey(service)] ?: return null
    val sealed = preferences[sealedSecretKey(service)] ?: return null
    val key = KeystoreKeys.find(keyAlias(service)) ?: return null
    val secret =
      runCatching { KeystoreCipher.open(key, Base64.getDecoder().decode(sealed)) }.getOrNull()
        ?: return null
    // Re-parsed rather than trusted. The stored string was produced by `parse` under whatever
    // policy was in force when it was written, and a debug-built profile restored onto a release
    // build would otherwise smuggle a cleartext URL past the policy. `Allowed` is deliberately
    // NOT used here, and no policy is injected into this class for that reason.
    val url = (IntegrationBaseUrl.parse(rawUrl, CleartextPolicy.Forbidden) as? BaseUrlResult.Valid)
      ?.url ?: return null
    return when (service) {
      IntegrationService.LIDARR -> IntegrationCredentials.Lidarr(url, secret)
      // Task 7 replaces this with the real Bindery member. Until then a Bindery entry cannot be
      // written (there is no member to write) and therefore cannot be read.
      IntegrationService.BINDERY -> null
    }
  }

  private fun secretOf(credentials: IntegrationCredentials): String = when (credentials) {
    is IntegrationCredentials.Lidarr -> credentials.apiKey
  }

  companion object {

    /** The Keystore alias holding [service]'s secret. One per service — see the class doc. */
    fun keyAlias(service: IntegrationService): String = when (service) {
      IntegrationService.LIDARR -> "app.muplay.integrations.lidarr"
      IntegrationService.BINDERY -> "app.muplay.integrations.bindery"
    }

    /** Whether [service]'s Keystore key exists. For tests, exactly like `CredentialStore.keyExists`. */
    fun keyExists(service: IntegrationService): Boolean = KeystoreKeys.exists(keyAlias(service))

    /**
     * The two DataStore keys per service. Derived from the enum constant's own name so a third
     * service cannot be added without its storage coming with it — but note that this makes those
     * names part of the on-disk contract, which is why `IntegrationCredentialStoreTest` writes
     * `lidarr_base_url` and `lidarr_sealed_secret` out as literals rather than reading them back
     * from here. Asserting the implementation against itself would let a silent rename through.
     */
    private fun baseUrlKey(service: IntegrationService) =
      stringPreferencesKey("${service.name.lowercase()}_base_url")

    private fun sealedSecretKey(service: IntegrationService) =
      stringPreferencesKey("${service.name.lowercase()}_sealed_secret")
  }
}
