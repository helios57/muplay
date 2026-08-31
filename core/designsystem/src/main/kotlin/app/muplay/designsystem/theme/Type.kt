package app.muplay.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The whole Material 3 scale, set deliberately rather than three slots overridden and the rest left
 * at the framework's baseline.
 *
 * There is no custom typeface, and that is a decision rather than an omission: a bundled font is
 * ~300 KB in an APK whose whole point is that it is small and does one job, and a downloadable one
 * needs a provider this app has no business talking to (the listing promises "the only computer
 * MuPlay talks to is the server whose address you typed in", and a font request to Google would
 * make that sentence false). So the personality has to come out of the *scale* instead — weight,
 * size ratios and tracking — which is where the two rules below come from.
 *
 * **Rule one: display and headline are tight, small labels are open.** Titles get negative tracking
 * so a long album name sets as one confident block; `labelMedium`/`labelSmall` get generous
 * positive tracking so a section heading reads as a rule over a shelf rather than as more prose.
 * That is the only typographic contrast available without a second family, so it is used hard.
 *
 * **Rule two: nothing is set in caps.** A section heading is `Continue listening`, not
 * `CONTINUE LISTENING`, and the difference is not taste. Those strings are this app's contract with
 * its device journeys — `BookLabels.kt`'s header says so — and `text.uppercase()` would break every
 * finder while a screen reader spelled the result out letter by letter. Tracking and weight buy the
 * same eyebrow effect and cost nothing. (Tracking is applied by the style, so the semantics text is
 * untouched: `onNodeWithText("Continue listening")` still matches.)
 */
val MuPlayTypography = Typography(
  displayLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 60.sp, letterSpacing = (-0.03).em),
  displayMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 42.sp, lineHeight = 50.sp, letterSpacing = (-0.025).em),
  displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.02).em),

  headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.02).em),
  headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 33.sp, letterSpacing = (-0.018).em),
  // The now-playing title. Two lines of this over a square of artwork is the whole hierarchy of
  // `PlayerScreen`, so it is the one size in this scale chosen by looking at a real track name on a
  // real phone rather than by stepping a ratio.
  headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.015).em),

  titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.01).em),
  titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
  titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),

  bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
  bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
  bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.3.sp),

  labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
  // The eyebrow. `MuPlaySectionHeader` uses this one; the tracking is what makes a sentence-case
  // string read as a heading without changing a character of it.
  labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.9.sp),
  labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.6.sp),
)

/**
 * Clock readouts: `0:41`, `4:32`, `1:04:19`.
 *
 * Monospaced, and the reason is mechanical rather than decorative. A position readout re-renders
 * about once a second, and in a proportional face the string jitters sideways as the digits change
 * width — worst exactly where it is read most, which is a player left running on a table. A
 * monospaced face pins every digit to the same advance, so the number ticks in place.
 *
 * It carries no size of its own on purpose: every call site merges it onto a scale style
 * (`MaterialTheme.typography.labelSmall.merge(MuPlayTimecode)`), so a timecode stays the size of
 * the text around it and only changes family.
 */
val MuPlayTimecode = TextStyle(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)
