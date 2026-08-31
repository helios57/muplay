package app.muplay.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * MuPlay's transport iconography, drawn here rather than depended on.
 *
 * **Why these are not Material's.** `androidx.compose.material:material-icons-core` carries
 * play/pause/skip and stops there; the two glyphs an audiobook player cannot do without —
 * `Replay30` and `Forward30` — live in `material-icons-extended`, an artifact that adds several
 * thousand vectors to the build so that two of them can be used. `BookPlayerScreen`'s own header
 * records that this project rejected that dependency and shipped text buttons instead. Authoring
 * ten paths is the third answer, and it is cheaper than either: no artifact, no R8 shrink to reason
 * about, and the set is coherent because one hand drew it.
 *
 * **The one rule the set follows: transport is solid, utilities are lines.** Play, pause and the
 * two skips are filled shapes — they are the controls a thumb goes to without looking, and mass is
 * what makes them findable. Everything else (the nudge arcs, the speed stepper, the sleep moon, the
 * dismiss cross) is a 2dp stroke. So "this is the thing that starts the sound" is legible before
 * any glyph is actually read, which is the state most of this app is used in.
 *
 * **Every one of these is decorative on its own.** A control's accessible name comes from the
 * `contentDescription` its call site passes to `Icon`, and that string is always the label constant
 * the control used to render as text (`PLAY_LABEL`, `BACK_30_LABEL`, …). The icons carry no
 * semantics and no strings; they are shapes.
 *
 * Geometry is in a 24x24 viewport with the origin at the top-left and **y increasing downward**,
 * which is why [arc] and [arrowHead] look like they have their signs the wrong way round: a
 * "clockwise" sweep on screen is an *increasing* angle here.
 */
object MuPlayIcons {

  /** Filled triangle. The primary action; deliberately the heaviest shape in the set. */
  val Play: ImageVector = solid("MuPlayPlay") {
    moveTo(8f, 5.2f)
    lineTo(19f, 12f)
    lineTo(8f, 18.8f)
    close()
  }

  /** Two bars. Same visual mass as [Play], so the button does not jump when it toggles. */
  val Pause: ImageVector = solid("MuPlayPause") {
    bar(7f, 5f, 10.4f, 19f)
    bar(13.6f, 5f, 17f, 19f)
  }

  /** Next track, and next chapter — the same gesture over a different unit. */
  val SkipNext: ImageVector = solid("MuPlaySkipNext") {
    moveTo(6f, 5.5f)
    lineTo(15f, 12f)
    lineTo(6f, 18.5f)
    close()
    bar(16.2f, 5.5f, 18.6f, 18.5f)
  }

  val SkipPrevious: ImageVector = solid("MuPlaySkipPrevious") {
    moveTo(18f, 5.5f)
    lineTo(9f, 12f)
    lineTo(18f, 18.5f)
    close()
    bar(5.4f, 5.5f, 7.8f, 18.5f)
  }

  /**
   * A ring open at the top with the head on the **left**, pointing anticlockwise: wind back.
   *
   * The book player draws a small `30` inside it rather than baking the numerals into the path, so
   * the same glyph serves the nudge (thirty seconds) and `Start from the beginning` (all the way).
   * Numerals in a path would also have had to be re-drawn the day somebody makes the nudge
   * configurable.
   */
  val RotateBack: ImageVector = stroked("MuPlayRotateBack") {
    arc(GAP_END_DEG, RING_SWEEP_DEG)
    arrowHead(GAP_START_DEG, clockwise = false)
  }

  /** [RotateBack]'s mirror: the head sits on the right of the same gap and points clockwise. */
  val RotateForward: ImageVector = stroked("MuPlayRotateForward") {
    arc(GAP_END_DEG, RING_SWEEP_DEG)
    arrowHead(GAP_END_DEG, clockwise = true)
  }

  /** The `Slower` half of the speed stepper. */
  val Minus: ImageVector = stroked("MuPlayMinus") {
    moveTo(5.5f, 12f)
    lineTo(18.5f, 12f)
  }

  /** The `Faster` half. */
  val Plus: ImageVector = stroked("MuPlayPlus") {
    moveTo(5.5f, 12f)
    lineTo(18.5f, 12f)
    moveTo(12f, 5.5f)
    lineTo(12f, 18.5f)
  }

  /**
   * The sleep timer.
   *
   * A crescent cut out of a disc rather than a stroked arc, because a stroke has one thickness and
   * a moon does not — the taper is the whole reason the shape reads as a moon and not as a `C`.
   * Two full circles in one path with [PathFillType.EvenOdd] does it: the second disc subtracts
   * itself from the first.
   */
  val Moon: ImageVector = solid("MuPlayMoon", fillType = PathFillType.EvenOdd) {
    circle(12f, 12f, 8f)
    circle(17.5f, 7.5f, 7.6f)
  }

  /**
   * Sending audio somewhere else: a screen open at the corner the waves arrive from.
   *
   * The source dot is a zero-length segment with a round cap rather than a filled circle, so the
   * whole glyph stays one stroked path and matches the rest of the utility set.
   */
  val Cast: ImageVector = stroked("MuPlayCast") {
    moveTo(4f, 9.5f)
    lineTo(4f, 5f)
    lineTo(20f, 5f)
    lineTo(20f, 19f)
    lineTo(10f, 19f)
    polyline(CAST_ORIGIN_X, CAST_ORIGIN_Y, CAST_INNER_WAVE, QUARTER_START_DEG, QUARTER_SWEEP_DEG)
    polyline(CAST_ORIGIN_X, CAST_ORIGIN_Y, CAST_OUTER_WAVE, QUARTER_START_DEG, QUARTER_SWEEP_DEG)
    moveTo(CAST_ORIGIN_X, CAST_ORIGIN_Y)
    lineTo(CAST_ORIGIN_X + DOT_NUDGE, CAST_ORIGIN_Y)
  }

  /** Dismisses the sleep timer. */
  val Close: ImageVector = stroked("MuPlayClose") {
    moveTo(6.8f, 6.8f)
    lineTo(17.2f, 17.2f)
    moveTo(17.2f, 6.8f)
    lineTo(6.8f, 17.2f)
  }

  // ---- Construction ----

  private fun builder(name: String) = ImageVector.Builder(
    name = name,
    defaultWidth = ICON_DP.dp,
    defaultHeight = ICON_DP.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
  )

  /**
   * Filled black, which is never what is drawn: `Icon` paints a vector through a tint colour
   * filter, so the fill here is only a placeholder saying "this region is solid".
   */
  private fun solid(
    name: String,
    fillType: PathFillType = PathFillType.NonZero,
    body: PathBuilder.() -> Unit,
  ): ImageVector = builder(name)
    .addPath(pathData = nodesOf(body), fill = SolidColor(Color.Black), pathFillType = fillType)
    .build()

  private fun stroked(name: String, body: PathBuilder.() -> Unit): ImageVector = builder(name)
    .addPath(
      pathData = nodesOf(body),
      stroke = SolidColor(Color.Black),
      strokeLineWidth = STROKE_WIDTH,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
    )
    .build()

  private fun nodesOf(body: PathBuilder.() -> Unit) = PathBuilder().apply(body).nodes

  /** An axis-aligned rectangle, as four lines. */
  private fun PathBuilder.bar(left: Float, top: Float, right: Float, bottom: Float) {
    moveTo(left, top)
    lineTo(right, top)
    lineTo(right, bottom)
    lineTo(left, bottom)
    close()
  }

  private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    polyline(cx, cy, r, 0f, FULL_TURN)
    close()
  }

  /** The nudge ring: [RING_RADIUS] about the viewport centre. */
  private fun PathBuilder.arc(startDeg: Float, sweepDeg: Float) {
    polyline(CENTRE, CENTRE, RING_RADIUS, startDeg, sweepDeg)
  }

  /**
   * A circular arc as a polyline.
   *
   * [ARC_SEGMENTS] over a full turn puts a vertex every 10 degrees, which at the sizes these are
   * drawn at (24-32dp) is under a third of a pixel off the true curve — invisible, and it avoids
   * `arcTo`'s large-arc/sweep flag pair, whose four-way ambiguity is genuinely hard to get right by
   * inspection and impossible to check without rendering.
   */
  private fun PathBuilder.polyline(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float) {
    val steps = (ARC_SEGMENTS * sweepDeg / FULL_TURN).toInt().coerceAtLeast(2)
    for (step in 0..steps) {
      val radians = (startDeg + sweepDeg * step / steps) * PI.toFloat() / HALF_TURN
      val x = cx + r * cos(radians)
      val y = cy + r * sin(radians)
      if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
  }

  /**
   * A solid triangle on the ring at [atDeg], pointing the way travel goes.
   *
   * [clockwise] is the direction of *travel*, which is what the arrow means; on screen, with y
   * downward, that is the direction of increasing angle.
   */
  private fun PathBuilder.arrowHead(atDeg: Float, clockwise: Boolean) {
    val radians = atDeg * PI.toFloat() / HALF_TURN
    val outX = cos(radians)
    val outY = sin(radians)
    val px = CENTRE + RING_RADIUS * outX
    val py = CENTRE + RING_RADIUS * outY
    val sign = if (clockwise) 1f else -1f
    val tangentX = -outY * sign
    val tangentY = outX * sign
    val back = HEAD_SIZE * 0.35f
    moveTo(px + tangentX * HEAD_SIZE, py + tangentY * HEAD_SIZE)
    lineTo(px - tangentX * back + outX * HEAD_SIZE, py - tangentY * back + outY * HEAD_SIZE)
    lineTo(px - tangentX * back - outX * HEAD_SIZE, py - tangentY * back - outY * HEAD_SIZE)
    close()
  }

  private const val ICON_DP = 24
  private const val VIEWPORT = 24f
  private const val CENTRE = 12f
  private const val STROKE_WIDTH = 2f
  private const val FULL_TURN = 360f
  private const val HALF_TURN = 180f
  private const val ARC_SEGMENTS = 36

  /** The nudge ring, and the gap at the top of it the two arrowheads sit either side of. */
  private const val RING_RADIUS = 8f
  private const val GAP_START_DEG = 250f
  private const val GAP_END_DEG = 290f
  private const val RING_SWEEP_DEG = FULL_TURN - (GAP_END_DEG - GAP_START_DEG)
  private const val HEAD_SIZE = 2.7f

  /** [Cast]'s waves: a quarter turn out of the bottom-left corner the screen is open at. */
  private const val CAST_ORIGIN_X = 4f
  private const val CAST_ORIGIN_Y = 19f
  private const val CAST_INNER_WAVE = 3.2f
  private const val CAST_OUTER_WAVE = 6.4f
  private const val QUARTER_START_DEG = 270f
  private const val QUARTER_SWEEP_DEG = 90f

  /** Long enough to be a segment, short enough that a round cap renders it as a dot. */
  private const val DOT_NUDGE = 0.01f
}
