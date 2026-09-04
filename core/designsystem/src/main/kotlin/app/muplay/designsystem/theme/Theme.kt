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

/**
 * [scheme] with the **audiobook voice in the roles Material reaches for by default**.
 *
 * `Color.kt` says `primary` is the music voice and `tertiary` the audiobook one, and that the book
 * screens "use it exactly where the music screens use `primary`". Sixteen call sites across the
 * three book screens do say `tertiary` by hand. The problem is everything that never asks: a
 * `Button` takes `primary` for its container, an `OutlinedButton` and a `TextButton` take it for
 * their content, a `Switch` takes `primary`/`onPrimary` for its track and thumb, a
 * `LinearProgressIndicator` for its bar, and `surfaceTint` — which neither scheme sets, so
 * `lightColorScheme()` defaults it to `primary` — tints every elevated surface with it.
 *
 * Measured on the tree this was written against: `BookScreen`'s skip-silence `Switch` and its
 * "start again" `OutlinedButton`, and `BookPlayerScreen`'s sleep-timer `OutlinedButton`, were all
 * rendering in the *music* voice on an audiobook screen. Nothing in their source names a colour,
 * which is why reading the book screens does not reveal it.
 *
 * **Remapping rather than recolouring is the point.** Only the four `primary` roles and
 * `surfaceTint` move; the chassis (surfaces, outlines, error, typography) is untouched, because
 * "the two instruments never read as two apps" is the other half of the same claim. The `tertiary`
 * roles are left exactly as they are, so the sixteen call sites that already had it right do not
 * shift underneath this.
 *
 * A plain function over a plain value, not a `@Composable`, for the reason [colorSchemeFor] gives:
 * it is then held by `BookVoiceTest` on the fast tier.
 */
internal fun bookVoiceScheme(scheme: ColorScheme): ColorScheme = scheme.copy(
  primary = scheme.tertiary,
  onPrimary = scheme.onTertiary,
  primaryContainer = scheme.tertiaryContainer,
  onPrimaryContainer = scheme.onTertiaryContainer,
  surfaceTint = scheme.tertiary,
)

/**
 * Wraps [content] so that **every** Material default inside it speaks the audiobook voice — see
 * [bookVoiceScheme] for what that means and which components were getting it wrong.
 *
 * Every audiobook screen belongs inside one of these. Doing it per call site is what produced the
 * leak: the explicit `tertiary` arguments are correct and were never the problem, but each is a
 * thing somebody has to remember, and the next component added to a book screen will not have it.
 * Here the default is right and an exception has to be written down, which is the way round that
 * survives.
 */
@Composable
fun BookVoice(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = bookVoiceScheme(MaterialTheme.colorScheme),
    typography = MaterialTheme.typography,
    shapes = MaterialTheme.shapes,
    content = content,
  )
}
