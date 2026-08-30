package app.muplay.requests

import app.muplay.integrations.IntegrationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The one boolean `:app` decides whether the requests destination exists from.
 *
 * Small, and gated here rather than in `:app` for exactly that reason: `:app`'s only floor is a
 * BUNDLE LINE that nothing but the emulator job can evaluate, so two lines living there would be two
 * lines no fast-tier gate ever sees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationsPresenceViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val services = FakeConfiguredServices()

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  @Test
  fun `nothing configured means no requests destination`() = runTest(dispatcher) {
    val viewModel = IntegrationsPresenceViewModel(services)
    advanceUntilIdle()

    assertThat(viewModel.anyConfigured.value).isFalse()
  }

  @Test
  fun `it starts false before the store has answered, so the destination is absent rather than flickering`() =
    runTest(dispatcher) {
      // Read **without** advancing: this is the value the first composition sees. Fail-closed is the
      // right direction for a destination -- absent-then-present is a screen arriving,
      // present-then-absent is a `NavDisplay` holding a key it has no entry for.
      services.save(lidarrCredentials())

      assertThat(IntegrationsPresenceViewModel(services).anyConfigured.value).isFalse()
    }

  @Test
  fun `either service alone is enough for the destination to exist`() = runTest(dispatcher) {
    // Both, separately: a check written against one service is a check that says nothing about a
    // user who runs the other.
    services.save(lidarrCredentials())
    val withLidarr = IntegrationsPresenceViewModel(services)
    advanceUntilIdle()
    assertThat(withLidarr.anyConfigured.value).isTrue()

    services.clear(IntegrationService.LIDARR)
    services.save(binderyCredentials())
    val withBindery = IntegrationsPresenceViewModel(services)
    advanceUntilIdle()
    assertThat(withBindery.anyConfigured.value).isTrue()
  }

  @Test
  fun `forgetting the last service takes the destination away again`() = runTest(dispatcher) {
    // The reverse transition, and the one `:app` pops the back stack on: a destination that stops
    // existing while its key is still on the stack is a `NavDisplay` crash.
    services.save(lidarrCredentials())
    val viewModel = IntegrationsPresenceViewModel(services)
    advanceUntilIdle()
    assertThat(viewModel.anyConfigured.value).isTrue()

    services.clear(IntegrationService.LIDARR)
    advanceUntilIdle()

    assertThat(viewModel.anyConfigured.value).isFalse()
  }
}
