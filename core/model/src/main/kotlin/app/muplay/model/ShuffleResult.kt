package app.muplay.model

/**
 * The outcome of a library-scoped shuffle.
 *
 * [discardedOutOfScope] is the number of songs the server returned that the local mirror does not
 * agree belong to the requested library. It is normally zero. A non-zero value means either that
 * the mirror is behind the server, or that the server's own scoping did not hold — and the two
 * are worth telling apart, which is why this is a count on the result rather than a silent
 * `filter` inside a repository.
 */
data class ShuffleResult(
  val songs: List<Song>,
  val discardedOutOfScope: Int,
)
