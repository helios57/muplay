package app.muplay.media

import androidx.media3.common.MediaMetadata

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
  /**
   * `MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER` for a book, `MEDIA_TYPE_MUSIC` for a song.
   *
   * Set by `MediaItems.of` from the user's own `LibraryRole` assignment, because Navidrome
   * hardcodes `child.Type = "music"` for every media file and no server field can answer it.
   * Carried here so navigation can choose a player and the UI can choose controls without anything
   * above `:core:media` re-deriving what a book is.
   *
   * A plain `Int` rather than an enum of this module's own: it is Media3's own vocabulary, the
   * value arrives from a `MediaMetadata` and goes nowhere but a comparison, and a second spelling
   * of "audiobook" is how two screens end up disagreeing about one book.
   */
  val mediaType: Int,
  /**
   * The player's current speed. A book's, or 1.0 for anything else -- see [BookSpeedController].
   *
   * Here rather than derived in the UI because it is **player** state: the speed control reaches
   * the player through a `MediaController`, and a car or a watch can change it without this process
   * ever seeing the tap.
   */
  val speed: Float,
  /**
   * Why playback stopped, or `null` when nothing has gone wrong.
   *
   * **Defaulted, and this is the one field in this class that is.** Every production publisher
   * (`PlaybackConnection.publish`) passes it; the default exists for the dozens of hand-built
   * fixtures across `:feature:player`, `:feature:book` and `:core:media`, for which "no error" is
   * the uninteresting case and spelling it out at each one would bury the field that the fixture
   * is actually about.
   *
   * A [PlaybackFailure] and not the `PlaybackException`: the exception's message is developer text
   * and can carry a URL, and in this app a stream URL carries the auth token. See that enum.
   */
  val failure: PlaybackFailure? = null,
) {

  /**
   * Whether what is playing is a book, which is a different question from what library it came
   * from.
   *
   * **This class's first author conditional**, and it is the reason its coverage floor moved from
   * LINE to BRANCH in Plan 4 Task 7: a getter that returned `true` unconditionally would leave
   * every line covered and every floor green while sending every listener to the wrong player
   * screen.
   *
   * Both constants, not just the chapter one. `MediaItems.of` stamps
   * `MEDIA_TYPE_AUDIO_BOOK_CHAPTER` on a file inside a book, and `MEDIA_TYPE_AUDIO_BOOK` is what a
   * single-file M4B or a browse item for a whole book carries; a UI that recognised only the first
   * would render a whole-book item with music controls.
   */
  val isAudiobook: Boolean
    get() = mediaType == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER ||
      mediaType == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK

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
      // `MEDIA_TYPE_MIXED`, not `MEDIA_TYPE_MUSIC`: nothing is loaded, so "this is a song" would be
      // a claim rather than the absence of one -- and it is the claim that makes [isAudiobook]
      // false for the right reason instead of by accident.
      mediaType = MediaMetadata.MEDIA_TYPE_MIXED,
      // 1.0, because that is what a player with nothing loaded is set to. A zero here would render
      // as "0.0x" on a screen the moment it was shown, before anything had played.
      speed = 1.0f,
      // `failure` is left at its default `null`: a player with nothing loaded has not failed.
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
