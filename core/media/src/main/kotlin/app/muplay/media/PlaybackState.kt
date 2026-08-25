package app.muplay.media

/**
 * Everything the UI needs to know about playback, as one immutable value.
 *
 * A `data class` rather than a sealed hierarchy: unlike `SetupUiState` or `SyncState`, there are no
 * mutually exclusive *shapes* here -- a player is always playing something or nothing, and
 * "nothing" is [NOTHING_PLAYING] rather than a separate type with different fields. Collected as a
 * `StateFlow` and read with `collectAsStateWithLifecycle()`, per the constraints.
 *
 * [positionMs] is a **snapshot**, refreshed on a timer by [PlaybackConnection]. Spec section 3 is
 * explicit that the UI collects the live player position and never the database at frame rate; this
 * is the live player position, sampled.
 *
 * Nothing here is or contains a URL that carries authentication. [artworkUri] is the one URL-shaped
 * field, and a cover-art URL carries the same token and salt a stream URL does -- so it is here
 * because an image loader needs it, and it must not be logged, printed, or asserted whole. The
 * stream URI is deliberately *not* a field: the UI has no use for it, and the surest way never to
 * leak a stream URL is not to carry it to a layer whose job is to display things.
 */
data class PlaybackState(
  val isPlaying: Boolean,
  val isBuffering: Boolean,
  val mediaId: String?,
  val title: String?,
  val artist: String?,
  val albumTitle: String?,
  val artworkUri: String?,
  val positionMs: Long,
  val durationMs: Long,
  val hasNext: Boolean,
  val hasPrevious: Boolean,
) {
  companion object {
    /**
     * What the UI renders before anything has been played, and what [PlaybackConnection] resets to
     * when it releases its controller.
     *
     * Every flag is `false` and every identity is `null`, which is the only self-consistent answer:
     * a `hasNext = true` here would render an enabled "next" button with no queue behind it, and a
     * non-null [title] would name a track that is not loaded. `PlaybackStateTest` holds each of
     * those to a value rather than trusting the shape.
     */
    val NOTHING_PLAYING = PlaybackState(
      isPlaying = false,
      isBuffering = false,
      mediaId = null,
      title = null,
      artist = null,
      albumTitle = null,
      artworkUri = null,
      positionMs = 0L,
      durationMs = 0L,
      hasNext = false,
      hasPrevious = false,
    )

    /**
     * The duration a UI should render, from the two sources that can supply one.
     *
     * A plain function over two nullable `Long`s, with no Media3 type in its signature, so the fast
     * tier can hold this decision to a floor -- the same split `StreamRetryPolicy` gets from
     * `NavidromeLoadErrorHandlingPolicy`, for the same reason. Recognising `C.TIME_UNSET` is the
     * adapter's job and stays in [PlaybackConnection]; deciding what to do with the answer is this.
     *
     * **Why there are two sources at all.** The player's own duration comes from the extractor, and
     * for a stream the server transcodes on the fly there is no `Content-Length` and no duration to
     * extract -- `player.duration` is `C.TIME_UNSET` for the whole track. That is not hypothetical:
     * it is what Navidrome does for an Ogg/Opus source, which is the one format this app always
     * transcodes. The mirrored `Song` knows the length, so `MediaItem`'s own metadata can carry it
     * and is the answer when the extractor has none.
     *
     * The player wins when it has an answer, because it measured the media that is actually
     * playing; the metadata is what the server said about it, which can be stale or wrong.
     *
     * Zero is the floor, and it is what "unknown" collapses to. A seek bar rendering a negative
     * sentinel as a length is worse than one rendering nothing.
     */
    fun durationMsOf(playerDurationMs: Long?, metadataDurationMs: Long?): Long =
      (playerDurationMs ?: metadataDurationMs ?: 0L).coerceAtLeast(0L)
  }
}
