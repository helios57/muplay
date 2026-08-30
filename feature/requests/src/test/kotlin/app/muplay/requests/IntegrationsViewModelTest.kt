package app.muplay.requests

import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.bindery.BinderyUnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Setting an integration up, and forgetting one.
 *
 * On the JVM because every seam this view model holds is an interface: the real
 * `IntegrationCredentialStore` seals its secret with a key from the Android Keystore, which is what
 * `IntegrationCredentialWriter` and `IntegrationCredentialEraser` exist to get out of the way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationsViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val services = FakeConfiguredServices()
  private val writer = RecordingCredentialWriter()
  private val eraser = RecordingCredentialEraser()
  private val probe = FakeConnectionProbe()

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(policy: CleartextPolicy = CleartextPolicy.Forbidden) =
    IntegrationsViewModel(services, writer, eraser, probe, policy)

  /**
   * The open form, after letting the state flow catch up.
   *
   * `uiState` is a `combine(...).stateIn(viewModelScope, Eagerly, ...)`, so a synchronous `edit` or
   * `setUrl` reaches the *screen* only once that coroutine runs -- which on a `StandardTestDispatcher`
   * means an explicit advance. Reading `uiState.value` without one measured `null` for every form in
   * this file, which is a test artefact and not a product defect: on a device the Main dispatcher
   * runs it within the frame.
   */
  private fun TestScope.form(viewModel: IntegrationsViewModel): IntegrationSetupUiState? {
    advanceUntilIdle()
    return viewModel.uiState.value.editing
  }

  /** Types a working Lidarr address and key, and tests the connection. */
  private fun IntegrationsViewModel.fillIn(
    service: IntegrationService = IntegrationService.LIDARR,
    url: String = "https://lidarr.example.com",
    key: String = "abc123",
  ) {
    edit(service)
    setUrl(url)
    setKey(key)
  }

  // ---- the list ---------------------------------------------------------------------------------

  @Test
  fun `both services are offered whether or not either is configured`() = runTest(dispatcher) {
    // The list is the way in, so it cannot depend on what is already set up -- and it is in
    // `IntegrationService.entries` order like every other list in this feature.
    val viewModel = viewModel()
    advanceUntilIdle()

    assertThat(viewModel.uiState.value.services)
      .containsExactly(IntegrationService.LIDARR, IntegrationService.BINDERY)
    assertThat(viewModel.uiState.value.configured).isEmpty()
    assertThat(viewModel.uiState.value.editing).isNull()
  }

  @Test
  fun `a configured service is shown as configured, and only that one`() = runTest(dispatcher) {
    services.save(binderyCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()

    assertThat(viewModel.uiState.value.configured).containsExactly(IntegrationService.BINDERY)
  }

  // ---- the form ---------------------------------------------------------------------------------

  @Test
  fun `opening a form starts it empty, with the prompt rather than an accusation`() = runTest(dispatcher) {
    // **The stored address is not read back and the stored key certainly is not.** A form that
    // pre-filled a secret would put it on screen, into the accessibility tree and into any
    // screenshot, for someone who only wanted to change the address.
    services.save(lidarrCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()

    viewModel.edit(IntegrationService.LIDARR)

    val open = checkNotNull(form(viewModel))
    assertThat(open.service).isEqualTo(IntegrationService.LIDARR)
    assertThat(open.urlText).isEmpty()
    assertThat(open.keyText).isEmpty()
    assertThat(open.urlError).isNotNull()
    assertThat(open.canTest).isFalse()
    assertThat(open.canSave).isFalse()
  }

  @Test
  fun `an address with no scheme is refused with advice rather than accepted`() = runTest(dispatcher) {
    val viewModel = viewModel()
    advanceUntilIdle()

    viewModel.edit(IntegrationService.LIDARR)
    viewModel.setUrl("192.168.1.20:8686")

    // `BaseUrlResult.message`'s wording, not this screen's -- so both services' forms say the same
    // thing without either of them owning the copy.
    assertThat(checkNotNull(form(viewModel)).urlError).isNotNull().asString().contains("https://")
    assertThat(checkNotNull(form(viewModel)).canTest).isFalse()
  }

  @Test
  fun `a cleartext address is refused in a build that forbids it, and accepted in one that does not`() =
    runTest(dispatcher) {
      // The policy is a value, injected, and both members are reachable from a JVM test -- which is
      // the whole reason it is not a `BuildConfig.DEBUG` branch.
      val strict = viewModel(CleartextPolicy.Forbidden)
      strict.edit(IntegrationService.LIDARR)
      strict.setUrl("http://192.168.1.20:8686")
      assertThat(checkNotNull(form(strict)).urlError).isNotNull().asString().contains("192.168.1.20")

      val lenient = viewModel(CleartextPolicy.Allowed)
      lenient.edit(IntegrationService.LIDARR)
      lenient.setUrl("http://192.168.1.20:8686")
      assertThat(checkNotNull(form(lenient)).urlError).isNull()
    }

  @Test
  fun `a well-formed address and a key are enough to test, but not to save`() = runTest(dispatcher) {
    // Saving needs a check that came back Ok. Storing a credential nobody has shown to work turns
    // every later failure into a service outage the user cannot diagnose.
    val viewModel = viewModel()
    viewModel.fillIn()

    val open = checkNotNull(form(viewModel))
    assertThat(open.urlError).isNull()
    assertThat(open.canTest).isTrue()
    assertThat(open.canSave).isFalse()
  }

  @Test
  fun `an address alone cannot be tested, because a ping would answer without a key`() = runTest(dispatcher) {
    // Lidarr's `/ping` is the only anonymous endpoint it has, so a check with an empty key would
    // come back green and tell the user their empty key works.
    val viewModel = viewModel()
    viewModel.edit(IntegrationService.LIDARR)
    viewModel.setUrl("https://lidarr.example.com")

    assertThat(checkNotNull(form(viewModel)).canTest).isFalse()

    viewModel.test()
    advanceUntilIdle()

    assertThat(probe.asked).isEmpty()
  }

  // ---- testing the connection --------------------------------------------------------------------

  @Test
  fun `a successful check is shown and unlocks the save button`() = runTest(dispatcher) {
    probe.observation = ConnectionObservation(reachable = true, identity = "Lidarr", failure = null)
    val viewModel = viewModel()
    viewModel.fillIn()

    viewModel.test()
    advanceUntilIdle()

    val open = checkNotNull(form(viewModel))
    assertThat(open.check).isEqualTo(ConnectionCheck.Ok("Lidarr"))
    assertThat(open.canSave).isTrue()
    assertThat(open.saving).isFalse()
  }

  @Test
  fun `the probe is handed exactly the credential the form describes`() = runTest(dispatcher) {
    // The one thing a setup screen can get wrong invisibly: probing a different host, or a
    // different service's credential type, from the one the user typed.
    val viewModel = viewModel()
    viewModel.fillIn(IntegrationService.BINDERY, url = "https://bindery.example.com/", key = "  key  ")

    viewModel.test()
    advanceUntilIdle()

    val asked = probe.asked.single()
    assertThat(asked).isInstanceOf(IntegrationCredentials.Bindery::class.java)
    assertThat(asked.baseUrl.value).isEqualTo("https://bindery.example.com/")
    // Trimmed: a key pasted with a trailing newline is the commonest way to make a good key look bad.
    assertThat((asked as IntegrationCredentials.Bindery).apiKey).isEqualTo("key")
  }

  @Test
  fun `a second tap while a probe is in flight starts no second probe`() = runTest(dispatcher) {
    // The whole point of `saving`: a real check is a network round trip, and two of them racing to
    // write the outcome means the *older* answer can win. Observable only with a probe that can be
    // held, which is why the fake has a gate.
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    probe.gate = gate
    val viewModel = viewModel()
    viewModel.fillIn()

    viewModel.test()
    advanceUntilIdle()
    assertThat(checkNotNull(form(viewModel)).saving).isTrue()

    viewModel.test()
    advanceUntilIdle()

    assertThat(probe.asked).hasSize(1)

    gate.complete(Unit)
    advanceUntilIdle()
    assertThat(checkNotNull(form(viewModel)).saving).isFalse()
    assertThat(checkNotNull(form(viewModel)).check).isEqualTo(ConnectionCheck.Ok("Lidarr"))
  }

  @Test
  fun `a rejected key leaves the save button locked`() = runTest(dispatcher) {
    probe.observation = ConnectionObservation(true, null, BinderyUnauthorizedException())
    val viewModel = viewModel()
    viewModel.fillIn(IntegrationService.BINDERY, url = "https://bindery.example.com")

    viewModel.test()
    advanceUntilIdle()

    val open = checkNotNull(form(viewModel))
    assertThat(open.check).isEqualTo(ConnectionCheck.Unauthorized)
    assertThat(open.canSave).isFalse()
  }

  @Test
  fun `a wrong application leaves the save button locked and names what was found`() = runTest(dispatcher) {
    // The most likely real mistake, and the one a green tick would turn into a stream of 404s.
    probe.observation = ConnectionObservation(true, "Sonarr", null)
    val viewModel = viewModel()
    viewModel.fillIn()

    viewModel.test()
    advanceUntilIdle()

    assertThat(checkNotNull(form(viewModel)).check).isEqualTo(ConnectionCheck.WrongApplication("Sonarr"))
    assertThat(checkNotNull(form(viewModel)).canSave).isFalse()
  }

  @Test
  fun `changing either field drops a check that is no longer about anything`() = runTest(dispatcher) {
    // A green tick left standing beside a changed address is a claim about a server nobody has
    // contacted -- and, worse, it would leave `canSave` true for it.
    val viewModel = viewModel()
    viewModel.fillIn()
    viewModel.test()
    advanceUntilIdle()
    assertThat(checkNotNull(form(viewModel)).canSave).isTrue()

    viewModel.setUrl("https://other.example.com")
    assertThat(checkNotNull(form(viewModel)).check).isNull()
    assertThat(checkNotNull(form(viewModel)).canSave).isFalse()

    viewModel.test()
    advanceUntilIdle()
    assertThat(checkNotNull(form(viewModel)).canSave).isTrue()

    viewModel.setKey("different")
    assertThat(checkNotNull(form(viewModel)).check).isNull()
    assertThat(checkNotNull(form(viewModel)).canSave).isFalse()
  }

  // ---- saving and forgetting ---------------------------------------------------------------------

  @Test
  fun `saving writes the credential the form describes and closes the form`() = runTest(dispatcher) {
    val viewModel = viewModel()
    viewModel.fillIn(url = "https://lidarr.example.com/lidarr", key = "abc123")
    viewModel.test()
    advanceUntilIdle()

    viewModel.save()
    advanceUntilIdle()

    val saved = writer.saved.single()
    assertThat(saved).isInstanceOf(IntegrationCredentials.Lidarr::class.java)
    // The path is kept -- Servarr applications support a `urlBase` and are commonly proxied -- and
    // the trailing slash is added, because Retrofit resolves a relative path by replacing the last
    // segment of a base URL that lacks one.
    assertThat(saved.baseUrl.value).isEqualTo("https://lidarr.example.com/lidarr/")
    assertThat((saved as IntegrationCredentials.Lidarr).apiKey).isEqualTo("abc123")
    assertThat(form(viewModel)).isNull()
  }

  @Test
  fun `saving without a successful check writes nothing at all`() = runTest(dispatcher) {
    // The predicate the button reads and the predicate `save` enforces are the same one, so a
    // programmatic call cannot get past a disabled button.
    val viewModel = viewModel()
    viewModel.fillIn()

    viewModel.save()
    advanceUntilIdle()

    assertThat(writer.saved).isEmpty()
    assertThat(form(viewModel)).isNotNull()
  }

  @Test
  fun `a url that a check was run against and then broken is not saved`() = runTest(dispatcher) {
    // The check is dropped by `setUrl`, so this can only pass by that dropping being real.
    val viewModel = viewModel()
    viewModel.fillIn()
    viewModel.test()
    advanceUntilIdle()
    viewModel.setUrl("not a url at all")

    viewModel.save()
    advanceUntilIdle()

    assertThat(writer.saved).isEmpty()
  }

  @Test
  fun `cancelling closes the form and writes nothing`() = runTest(dispatcher) {
    val viewModel = viewModel()
    viewModel.fillIn()

    viewModel.cancel()

    assertThat(form(viewModel)).isNull()
    assertThat(writer.saved).isEmpty()
  }

  @Test
  fun `forgetting one service forgets only that one`() = runTest(dispatcher) {
    // The whole point of the per-service Keystore alias: forgetting Lidarr must not sign the user
    // out of Bindery.
    services.save(lidarrCredentials())
    services.save(binderyCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()

    viewModel.forget(IntegrationService.LIDARR)
    advanceUntilIdle()

    assertThat(eraser.forgotten).containsExactly(IntegrationService.LIDARR)
  }

  @Test
  fun `forgetting the service whose form is open closes it`() = runTest(dispatcher) {
    val viewModel = viewModel()
    viewModel.edit(IntegrationService.LIDARR)

    viewModel.forget(IntegrationService.LIDARR)
    advanceUntilIdle()

    assertThat(form(viewModel)).isNull()
  }

  @Test
  fun `forgetting a different service leaves a half-typed form alone`() = runTest(dispatcher) {
    // The other side of the same branch. Somebody's half-typed Bindery key is their work, and
    // throwing it away because they tidied up their Lidarr would be gratuitous.
    val viewModel = viewModel()
    viewModel.fillIn(IntegrationService.BINDERY, url = "https://bindery.example.com", key = "half")

    viewModel.forget(IntegrationService.LIDARR)
    advanceUntilIdle()

    assertThat(checkNotNull(form(viewModel)).service).isEqualTo(IntegrationService.BINDERY)
    assertThat(checkNotNull(form(viewModel)).keyText).isEqualTo("half")
  }

  @Test
  fun `opening the other service's form replaces the first, so two are never half-filled at once`() =
    runTest(dispatcher) {
      // Two open forms is two sets of credentials a user can confuse, and the failure -- a Lidarr key
      // saved under Bindery -- is one `RequestsRepository` carries a corrupt-store branch for.
      val viewModel = viewModel()
      viewModel.fillIn(IntegrationService.LIDARR)

      viewModel.edit(IntegrationService.BINDERY)

      assertThat(checkNotNull(form(viewModel)).service).isEqualTo(IntegrationService.BINDERY)
      assertThat(checkNotNull(form(viewModel)).keyText).isEmpty()
    }

  @Test
  fun `nothing happens when there is no form open`() = runTest(dispatcher) {
    // Every action is reachable from a screen whose form has just been closed by a recomposition,
    // so each of them has to survive a null.
    val viewModel = viewModel()

    viewModel.setUrl("https://x.example.com")
    viewModel.setKey("k")
    viewModel.test()
    viewModel.save()
    viewModel.cancel()
    advanceUntilIdle()

    assertThat(form(viewModel)).isNull()
    assertThat(probe.asked).isEmpty()
    assertThat(writer.saved).isEmpty()
  }
}
