package app.muplay.setup

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
 * A copy of the helper in `:feature:requests`, `:feature:book`, `:feature:castpicker` and `:app` --
 * read `:feature:requests`' for the measurements the rule is built on, `:feature:book`'s for a
 * falsification of it against a real row, and `:app`'s for what a sweep over a roomy screen does
 * **not** gate. There is no shared `androidTest` artifact in this build, deliberately.
 *
 * **Copy the helper, never the falsification record.** A `sed` port of this file once claimed, of
 * one module, three measurements taken in another. What this module measured is recorded at
 * `ServerSectionTest.everyControlOnTheServerSectionIsBigEnoughToTap` instead of here.
 *
 * **What it gates here is thin, and that is measured rather than assumed.** This section's only
 * control is a Material `OutlinedButton`, which Material sizes correctly on its own, and the
 * confirmation's two buttons are `TextButton`s in a dialog Material lays out. The sweep was written
 * to fire when a short row runs into a neighbour, and this section has neither a short row nor a
 * crowd -- so both cases here pass, and would keep passing under every single-line regression this
 * file could suffer.
 *
 * It is also **not** where the cross-root filter below earns its place, which is the claim the
 * first draft of this KDoc made. Deleting `.filter { other -> other.root === node.root }` and
 * re-running left all ten tests green: with one control behind the scrim, the dialog's buttons do
 * not happen to land on it. The filter is held by `:app`'s `ServerChangeJourneyTest`, on the
 * assembled settings screen, where there is a switch under the dialog to land on -- 8.95dp of it.
 *
 * So this file is a guard for the day this section grows a second control, not a gate on the one
 * it has. The tests that hold this section are the eight beside the sweep in [ServerSectionTest].
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
