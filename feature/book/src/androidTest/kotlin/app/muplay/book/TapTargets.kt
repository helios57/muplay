package app.muplay.book

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.designsystem.theme.MuPlaySpacing
import org.assertj.core.api.Assertions.assertThat

/**
 * Asserts that every node on the composed screen carrying a click action can be hit: touch bounds of
 * at least [MuPlaySpacing.minTouchTarget] in both directions, and no two non-nested targets sharing
 * any of them.
 *
 * A sweep rather than a list, so a control added next year is covered by a test written today. This
 * module has the most tappable surface in the app -- a chapter list, a shelf of books, and five
 * transport controls -- and the chapter list is the one place here where rows sit **directly on
 * each other** with no gap at all, by design (see `BookScreen`'s own note on why a list-wide
 * `verticalArrangement` was wrong for it). Zero gap is where a short row has nowhere to expand.
 *
 * ### Why it measures what it measures
 *
 * Both natural choices are wrong, and both were measured wrong rather than argued wrong, in
 * `:feature:requests` where this helper was first written -- see its copy for the raw numbers.
 * In short: `touchBoundsInRoot` **alone cannot fail**, because Compose grows a small pointer-input
 * area to the minimum touch target by itself; and `SemanticsNode.size` **reports Material as
 * broken**, because `minimumInteractiveComponentSize` reserves a `TextButton`'s 48dp on an ancestor
 * rather than on the node carrying the click, so every one of them measures 40.00dp.
 *
 * What survives both is the overlap check. Expansion to 48dp is only worth anything when the space
 * it expands into is free: two short rows stacked in a column each grow to 48dp and then run into
 * each other, so part of each "target" belongs to its neighbour. A lone small control with room
 * around it genuinely can be hit, and Material's 40dp buttons are fine for the same reason. Nested
 * targets -- a button inside a clickable row -- are excluded, because containment is the one
 * overlap that is intentional.
 *
 * `useUnmergedTree` is load-bearing for a third, independent reason: `Modifier.clickable` applies
 * `semantics(mergeDescendants = true)`, so a tappable thing *inside* a tappable row vanishes into
 * its parent on the merged tree, and a row is exactly where a too-small control hides.
 *
 * The empty-sweep guard is not decoration either: a screen that failed to compose, or a state that
 * rendered no control, would otherwise pass this silently.
 *
 * ### Falsified here
 *
 * Deleting `ChapterRow`'s `heightIn(min = MuPlaySpacing.minTouchTarget)` -- the one line that KDoc
 * says is there because the row "measured about 44dp before this pass" -- makes
 * `noTwoChapterRowsFightOverTheSamePixels` fail with the two collisions it should:
 *
 * ```
 * ["1. The Opening and 2. The Middle: 19.43dp of their touch bounds is the same place",
 *  "2. The Middle and 3. The End: 19.43dp of their touch bounds is the same place"]
 * ```
 *
 * Note the number. Without the constraint the row is a 20dp `bodyMedium` line box plus 4dp of
 * padding either side, so about 28dp, and Compose grows each one to 48dp -- roughly 10dp into each
 * neighbour, from both sides, which is the 19.43dp measured. The rows have no gap to expand into
 * because `BookScreen` deliberately gives the chapter `LazyColumn` no `verticalArrangement`.
 *
 * **The other 49 tests in this module were green over that same mutation.** Nothing here asserted
 * on a row height, so the constraint whose KDoc explains at length why it exists was, until this
 * sweep, held by nothing at all.
 *
 * A copy of `:feature:requests`' file of the same name. There is no shared `androidTest` artifact in
 * this build, deliberately. **Copy the helper, never the falsification record**: a `sed` port of
 * this file into `:feature:castpicker` once renamed its way into three measurements that were real
 * and were about another module's screens.
 */
internal fun ComposeContentTestRule.assertEveryTapTargetIsBigEnough() {
  val density = InstrumentationRegistry.getInstrumentation()
    .targetContext.resources.displayMetrics.density
  val minimumPx = MuPlaySpacing.minTouchTarget.value * density

  val tappable = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
  assertThat(tappable).describedAs("nodes with a click action on this screen").isNotEmpty()

  val tooSmall = tappable.mapNotNull { node ->
    val bounds = node.touchBoundsInRoot
    // Half a device pixel of slack, the tolerance `assertHeightIsAtLeast` itself uses: 48dp at
    // 420dpi is 126.0 pixels of intent and can measure as 125.99.
    if (bounds.width + 0.5f >= minimumPx && bounds.height + 0.5f >= minimumPx) {
      null
    } else {
      "%s: %.2fdp x %.2fdp".format(describe(node), bounds.width / density, bounds.height / density)
    }
  }

  assertThat(tooSmall)
    .describedAs(
      "every tappable node needs touch bounds of at least ${MuPlaySpacing.minTouchTarget} in both " +
        "directions -- give the row `Modifier.heightIn(min = MuPlaySpacing.minTouchTarget)` " +
        "beside its `clickable`, rather than letting its height arrive as the sum of a padding " +
        "and whatever the text happens to measure",
    )
    .isEmpty()

  val crowded = tappable.flatMapIndexed { index: Int, node: SemanticsNode ->
    tappable.drop(index + 1)
      // Same window only. A dialog composes into a root of its own and its bounds are measured
      // from that root's origin, so a button in a dialog and a row on the screen underneath are
      // two rectangles in two coordinate spaces -- and the one underneath is behind a modal scrim
      // and cannot be tapped at all. Measured, on the real settings screen with the sign-out
      // confirmation up: `Cancel` and the renderer-direct switch were reported as sharing 8.95dp,
      // which is a true statement about the pixels and says nothing about whether anything is
      // hard to hit.
      .filter { other -> other.root === node.root }
      .filterNot { other -> node.contains(other) || other.contains(node) }
      .filter { other -> node.touchBoundsInRoot.overlaps(other.touchBoundsInRoot) }
      .map { other ->
        "%s and %s: %.2fdp of their touch bounds is the same place".format(
          describe(node),
          describe(other),
          node.touchBoundsInRoot.intersect(other.touchBoundsInRoot).let {
            minOf(it.width, it.height)
          } / density,
        )
      }
  }

  assertThat(crowded)
    .describedAs(
      "two tappable nodes that are not nested may not share touch bounds -- when rows are shorter " +
        "than ${MuPlaySpacing.minTouchTarget} the expansion Compose applies for them runs into " +
        "the neighbour, so the 48dp each one reports is partly its neighbour's",
    )
    .isEmpty()
}

/** Whether [other] is this node or sits under it, i.e. the one overlap that is deliberate. */
private fun SemanticsNode.contains(other: SemanticsNode): Boolean =
  generateSequence(other) { it.parent }.any { it.id == id }

/**
 * Whatever the node or anything under it says about itself, so a failure names the row rather than
 * a node id. The descendants are searched because on the unmerged tree a clickable `Row` carries no
 * text of its own -- its children do, and those children are what a reader recognises.
 */
private fun describe(node: SemanticsNode): String =
  generateSequence(listOf(node)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }
    .flatten()
    .firstNotNullOfOrNull { candidate ->
      candidate.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        ?: candidate.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        ?: candidate.config.getOrNull(SemanticsProperties.TestTag)
    }
    ?: "node ${node.id}"
