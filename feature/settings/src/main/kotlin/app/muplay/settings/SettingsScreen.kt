package app.muplay.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** The heading, and what a screen with nothing in it says. Asserted by `SettingsScreenTest`. */
const val SETTINGS_TITLE: String = "Settings"

/**
 * Shown when the multibound set is empty, which is the state a build with casting removed is in.
 *
 * Worth a sentence rather than a blank screen: "there is nothing to configure" is information, and
 * an empty column is indistinguishable from a screen that failed to load.
 */
const val SETTINGS_EMPTY: String = "There is nothing to configure yet."

/**
 * The settings screen.
 *
 * It renders [SettingsSection]s and knows nothing else -- see that interface's own documentation
 * for why the arrow points this way, and `ConventionTest`'s `the settings slot never learns what is
 * in it` for the rule that keeps it pointing this way.
 */
@Composable
fun SettingsScreen(
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  SettingsScreen(sections = viewModel.sections, modifier = modifier)
}

/**
 * The stateless half, so that the screen can be composed on a device with sections written by hand
 * and no Hilt graph at all -- the same split `:feature:player`'s screens use, and the reason this
 * module's Compose coverage is measurable without an application component.
 */
@Composable
fun SettingsScreen(
  sections: List<SettingsSection>,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      // Scrollable, not a `LazyColumn`: the number of sections is bounded by the number of feature
      // modules in the build, and a `LazyColumn` would only compose the visible ones -- which is
      // wrong for a screen whose sections each own a subscription to their own preference.
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(text = SETTINGS_TITLE, style = MaterialTheme.typography.headlineSmall)

    if (sections.isEmpty()) {
      Text(text = SETTINGS_EMPTY, style = MaterialTheme.typography.bodyMedium)
    }

    sections.forEachIndexed { index, section ->
      if (index > 0) HorizontalDivider()
      section.Content()
    }
  }
}
