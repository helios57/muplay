package app.muplay.requests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.requests.ConfiguredServices
import app.muplay.settings.SettingsSection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The always-present row. Asserted by Task 11's journey through `settings:integrations`. */
const val INTEGRATIONS_TITLE: String = "Integrations"

/** What the row says when nothing is set up, which is where every user starts. */
const val INTEGRATIONS_NONE: String =
  "Request albums and audiobooks from Lidarr or Bindery. Neither is set up."

/** The row that reaches the requests screen. Present only while something is configured. */
const val REQUESTS_TITLE: String = "Requests"

const val REQUESTS_SUBTITLE: String = "Search for something to add, and see what you have asked for."

/**
 * This plan's entire always-present UI: one settings row, and a second that appears once a service
 * is configured.
 *
 * **The severability contract, as a composable.** A user who runs neither service sees the first row
 * and nothing else -- no requests list, no empty state, no disabled search box, and no requests
 * destination in the navigation graph at all. The first row is a *switch*, in the same category as
 * the server-URL field: present because the app has to be configurable, not because a feature is on.
 * The second is the feature, and it is absent until the feature exists.
 *
 * A `@Singleton` bound `@IntoSet` from this module's own DI, so that deleting `feature/requests/`
 * deletes the row with it and `:feature:settings` -- which names nothing here -- keeps compiling.
 *
 * **Nothing in this file is gated.** It is Compose, so it measures ~0 without a real composition,
 * and this module has no device tier by design (see `di.RequestsFeatureModule`'s own note). It is
 * therefore reported by `warnUngatedClasses` on every run, deliberately: a `requiresInstrumentedData`
 * floor on a module that is not on the emulator job's command line would be skipped by the same
 * omission that skips its tests, which reads like a gate and is not one.
 */
@Singleton
class IntegrationsSection @Inject constructor(
  services: ConfiguredServices,
) : SettingsSection {

  /**
   * After casting's 200, sparsely, per [SettingsSection.order] -- so a later section can be placed
   * either side of this one without renumbering a module that has nothing to do with it.
   */
  override val order: Int = 300

  /**
   * Mapped **here and not inside [Content]**, which is not a style preference: lint's
   * `FlowOperatorInvokedInComposition` fails the build on a `map` in a composable, and it is right
   * to -- an operator applied during composition builds a new `Flow` on every recomposition, so the
   * collector is torn down and restarted each time. Measured: `:feature:requests:lintDebug` went red
   * on exactly that line.
   *
   * `internal` rather than `private` so `IntegrationsSectionTest` can assert that the set this row
   * renders from is the set of *configured services* and not, say, the whole credential map -- which
   * would put a secret in a field a composable can reach. One relaxed modifier, no second accessor:
   * a `…ForTest` property beside a private one is a production member that exists for a test.
   */
  internal val configuredServices: Flow<Set<IntegrationService>> =
    services.configured().map { it.keys }

  @Composable
  override fun Content(onNavigate: (NavKey) -> Unit) {
    // `initialValue = emptySet()`, so the requests row is absent for the frame before the store has
    // answered rather than appearing and then vanishing.
    val configured by configuredServices.collectAsStateWithLifecycle(initialValue = emptySet())

    IntegrationsSectionContent(
      configured = configured,
      onOpenIntegrations = { onNavigate(IntegrationsRoute) },
      onOpenRequests = { onNavigate(RequestsRoute) },
    )
  }
}

/**
 * The stateless half, so the section can be composed with no Hilt graph and no DataStore -- the same
 * split `RendererDirectSection` and `:feature:player`'s screens use.
 */
@Composable
internal fun IntegrationsSectionContent(
  configured: Set<IntegrationService>,
  onOpenIntegrations: () -> Unit,
  onOpenRequests: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
    SettingsRow(
      title = INTEGRATIONS_TITLE,
      subtitle = if (configured.isEmpty()) {
        INTEGRATIONS_NONE
      } else {
        // Named, in `IntegrationService.entries` order like every other list here.
        "Set up: " + IntegrationService.entries.filter { it in configured }.joinToString { it.displayName }
      },
      testTag = "settings:integrations",
      onClick = onOpenIntegrations,
    )

    // Absent, not disabled. A greyed-out row for a feature the user does not have is the dead UI
    // this plan's severability contract forbids.
    if (configured.isNotEmpty()) {
      SettingsRow(
        title = REQUESTS_TITLE,
        subtitle = REQUESTS_SUBTITLE,
        testTag = "settings:requests",
        onClick = onOpenRequests,
      )
    }
  }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, testTag: String, onClick: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .testTag(testTag)
      .clickable(onClick = onClick)
      .padding(vertical = MuPlaySpacing.sm),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs),
  ) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    Text(
      text = subtitle,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
