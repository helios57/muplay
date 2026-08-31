package app.muplay.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The spacing scale. Six steps, and every gap and pad in the app is one of them.
 *
 * A plain `object` rather than a `CompositionLocal`, because there is exactly one theme and a local
 * would buy nothing but an indirection and a default nobody reads. Screens import it and write
 * `MuPlaySpacing.lg` where they used to write `16.dp`, which is the whole point: the numbers stop
 * being decided one screen at a time.
 *
 * The steps are 4/8/12/16/24/32 — a 4dp grid, which is what Android's own touch and density model
 * is built on. [gutter] is named rather than numbered because it is a decision, not a size: the
 * distance from a screen's content to its edge, the same on every screen, so the app has one
 * left margin.
 */
object MuPlaySpacing {
  val xs = 4.dp
  val sm = 8.dp
  val md = 12.dp
  val lg = 16.dp
  val xl = 24.dp
  val xxl = 32.dp

  /** Screen edge to content. One value, so every screen has the same margin. */
  val gutter = 20.dp

  /**
   * The smallest a control may be and still be hit reliably. Android's own accessibility guidance
   * and Material's `minimumInteractiveComponentSize` agree on 48dp; it is named here so a screen
   * that sizes a control by hand has something to size it *to* rather than a number to guess.
   */
  val minTouchTarget = 48.dp

  /** The primary transport button — play/pause, the one control a thumb finds without looking. */
  val transportPrimary = 68.dp

  /** The two controls beside it: the ±30s nudge on the book player. */
  val transportSecondary = 56.dp
}

/**
 * Softer corners than Material's baseline, top to bottom.
 *
 * Media surfaces are pictures — cover art, a shelf of books — and a picture in a sharp-cornered
 * frame reads as a database row. The `large`/`extraLarge` steps in particular are what make the
 * bookshelf read as a shelf of objects rather than as a list of strings, which is the one thing the
 * shelf screen has to do.
 */
val MuPlayShapes = Shapes(
  extraSmall = RoundedCornerShape(6.dp),
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(14.dp),
  large = RoundedCornerShape(20.dp),
  extraLarge = RoundedCornerShape(28.dp),
)
