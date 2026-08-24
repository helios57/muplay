package app.muplay.database

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A plain JVM test against the platform's software AES provider, which is the whole reason
 * [KeystoreCipher] takes a `SecretKey` instead of fetching one from `AndroidKeyStore`: the
 * *cryptographic contract* is testable in Tier 1, and only the key's storage needs a device.
 */
class KeystoreCipherTest {

  private fun key(): SecretKey =
    KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  @Test
  fun `round trips a non-ascii secret`() {
    val k = key()
    // A non-ASCII password is the case that breaks a charset-sloppy implementation, and this
    // project has a live user with a German-language server. Byte-for-byte UTF-8, both ways.
    val secret = "hunter2-Ünïcödé-🎵"

    assertThat(KeystoreCipher.open(k, KeystoreCipher.seal(k, secret))).isEqualTo(secret)
  }

  @Test
  fun `every seal uses a fresh iv`() {
    val k = key()

    val a = KeystoreCipher.seal(k, "same")
    val b = KeystoreCipher.seal(k, "same")

    // GCM with a reused IV under the same key is a catastrophic break — it leaks the XOR of the
    // two plaintexts and the authentication key — not a nitpick.
    assertThat(a).isNotEqualTo(b)
  }

  @Test
  fun `tampering with the ciphertext is detected`() {
    val k = key()
    val sealed = KeystoreCipher.seal(k, "secret")
    sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()

    assertThatThrownBy { KeystoreCipher.open(k, sealed) }
      .isInstanceOf(GeneralSecurityException::class.java)
  }

  @Test
  fun `a different key cannot open it`() {
    val sealed = KeystoreCipher.seal(key(), "secret")

    assertThatThrownBy { KeystoreCipher.open(key(), sealed) }
      .isInstanceOf(GeneralSecurityException::class.java)
  }

  @Test
  fun `a blob too short to hold an iv is rejected by name`() {
    // Not a theoretical case: a truncated or corrupted DataStore value arrives here as a short
    // byte array, and the difference between a clear GeneralSecurityException and an
    // ArrayIndexOutOfBoundsException three frames down is the difference between a diagnosable
    // failure and a mystery.
    assertThatThrownBy { KeystoreCipher.open(key(), ByteArray(4)) }
      .isInstanceOf(GeneralSecurityException::class.java)
      .hasMessageContaining("too short")
  }
  @Test
  fun `a provider iv of the wrong length is rejected rather than silently truncated`() {
    // Unreachable through `seal` -- every GCM provider on the JVM and on a device returns 12
    // bytes -- but `open` slices a fixed 12-byte prefix back off, so a provider that ever chose
    // differently would corrupt every stored credential without failing. Asserted directly
    // because the guard is worth keeping and an untestable guard is worth less.
    assertThatThrownBy { KeystoreCipher.requireProviderIv(ByteArray(16)) }
      .isInstanceOf(GeneralSecurityException::class.java)
      .hasMessageContaining("12-byte GCM IV")
      .hasMessageContaining("got 16")

    // And the happy path returns its input unchanged, so `seal` cannot be quietly broken by it.
    val twelve = ByteArray(12) { it.toByte() }
    assertThat(KeystoreCipher.requireProviderIv(twelve)).isEqualTo(twelve)
  }

}
