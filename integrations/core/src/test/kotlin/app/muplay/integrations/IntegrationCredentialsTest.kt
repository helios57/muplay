package app.muplay.integrations

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The credential type's own behaviour, and the one thing about the store that needs no device.
 *
 * `IntegrationCredentials.Lidarr.toString()` is a **security control**, exactly like
 * `SubsonicCredentials.toString()` in `:core:model`: a `data class` generates a `toString()`
 * naming every constructor property, and a Lidarr API key is instance-wide and carries admin
 * authority over the user's download client. The first `Log.d(state)` anyone writes -- or the
 * message of any `Throwable` that interpolates a credential -- publishes it. This project has
 * already found that exact defect once.
 *
 * These live in the **JVM** tier deliberately, and that is not a convenience. Coverage is measured
 * per module *and per tier*: a security control asserted only from an instrumented test is gated
 * only by the 45-minute tier, and this one needs no Android type at all (`keyAlias` is a `when`
 * over an enum; `toString` is string interpolation). It is also what puts these assertions within
 * reach of `ci/mutation-probes.sh`, which runs the JVM suites only.
 */
class IntegrationCredentialsTest {

  private fun url(raw: String) =
    (IntegrationBaseUrl.parse(raw, CleartextPolicy.Forbidden) as BaseUrlResult.Valid).url

  private val lidarr = IntegrationCredentials.Lidarr(
    baseUrl = url("https://lidarr.example.com"),
    apiKey = API_KEY,
  )

  @Test
  fun `toString does not leak the api key`() {
    assertThat(lidarr.toString()).doesNotContain(API_KEY)
  }

  @Test
  fun `toString still identifies which server it is for`() {
    // A redaction that returned a constant would satisfy the test above and make every log line
    // about an integration useless. The point is to hide one field, not the object.
    assertThat(lidarr.toString()).contains("https://lidarr.example.com/")
  }

  @Test
  fun `the api key is still carried, only hidden from toString`() {
    assertThat(lidarr.apiKey).isEqualTo(API_KEY)
  }

  @Test
  fun `two credentials differing only by api key are not equal`() {
    // Redacting by dropping the field from the class would break every request silently; this
    // pins the key as part of the value, not merely as a property that happens to exist.
    assertThat(lidarr)
      .isNotEqualTo(IntegrationCredentials.Lidarr(url("https://lidarr.example.com"), "ffffffff"))
  }

  @Test
  fun `a member reports its own service rather than a stored one`() {
    // `service` is a `get()` on each member, not a constructor property, so there is no way to
    // construct a Lidarr credential that claims to be Bindery's.
    assertThat(lidarr.service).isEqualTo(IntegrationService.LIDARR)
    assertThat(lidarr.copy(apiKey = "ffffffff").service).isEqualTo(IntegrationService.LIDARR)
  }

  /**
   * The independence property, at the alias level, and the reason it is a whole test of its own:
   * with one shared Keystore alias, `clear(LIDARR)` would either leave a key behind that still
   * opens Bindery's blob or destroy it and sign the user out of a service they did not ask to
   * forget. Neither failure is visible to any test that configures a single service.
   *
   * Asserted as an exact mapped list rather than "they are different", so an implementation that
   * returned one constant alias fails, and so does one that returned the aliases in the wrong
   * order.
   */
  @Test
  fun `the two services use two different keystore aliases`() {
    assertThat(IntegrationService.entries.map { IntegrationCredentialStore.keyAlias(it) })
      .containsExactly("app.muplay.integrations.lidarr", "app.muplay.integrations.bindery")
  }

  /**
   * The aliases are the app's own namespace and are not the one holding the Navidrome password.
   * `CredentialStore.clear()` destroys `app.muplay.credentials`; an integration sharing it would
   * be signed out by a Navidrome sign-out, and the symptom would read as a Lidarr bug.
   */
  @Test
  fun `no integration shares the navidrome credential alias`() {
    assertThat(IntegrationService.entries.map { IntegrationCredentialStore.keyAlias(it) })
      .allSatisfy { assertThat(it).startsWith("app.muplay.integrations.") }
      .doesNotContain("app.muplay.credentials")
  }

  private companion object {
    /** Shaped like a real Lidarr key: 32 lowercase hex characters. */
    private const val API_KEY = "0123456789abcdef0123456789abcdef"
  }
}
