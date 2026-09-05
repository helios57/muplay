package app.muplay.designsystem.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.muplay.designsystem.theme.MuPlaySpacing

/**
 * The A–Z strip down the right edge of a long list. Drag it, or tap a letter, to jump.
 *
 * [buckets] comes from [fastScrollBuckets] over the labels the list is showing, in the order it is
 * showing them, and [onBucketSelected] is handed the bucket whose [FastScrollBucket.itemIndex] is an
 * index **into that label list** — not into the `LazyColumn`, which usually has header items of its
 * own. Converting one to the other is the caller's job because only the caller knows what else is
 * in its list.
 *
 * Renders nothing at all when [offersFastScroll] says a bar here would be furniture or would lie.
 * That check is inside rather than at every call site: a screen's list changes shape as the user
 * searches and syncs, so "is this list long, varied and alphabetical" is a question that has to be
 * asked on every composition, and four screens each remembering to ask is four chances to forget.
 *
 * ### One pointer region, not one clickable letter per row
 *
 * The whole strip is a single `pointerInput`. Twenty-seven `Modifier.clickable` letters would be
 * the obvious build and it is wrong twice over. A drag is the gesture this control is for — a fast
 * scroll you have to tap twenty-seven times is not fast — and separate clickables cannot see a
 * drag that crosses between them. And this repository's tap-target sweeps assert that no two
 * non-nested clickable nodes' touch bounds overlap, which twenty-seven ~14dp rows in a column would
 * violate by construction: Compose grows each one to a 48dp minimum touch target and they then all
 * collide. One region has one set of bounds and is the honest description of one gesture.
 *
 * Down and drag are handled by the same code path, so a tap is just a drag that did not move. The
 * repeated-jump guard is what makes a drag usable: a finger moving one pixel would otherwise ask the
 * list to scroll on every pointer event to the place it is already at.
 *
 * ### It is deliberately invisible to TalkBack
 *
 * `clearAndSetSemantics {}` with an empty block removes the letters from the semantics tree. Two
 * reasons, and neither is "accessibility is not worth it". A screen reader user reaches a list item
 * by swiping or by heading navigation, and this control cannot be operated that way at all — it
 * answers raw pointer positions, so touch exploration reads a letter out and then does nothing when
 * you double-tap it. Leaving it in the tree adds twenty-seven single-letter nodes in front of every
 * row of the list, which is worse than absent. The second reason is measured rather than argued:
 * `:app`'s journeys and `StoreScreenshotsTest` find nodes by their visible text, and a screen
 * carrying a bare `A`, `B`, `C` … is a screen where several of those finders become ambiguous.
 *
 * A test tag is the one thing left in that node, and it is not a hole in the reasoning above: a tag
 * is not spoken, not matched by any text finder, and carries no string a journey could collide
 * with. It is there so a device test can assert this control appears and disappears when it should,
 * which is otherwise unobservable precisely because the control is invisible to semantics.
 */
@Composable
fun FastScrollBar(
  buckets: List<FastScrollBucket>,
  itemCount: Int,
  onBucketSelected: (FastScrollBucket) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!offersFastScroll(itemCount = itemCount, buckets = buckets)) return

  var heightPx by remember { mutableIntStateOf(0) }
  var activeIndex by remember { mutableIntStateOf(NOTHING_SELECTED) }
  // The gesture loop outlives recompositions -- `pointerInput` restarts only when `buckets` changes
  // -- so it must not close over the lambda it was composed with, or a jump would be delivered to
  // whichever screen state was current when the finger first went down.
  val select by rememberUpdatedState(onBucketSelected)

  Surface(
    shape = MaterialTheme.shapes.large,
    color = if (activeIndex == NOTHING_SELECTED) {
      MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    } else {
      MaterialTheme.colorScheme.surfaceContainerHighest
    },
    modifier = modifier
      .fillMaxHeight()
      .padding(vertical = MuPlaySpacing.sm)
      .width(FAST_SCROLL_BAR_WIDTH)
      .onSizeChanged { heightPx = it.height }
      .clearAndSetSemantics { testTag = FAST_SCROLL_BAR_TAG }
      .pointerInput(buckets) {
        awaitEachGesture {
          var lastIndex = NOTHING_SELECTED
          fun jumpTo(y: Float) {
            val index = bucketIndexAt(y = y, heightPx = heightPx, bucketCount = buckets.size)
            if (index == lastIndex) return
            lastIndex = index
            activeIndex = index
            select(buckets[index])
          }

          val down = awaitFirstDown(requireUnconsumed = false)
          // Consumed so the list underneath does not also scroll: the bar is inside the same
          // `Box`, and an unconsumed drag here reaches the `LazyColumn` as a fling.
          down.consume()
          jumpTo(down.position.y)
          var change = down
          while (change.pressed) {
            change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            change.consume()
            jumpTo(change.position.y)
          }
          activeIndex = NOTHING_SELECTED
        }
      },
  ) {
    Column(
      // `SpaceEvenly` is the same division `bucketIndexAt` does, which is what makes the letter
      // under the finger the letter that answers. Any other arrangement would draw a bar whose
      // rows and whose hit regions disagree, and the disagreement would grow toward the ends.
      verticalArrangement = Arrangement.SpaceEvenly,
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxHeight(),
    ) {
      buckets.forEachIndexed { index, bucket ->
        Text(
          text = bucket.label.toString(),
          style = MaterialTheme.typography.labelSmall,
          textAlign = TextAlign.Center,
          fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
          color = if (index == activeIndex) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
    }
  }
}

/** The only mark [FastScrollBar] leaves in the semantics tree. See the KDoc above for why. */
const val FAST_SCROLL_BAR_TAG: String = "fastScrollBar"

private const val NOTHING_SELECTED = -1
private val FAST_SCROLL_BAR_WIDTH = MuPlaySpacing.xxl
