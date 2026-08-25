package app.muplay.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `KeystoreKeys` against the real `AndroidKeyStore` provider, which is the only provider whose
 * behaviour matters — a software AES provider on the JVM accepts things Keystore rejects, and this
 * project has already been bitten by exactly that (see `KeystoreCipher.seal`'s doc).
 *
 * Two aliases throughout, never one. The whole reason this object exists is that two stores now
 * hold keys side by side, and an implementation that ignored its `alias` argument and used a
 * single hardcoded one would pass every single-alias test ever written. That is the
 * argument-passthrough defect this project has shipped before.
 *
 * Neither alias here is one the app ships (`app.muplay.credentials`,
 * `app.muplay.integrations.lidarr`, `app.muplay.integrations.bindery`): this class deletes the
 * aliases it names in both `@Before` and `@After`, and the emulator is shared, so naming a real
 * one would sign a concurrently-running `CredentialStoreTest` out from under itself.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreKeysTest {

  private val first = "app.muplay.test.first"
  private val second = "app.muplay.test.second"

  @Before fun clean() = deleteBoth()

  @After fun tidy() = deleteBoth()

  private fun deleteBoth() {
    KeystoreKeys.delete(first)
    KeystoreKeys.delete(second)
  }

  @Test
  fun findReturnsNullForAnAliasThatWasNeverCreatedAndDoesNotCreateIt() {
    assertThat(KeystoreKeys.find(first)).isNull()
    // The important half: a `find` that quietly created the key would make `exists` meaningless
    // and would make CredentialStore.read()'s "no key means signed out" branch unreachable.
    assertThat(KeystoreKeys.exists(first)).isFalse()
  }

  @Test
  fun getOrCreateCreatesAKeyThatFindThenReturnsAndIsStableAcrossCalls() {
    val created = KeystoreKeys.getOrCreate(first)

    assertThat(KeystoreKeys.exists(first)).isTrue()
    // Keystore keys have no extractable material, so identity is compared through a round trip.
    val sealed = KeystoreCipher.seal(created, "hunter2")
    assertThat(KeystoreCipher.open(KeystoreKeys.getOrCreate(first), sealed)).isEqualTo("hunter2")
    assertThat(KeystoreCipher.open(checkNotNull(KeystoreKeys.find(first)), sealed)).isEqualTo("hunter2")
  }

  /**
   * The sealing is real, not a pass-through, asserted here as well as through the round trip
   * above: `open(seal(x)) == x` holds just as well for a `seal` that returns its own input.
   */
  @Test
  fun aSealedSecretIsNeitherThePlaintextNorTheSameBytesTwice() {
    val key = KeystoreKeys.getOrCreate(first)

    val once = KeystoreCipher.seal(key, "hunter2")
    val twice = KeystoreCipher.seal(key, "hunter2")

    assertThat(String(once, Charsets.ISO_8859_1)).doesNotContain("hunter2")
    // AES-GCM with a reused IV leaks the XOR of the two plaintexts *and* the authentication key,
    // so two seals of the same string under the same key must not produce the same bytes.
    assertThat(once).isNotEqualTo(twice)
    // ...and both must still open, so "different" cannot be satisfied by returning garbage.
    assertThat(KeystoreCipher.open(key, once)).isEqualTo("hunter2")
    assertThat(KeystoreCipher.open(key, twice)).isEqualTo("hunter2")
  }

  @Test
  fun aTamperedCiphertextFailsRatherThanOpeningToGarbage() {
    val key = KeystoreKeys.getOrCreate(first)
    val sealed = KeystoreCipher.seal(key, "hunter2")

    // One bit of the ciphertext body, past the 12-byte IV prefix. GCM authenticates, so this must
    // be refused, not decrypted into whatever it happens to produce.
    val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

    assertThatThrownBy { KeystoreCipher.open(key, tampered) }
      .isInstanceOf(javax.crypto.AEADBadTagException::class.java)
  }

  @Test
  fun twoAliasesAreTwoDifferentKeys() {
    // The argument-passthrough proof. An implementation that hardcoded one alias would open this
    // blob with the wrong key and this test would fail -- which is the point.
    val sealedByFirst = KeystoreCipher.seal(KeystoreKeys.getOrCreate(first), "secret-one")
    KeystoreKeys.getOrCreate(second)

    assertThat(KeystoreCipher.open(checkNotNull(KeystoreKeys.find(first)), sealedByFirst))
      .isEqualTo("secret-one")
    assertThatThrownBy { KeystoreCipher.open(checkNotNull(KeystoreKeys.find(second)), sealedByFirst) }
      .isInstanceOf(javax.crypto.AEADBadTagException::class.java)
  }

  @Test
  fun deleteRemovesOnlyTheAliasItWasGiven() {
    KeystoreKeys.getOrCreate(first)
    KeystoreKeys.getOrCreate(second)

    KeystoreKeys.delete(first)

    // Two observations, opposite directions, from one call. A `delete` that cleared everything
    // passes the first assertion and fails the second.
    assertThat(KeystoreKeys.exists(first)).isFalse()
    assertThat(KeystoreKeys.exists(second)).isTrue()
  }

  @Test
  fun deletingAnAliasThatDoesNotExistIsNotAnError() {
    // `clear()` on a never-configured service is a normal path, not an exceptional one.
    KeystoreKeys.delete("app.muplay.test.never-created")
    assertThat(KeystoreKeys.exists("app.muplay.test.never-created")).isFalse()
  }
}
