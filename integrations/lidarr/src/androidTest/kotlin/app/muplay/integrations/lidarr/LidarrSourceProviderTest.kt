package app.muplay.integrations.lidarr

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
import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentialStore
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `LidarrSourceProvider` against the **real** credential store — a real DataStore file and the
 * real Android Keystore.
 *
 * Instrumented rather than JVM for one mechanical reason: [IntegrationCredentialStore] is a
 * concrete class over DataStore and `AndroidKeyStore`, neither of which exists on a JVM unit-test
 * classpath, and this project ships no mock framework to stand in for it. A fake store would prove
 * that `current()` calls *something*; only the real one proves it calls the thing that actually
 * holds a user's key.
 *
 * **All four configuration states are exercised**, because the plan's severability contract names
 * "a not-configured path that every test configures around" as this plan's single most likely
 * defect. Bindery-only and both-configured are reached by planting a Bindery entry on disk by
 * hand: `IntegrationCredentials` has no Bindery member yet (Task 8 adds it), so `save` cannot
 * write one, and a state that cannot be written is still a state the shipped store can be *given*
 * by a future version's leftovers.
 *
 * Method names are camelCase: `minSdk 26` compiles DEX 035, which forbids spaces in any
 * SimpleName, and a backticked `runTest` names its own synthetic lambda class after the method.
 */
@RunWith(AndroidJUnit4::class)
class LidarrSourceProviderTest {

  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var store: IntegrationCredentialStore
  private lateinit var factory: RecordingFactory
  private lateinit var provider: LidarrSourceProvider

  /**
   * Records what it was asked to build from, and hands back a distinct instance each time.
   *
   * A hand-written fake, not a mock: the question this class exists to answer is *which
   * credentials reached the factory*, and a provider that built a client from a constant would
   * satisfy every "is not null" assertion in this file.
   */
  private class RecordingFactory : LidarrSourceFactory {
    val seen = mutableListOf<IntegrationCredentials.Lidarr>()

    override fun create(credentials: IntegrationCredentials.Lidarr): LidarrSource {
      seen += credentials
      return object : LidarrSource {
        override suspend fun ping(): Boolean = true
        override suspend fun status(): LidarrServer =
          LidarrServer("Lidarr", "Lidarr", "0", "", "none")
      }
    }
  }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // A fresh file per method, named with `System.nanoTime()`: DataStore refuses a second instance
    // over a path already active in the process, registers on first *use* rather than on
    // construction, and never cancels the default scope a factory-built store runs on. A fixed
    // name here would pass the first method and fail every one after it, naming DataStore rather
    // than the test that built the second instance.
    file = File(context.filesDir, "lidarr-provider-test-${System.nanoTime()}.preferences_pb")
    file.delete()
    dataStore = PreferenceDataStoreFactory.create { file }
    store = IntegrationCredentialStore(dataStore)
    factory = RecordingFactory()
    provider = LidarrSourceProvider(store, factory)
    deleteEveryAlias()
  }

  @After
  fun tearDown() {
    file.delete()
    deleteEveryAlias()
  }

  private fun deleteEveryAlias() =
    IntegrationService.entries.forEach { KeystoreKeys.delete(IntegrationCredentialStore.keyAlias(it)) }

  private fun url(raw: String): IntegrationBaseUrl =
    (IntegrationBaseUrl.parse(raw, CleartextPolicy.Allowed) as BaseUrlResult.Valid).url

  /** Writes a complete, openable Bindery entry that no `IntegrationCredentials` member can produce. */
  private suspend fun plantBinderyEntry() {
    val sealed = KeystoreCipher.seal(
      KeystoreKeys.getOrCreate(IntegrationCredentialStore.keyAlias(IntegrationService.BINDERY)),
      "bindery-secret",
    )
    dataStore.edit {
      it[stringPreferencesKey("bindery_base_url")] = "https://bindery.example.com/"
      it[stringPreferencesKey("bindery_sealed_secret")] = Base64.getEncoder().encodeToString(sealed)
    }
  }

  @Test
  fun nothingConfiguredYieldsNoSourceAtAll() = runTest {
    // The state a real user with neither service is in, permanently. `null`, not an exception and
    // not a half-built client: the plan's "fail closed" clause means resolving toward doing less.
    assertThat(provider.current()).isNull()
    // ...and nothing was built. Without this, a provider that constructed a client and then threw
    // it away would pass the line above.
    assertThat(factory.seen).isEmpty()
  }

  @Test
  fun aConfiguredLidarrYieldsASourceBuiltFromTheStoredCredentials() = runTest {
    store.save(IntegrationCredentials.Lidarr(url("https://lidarr.example.com/lidarr"), API_KEY))

    val source = provider.current()

    assertThat(source).isNotNull()
    // The discriminating half: which credentials reached the factory. A provider that built its
    // client from a constant, or from the wrong service's entry, satisfies `isNotNull` above.
    assertThat(factory.seen).hasSize(1)
    assertThat(factory.seen.single().apiKey).isEqualTo(API_KEY)
    assertThat(factory.seen.single().baseUrl.value).isEqualTo("https://lidarr.example.com/lidarr/")
  }

  @Test
  fun aSecondConfigurationIsBuiltFromTheSecondSetOfCredentials() = runTest {
    // The second observation. `current()` reads the store on every call rather than caching the
    // first answer -- which matters because the configuration screen saves and then immediately
    // asks, and a cached provider would hand the old key to the connection test.
    store.save(IntegrationCredentials.Lidarr(url("https://first.example.com"), API_KEY))
    provider.current()

    store.save(IntegrationCredentials.Lidarr(url("https://second.example.com"), OTHER_API_KEY))
    provider.current()

    assertThat(factory.seen.map { it.apiKey }).containsExactly(API_KEY, OTHER_API_KEY)
    assertThat(factory.seen.map { it.baseUrl.value })
      .containsExactly("https://first.example.com/", "https://second.example.com/")
  }

  @Test
  fun aConfiguredBinderyAloneYieldsNoLidarrSource() = runTest {
    plantBinderyEntry()

    // Severability clause 3: neither service is reachable from the other's code path. A user with
    // only Bindery configured must get no Lidarr client -- not one built from Bindery's URL and
    // secret, which is exactly what a provider that read `configured.values.first()` would do.
    assertThat(provider.current()).isNull()
    assertThat(factory.seen).isEmpty()
  }

  @Test
  fun bothConfiguredYieldsTheLidarrCredentialsAndNotTheOthers() = runTest {
    store.save(IntegrationCredentials.Lidarr(url("https://lidarr.example.com"), API_KEY))
    plantBinderyEntry()

    assertThat(provider.current()).isNotNull()
    assertThat(factory.seen).hasSize(1)
    assertThat(factory.seen.single().apiKey).isEqualTo(API_KEY)
    assertThat(factory.seen.single().baseUrl.value).isEqualTo("https://lidarr.example.com/")
  }

  @Test
  fun forgettingLidarrTakesTheProviderBackToNoSource() = runTest {
    store.save(IntegrationCredentials.Lidarr(url("https://lidarr.example.com"), API_KEY))
    assertThat(provider.current()).isNotNull()

    store.clear(IntegrationService.LIDARR)

    // The transition back, which a test that only ever configures forward cannot see. This is the
    // path a user takes when they remove a service, and the one where a cached provider hands a
    // client built on a key that has just been destroyed.
    assertThat(provider.current()).isNull()
    assertThat(factory.seen).hasSize(1)
  }

  @Test
  fun aStoredCleartextUrlLeavesTheProviderWithNoSource() = runTest {
    // `IntegrationCredentialStore.read` re-parses the stored URL under `CleartextPolicy.Forbidden`
    // and drops it if it no longer passes -- a debug-built profile restored onto a release build.
    // The provider must inherit that verdict rather than route around it, so a `http://` entry
    // planted on disk reads as not configured here too.
    val sealed = KeystoreCipher.seal(
      KeystoreKeys.getOrCreate(IntegrationCredentialStore.keyAlias(IntegrationService.LIDARR)),
      API_KEY,
    )
    dataStore.edit {
      it[stringPreferencesKey("lidarr_base_url")] = "http://192.168.1.20:8686/"
      it[stringPreferencesKey("lidarr_sealed_secret")] = Base64.getEncoder().encodeToString(sealed)
    }

    assertThat(provider.current()).isNull()
    assertThat(factory.seen).isEmpty()
  }

  private companion object {
    /** Shaped like a real Lidarr key (32 lowercase hex), and distinctive enough to grep for. */
    private const val API_KEY = "0123456789abcdef0123456789abcdef"
    private const val OTHER_API_KEY = "fedcba9876543210fedcba9876543210"
  }
}
