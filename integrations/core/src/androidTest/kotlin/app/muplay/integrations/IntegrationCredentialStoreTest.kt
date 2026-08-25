package app.muplay.integrations

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.KeystoreCipher
import app.muplay.database.KeystoreKeys
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The credential store, against the real Android Keystore and a real DataStore file.
 *
 * **Every test here exercises more than one configuration state.** The plan's severability
 * contract names "a service-not-configured path that every test configures around" as the single
 * most likely defect in this plan, and this is the layer where that path is born: `load` returning
 * `null`, `configured` returning an empty map, and `clear` on one service leaving the other alone
 * are the three behaviours the whole feature's optionality rests on.
 *
 * A **fresh DataStore file per test method**, named with `System.nanoTime()`, for a mechanical
 * reason this project has already paid for once: DataStore refuses a second instance over a path
 * that is already active in the process (`IllegalStateException: There are multiple DataStores
 * active for the same file`), the registration happens on first *use* rather than on construction,
 * and the default scope a factory-built store runs on is never cancelled. A fixed file name here
 * would pass the first test method and fail every one after it, naming DataStore rather than the
 * test that built the second instance.
 */
@RunWith(AndroidJUnit4::class)
class IntegrationCredentialStoreTest {

  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var store: IntegrationCredentialStore

  private fun url(raw: String): IntegrationBaseUrl =
    (IntegrationBaseUrl.parse(raw, CleartextPolicy.Allowed) as BaseUrlResult.Valid).url

  private val lidarr = IntegrationCredentials.Lidarr(
    baseUrl = url("https://lidarr.example.com"),
    apiKey = API_KEY,
  )

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    file = File(context.filesDir, "integration-credentials-test-${System.nanoTime()}.preferences_pb")
    file.delete()
    dataStore = PreferenceDataStoreFactory.create { file }
    store = IntegrationCredentialStore(dataStore)
    deleteEveryAlias()
  }

  @After
  fun tearDown() {
    file.delete()
    deleteEveryAlias()
  }

  private fun deleteEveryAlias() =
    IntegrationService.entries.forEach { KeystoreKeys.delete(IntegrationCredentialStore.keyAlias(it)) }

  @Test
  fun nothingIsConfiguredBeforeAnythingIsSaved() = runTest {
    // The path every other test would configure around. `configured` must be *observed empty*,
    // and `load` must be observed null for each service individually -- an empty map alone would
    // be satisfied by a `load` that threw.
    assertThat(store.configured.first()).isEmpty()
    assertThat(store.load(IntegrationService.LIDARR)).isNull()
    assertThat(store.load(IntegrationService.BINDERY)).isNull()
  }

  @Test
  fun aSavedCredentialRoundTripsEveryField() = runTest {
    store.save(lidarr)

    val loaded = store.load(IntegrationService.LIDARR)

    // Field by field, not `isEqualTo(lidarr)` alone: `isEqualTo` on a data class is one
    // assertion whose failure message names the whole object, and the point of this project's
    // field rule is that each field is individually unable to be a constant.
    assertThat(loaded).isInstanceOf(IntegrationCredentials.Lidarr::class.java)
    loaded as IntegrationCredentials.Lidarr
    assertThat(loaded.baseUrl.value).isEqualTo("https://lidarr.example.com/")
    assertThat(loaded.apiKey).isEqualTo(API_KEY)
    assertThat(loaded.service).isEqualTo(IntegrationService.LIDARR)
  }

  @Test
  fun aSecondSaveWithDifferentValuesReplacesTheFirst() = runTest {
    // The two-observations rule for a persisted value: a store that wrote the first value and
    // then ignored later writes passes the round-trip test above.
    store.save(lidarr)
    store.save(lidarr.copy(baseUrl = url("https://other.example.com"), apiKey = "ffffffff"))

    val loaded = store.load(IntegrationService.LIDARR) as IntegrationCredentials.Lidarr
    assertThat(loaded.baseUrl.value).isEqualTo("https://other.example.com/")
    assertThat(loaded.apiKey).isEqualTo("ffffffff")
  }

  @Test
  fun theApiKeyIsNotReadableFromThePreferencesFile() = runTest {
    store.save(lidarr)

    // The seal, proven at the bytes rather than argued. DataStore's file is a protobuf, so the
    // key would appear as a plain UTF-8 substring if it were stored unsealed. Compared as bytes
    // via an ISO-8859-1 view, which is lossless over arbitrary bytes -- this project has already
    // shipped a version of this assertion that decoded UTF-8 as ISO-8859-1 and could therefore
    // never match its own needle. The needle here is ASCII, so both readings agree, and the
    // control assertion below is what proves the search can match at all.
    val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
    assertThat(bytes).doesNotContain(API_KEY)
    // ...and the base URL *is* readable, which is the deliberate half: it is not a secret, and
    // `IntegrationBaseUrl.parse` guarantees it carries none. It is also the control that proves
    // the assertion above is a negative result rather than a search that can never match.
    assertThat(bytes).contains("https://lidarr.example.com/")
  }

  /**
   * The seal is a real seal, not a pass-through. `open(seal(x)) == x` -- which the round-trip test
   * above asserts -- holds just as well for a `seal` that returns its own input, so the ciphertext
   * is inspected directly here: it is not the plaintext, and it is not the same bytes twice.
   */
  @Test
  fun twoSavesOfTheSameApiKeyProduceDifferentCiphertext() = runTest {
    store.save(lidarr)
    val once = sealedSecretOnDisk()
    store.save(lidarr)
    val twice = sealedSecretOnDisk()

    assertThat(String(once, Charsets.ISO_8859_1)).doesNotContain(API_KEY)
    // AES-GCM under a reused IV leaks the XOR of both plaintexts and the authentication key, so
    // sealing the same secret twice under one key must not produce the same blob.
    assertThat(once).isNotEqualTo(twice)
    // ...and it still opens, so "different" cannot be satisfied by writing garbage.
    assertThat((store.load(IntegrationService.LIDARR) as IntegrationCredentials.Lidarr).apiKey)
      .isEqualTo(API_KEY)
  }

  @Test
  fun aTamperedCiphertextReadsAsNotConfiguredRatherThanThrowing() = runTest {
    store.save(lidarr)

    // One flipped bit inside the ciphertext. GCM authenticates, so this cannot open -- and the
    // caller must be told "configure this again" rather than shown a GeneralSecurityException
    // thrown from inside a Flow the settings screen is collecting.
    val corrupted = sealedSecretOnDisk().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
    dataStore.edit {
      it[stringPreferencesKey("lidarr_sealed_secret")] = Base64.getEncoder().encodeToString(corrupted)
    }

    assertThat(store.load(IntegrationService.LIDARR)).isNull()
    assertThat(store.configured.first()).isEmpty()
  }

  @Test
  fun aPartiallyWrittenServiceReadsAsNotConfigured() = runTest {
    // Three preference keys are three separate writes and an app can be killed between them.
    // Both halves individually, in both directions, on the same store.
    store.save(lidarr)
    dataStore.edit { it.remove(stringPreferencesKey("lidarr_base_url")) }
    assertThat(store.load(IntegrationService.LIDARR)).isNull()

    store.save(lidarr)
    dataStore.edit { it.remove(stringPreferencesKey("lidarr_sealed_secret")) }
    assertThat(store.load(IntegrationService.LIDARR)).isNull()
  }

  /**
   * The ruling in this task's brief, exercised rather than asserted in prose: [IntegrationBaseUrl]
   * is re-parsed on read under [CleartextPolicy.Forbidden], never under the policy that was in
   * force when it was written. A stored string is the one place the cleartext policy could be
   * bypassed -- an app-data restore, a rooted device, an `adb run-as` write -- and dropping is the
   * safe direction.
   */
  @Test
  fun aStoredCleartextUrlIsDroppedRatherThanUsed() = runTest {
    store.save(lidarr)
    // Planted the way a restored profile would plant it: the sealed secret and the Keystore key
    // are untouched and perfectly readable, so the only thing that can reject this is the policy.
    dataStore.edit {
      it[stringPreferencesKey("lidarr_base_url")] = "http://lidarr.example.com/"
    }

    assertThat(store.load(IntegrationService.LIDARR)).isNull()
    assertThat(store.configured.first()).isEmpty()

    // The control, on the same store and the same secret: put an https URL back and it loads
    // again. Without this a `read` that returned null unconditionally would pass the two
    // assertions above.
    dataStore.edit {
      it[stringPreferencesKey("lidarr_base_url")] = "https://lidarr.example.com/"
    }
    assertThat(store.load(IntegrationService.LIDARR)).isNotNull()
  }

  @Test
  fun configuredReportsExactlyTheServicesThatAreConfigured() = runTest {
    // Three of the four combinations, in order, on one store. The fourth (both configured) needs
    // Task 7's Bindery member to exist; the alias half of it is asserted below, which is the part
    // that can be expressed today.
    assertThat(store.configured.first().keys).isEmpty()

    store.save(lidarr)
    assertThat(store.configured.first().keys).containsExactly(IntegrationService.LIDARR)

    store.clear(IntegrationService.LIDARR)
    assertThat(store.configured.first().keys).isEmpty()
  }

  @Test
  fun clearingOneServiceDestroysItsKeyAndItsEntries() = runTest {
    store.save(lidarr)
    assertThat(IntegrationCredentialStore.keyExists(IntegrationService.LIDARR)).isTrue()

    store.clear(IntegrationService.LIDARR)

    assertThat(store.load(IntegrationService.LIDARR)).isNull()
    // Destroying the key, not just the ciphertext: a key left behind still opens a backup copy.
    assertThat(IntegrationCredentialStore.keyExists(IntegrationService.LIDARR)).isFalse()
  }

  /**
   * The severability contract itself, at the layer that can express it today.
   *
   * Bindery has no [IntegrationCredentials] member until Task 7, so its credential cannot be
   * saved -- but its *key* can be created, and that is the half a shared-alias implementation
   * would destroy. With one alias for both services, `clear(LIDARR)` either leaves a key behind
   * that still opens Bindery's blob or destroys it and silently signs the user out of a service
   * they did not ask to forget. Both failures are invisible to a test that configures one service,
   * which is why this one touches two aliases.
   */
  @Test
  fun clearingOneServiceLeavesTheOtherServicesKeyAlone() = runTest {
    store.save(lidarr)
    KeystoreKeys.getOrCreate(IntegrationCredentialStore.keyAlias(IntegrationService.BINDERY))

    store.clear(IntegrationService.LIDARR)

    // Two observations in opposite directions from one call: a `clear` that destroyed everything
    // passes the first and fails the second.
    assertThat(IntegrationCredentialStore.keyExists(IntegrationService.LIDARR)).isFalse()
    assertThat(IntegrationCredentialStore.keyExists(IntegrationService.BINDERY)).isTrue()
  }

  @Test
  fun clearingAServiceThatWasNeverConfiguredIsANoOpRatherThanAFailure() = runTest {
    store.clear(IntegrationService.BINDERY)
    assertThat(store.configured.first()).isEmpty()
  }

  @Test
  fun aCredentialWhoseKeyWasDestroyedOutFromUnderItReadsAsNotConfigured() = runTest {
    store.save(lidarr)
    KeystoreKeys.delete(IntegrationCredentialStore.keyAlias(IntegrationService.LIDARR))

    // Not an exception through a Flow the UI collects. "You have to configure this again" is the
    // only thing a caller can do about it -- exactly what `CredentialStore.read` already decided
    // for the Navidrome password.
    assertThat(store.load(IntegrationService.LIDARR)).isNull()
    assertThat(store.configured.first()).isEmpty()
  }

  /**
   * A complete, **openable** Bindery entry on disk reads as not configured until Task 7 adds the
   * member — and, critically, is not mistaken for Lidarr's.
   *
   * Planted the way a downgrade or a restore from a newer build would leave it: Bindery's own
   * Keystore alias, its own two preference keys, a secret that really does open. Everything `read`
   * checks passes, so the only thing that can decide the answer is the `when` over `service` — and
   * a `read` that ignored its `service` argument would return a `Lidarr` credential here. That arm
   * was reachable by no other test in this suite, which is exactly why it was worth writing rather
   * than excusing with a lower coverage floor.
   */
  @Test
  fun aStoredBinderyEntryReadsAsNotConfiguredRatherThanAsLidarrs() = runTest {
    store.save(lidarr)
    val sealed = KeystoreCipher.seal(
      KeystoreKeys.getOrCreate(IntegrationCredentialStore.keyAlias(IntegrationService.BINDERY)),
      "bindery-secret",
    )
    dataStore.edit {
      it[stringPreferencesKey("bindery_base_url")] = "https://bindery.example.com/"
      it[stringPreferencesKey("bindery_sealed_secret")] = Base64.getEncoder().encodeToString(sealed)
    }

    assertThat(store.load(IntegrationService.BINDERY)).isNull()
    // ...and it disturbs neither the service that IS configured nor what `configured` reports.
    assertThat(store.configured.first().keys).containsExactly(IntegrationService.LIDARR)
    assertThat((store.load(IntegrationService.LIDARR) as IntegrationCredentials.Lidarr).apiKey)
      .isEqualTo(API_KEY)
  }

  /**
   * A key pasted out of a browser address bar arrives inside the URL. Lidarr accepts it there;
   * MuPlay must not keep it there, because a base URL is written to DataStore in the clear, is
   * interpolated into every OkHttp log line and into the message of any `IOException` a crash
   * reporter uploads.
   *
   * Asserted on a credential that has been through the store, not only on `parse`'s own return
   * value: this is the type that ends up in a log line.
   */
  @Test
  fun anApiKeySmuggledIntoTheUrlNeverReachesTheStoredCredential() = runTest {
    val smuggled = IntegrationCredentials.Lidarr(
      baseUrl = url("https://user:$API_KEY@lidarr.example.com/lidarr?apikey=$API_KEY#$API_KEY"),
      apiKey = API_KEY,
    )

    store.save(smuggled)

    val loaded = store.load(IntegrationService.LIDARR) as IntegrationCredentials.Lidarr
    assertThat(loaded.baseUrl.value).doesNotContain(API_KEY)
    assertThat(loaded.toString()).doesNotContain(API_KEY)
    // The path survives -- Servarr apps are commonly proxied at a `urlBase` -- so the stripping is
    // targeted rather than a truncation that happens to remove the secret.
    assertThat(loaded.baseUrl.value).isEqualTo("https://lidarr.example.com/lidarr/")
    // And the key itself is still carried, in the field that gets sealed.
    assertThat(loaded.apiKey).isEqualTo(API_KEY)
    // The clear half of the file must not have kept it either.
    assertThat(file.readBytes().toString(Charsets.ISO_8859_1)).doesNotContain(API_KEY)
  }

  private suspend fun sealedSecretOnDisk(): ByteArray =
    Base64.getDecoder().decode(dataStore.data.first()[stringPreferencesKey("lidarr_sealed_secret")])

  private companion object {
    /**
     * Distinctive enough that finding it in a file is evidence rather than a coincidence, and
     * shaped like a real Lidarr key (32 lowercase hex characters).
     */
    private const val API_KEY = "0123456789abcdef0123456789abcdef"
  }
}
