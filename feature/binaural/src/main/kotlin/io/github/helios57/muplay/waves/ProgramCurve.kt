package io.github.helios57.muplay.waves

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import io.github.helios57.muplay.binaural.BinauralProfile
import io.github.helios57.muplay.binaural.ProfileTimeline
import io.github.helios57.muplay.designsystem.theme.MuPlaySpacing

/**
 * The beat over the length of a program, with the band edges drawn faintly behind it and, while
 * it plays, a marker where it is now. The editor draws the draft through the same composable, so
 * a change to a step shows its shape before it is saved.
 */
@Composable
fun ProgramCurve(
  profile: BinauralProfile,
  modifier: Modifier = Modifier,
  progress: Float? = null,
) {
  val timeline = remember(profile) { ProfileTimeline(profile) }
  val points = remember(timeline) {
    val total = timeline.totalSeconds
    List(CURVE_POINTS + 1) { i -> timeline.beatAt(total * i / CURVE_POINTS) }
  }
  val top = remember(points) { maxOf(16.0, points.max() * 1.15) }
  val measurer = rememberTextMeasurer()
  val line = MaterialTheme.colorScheme.primary
  val grid = MaterialTheme.colorScheme.outlineVariant
  val label = MaterialTheme.colorScheme.onSurfaceVariant
  val marker = MaterialTheme.colorScheme.tertiary
  val description = "Beat frequency, ${programPath(profile)}, over ${formatLength(timeline.totalSeconds)}"
  Canvas(
    modifier = modifier
      .fillMaxWidth()
      .height(MuPlaySpacing.chartHeight)
      .semantics { contentDescription = description },
  ) {
    fun y(hz: Double) = (size.height * (1 - hz / top)).toFloat()
    val dashes = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    for (edge in BAND_EDGES.filter { it < top }) {
      drawLine(grid, Offset(0f, y(edge)), Offset(size.width, y(edge)), strokeWidth = 1f, pathEffect = dashes)
      drawText(measurer, "${formatHz(edge)} Hz", Offset(4f, y(edge) - 30f), TextStyle(color = label, fontSize = 10.sp))
    }
    val path = Path()
    points.forEachIndexed { i, hz ->
      val x = size.width * i / CURVE_POINTS
      if (i == 0) path.moveTo(x, y(hz)) else path.lineTo(x, y(hz))
    }
    drawPath(path, line, style = Stroke(width = MuPlaySpacing.chartStroke.toPx()))
    if (progress != null) {
      val x = size.width * progress
      drawLine(marker, Offset(x, 0f), Offset(x, size.height), strokeWidth = MuPlaySpacing.chartMarker.toPx())
    }
  }
}

private const val CURVE_POINTS = 120
private val BAND_EDGES = listOf(4.0, 8.0, 12.0, 15.0, 30.0)
