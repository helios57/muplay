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
  primaryContainer = MuPlayPrimaryContainerLight,
  onPrimaryContainer = MuPlayOnPrimaryContainerLight,
  secondary = MuPlaySecondaryLight,
  onSecondary = MuPlayOnSecondaryLight,
  secondaryContainer = MuPlaySecondaryContainerLight,
  onSecondaryContainer = MuPlayOnSecondaryContainerLight,
  tertiary = MuPlayTertiaryLight,
  onTertiary = MuPlayOnTertiaryLight,
  tertiaryContainer = MuPlayTertiaryContainerLight,
  onTertiaryContainer = MuPlayOnTertiaryContainerLight,
  error = MuPlayErrorLight,
  onError = MuPlayOnErrorLight,
  errorContainer = MuPlayErrorContainerLight,
  onErrorContainer = MuPlayOnErrorContainerLight,
  background = MuPlayBackgroundLight,
  onBackground = MuPlayOnBackgroundLight,
  surface = MuPlaySurfaceLight,
  onSurface = MuPlayOnSurfaceLight,
  surfaceVariant = MuPlaySurfaceVariantLight,
  onSurfaceVariant = MuPlayOnSurfaceVariantLight,
  outline = MuPlayOutlineLight,
  outlineVariant = MuPlayOutlineVariantLight,
  surfaceContainerLowest = MuPlaySurfaceContainerLowestLight,
  surfaceContainerLow = MuPlaySurfaceContainerLowLight,
  surfaceContainer = MuPlaySurfaceContainerLight,
  surfaceContainerHigh = MuPlaySurfaceContainerHighLight,
  surfaceContainerHighest = MuPlaySurfaceContainerHighestLight,
  inverseSurface = MuPlayInverseSurfaceLight,
  inverseOnSurface = MuPlayInverseOnSurfaceLight,
  inversePrimary = MuPlayInversePrimaryLight,
  scrim = MuPlayScrim,
)

private val DarkColorScheme = darkColorScheme(
  primary = MuPlayPrimaryDark,
  onPrimary = MuPlayOnPrimaryDark,
  primaryContainer = MuPlayPrimaryContainerDark,
  onPrimaryContainer = MuPlayOnPrimaryContainerDark,
  secondary = MuPlaySecondaryDark,
  onSecondary = MuPlayOnSecondaryDark,
  secondaryContainer = MuPlaySecondaryContainerDark,
  onSecondaryContainer = MuPlayOnSecondaryContainerDark,
  tertiary = MuPlayTertiaryDark,
  onTertiary = MuPlayOnTertiaryDark,
  tertiaryContainer = MuPlayTertiaryContainerDark,
  onTertiaryContainer = MuPlayOnTertiaryContainerDark,
  error = MuPlayErrorDark,
  onError = MuPlayOnErrorDark,
  errorContainer = MuPlayErrorContainerDark,
  onErrorContainer = MuPlayOnErrorContainerDark,
  background = MuPlayBackgroundDark,
  onBackground = MuPlayOnBackgroundDark,
  surface = MuPlaySurfaceDark,
  onSurface = MuPlayOnSurfaceDark,
  surfaceVariant = MuPlaySurfaceVariantDark,
  onSurfaceVariant = MuPlayOnSurfaceVariantDark,
  outline = MuPlayOutlineDark,
  outlineVariant = MuPlayOutlineVariantDark,
  surfaceContainerLowest = MuPlaySurfaceContainerLowestDark,
  surfaceContainerLow = MuPlaySurfaceContainerLowDark,
  surfaceContainer = MuPlaySurfaceContainerDark,
  surfaceContainerHigh = MuPlaySurfaceContainerHighDark,
  surfaceContainerHighest = MuPlaySurfaceContainerHighestDark,
  inverseSurface = MuPlayInverseSurfaceDark,
  inverseOnSurface = MuPlayInverseOnSurfaceDark,
  inversePrimary = MuPlayInversePrimaryDark,
  scrim = MuPlayScrim,
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
 * MuPlay's Material 3 theme: colour scheme (light/dark, following the system setting),
 * [MuPlayTypography] and [MuPlayShapes].
 *
 * **No dynamic colour, and that is now a product decision rather than a deferral.** Material You
 * would repaint this app from the wallpaper, and the palette this theme carries is not decoration:
 * `primary` means "music" and `tertiary` means "audiobook" on every screen, which is the one thing
 * `docs/STORE-LISTING.md` promises hardest. A wallpaper cannot be relied on to keep those two
 * apart. The listing's "Not in this version" table already disclaims dynamic colour and
 * `StoreListingTest` holds it there.
 *
 * The three arguments below are the app's whole visual system, and each has its own file: see
 * `Color.kt` for the two-voices palette, `Type.kt` for why there is no bundled typeface, and
 * `Dimens.kt` for the 4dp spacing grid and the softened corners.
 */
@Composable
fun MuPlayTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = colorSchemeFor(darkTheme),
    typography = MuPlayTypography,
    shapes = MuPlayShapes,
    content = content,
  )
}
