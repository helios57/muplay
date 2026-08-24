package app.muplay.database

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.KeyStore
import java.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.SubsonicCredentials
import java.io.File
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real thing: the device's own `AndroidKeyStore` provider, a real DataStore file, a real
 * process. A JVM test cannot exercise a hardware-backed keystore at all, and the failure this
 * class exists to catch — a key that cannot be retrieved after the first process, or a `clear()`
 * that removes the DataStore entry but leaves the key behind — is invisible without one.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {

  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var store: CredentialStore

  private val credentials =
    SubsonicCredentials("http://localhost:4533", "admin", "Ünïcödé-pässwörd-🎵")

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    file = File(context.filesDir, "credential-store-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    store = CredentialStore(dataStore)
  }

  @After
  fun tearDown() = runTest {
    store.clear()
    file.delete()
  }

  @Test
  fun nothingIsStoredBeforeAnythingIsSaved() = runTest {
    assertThat(store.load()).isNull()
  }

  @Test
  fun credentialsRoundTripThroughTheRealKeystore() = runTest {
    store.save(credentials)

    assertThat(store.load()).isEqualTo(credentials)
  }

  /**
   * The property that makes this worth having at all: what lands on disk is not the password.
   * Reading the raw DataStore file and searching it for the plaintext is the only assertion that
   * actually proves encryption happened — a round trip alone would pass just as well against an
   * implementation that stored the password verbatim.
   */
  @Test
  fun thePasswordIsNotOnDiskInPlaintext() = runTest {
    store.save(credentials)

    // Compared as BYTES, not as a decoded string. The first version of this test read the file
    // with `toString(Charsets.ISO_8859_1)` and searched it for Unicode needles -- and since the
    // password is written as UTF-8, a multi-byte character can never match its ISO-8859-1
    // decoding, so all three assertions passed against a file that literally contained the
    // plaintext. A review proved that on-device by planting the password in the file. The
    // property held; the test did not test it.
    //
    // Searching for the UTF-8 bytes is the question actually worth asking, because those are the
    // bytes `save` would write if the sealing were removed.
    val onDisk = file.readBytes()
    val secret = credentials.password.toByteArray(Charsets.UTF_8)
    assertThat(onDisk.indexOfSlice(secret))
      .describedAs("the plaintext password's UTF-8 bytes in %s", file.name)
      .isEqualTo(-1)
    // A prefix too, so a future password whose full bytes happen not to appear contiguously still
    // fails if a recognisable run of it does.
    assertThat(onDisk.indexOfSlice("pässwörd".toByteArray(Charsets.UTF_8)))
      .describedAs("a recognisable run of the plaintext password")
      .isEqualTo(-1)
    // The non-secret half is deliberately stored in the clear. This is the control: it proves the
    // search works and is looking at a file with real content, so the two assertions above are
    // negative results rather than a search that can never match anything.
    assertThat(onDisk.indexOfSlice(credentials.username.toByteArray(Charsets.UTF_8)))
      .describedAs("the username, which is stored in the clear on purpose")
      .isNotEqualTo(-1)
  }

  @Test
  fun savingAgainReplacesTheStoredCredentials() = runTest {
    store.save(credentials)
    val replacement = SubsonicCredentials("https://music.example", "alice", "sesame")

    store.save(replacement)

    assertThat(store.load()).isEqualTo(replacement)
  }

  @Test
  fun clearRemovesEverythingIncludingTheKey() = runTest {
    store.save(credentials)

    store.clear()

    assertThat(store.load()).isNull()
    // Not just the DataStore entry: the Keystore alias itself must be gone, or a "signed out"
    // device still holds the key that decrypts a backup of the ciphertext.
    assertThat(CredentialStore.keyExists()).isFalse
  }
  /**
   * Every partial-state recovery path in `read`, which the happy-path tests never reach.
   *
   * These are not hypothetical: a DataStore write is three separate preference keys, an app can
   * be killed between them, and the Keystore can lose a key independently of the file (a device
   * restore, a security-policy change, a user clearing credentials at the OS level). Each of
   * these must read as "nothing stored, log in again" rather than crashing a Flow the UI is
   * collecting.
   *
   * The key names are written out as literals rather than read from `CredentialStore`'s own
   * private constants, deliberately: they are the on-disk contract, and renaming one is a silent
   * data migration that logs every existing user out. Asserting the implementation against
   * itself would let that through.
   */
  @Test
  fun partiallyWrittenCredentialsReadAsNothingStored() = runTest {
    store.save(SubsonicCredentials("https://music.example", "alice", "sesame"))

    // Username and sealed password present, base URL gone.
    dataStore.edit { it.remove(stringPreferencesKey("server_base_url")) }
    assertThat(store.load()).isNull()

    store.save(SubsonicCredentials("https://music.example", "alice", "sesame"))
    dataStore.edit { it.remove(stringPreferencesKey("server_username")) }
    assertThat(store.load()).isNull()

    store.save(SubsonicCredentials("https://music.example", "alice", "sesame"))
    dataStore.edit { it.remove(stringPreferencesKey("server_sealed_password")) }
    assertThat(store.load()).isNull()
  }

  @Test
  fun credentialsWhoseKeystoreKeyIsGoneReadAsNothingStored() = runTest {
    store.save(SubsonicCredentials("https://music.example", "alice", "sesame"))

    // The file survives, the key does not -- a device restore, or the user clearing credentials
    // at the OS level. Without this path the app would hold three unreadable strings and believe
    // it was signed in.
    // The alias is a literal here for the same reason the preference keys are: it identifies a
    // key that already exists on real devices, so renaming it silently logs everyone out.
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      .deleteEntry("app.muplay.credentials")

    assertThat(store.load()).isNull()
  }

  @Test
  fun anUnopenableBlobReadsAsNothingStored() = runTest {
    store.save(SubsonicCredentials("https://music.example", "alice", "sesame"))

    // A corrupted or truncated ciphertext: GCM authentication fails, and the caller must be told
    // "log in again" rather than shown a GeneralSecurityException from inside a Flow.
    dataStore.edit {
      it[stringPreferencesKey("server_sealed_password")] = Base64.getEncoder().encodeToString(ByteArray(32))
    }

    assertThat(store.load()).isNull()
  }

  /** Index of [needle] in this array, or -1. `ByteArray` has no `indexOf` for a subsequence. */
  private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (start in 0..size - needle.size) {
      for (offset in needle.indices) {
        if (this[start + offset] != needle[offset]) continue@outer
      }
      return start
    }
    return -1
  }

}
