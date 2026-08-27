package app.muplay.model.browse

import app.muplay.model.Song

/**
 * What a playable browse id expands to: a queue, and where in it to start.
 *
 * **No position.** Spec section 3 puts the position under `MuPlayer`'s `ForwardingPlayer` seam so
 * that no caller can set a wrong one, and this type is a caller. The index is here because Plan 4's
 * seam correction gives the index to the caller -- *"play this book"* and *"play chapter 1 from the
 * top"* are indistinguishable to a policy and obvious to whoever was tapped on.
 *
 * In `:core:model` rather than in `:core:database`, where the only thing that produces one lives,
 * for the same reason `BrowseNode` is: `:core:media` consumes it and does not depend on
 * `:core:database` for its types.
 */
data class BrowseSelection(
  val songs: List<Song>,
  val startIndex: Int,
) {
  companion object {
    /**
     * Nothing to play.
     *
     * Distinct from `null`, which every producer in this project uses for *"that id names nothing
     * playable"* -- an answer a car renders as an error rather than as an empty queue. This value
     * is for a caller that has to hand back a selection unconditionally; `PlaybackQueue.of` refuses
     * its `songs`, deliberately, so it can never reach a player.
     */
    val EMPTY: BrowseSelection = BrowseSelection(emptyList(), 0)
  }
}
