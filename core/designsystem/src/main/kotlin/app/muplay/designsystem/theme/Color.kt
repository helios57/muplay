package app.muplay.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * MuPlay's palette. **Two voices, one chassis** — and that is a statement about the product, not a
 * decoration on it.
 *
 * This app's own store listing puts the same sentence at the top of its description: music and
 * audiobooks are *kept apart*, and a book opens in "a different instrument, not a mode" of the
 * music player. The palette says so before a word is read:
 *
 * - **`primary` is the music voice — cold light.** The blue-green of a level meter or a DAC's
 *   power LED. `PlayerScreen`, `MiniPlayer` and everything in `:feature:library` are lit by it.
 * - **`tertiary` is the audiobook voice — warm light.** A reading lamp. `BookshelfScreen`,
 *   `BookScreen` and `BookPlayerScreen` use it exactly where the music screens use `primary`:
 *   progress, the artist/chapter line, "how much is left".
 * - **The chassis is shared.** Surfaces, outlines and type are identical across both, so the two
 *   instruments never read as two apps — only as two lamps over one deck.
 *
 * The neutrals are not grey. Light mode is a **warm paper** neutral and dark mode a **cold
 * graphite** one, which is the other thing this product is for: the listing's "made for listening
 * with the screen off" is a real brief, and the dark scheme is the one most of this app's life is
 * spent in. It is built first, and the light scheme is derived from it rather than the reverse.
 *
 * Every role Material 3 defines is given a value here. The previous palette set six and let
 * `lightColorScheme()`/`darkColorScheme()` default the other thirty-odd to the framework's baseline
 * purple, which is why surfaces and containers read as the Compose template: the *accent* had been
 * changed and the *chassis* had not. Filling the scheme in is most of what this file is for.
 *
 * Contrast was checked for the pairs that carry text or icons, against WCAG AA (4.5:1) for body and
 * 3:1 for large/graphical:
 *
 * | pair                                        | ratio  |
 * |---------------------------------------------|--------|
 * | `MuPlayPrimaryLight` on `…SurfaceLight`      | ~5.5:1 |
 * | white on `MuPlayPrimaryLight`                | ~5.4:1 |
 * | `MuPlayTertiaryLight` on `…SurfaceLight`     | ~6.1:1 |
 * | `MuPlayOnSurfaceLight` on `…SurfaceLight`    | ~15:1  |
 * | `MuPlayPrimaryDark` on `…SurfaceDark`        | ~11:1  |
 * | `MuPlayTertiaryDark` on `…SurfaceDark`       | ~10:1  |
 * | `MuPlayOnSurfaceVariantDark` on `…SurfaceDark`| ~9:1  |
 *
 * Kept as top-level `val`s rather than moved inside the two schemes because `ThemeTest` names two
 * of them, and because a reviewer comparing the light and dark halves of one role wants them on
 * adjacent lines.
 */

// ---- The music voice: cold light. ----

val MuPlayPrimaryLight = Color(0xFF00695E)
val MuPlayOnPrimaryLight = Color(0xFFFFFFFF)
val MuPlayPrimaryContainerLight = Color(0xFF7FF8E4)
val MuPlayOnPrimaryContainerLight = Color(0xFF00201B)

val MuPlayPrimaryDark = Color(0xFF5EDBC7)
val MuPlayOnPrimaryDark = Color(0xFF003731)
val MuPlayPrimaryContainerDark = Color(0xFF005048)
val MuPlayOnPrimaryContainerDark = Color(0xFF7FF8E4)

// ---- Secondary: the same voice, spoken quietly. Chips, tonal buttons, inactive tracks. ----

val MuPlaySecondaryLight = Color(0xFF4A635E)
val MuPlayOnSecondaryLight = Color(0xFFFFFFFF)
val MuPlaySecondaryContainerLight = Color(0xFFCCE8E1)
val MuPlayOnSecondaryContainerLight = Color(0xFF06201C)

val MuPlaySecondaryDark = Color(0xFFB1CCC6)
val MuPlayOnSecondaryDark = Color(0xFF1C3531)
val MuPlaySecondaryContainerDark = Color(0xFF334B47)
val MuPlayOnSecondaryContainerDark = Color(0xFFCCE8E1)

// ---- The audiobook voice: warm light. ----

val MuPlayTertiaryLight = Color(0xFF7A5900)
val MuPlayOnTertiaryLight = Color(0xFFFFFFFF)
val MuPlayTertiaryContainerLight = Color(0xFFFFDF9B)
val MuPlayOnTertiaryContainerLight = Color(0xFF261A00)

val MuPlayTertiaryDark = Color(0xFFF2C14E)
val MuPlayOnTertiaryDark = Color(0xFF402D00)
val MuPlayTertiaryContainerDark = Color(0xFF5C4300)
val MuPlayOnTertiaryContainerDark = Color(0xFFFFDF9B)

// ---- Error. Material's own, unchanged: a red that means one thing everywhere. ----

val MuPlayErrorLight = Color(0xFFBA1A1A)
val MuPlayOnErrorLight = Color(0xFFFFFFFF)
val MuPlayErrorContainerLight = Color(0xFFFFDAD6)
val MuPlayOnErrorContainerLight = Color(0xFF410002)

val MuPlayErrorDark = Color(0xFFFFB4AB)
val MuPlayOnErrorDark = Color(0xFF690005)
val MuPlayErrorContainerDark = Color(0xFF93000A)
val MuPlayOnErrorContainerDark = Color(0xFFFFDAD6)

// ---- The chassis. Warm paper in light, cold graphite in dark. ----

val MuPlayBackgroundLight = Color(0xFFFBF9F5)
val MuPlayOnBackgroundLight = Color(0xFF1A1C1B)
val MuPlaySurfaceLight = Color(0xFFFBF9F5)
val MuPlayOnSurfaceLight = Color(0xFF1A1C1B)
val MuPlaySurfaceVariantLight = Color(0xFFDBE5E1)
val MuPlayOnSurfaceVariantLight = Color(0xFF3F4946)
val MuPlayOutlineLight = Color(0xFF6F7976)
val MuPlayOutlineVariantLight = Color(0xFFBFC9C5)

/** The five surface tiers a shelf needs to stack a card on a page without a border. */
val MuPlaySurfaceContainerLowestLight = Color(0xFFFFFFFF)
val MuPlaySurfaceContainerLowLight = Color(0xFFF5F3EF)
val MuPlaySurfaceContainerLight = Color(0xFFEFEDE9)
val MuPlaySurfaceContainerHighLight = Color(0xFFE9E7E3)
val MuPlaySurfaceContainerHighestLight = Color(0xFFE3E1DD)

val MuPlayInverseSurfaceLight = Color(0xFF2E312F)
val MuPlayInverseOnSurfaceLight = Color(0xFFEFF1EE)
val MuPlayInversePrimaryLight = Color(0xFF5EDBC7)
val MuPlayScrim = Color(0xFF000000)

val MuPlayBackgroundDark = Color(0xFF0E1413)
val MuPlayOnBackgroundDark = Color(0xFFDEE4E1)
val MuPlaySurfaceDark = Color(0xFF0E1413)
val MuPlayOnSurfaceDark = Color(0xFFDEE4E1)
val MuPlaySurfaceVariantDark = Color(0xFF3F4946)
val MuPlayOnSurfaceVariantDark = Color(0xFFBEC9C4)
val MuPlayOutlineDark = Color(0xFF89938F)

/**
 * The quietest rule, and also the seek bar's and `ProgressRule`'s unplayed track.
 *
 * **It is 2.00:1 against `MuPlaySurfaceDark`, and that is left as it is deliberately.** An audit
 * called the 3dp track near-invisible in dark, which is true; the fix is not, because a track has
 * two contrast obligations at once and they pull against each other. It must be visible against
 * the background *and* the played fill drawn over it must be visible against it. Measured across
 * the neutral ramp, against the three surfaces a track is actually drawn on (`surface`,
 * `surfaceContainer`, `surfaceContainerLow`) and both fills (`primary`, `tertiary`):
 *
 * | candidate | vs surface | vs surfaceContainer | vs primary | worst |
 * |-----------|-----------|---------------------|-----------|-------|
 * | `#3F4946` (this)  | 2.00 | 1.79 | 5.52 | 1.79 |
 * | `#5B6561`         | 3.08 | 2.71 | 3.57 | 2.71 |
 * | **`#646E6A`**     | 3.53 | 3.10 | 3.12 | **3.10** |
 * | `#6D7773`         | 4.02 | 3.54 | 2.74 | 2.74 |
 *
 * So exactly one value on the ramp clears 3:1 everywhere, with 0.10 to spare.
 *
 * **The light half cannot be fixed at all, and that is what settles it.** Light `surface`
 * (`#FBF9F5`) to light `primary` (`#00695E`) is only **6.28:1** end to end. A track sits between
 * them, and the two ratios multiply to roughly that total, so both reaching 3:1 needs **9:1** of
 * range. Brute-forced over all 256 greys, the best achievable worst-case in light is 3.18:1 — and
 * only at `#000000`, which is a bold rule, not a hairline. There is no light value that is both
 * compliant and a hairline.
 *
 * Taking `#646E6A` in dark alone would therefore buy a compliant dark track at the cost of a
 * divider that is 3.53:1 in dark and 1.61:1 in light — the same role reading as a crisp grid in
 * one theme and a whisper in the other. Material's own `outlineVariant` is ~1.6:1 for this reason.
 *
 * The real fix is a track role of its own, separate from the divider role, which is a colour
 * outside `ColorScheme` and the theme machinery to carry it. Worth doing; not worth doing
 * halfway. **Do not "fix" this by nudging one theme.**
 */
val MuPlayOutlineVariantDark = Color(0xFF3F4946)

val MuPlaySurfaceContainerLowestDark = Color(0xFF090F0E)
val MuPlaySurfaceContainerLowDark = Color(0xFF161D1B)
val MuPlaySurfaceContainerDark = Color(0xFF1A2120)
val MuPlaySurfaceContainerHighDark = Color(0xFF252B2A)
val MuPlaySurfaceContainerHighestDark = Color(0xFF303635)

val MuPlayInverseSurfaceDark = Color(0xFFDEE4E1)
val MuPlayInverseOnSurfaceDark = Color(0xFF2B3230)
val MuPlayInversePrimaryDark = Color(0xFF00695E)
