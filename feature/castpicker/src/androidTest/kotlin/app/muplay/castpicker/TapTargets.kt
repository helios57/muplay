package app.muplay.castpicker

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
 * **A sweep rather than a list**, because the defect that prompted it was invisible to a list. This
 * module is where that defect was: `DeviceRow` measured 32.38dp for any speaker that reported no
 * model, and the only reason nobody saw it is that every fixture in this suite reported one. A
 * sweep over `hasClickAction()` has no fixture bias about *which* controls it looks at -- though it
 * has one about which it can see anything wrong with, which is the last section here.
 *
 * ### Why it measures what it measures
 *
 * Both natural choices are wrong, and both were measured wrong rather than argued wrong, in
 * `:feature:requests` where this helper was first written -- see its copy for the raw numbers.
 * In short: `touchBoundsInRoot` **alone cannot fail**, because Compose grows a small pointer-input
 * area to the minimum touch target by itself; and `SemanticsNode.size` **reports Material as
 * broken**, because `minimumInteractiveComponentSize` reserves a `TextButton`'s 48dp on an
 * ancestor rather than on the node carrying the click, so every one of them measures 40.00dp.
 *
 * What survives both is the overlap check. Expansion to 48dp is only worth anything when the space
 * it expands into is free: two short rows stacked in a `Column` each grow to 48dp and then run into
 * each other, so part of each "target" belongs to its neighbour. A lone small control with room
 * around it genuinely can be hit, and Material's 40dp buttons are fine for the same reason.
 * Nested targets -- a button inside a clickable row -- are excluded, because containment is the one
 * overlap that is intentional.
 *
 * `useUnmergedTree` is load-bearing for a third, independent reason: `Modifier.clickable` applies
 * `semantics(mergeDescendants = true)`, so a tappable thing *inside* a tappable row vanishes into
 * its parent on the merged tree, and a row is exactly where a too-small control hides.
 *
 * The empty-sweep guard is not decoration either: a screen that failed to compose, or a state that
 * rendered no control, would otherwise pass this silently.
 *
 * ### Falsified against the defect itself, not against a stand-in
 *
 * Deleting the one `heightIn` line from `DeviceRow` restores the real defect exactly -- speaker rows
 * back to 32.38dp in a `spacedBy(MuPlaySpacing.md)` column -- and this sweep fires, naming the two
 * speakers:
 *
 *     Study Amp and Kitchen Display: 3.43dp of their touch bounds is the same place
 *
 * 3.43dp is small and it is the whole margin: 12dp of gap against about 7.8dp of expansion on each
 * side. A rule that only checked sizes would have called both rows 48dp and passed.
 *
 * ### It needs two short rows to see anything, and that is the fixture's job
 *
 * The first version of that fixture gave one of the two speakers a model name, which makes its row
 * two lines and tall enough to need no expansion -- so nothing could collide with it. Under the
 * identical mutation this sweep stayed **green** while
 * `CastPickerSheetTest.everySpeakerRowIsBigEnoughToTapWhetherOrNotItReportedAModel` went red at
 * 32.38dp. The fixture gates as much as the assertion does, which is the original defect's own
 * lesson arriving from the other side.
 *
 * A copy of `:feature:requests`' file of the same name. There is no shared `androidTest` artifact in
 * this build, deliberately -- see `RequestsFixtures` for the same duplication and the same reason.
 * **Copy the helper, never the falsification record**: the first port of this file renamed
 * `SettingsRow` to `DeviceRow` throughout and thereby claimed, of this module, three measurements
 * taken in another one.
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
