package app.muplay.player

import androidx.media3.common.MediaMetadata
import app.muplay.media.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared fixtures for this module's two instrumented suites.
 *
 * Every string here is **pairwise different**, and that is the point rather than tidiness: a screen
 * that rendered `artist` where `title` belongs would satisfy every "is it displayed" assertion in
 * both suites if the two fixtures shared a value. `MuPlaybackServiceTest` makes the same check
 * against the live container's own fixture, for the same reason.
 */
internal const val TRACK_TITLE = "Fixture Title"
internal const val TRACK_ARTIST = "Fixture Artist"
internal const val TRACK_ALBUM = "Fixture Album"

/**
 * A cover-art URL of the shape Navidrome really returns: a token and a per-request salt in the
 * query string. Both suites assert that **no part of this ever reaches the semantics tree** — a URL
 * that carries authentication must be handed to the image loader and to nothing else, and an
 * artwork or debug surface is the easiest place in an app to leak one.
 */
internal const val ARTWORK_TOKEN = "f1xtur3t0k3n"
internal const val ARTWORK_SALT = "f1xtur3salt"
internal const val ARTWORK_URL =
  "https://navidrome.example/rest/getCoverArt?u=fixture&t=$ARTWORK_TOKEN&s=$ARTWORK_SALT&id=al-1"

internal val PLAYING = PlaybackState(
  isPlaying = true,
  isBuffering = false,
  mediaId = "song-1",
  title = TRACK_TITLE,
  artist = TRACK_ARTIST,
  albumTitle = TRACK_ALBUM,
  artworkUri = ARTWORK_URL,
  positionMs = 61_000L,
  durationMs = 3_661_000L,
  hasNext = true,
  hasPrevious = false,
  // A song. `mediaType` and `speed` arrived with Plan 4 Task 7's per-book speed;
  // they carry no default, deliberately, so a caller cannot silently omit the
  // field that decides whether a listener gets book controls or music ones.
  mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
  speed = 1.0f,
)

internal fun content(
  playback: PlaybackState = PLAYING,
  displayPositionMs: Long = playback.positionMs,
  isScrubbing: Boolean = false,
) = PlayerUiState.Content(playback, displayPositionMs, isScrubbing)

/**
 * A hand-written [PlaybackControls] for the two suites' *stateful* cases — the ones that compose
 * `PlayerScreen()`/`MiniPlayer()`'s Hilt-bound entry points over a real [PlayerViewModel].
 *
 * Those entry points are what `:app` actually calls, and the hop they make — `uiState` out of the
 * view model and into the stateless overload, and each control back into a view-model method — is
 * covered by nothing else: the JVM tests stop at the view model, and the stateless UI cases start
 * after it. This project records "the layer at which a decision was verified versus applied" as its
 * own defect class; this fake is what lets that hop be observed where it is applied.
 *
 * `hiltViewModel()` stays the *default* argument, so production wiring is unchanged and only the
 * default expression itself goes unexercised here.
 */
internal class RecordingPlaybackControls : PlaybackControls {

  val calls = mutableListOf<String>()

  private val published = MutableStateFlow(PlaybackState.NOTHING_PLAYING)
  override val state: StateFlow<PlaybackState> = published

  fun publish(playback: PlaybackState) {
    published.value = playback
  }

  var playerIsPlaying = true

  override suspend fun connect() {
    calls += "connect"
  }

  override suspend fun isPlaying(): Boolean {
    calls += "isPlaying"
    return playerIsPlaying
  }

  override suspend fun play() {
    calls += "play"
  }

  override suspend fun pause() {
    calls += "pause"
  }

  override suspend fun next() {
    calls += "next"
  }

  override suspend fun previous() {
    calls += "previous"
  }

  override suspend fun seekTo(positionMs: Long) {
    calls += "seekTo($positionMs)"
  }

  override suspend fun retry() {
    calls += "retry"
  }
}
