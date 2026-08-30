package app.muplay.requests

import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two predicates the setup form's buttons read, and the one function that turns a filled-in form
 * into a credential.
 *
 * Driven directly rather than through the view model, because two of the states matter and cannot be
 * reached from outside: `saving = true` exists only for the instant a probe is in flight, and it is
 * exactly the instant in which a second tap must not start a second one.
 */
class IntegrationSetupUiStateTest {

  private fun form(
    service: IntegrationService = IntegrationService.LIDARR,
    urlText: String = "https://lidarr.example.com",
    keyText: String = "abc123",
    urlError: String? = null,
    check: ConnectionCheck? = null,
    saving: Boolean = false,
  ) = IntegrationSetupUiState(service, urlText, keyText, urlError, check, saving)

  @Test
  fun `a complete form can be tested but not saved until a check comes back ok`() {
    assertThat(form().canTest).isTrue()
    assertThat(form().canSave).isFalse()
    assertThat(form(check = ConnectionCheck.Ok("Lidarr")).canSave).isTrue()
  }

  @Test
  fun `no outcome but Ok unlocks saving`() {
    // Exact, over every member: a `check != null` here would let a user save a Sonarr URL the moment
    // the check told them it was a Sonarr.
    val outcomes = listOf(
      ConnectionCheck.Unreachable,
      ConnectionCheck.Unauthorized,
      ConnectionCheck.WrongApplication("Sonarr"),
      ConnectionCheck.Failed("no route"),
    )

    assertThat(outcomes.map { form(check = it).canSave }).containsOnly(false)
  }

  @Test
  fun `a form with a probe in flight offers neither button`() {
    // Otherwise a second tap starts a second probe against a server that is already answering the
    // first, and the two race to write the outcome.
    assertThat(form(saving = true).canTest).isFalse()
    assertThat(form(check = ConnectionCheck.Ok("Lidarr"), saving = true).canSave).isFalse()
  }

  @Test
  fun `a missing key or a missing address offers nothing`() {
    // Both halves. An address alone would be tested against Lidarr's anonymous `/ping`, which
    // answers -- so a user with no key would be told their connection is fine.
    assertThat(form(keyText = "").canTest).isFalse()
    assertThat(form(keyText = "   ").canTest).isFalse()
    assertThat(form(urlText = "", urlError = null).canTest).isFalse()
    assertThat(form(urlError = "something is wrong").canTest).isFalse()
  }

  // ---- credentialFrom ---------------------------------------------------------------------------

  @Test
  fun `each service's form becomes its own credential type`() {
    // The failure this refuses is a Lidarr key sent to Bindery, which is a corrupt store
    // `RequestsRepository` then has to carry a branch for.
    assertThat(credentialFrom(form(IntegrationService.LIDARR), CleartextPolicy.Forbidden))
      .isInstanceOf(IntegrationCredentials.Lidarr::class.java)
    assertThat(credentialFrom(form(IntegrationService.BINDERY), CleartextPolicy.Forbidden))
      .isInstanceOf(IntegrationCredentials.Bindery::class.java)
  }

  @Test
  fun `a form with no key describes no credential`() {
    assertThat(credentialFrom(form(keyText = ""), CleartextPolicy.Forbidden)).isNull()
    assertThat(credentialFrom(form(keyText = "  "), CleartextPolicy.Forbidden)).isNull()
  }

  @Test
  fun `a form whose address does not parse describes no credential`() {
    assertThat(credentialFrom(form(urlText = "not a url"), CleartextPolicy.Forbidden)).isNull()
    assertThat(credentialFrom(form(urlText = ""), CleartextPolicy.Forbidden)).isNull()
  }

  @Test
  fun `the cleartext policy is what decides an http address, in both directions`() {
    // A value, injected, and both members reachable from a JVM test -- which is the whole reason it
    // is not a `BuildConfig.DEBUG` branch with one arm no test can ever take.
    val http = form(urlText = "http://192.168.1.20:8686")

    assertThat(credentialFrom(http, CleartextPolicy.Forbidden)).isNull()
    assertThat(credentialFrom(http, CleartextPolicy.Allowed)?.baseUrl?.value)
      .isEqualTo("http://192.168.1.20:8686/")
  }

  @Test
  fun `a key pasted with whitespace round it is stored trimmed`() {
    // The commonest way to make a good key look like a bad one.
    val credentials = credentialFrom(form(keyText = " abc123\n"), CleartextPolicy.Forbidden)

    assertThat((credentials as IntegrationCredentials.Lidarr).apiKey).isEqualTo("abc123")
  }

  @Test
  fun `a credential built here carries no secret in its url`() {
    // `IntegrationBaseUrl.parse` strips query, fragment and userinfo, and its constructor is private
    // -- so this is the only way a credential in this app can be built, and there is no second path
    // that skips the stripping.
    val credentials = credentialFrom(
      form(urlText = "https://user:pw@lidarr.example.com/lidarr?apikey=SECRET#frag"),
      CleartextPolicy.Forbidden,
    )

    assertThat(checkNotNull(credentials).baseUrl.value).isEqualTo("https://lidarr.example.com/lidarr/")
    assertThat(credentials.toString()).doesNotContain("abc123").doesNotContain("SECRET")
  }
}
