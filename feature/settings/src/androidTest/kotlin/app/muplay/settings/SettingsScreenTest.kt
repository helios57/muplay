package app.muplay.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings slot, composed, with sections written by hand.
 *
 * Deliberately **not** with the real `RendererDirectSection`: this module must not be able to see
 * it, and a test here that reached for it would be the first crack in exactly the dependency rule
 * this module exists to hold. `:feature:castpicker`'s own instrumented suite composes the real
 * section, inside this same screen.
 *
 * camelCase names, per CLAUDE.md: D8 refuses spaces in any SimpleName at `minSdk 26`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun aScreenWithNoSectionsSaysSoRatherThanShowingNothing() {
    // The state a build with casting removed is in, and it is a real one -- Plan 6's severability
    // contract says `git rm -r core/cast feature/castpicker` must leave a working app. A blank
    // column is indistinguishable from a screen that failed to load.
    composeRule.setContent { SettingsScreen(sections = emptyList()) }

    composeRule.onNodeWithText(SETTINGS_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText(SETTINGS_EMPTY).assertIsDisplayed()
  }

  @Test
  fun aSectionTheScreenKnowsNothingAboutIsRendered() {
    // The whole contract of the slot, in one assertion: the screen has never heard of `Loud`, and
    // `Loud` appears on it.
    composeRule.setContent { SettingsScreen(sections = listOf(Loud)) }

    composeRule.onNodeWithText("Loud section").assertIsDisplayed()
    // ...and the empty-state sentence is gone, which is the other half of the `if` above it.
    composeRule.onNodeWithText(SETTINGS_EMPTY).assertDoesNotExist()
  }

  @Test
  fun sectionsAreLaidOutInTheirStatedOrderAndNotTheOrderTheyWerePassedIn() {
    // Read off the composition's own geometry rather than from the list: `SettingsViewModelTest`
    // already asserts what `orderedSections` returns, and what could still be wrong here is the
    // screen iterating something else -- the unsorted set, say, or the list reversed by a
    // `LazyColumn` with `reverseLayout`. The list is handed over in the wrong order on purpose.
    composeRule.setContent { SettingsScreen(sections = orderedSections(setOf(Late, Early))) }

    val earlyY = composeRule.onNodeWithText("Early section").fetchSemanticsNode().positionInRoot.y
    val lateY = composeRule.onNodeWithText("Late section").fetchSemanticsNode().positionInRoot.y

    assertThat(earlyY).isLessThan(lateY)
  }

  private class FakeSection(override val order: Int, private val label: String) : SettingsSection {
    @Composable
    override fun Content() {
      Text(label)
    }
  }

  private companion object {
    val Loud = FakeSection(order = 100, label = "Loud section")
    val Early = FakeSection(order = 100, label = "Early section")
    val Late = FakeSection(order = 900, label = "Late section")
  }
}
