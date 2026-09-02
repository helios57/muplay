package app.muplay.requests

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService

/**
 * The integrations screen: which services are set up, and the one form that is open.
 *
 * [editing] is `null` when the screen is a list, which is the state a returning user sees. There is
 * exactly one form at a time on purpose: two half-filled forms is two sets of credentials a user can
 * confuse, and the failure -- a Lidarr key saved under Bindery -- is one `RequestsRepository` has to
 * carry a corrupt-store branch for.
 */
data class IntegrationsUiState(
  /** Every service, in `IntegrationService.entries` order. Fixed; only [configured] moves. */
  val services: List<IntegrationService> = IntegrationService.entries,
  val configured: Set<IntegrationService> = emptySet(),
  val editing: IntegrationSetupUiState? = null,
)

/**
 * One service's setup form.
 *
 * [urlError] and [check] are separate because they answer different questions and are produced at
 * different times: the first is decided on every keystroke by `IntegrationBaseUrl.parse`, and the
 * second only when the user asks for a connection test.
 */
data class IntegrationSetupUiState(
  val service: IntegrationService,
  val urlText: String,
  val keyText: String,
  /** The sentence under the URL field, or `null`. `BaseUrlResult.message`'s, never this screen's. */
  val urlError: String?,
  val check: ConnectionCheck? = null,
  /** True while a connection test or a save is in flight; both disable the whole form. */
  val saving: Boolean = false,
) {

  /**
   * Whether "Test connection" can be pressed.
   *
   * Both fields, because both are needed: Lidarr's `/ping` would answer without a key and telling a
   * user their empty key works is the exact unhelpfulness this whole check exists to avoid.
   */
  val canTest: Boolean get() = urlError == null && urlText.isNotBlank() && keyText.isNotBlank() && !saving

  /**
   * Redacts the key, for the same reason `IntegrationCredentials.Bindery.toString` does and with
   * the same thing at stake: [keyText] holds an API key a user has just typed, and Bindery's is
   * instance-wide and always admin.
   *
   * A `data class` generates a `toString` that prints every property. That output reaches anywhere
   * a UI state is printed -- a crash dump, a debugger, a stray log line, a test failure message --
   * and this project's promise is that a key is sealed in the AndroidKeystore and never rendered.
   * `IntegrationCredentials` already keeps that promise on the *storage* side; this is the same
   * promise on the side where the key is still plain text in memory.
   *
   * Found by a security review, and it had hidden in an unusual way: all three `ConventionTest`
   * rules that police credential handling scan `File(repoRoot(), "integrations")` only, and this
   * class lives in `feature/requests`. The rule beside them now closes that gap.
   */
  override fun toString(): String =
    "IntegrationSetupUiState(service=$service, urlText=$urlText, keyText=<redacted>, " +
      "urlError=$urlError, check=$check, saving=$saving)"

  /**
   * Whether "Save" can be pressed.
   *
   * **A successful connection check is required, not merely a well-formed URL.** That is the plan's
   * rule and it is the right one here: the alternative is storing a credential that has never once
   * been shown to work, and then reporting every later failure as a service outage. It also makes
   * "Test connection" load-bearing rather than decoration.
   */
  val canSave: Boolean get() = canTest && check is ConnectionCheck.Ok
}

/**
 * The credential [state] describes, or `null` if it does not describe one yet.
 *
 * A free function rather than a method, so that the parse -- the one place a URL is admitted into
 * this app at all -- takes the [policy] as an argument and is gated on the fast tier with both
 * members of it. `IntegrationBaseUrl`'s constructor is private and [IntegrationBaseUrl.parse] is the
 * only way to reach one, which is what makes it impossible to build a credential around a URL that
 * has not been through the cleartext policy and the secret-stripping.
 */
internal fun credentialFrom(
  state: IntegrationSetupUiState,
  policy: CleartextPolicy,
): IntegrationCredentials? {
  if (state.keyText.isBlank()) return null
  val url = (IntegrationBaseUrl.parse(state.urlText, policy) as? BaseUrlResult.Valid)?.url ?: return null
  return when (state.service) {
    IntegrationService.LIDARR -> IntegrationCredentials.Lidarr(url, state.keyText.trim())
    IntegrationService.BINDERY -> IntegrationCredentials.Bindery(url, state.keyText.trim())
  }
}
