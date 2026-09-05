package app.muplay

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.designsystem.theme.MuPlaySpacing
import org.assertj.core.api.Assertions.assertThat

/**
 * Asserts that every node on the screen currently showing carries touch bounds of at least
 * [MuPlaySpacing.minTouchTarget] in both directions, and that no two non-nested targets share any
 * of them.
 *
 * The third copy of a helper that lives in `:feature:requests`, `:feature:book` and
 * `:feature:castpicker` -- read `:feature:requests`' for the measurements behind the rule, and
 * `:feature:book`'s for a falsification of it against a real row. What is different here, and the
 * reason it is worth a fourth copy rather than a fourth screen in a feature module, is the
 * **receiver**: a journey drives the assembled app through `MainActivity`, so this one hangs off
 * [SemanticsNodeInteractionsProvider] and works against any compose rule.
 *
 * That is what buys the one thing no feature module can test. The settings screen is empty by
 * design -- `:feature:settings` draws a title, a divider and nothing else, and every row on it is
 * contributed by another module's `SettingsSection`. So the question "do a destructive button, a
 * switch and an integrations row crowd each other once they are on one screen together" has no
 * home in any single module, and this is the only place all three are ever composed at their real
 * spacing.
 *
 * ### What it measured, and what it does not gate
 *
 * The cross-window filter is here because of a **false red**, measured in both directions on the
 * real screen with the sign-out confirmation up. Without it:
 *
 * ```
 * ["Let speakers stream from Navidrome directly and Cancel: 8.95dp of their touch bounds is the
 *   same place", "... and Sign out: 8.95dp ..."]
 * ```
 *
 * Both statements are true about the pixels and neither is about anything a user can mis-tap: a
 * dialog composes into a root of its own, and the switch it was reported as crowding is behind a
 * modal scrim. `theSignOutConfirmationsButtonsAreBigEnoughToTap` is what holds the filter -- delete
 * the `other.root === node.root` line and that test goes red with the message above.
 *
 * **And here is what a green from this file does not mean.** Deleting
 * `RendererDirectSwitch`'s `heightIn(min = MuPlaySpacing.minTouchTarget)` -- the real 32dp defect
 * this whole sweep was written for, on a row that is on this very screen -- leaves both tests here
 * **passing**. Measured, not assumed. The reason is slack: that row sits in a section with 8dp
 * between its own children and 16dp between it and the next section, so a shortened row expands to
 * 48dp without reaching another tappable node, and the size half of the sweep cannot fail by
 * construction (see the copies in the feature modules for why).
 *
 * So this file gates the *arrangement* of the assembled screen and nothing about any one row.
 * The row itself is held where it is drawn: `:feature:castpicker`'s `RendererDirectSectionTest`
 * asserts its height directly, and that is the test that goes red for the defect above. A sweep
 * over a screen with generous gaps is a guard against the day a section ships a short row beside
 * another one -- not a gate on the rows that are there today.
 */
internal fun SemanticsNodeInteractionsProvider.assertEveryTapTargetIsBigEnough() {
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
