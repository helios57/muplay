package app.muplay.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.message
import app.muplay.integrations.requests.ConfiguredServices
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where a service's credentials are written.
 *
 * A seam, unlike [RequestsViewModel]'s repository, and for the reason this project's other seams
 * exist rather than as a habit: `IntegrationCredentialStore` seals its secret with a key from the
 * **Android Keystore** and stores it in DataStore, so nothing about this screen would be provable
 * anywhere but on an emulator behind it. Two single-method interfaces rather than one two-method
 * one, so both bind with a one-line SAM conversion in `di.RequestsFeatureModule` -- a `@Provides`
 * returning an object expression would be wiring only the Hilt graph ever runs, in a module that
 * has no device tier to run it.
 */
fun interface IntegrationCredentialWriter {
  suspend fun save(credentials: IntegrationCredentials)
}

/** Forgets one service: its DataStore entries **and** its own Keystore key. See [IntegrationCredentialWriter]. */
fun interface IntegrationCredentialEraser {
  suspend fun forget(service: IntegrationService)
}

/**
 * The integrations screen.
 *
 * It owns one form at a time and the four things that can be done to it, and it writes nothing until
 * a connection check has come back [ConnectionCheck.Ok] -- see [IntegrationSetupUiState.canSave].
 *
 * **Reading `configured` and writing it go through different seams on purpose.**
 * [ConfiguredServices] is `:integrations:requests`' own port, already bound to the real store for
 * `RequestsRepository`, so the list this screen shows and the map the repository polls with are the
 * same read and cannot disagree.
 */
@HiltViewModel
class IntegrationsViewModel @Inject constructor(
  services: ConfiguredServices,
  private val writer: IntegrationCredentialWriter,
  private val eraser: IntegrationCredentialEraser,
  private val probe: ConnectionProbe,
  private val policy: CleartextPolicy,
) : ViewModel() {

  private val editing = MutableStateFlow<IntegrationSetupUiState?>(null)

  val uiState: StateFlow<IntegrationsUiState> =
    combine(services.configured().map { it.keys }, editing) { configured, form ->
      IntegrationsUiState(configured = configured, editing = form)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, IntegrationsUiState())

  /**
   * Open [service]'s form.
   *
   * **The stored URL is not read back into the field and the stored key certainly is not.** A form
   * that pre-filled a secret would put it back on screen, into the accessibility tree and into any
   * screenshot, for a user who only wanted to change the address; and a saved integration is
   * replaced rather than edited, which is what `IntegrationCredentialStore.save` does anyway.
   */
  fun edit(service: IntegrationService) {
    editing.value = IntegrationSetupUiState(
      service = service,
      urlText = "",
      keyText = "",
      // Blank is not an error to shout about on a form nobody has typed in yet, but it is also not
      // valid -- `BaseUrlResult.Blank`'s own message is the prompt, and `canSave` refuses it.
      urlError = BaseUrlResult.Blank.message(service),
    )
  }

  /** Close the form without saving. */
  fun cancel() {
    editing.value = null
  }

  /**
   * The address changed.
   *
   * Parsed on **every keystroke**, under the injected [CleartextPolicy], so a release build refuses
   * an `http://` address at configuration time with a sentence the user can act on rather than at
   * request time with OkHttp's `UnknownServiceException`.
   *
   * Any previous connection check is dropped: a green tick left standing beside a changed address is
   * a claim about a server nobody has contacted.
   */
  fun setUrl(text: String) {
    updateForm { form ->
      form.copy(
        urlText = text,
        urlError = IntegrationBaseUrl.parse(text, policy).message(form.service),
        check = null,
      )
    }
  }

  /** The key changed. The check is dropped for the same reason [setUrl] drops it. */
  fun setKey(text: String) {
    updateForm { form -> form.copy(keyText = text, check = null) }
  }

  /**
   * Test the connection.
   *
   * Does nothing at all when the form cannot describe a credential, rather than probing a
   * half-typed address: [IntegrationSetupUiState.canTest] is the same predicate the button's enabled
   * state reads, so the two cannot disagree.
   */
  fun test() {
    val form = editing.value ?: return
    val credentials = credentialFrom(form, policy)?.takeIf { form.canTest } ?: return
    viewModelScope.launch {
      updateForm { it.copy(saving = true) }
      val observation = probe.observe(credentials)
      updateForm {
        it.copy(
          saving = false,
          check = ConnectionCheck.of(
            service = form.service,
            reachable = observation.reachable,
            identity = observation.identity,
            failure = observation.failure,
          ),
        )
      }
    }
  }

  /**
   * Save, and close the form.
   *
   * Refuses unless [IntegrationSetupUiState.canSave] -- which requires a connection check that came
   * back [ConnectionCheck.Ok]. Storing a credential that has never been shown to work turns every
   * later failure into a service outage nobody can diagnose.
   */
  fun save() {
    val form = editing.value ?: return
    // One expression rather than a `canSave` guard followed by a separate `credentialFrom(...) ?:
    // return`: written that way, the second null arm is unreachable -- `canSave` already implies a
    // parsed URL and a non-blank key -- and it measured as a branch no test could ever take.
    val credentials = credentialFrom(form, policy)?.takeIf { form.canSave } ?: return
    viewModelScope.launch {
      updateForm { it.copy(saving = true) }
      writer.save(credentials)
      // Closed rather than left showing a "saved" form. The plan's state carried a `saved` flag for
      // this moment; there is nothing for it to say, because `configured` moves on the same write
      // and the list behind the form is the honest confirmation. A flag nothing renders is a field
      // no test can fail on.
      editing.value = null
    }
  }

  /**
   * Forget [service] entirely.
   *
   * Only that service's entries and only that service's Keystore key -- the whole point of the
   * per-service alias, and the reason forgetting one does not sign the user out of the other.
   */
  fun forget(service: IntegrationService) {
    viewModelScope.launch {
      eraser.forget(service)
      // If the form for the service just forgotten is open, close it; another service's form is
      // somebody's half-typed work and is left alone.
      editing.update { form -> form?.takeIf { it.service != service } }
    }
  }

  /**
   * Applies [block] to the open form, if there is one.
   *
   * **One null check rather than five.** Every mutator below runs after the form has already been
   * read non-null, so `editing.update { it?.copy(...) }` written at each call site is five null arms
   * nothing can ever take -- five permanently uncovered branches, in a class whose BRANCH floor is
   * the only thing gating its decisions. Measured: with the five inline, this class read 15/18
   * = 0.8333; with them folded here it reads what this table records.
   */
  private fun updateForm(block: (IntegrationSetupUiState) -> IntegrationSetupUiState) {
    editing.update { form -> form?.let(block) }
  }
}
