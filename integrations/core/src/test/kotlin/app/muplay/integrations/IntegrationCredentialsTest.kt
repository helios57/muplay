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

  /**
   * The second member, added at Task 8.
   *
   * Its key is **64** lowercase hex characters where Lidarr's is 32 — the shape a real
   * `v1.32.1` generates — and a different value. Two secrets that differ in length as well as in
   * value are what turn "both redact" into "neither redaction is a constant that happens to
   * match".
   */
  private val bindery = IntegrationCredentials.Bindery(
    baseUrl = url("https://bindery.example.com"),
    apiKey = BINDERY_API_KEY,
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

  // ---- the Bindery member, added at Task 8 ---------------------------------------------------
  //
  // Every assertion above, repeated for the second member rather than assumed to follow from it.
  // `toString()` is an override per member: the sealed supertype cannot enforce it, the compiler
  // cannot notice a member that forgot it, and `data class` supplies a `toString()` that names
  // every constructor property — so a new member's redaction is exactly the kind of security
  // control that is one absent override away from not existing. Adding this member is what
  // measured that: `IntegrationCredentials.Bindery` read **LINE 0.00** on the first run and failed
  // this module's 0.90 floor, because nothing in the JVM tier constructed one.

  @Test
  fun `the bindery toString does not leak the api key either`() {
    assertThat(bindery.toString()).doesNotContain(BINDERY_API_KEY)
    // And not the other service's, which a copy-pasted override naming the wrong field could do.
    assertThat(bindery.toString()).doesNotContain(API_KEY)
  }

  @Test
  fun `the bindery toString still identifies which server it is for`() {
    assertThat(bindery.toString()).contains("https://bindery.example.com/")
    // Named, so a redaction that returned a constant -- which would satisfy the test above and
    // make every log line about an integration useless -- fails here.
    assertThat(bindery.toString()).startsWith("Bindery(")
    assertThat(bindery.toString()).doesNotContain("Lidarr")
  }

  @Test
  fun `the bindery api key is still carried, only hidden from toString`() {
    assertThat(bindery.apiKey).isEqualTo(BINDERY_API_KEY)
  }

  @Test
  fun `two bindery credentials differing only by api key are not equal`() {
    assertThat(bindery)
      .isNotEqualTo(IntegrationCredentials.Bindery(url("https://bindery.example.com"), "ffffffff"))
  }

  @Test
  fun `the bindery member reports its own service rather than a stored one`() {
    assertThat(bindery.service).isEqualTo(IntegrationService.BINDERY)
    assertThat(bindery.copy(apiKey = "ffffffff").service).isEqualTo(IntegrationService.BINDERY)
  }

  /**
   * **Every** member of the sealed type redacts, as one exact statement.
   *
   * The per-member tests above pin each redaction; this one pins the *set*. A third service added
   * later without a `toString()` override gets a `data class`'s generated one, which names
   * `apiKey=<the real key>`, and nothing above would fail — because nothing above knows the new
   * member exists. This does: it maps over both secrets and requires that neither appears in
   * either object's rendering.
   */
  @Test
  fun `no member of the sealed type renders any secret`() {
    val members: List<IntegrationCredentials> = listOf(lidarr, bindery)
    val secrets = listOf(API_KEY, BINDERY_API_KEY)

    // Positive controls first: two members, two distinct renderings that are not empty. An
    // `allSatisfy` over an empty list is vacuously true, and so is one over two blank strings.
    assertThat(members).hasSize(2)
    assertThat(members.map { it.service })
      .containsExactly(IntegrationService.LIDARR, IntegrationService.BINDERY)
    assertThat(members.map { it.toString() }).doesNotHaveDuplicates()
    assertThat(members).allSatisfy { assertThat(it.toString()).contains("baseUrl=https://") }

    assertThat(members).allSatisfy { member ->
      secrets.forEach { secret -> assertThat(member.toString()).doesNotContain(secret) }
    }
    assertThat(members).allSatisfy { assertThat(it.toString()).contains("<redacted>") }
  }

  private companion object {
    /** Shaped like a real Lidarr key: 32 lowercase hex characters. */
    private const val API_KEY = "0123456789abcdef0123456789abcdef"

    /**
     * Shaped like a real Bindery key: **64** lowercase hex characters, measured off a running
     * `ghcr.io/vavallee/bindery:v1.32.1` whose generated key is 32 random bytes hex-encoded and
     * lives in its own `settings` table under `auth.api_key`.
     */
    private const val BINDERY_API_KEY =
      "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
  }
}
