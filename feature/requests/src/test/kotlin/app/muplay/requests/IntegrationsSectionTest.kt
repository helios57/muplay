package app.muplay.requests

import app.muplay.integrations.IntegrationService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The section's own two non-Compose facts.
 *
 * Its `Content` is Compose and measures nothing without a real composition -- this module has no
 * device tier, deliberately, so that whole file is left to `warnUngatedClasses` (see this module's
 * entry in `coverageFloors`). What *is* checkable here is the number that decides where the row
 * appears and the flow it reads, and both have gone wrong in this repository before: an order
 * clashing with another module's is resolved by class name, silently, and a flow mapped inside a
 * composable is rebuilt on every recomposition.
 */
class IntegrationsSectionTest {

  @Test
  fun `the section sits after casting's, sparsely`() {
    // Sparse and distinct: `orderedSections` breaks ties by class name, so two sections sharing an
    // order are laid out in an order nobody chose. 200 is `RendererDirectSection`'s.
    assertThat(IntegrationsSection(FakeConfiguredServices()).order).isEqualTo(300)
  }

  @Test
  fun `it reads the same configured set the requests screen does`() = runTest {
    // The set, not the credentials: a section that held the whole map would have a secret in a field
    // a composable can reach.
    val services = FakeConfiguredServices()
    services.save(binderyCredentials())

    val section = IntegrationsSection(services)

    assertThat(section.configuredServices.first()).containsExactly(IntegrationService.BINDERY)
  }
}
