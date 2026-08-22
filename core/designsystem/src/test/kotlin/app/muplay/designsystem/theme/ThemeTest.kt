package app.muplay.designsystem.theme

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [colorSchemeFor] is the one piece of branching logic [MuPlayTheme] owns, pulled into a plain
 * function specifically so it is testable here — see its own KDoc. `lightColorScheme()`/
 * `darkColorScheme()` and the [androidx.compose.ui.graphics.Color] values they are built from are
 * plain JVM-computable values with no Android framework or composition dependency, so this needs
 * neither Robolectric nor an emulator.
 */
class ThemeTest {

  @Test
  fun `colorSchemeFor false returns the light scheme`() {
    assertThat(colorSchemeFor(darkTheme = false).primary).isEqualTo(MuPlayPrimaryLight)
  }

  @Test
  fun `colorSchemeFor true returns the dark scheme`() {
    assertThat(colorSchemeFor(darkTheme = true).primary).isEqualTo(MuPlayPrimaryDark)
  }
}
