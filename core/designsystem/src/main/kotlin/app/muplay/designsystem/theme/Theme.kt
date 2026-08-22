package app.muplay.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
 * MuPlay's Material 3 theme: colour scheme (light/dark, following the system setting) and
 * [MuPlayTypography]. No dynamic colour (`dynamicColorScheme`) yet — that needs API 31+ branching
 * this task has no requirement to exercise.
 */
@Composable
fun MuPlayTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  MaterialTheme(
    colorScheme = colorScheme,
    typography = MuPlayTypography,
    content = content,
  )
}
