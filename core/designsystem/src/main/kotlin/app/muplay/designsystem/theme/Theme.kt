package app.muplay.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
  primary = MuPlayPrimaryLight,
  onPrimary = MuPlayOnPrimaryLight,
  secondary = MuPlaySecondaryLight,
  onSecondary = MuPlayOnSecondaryLight,
  tertiary = MuPlayTertiaryLight,
  error = MuPlayErrorLight,
)

private val DarkColorScheme = darkColorScheme(
  primary = MuPlayPrimaryDark,
  onPrimary = MuPlayOnPrimaryDark,
  secondary = MuPlaySecondaryDark,
  onSecondary = MuPlayOnSecondaryDark,
  tertiary = MuPlayTertiaryDark,
  error = MuPlayErrorDark,
)

/**
 * The one piece of branching logic [MuPlayTheme] owns — which colour scheme to use — pulled out
 * into a plain, non-`@Composable` function so it is JVM-testable without Compose UI test
 * infrastructure (Robolectric or an emulator): [lightColorScheme]/[darkColorScheme] return a
 * plain [ColorScheme] value, not something that needs a composition to build or compare. See
 * `ThemeTest` — the same reasoning `SetupFailureReason.toMessage` documents for pulling
 * non-Compose branches out of a screen file applies here too.
 */
internal fun colorSchemeFor(darkTheme: Boolean): ColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

/**
 * MuPlay's Material 3 theme: colour scheme (light/dark, following the system setting) and
 * [MuPlayTypography]. No dynamic colour (`dynamicColorScheme`) yet — that needs API 31+ branching
 * this task has no requirement to exercise.
 */
@Composable
fun MuPlayTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = colorSchemeFor(darkTheme),
    typography = MuPlayTypography,
    content = content,
  )
}
