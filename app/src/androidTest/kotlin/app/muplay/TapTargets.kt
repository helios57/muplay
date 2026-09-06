package app.muplay

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Rect
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
 *
 * ### The size half has never gated a row, and its one red was an artefact
 *
 * Compose grows a small target's touch bounds to the minimum on its own, so `touchBoundsInRoot`
 * cannot report a real row as too small: `:feature:requests`' copy measured a deliberate ~20dp
 * `Text(modifier = Modifier.clickable {})` reporting `Rect.fromLTRB(-23.5, 53.5, 102.5, 179.5)` --
 * 126x126px, exactly 48.0dp at 420dpi, with a **negative left edge** where the rectangle had been
 * grown outside its own parent.
 *
 * The only red that half has ever produced here is the one that sent this file to a snapshot:
 *
 * ```
 * Expecting empty but was: ["settings:integrations: 0.00dp x 0.00dp"]
 * ```
 *
 * in a full `:app` run, for a full-height row on a screen the same class had measured correctly --
 * and running that class alone was 5/5 green on the identical tree. So the half is kept for the one
 * thing it can still say (a control that is not on the screen at all), the snapshot below is what
 * stops it saying it about a control that merely stopped being there mid-read, and row height is
 * gated by `assertHeightIsAtLeast` in the module that draws the row.
 */
internal fun SemanticsNodeInteractionsProvider.assertEveryTapTargetIsBigEnough() {
  val density = InstrumentationRegistry.getInstrumentation()
    .targetContext.resources.displayMetrics.density
  val minimumPx = MuPlaySpacing.minTouchTarget.value * density

  val tappable = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
  assertThat(tappable).describedAs("nodes with a click action on this screen").isNotEmpty()

  // Measured **once**, here, and only for nodes still in the tree when it is read.
  //
  // `fetchSemanticsNodes()` hands back a snapshot of the tree at one instant and every geometry
  // read afterwards goes to the live layout -- so a screen still settling can drop a row in
  // between, and Compose reports no bounds at all for a node that has left. Measured in a full
  // `:app` run:
  //
  //     Expecting empty but was: ["settings:integrations: 0.00dp x 0.00dp"]
  //
  // for a full-height row on a screen the same class had just measured correctly; run alone, that
  // class was 5/5 green on the identical tree. A control that is 48dp when it is there and absent
  // when it is not was reported as one that is too small to hit, which is this repository's
  // recurring shape -- a real observation of the wrong moment.
  //
  // Reading once also closes most of the window: the crowding half below used to re-read every
  // node's bounds once per *pair*, so an n-node screen took O(n^2) reads spread over as long as
  // they took, each of them able to disagree with the last.
  // All three conditions, because the first two are not enough and that was measured rather than
  // reasoned: with only `isAttached` the same `0.00dp x 0.00dp` came back on a later run. A node
  // can be attached and not yet *placed*, and an unplaced node answers every geometry query with
  // `Rect.Zero` instead of throwing.
  //
  // Dropping an empty rectangle cannot hide a real defect, which is the thing to check before
  // adding any filter to a sweep. Compose grows a small target's touch bounds to the 48dp minimum
  // on its own -- a deliberate ~20dp control measured `Rect.fromLTRB(-23.5, 53.5, 102.5, 179.5)`,
  // exactly 48dp -- so a *placed* control cannot report zero however small it is drawn. Zero means
  // "not on the screen", and a control that is not on the screen is what the other assertions in
  // each journey are for.
  val targets = tappable
    .filter { it.layoutInfo.isAttached && it.layoutInfo.isPlaced }
    .map { Target(it, describe(it), it.touchBoundsInRoot, it.reachableTouchBounds()) }
    .filterNot { it.touch.isEmpty }
  assertThat(targets).describedAs("tappable nodes still in the tree when their bounds were read")
    .isNotEmpty()

  val tooSmall = targets.mapNotNull { target ->
    val bounds = target.touch
    // Half a device pixel of slack, the tolerance `assertHeightIsAtLeast` itself uses: 48dp at
    // 420dpi is 126.0 pixels of intent and can measure as 125.99.
    if (bounds.width + 0.5f >= minimumPx && bounds.height + 0.5f >= minimumPx) {
      null
    } else {
      "%s: %.2fdp x %.2fdp".format(target.name, bounds.width / density, bounds.height / density)
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

  val crowded = targets.flatMapIndexed { index: Int, target: Target ->
    val node = target.node
    targets.drop(index + 1).map { it.node }
      // Same window only. A dialog composes into a root of its own and its bounds are measured
      // from that root's origin, so a button in a dialog and a row on the screen underneath are
      // two rectangles in two coordinate spaces -- and the one underneath is behind a modal scrim
      // and cannot be tapped at all. Measured, on the real settings screen with the sign-out
      // confirmation up: `Cancel` and the renderer-direct switch were reported as sharing 8.95dp,
      // which is a true statement about the pixels and says nothing about whether anything is
      // hard to hit.
      .filter { other -> other.root === node.root }
      .filterNot { other -> node.contains(other) || other.contains(node) }
      .map { other -> targets.first { it.node === other } }
      .filter { other -> target.reachable.overlaps(other.reachable) }
      .map { other ->
        "%s %s and %s %s: %.2fdp of their touch bounds is the same place".format(
          target.name,
          target.reachable.dp(density),
          other.name,
          other.reachable.dp(density),
          target.reachable.intersect(other.reachable).let { minOf(it.width, it.height) } / density,
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

/**
 * One tappable node, with its geometry read at a single instant.
 *
 * The whole point is that [touch] and [reachable] are values rather than properties: read live,
 * they answer about whatever the screen is doing at the moment each assertion happens to ask, and a
 * sweep whose two halves disagree about the same node is a sweep that cannot be diagnosed. See the
 * comment where these are taken.
 */
private class Target(
  val node: SemanticsNode,
  val name: String,
  val touch: Rect,
  val reachable: Rect,
)

/**
 * The part of [SemanticsNode.touchBoundsInRoot] a finger can actually reach.
 *
 * An ancestor that clips -- a `verticalScroll` container, most often -- is not asked to deliver a
 * touch to the part of a child it has cut away, so that part is not a tap target and cannot crowd
 * one. Measured on the assembled settings screen, which is exactly such a container:
 *
 * ```
 * settings:integrations touch=[t=752.4 b=800.4] root=[t=752.4 b=786.3]
 *   and nav:tab:Albums  touch=[t=786.3 b=866.3]: 14.10dp ... is the same place
 * ```
 *
 * The settings list is a `Column(verticalScroll(..))` whose viewport ends where the navigation bar
 * begins, at 786.3. The row is a full 48dp tall and its last 14.1dp is below the fold -- drawn by
 * nobody and tappable by nobody. Reported against all four tabs at once, which is the tell: a
 * full-width row cannot be crowded *sideways* by four separate things.
 *
 * ### Why the obvious comparison does not work
 *
 * `boundsInRoot` and `boundsInWindow` are **both already clipped**, and on this row they are
 * identical (`root=[t=752.4 b=786.3] win=[t=752.4 b=786.3]`), so neither can be used as the
 * unclipped reference for the other. `touchBoundsInRoot` is the odd one out: it is grown from the
 * node's *unclipped* rect. A first attempt at this function compared root against window, found
 * them equal, trimmed nothing, and left the sweep red -- measured, not reasoned.
 *
 * What does work is [SemanticsNode.size], the node's own layout size before any ancestor clip.
 * `positionInRoot + size` is therefore the unclipped rect, and any edge where `boundsInRoot` is
 * tighter than that is an edge some ancestor cut. Only those edges are trimmed: elsewhere the
 * expansion Compose applies to a small target is left exactly as it is, because that expansion is
 * the whole subject of this sweep and trimming it would make the check unable to fail.
 *
 * Note this is deliberately **not** applied to the size half above. A row half-scrolled past the
 * fold is still big enough to hit; the reader scrolls and hits it. The two halves ask different
 * questions -- "is this target big enough" and "can these two be confused" -- and clipping is only
 * an answer to the second.
 *
 * ### Falsified, because a filter that removes a red has to be shown not to remove all of them
 *
 * Three runs on one tree, in order (and the third re-run unchanged after the geometry snapshot
 * above was introduced, since a filter is only as good as its last falsification):
 *
 *  - **without this function** the sweep is red on the clipped band above -- the false positive;
 *  - **with it** `ServerChangeJourneyTest` is 5/5 green;
 *  - **with it, and the settings column pulled into itself** (`Arrangement.spacedBy(MuPlaySpacing.lg)`
 *    -> `spacedBy(-MuPlaySpacing.xl)`, a *layout* overlap high on the screen where no clip is
 *    involved) it is red again, naming two nodes a user really could confuse:
 *
 * ```
 * Sign out [l=20.2 t=141.1 r=119.6 b=189.1] and Let speakers stream from Navidrome directly
 *   [l=20.2 t=142.1 r=391.2 b=191.2]: 47.05dp of their touch bounds is the same place
 * ```
 *
 * The middle run is the one that would have been assumed rather than measured, and the third is
 * the one that matters: a filter added to silence a red is indistinguishable from a filter that
 * silences every red until somebody makes it fire again.
 */
private fun SemanticsNode.reachableTouchBounds(): Rect {
  val clipped = boundsInRoot
  if (clipped.isEmpty) return Rect.Zero
  val touch = touchBoundsInRoot
  // Half a device pixel, the same slack the size half uses: an uncut edge can still round.
  val slack = 0.5f
  val unclipped = Rect(
    left = positionInRoot.x,
    top = positionInRoot.y,
    right = positionInRoot.x + size.width,
    bottom = positionInRoot.y + size.height,
  )
  return Rect(
    left = if (clipped.left > unclipped.left + slack) maxOf(touch.left, clipped.left) else touch.left,
    top = if (clipped.top > unclipped.top + slack) maxOf(touch.top, clipped.top) else touch.top,
    right = if (clipped.right < unclipped.right - slack) minOf(touch.right, clipped.right) else touch.right,
    bottom = if (clipped.bottom < unclipped.bottom - slack) minOf(touch.bottom, clipped.bottom) else touch.bottom,
  )
}

/**
 * A rectangle in dp, for a failure message. A reader has to be able to tell *which way* two nodes
 * overlap -- a row crowded from below by a navigation bar and one crowded sideways by its own
 * neighbour want different fixes, and the shared-dp figure alone says neither.
 */
private fun Rect.dp(density: Float): String =
  "[l=%.1f t=%.1f r=%.1f b=%.1f]".format(left / density, top / density, right / density, bottom / density)

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
