package app.muplay.requests

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.designsystem.theme.MuPlaySpacing
import org.assertj.core.api.Assertions.assertThat

/**
 * Asserts that every node on the composed screen carrying a click action lays out at least
 * [MuPlaySpacing.minTouchTarget] in both directions, and names every one that does not.
 *
 * **A sweep rather than a list**, because the defect that prompted it was invisible to a list. A
 * speaker row on the cast picker measured 32.38dp for any device that reported no model, and the
 * only reason nobody saw it is that every fixture in that suite reported one. A sweep over
 * `hasClickAction()` has no fixture bias: it asks the composed tree what is tappable and measures
 * all of it, so a control added next year is covered by a test written today.
 *
 * ### Two obvious measures are wrong, and each was measured wrong rather than argued wrong
 *
 * **`touchBoundsInRoot` alone is unfalsifiable.** Compose's hit-testing grows a small pointer-input
 * area to the minimum touch target by itself, so that rectangle is almost never under 48dp whatever
 * the layout does. Measured, with a deliberate ~20dp `Text(modifier = Modifier.clickable {})`
 * injected into `SettingsRow` to falsify this sweep:
 *
 *     PROBE TINY Rect.fromLTRB(-23.5, 53.5, 102.5, 179.5)
 *
 * 126 x 126 device pixels, which at this emulator's 420dpi is exactly 48.0dp x 48.0dp -- the same
 * number being asserted, so the assertion passed over a target built to fail it. The negative left
 * edge is the tell: the rectangle had been grown outside its own parent.
 *
 * **`SemanticsNode.size` alone reports Material as broken.** On the unmerged tree the clickable
 * node of a `TextButton` measures **40.00dp** tall -- `minimumInteractiveComponentSize` reserves
 * its 48dp on an ancestor, not on the node carrying the click. Measured, on this module's own two
 * screens, seven controls at 40.00dp: `setup:test`, `setup:save`, `Cancel`,
 * `integrations:setup:BINDERY`, `Asked already`, `Play`, `Forget`. Every one of them is fine; a
 * rule that names them is a rule nobody can satisfy without abandoning Material.
 *
 * ### What is actually asserted: the bounds are big **and** nobody else is standing in them
 *
 * Expansion to 48dp is only worth anything when the space it expands into is free. That is the
 * whole difference between the two cases above and the defect that prompted this file: two 32.38dp
 * speaker rows stacked in a `Column` each grow to 48dp and then **overlap each other by ~16dp**, so
 * a third of each "target" belongs to its neighbour and neither can be hit reliably. A lone 20dp
 * control with empty space around it genuinely can be. So this sweep asserts both halves, and
 * nested targets (a button inside a clickable row) are excluded from the overlap check because
 * containment is the one overlap that is intentional.
 *
 * ### `useUnmergedTree`, and that is load-bearing too
 *
 * `Modifier.clickable` applies `semantics(mergeDescendants = true)`, so a tappable thing *inside* a
 * tappable row vanishes into its parent on the merged tree -- and a row is exactly where a
 * too-small control hides. The same falsification above reported nothing at all on the merged tree,
 * for that second, independent reason.
 *
 * The empty-sweep guard is not decoration either: a screen that failed to compose, or a state that
 * rendered no control, would otherwise pass this silently.
 *
 * ### Falsified, and what it does *not* catch
 *
 * Shortening both `SettingsRow`s to a single line with 4dp of padding -- ~32dp, the cast-picker
 * defect's own shape -- fires it, naming the pair:
 *
 *     settings:integrations and settings:requests: 7.62dp of their touch bounds is the same place
 *
 * Two things that did **not** fire, both worth knowing before trusting this:
 *
 * - **The size half has never fired**, under either mutation. Compose's automatic expansion makes
 *   a small target report 48dp on its own, so that assertion is a cheap backstop for a target whose
 *   expansion is clipped, not the gate. The overlap half is the gate.
 * - **A one-line row with 8dp padding -- 40dp -- in this `spacedBy(8.dp)` column passes.** The 4dp
 *   of expansion each side exactly meets the 8dp gap and `Rect.overlaps` is strict, so it touches
 *   without overlapping. That is the honest reading of this rule: it is not "every target is 48dp",
 *   it is "no two targets are fighting over the same pixels", which is the property a thumb
 *   actually cares about and the one Material's 40dp buttons satisfy legitimately.
 *
 * It lives in this module because this is where it was written and falsified. Porting it is a copy
 * of this one file -- there is no shared `androidTest` artifact here, deliberately.
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
