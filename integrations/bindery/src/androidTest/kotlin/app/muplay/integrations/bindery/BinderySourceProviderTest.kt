package app.muplay.integrations.bindery

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
 * `BinderySourceProvider` against the **real** credential store — a real DataStore file and the
 * real Android Keystore.
 *
 * Instrumented rather than JVM for one mechanical reason: [IntegrationCredentialStore] is a
 * concrete class over DataStore and `AndroidKeyStore`, neither of which exists on a JVM unit-test
 * classpath, and this project ships no mock framework to stand in for it. A fake store would prove
 * that `current()` calls *something*; only the real one proves it calls the thing that actually
 * holds a user's key.
 *
 * **All four configuration states are exercised** — neither, Lidarr only, Bindery only, both —
 * because the plan's severability contract names "a not-configured path that every test configures
 * around" as this plan's single most likely defect. Unlike its `:integrations:lidarr` counterpart,
 * every one of the four can now be reached through the shipped API: Task 8 added the Bindery
 * member, so `save` can write both.
 *
 * Method names are camelCase: `minSdk 26` compiles DEX 035, which forbids spaces in any
 * SimpleName, and a backticked `runTest` names its own synthetic lambda class after the method.
 */
@RunWith(AndroidJUnit4::class)
class BinderySourceProviderTest {

  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var store: IntegrationCredentialStore
  private lateinit var factory: RecordingFactory
  private lateinit var provider: BinderySourceProvider

  /**
   * Records what it was asked to build from, and hands back a distinct instance each time.
   *
   * A hand-written fake, not a mock: the question this class exists to answer is *which
   * credentials reached the factory*, and a provider that built a client from a constant would
   * satisfy every "is not null" assertion in this file.
   */
  private class RecordingFactory : BinderySourceFactory {
    val seen = mutableListOf<IntegrationCredentials.Bindery>()

    override fun create(credentials: IntegrationCredentials.Bindery): BinderySource {
      seen += credentials
      return object : BinderySource {
        override suspend fun health(): BinderyServer = BinderyServer("ok", "v1.32.1")

        // This class answers "which credentials reached the factory", never "what did the server
        // say", so each of these throws rather than returning an empty list: a silent
        // `emptyList()` is exactly the shape that lets a later test in this file assert something
        // about a search and be satisfied by a fake that never searched.
        override suspend fun searchBooks(term: String): List<BinderyBookCandidate> =
          throw UnsupportedOperationException("this fake answers nothing about the wire")

        // The throw matters more here than anywhere above: a `submitBook` that quietly returned a
        // book would be a fake that silently succeeds at acquiring nothing, which is the one
        // behaviour this module must never fake.
        override suspend fun submitBook(
          candidate: BinderyBookCandidate,
          mediaType: BinderyMediaType,
          searchOnAdd: Boolean,
        ): BinderyBook =
          throw UnsupportedOperationException("this fake answers nothing about the wire")

        override suspend fun books(status: String?, limit: Int, offset: Int): BinderyBookPage =
          throw UnsupportedOperationException("this fake answers nothing about the wire")
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
    file = File(context.filesDir, "bindery-provider-test-${System.nanoTime()}.preferences_pb")
    file.delete()
    dataStore = PreferenceDataStoreFactory.create { file }
    store = IntegrationCredentialStore(dataStore)
    factory = RecordingFactory()
    provider = BinderySourceProvider(store, factory)
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

  private fun bindery(raw: String, key: String) =
    IntegrationCredentials.Bindery(url(raw), key)

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
  fun aConfiguredBinderyYieldsASourceBuiltFromTheStoredCredentials() = runTest {
    store.save(bindery("https://bindery.example.com/bindery", API_KEY))

    val source = provider.current()

    assertThat(source).isNotNull()
    // The discriminating half: which credentials reached the factory. A provider that built its
    // client from a constant, or from the wrong service's entry, satisfies `isNotNull` above.
    assertThat(factory.seen).hasSize(1)
    assertThat(factory.seen.single().apiKey).isEqualTo(API_KEY)
    assertThat(factory.seen.single().baseUrl.value).isEqualTo("https://bindery.example.com/bindery/")
  }

  @Test
  fun aSecondConfigurationIsBuiltFromTheSecondSetOfCredentials() = runTest {
    // The second observation. `current()` reads the store on every call rather than caching the
    // first answer -- which matters because the configuration screen saves and then immediately
    // asks, and a cached provider would hand the old key to the connection test.
    store.save(bindery("https://first.example.com", API_KEY))
    provider.current()

    store.save(bindery("https://second.example.com", OTHER_API_KEY))
    provider.current()

    assertThat(factory.seen.map { it.apiKey }).containsExactly(API_KEY, OTHER_API_KEY)
    assertThat(factory.seen.map { it.baseUrl.value })
      .containsExactly("https://first.example.com/", "https://second.example.com/")
  }

  @Test
  fun aConfiguredLidarrAloneYieldsNoBinderySource() = runTest {
    store.save(IntegrationCredentials.Lidarr(url("https://lidarr.example.com"), LIDARR_API_KEY))

    // **Severability clause 3, from the side that can now assert it properly.** Neither service is
    // reachable from the other's code path: a user with only Lidarr configured must get no Bindery
    // client -- not one built from Lidarr's URL and its admin key, which is exactly what a provider
    // that read `configured.values.first()` would do.
    assertThat(provider.current()).isNull()
    assertThat(factory.seen).isEmpty()
  }

  @Test
  fun bothConfiguredYieldsTheBinderyCredentialsAndNotTheOthers() = runTest {
    store.save(IntegrationCredentials.Lidarr(url("https://lidarr.example.com"), LIDARR_API_KEY))
    store.save(bindery("https://bindery.example.com", API_KEY))

    assertThat(provider.current()).isNotNull()
    assertThat(factory.seen).hasSize(1)
    assertThat(factory.seen.single().apiKey).isEqualTo(API_KEY)
    assertThat(factory.seen.single().baseUrl.value).isEqualTo("https://bindery.example.com/")
    // Neither of Lidarr's values leaked across. Named rather than implied: the two secrets differ
    // in length as well as in value, so a provider that read the wrong entry fails here loudly.
    assertThat(factory.seen.single().apiKey).isNotEqualTo(LIDARR_API_KEY)
    assertThat(factory.seen.single().baseUrl.value).doesNotContain("lidarr")
  }

  @Test
  fun forgettingBinderyTakesTheProviderBackToNoSource() = runTest {
    store.save(bindery("https://bindery.example.com", API_KEY))
    assertThat(provider.current()).isNotNull()

    store.clear(IntegrationService.BINDERY)

    // The transition back, which a test that only ever configures forward cannot see. This is the
    // path a user takes when they remove a service, and the one where a cached provider hands a
    // client built on a key that has just been destroyed.
    assertThat(provider.current()).isNull()
    assertThat(factory.seen).hasSize(1)
  }

  @Test
  fun forgettingLidarrLeavesTheBinderySourceIntact() = runTest {
    store.save(IntegrationCredentials.Lidarr(url("https://lidarr.example.com"), LIDARR_API_KEY))
    store.save(bindery("https://bindery.example.com", API_KEY))

    store.clear(IntegrationService.LIDARR)

    // The other direction of the severability contract: forgetting one service must not disturb
    // the other. With a shared Keystore alias this is where it breaks, and the failure would be a
    // Bindery that silently stopped working when a user removed Lidarr.
    assertThat(provider.current()).isNotNull()
    assertThat(factory.seen.single().apiKey).isEqualTo(API_KEY)
  }

  @Test
  fun aStoredCleartextUrlLeavesTheProviderWithNoSource() = runTest {
    // `IntegrationCredentialStore.read` re-parses the stored URL under `CleartextPolicy.Forbidden`
    // and drops it if it no longer passes -- a debug-built profile restored onto a release build.
    // The provider must inherit that verdict rather than route around it, so a `http://` entry
    // planted on disk reads as not configured here too.
    val sealed = KeystoreCipher.seal(
      KeystoreKeys.getOrCreate(IntegrationCredentialStore.keyAlias(IntegrationService.BINDERY)),
      API_KEY,
    )
    dataStore.edit {
      it[stringPreferencesKey("bindery_base_url")] = "http://192.168.1.20:8787/"
      it[stringPreferencesKey("bindery_sealed_secret")] = Base64.getEncoder().encodeToString(sealed)
    }

    assertThat(provider.current()).isNull()
    assertThat(factory.seen).isEmpty()
  }

  private companion object {
    /**
     * Shaped like a real Bindery key: 64 lowercase hex characters, measured off a running
     * `v1.32.1` whose generated key is 32 random bytes hex-encoded.
     */
    private const val API_KEY =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private const val OTHER_API_KEY =
      "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

    /** A real *Lidarr* key is half as long. Different length as well as different value. */
    private const val LIDARR_API_KEY = "aaaaaaaabbbbbbbbccccccccdddddddd"
  }
}
