package app.muplay.player

import app.muplay.media.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [PlayerViewModel]'s own logic, on the JVM.
 *
 * It is reachable at all because the view model is constructed over [PlaybackControls] rather than
 * over `PlaybackConnection` directly — the same seam, for the same stated reason, as
 * `:feature:library`'s `LibrarySource`/`AlbumSource` and `:feature:setup`'s
 * `SetupCredentialSink`/`SetupLibrarySink`: `PlaybackConnection` is a concrete `@Inject`-
 * constructed class that binds a `MediaController` to the main `Looper`, so it cannot be
 * subclassed into a hand-written fake and constructing the real one needs a device and a running
 * media session. This project bans mock frameworks, so a seam is the only way this class's own
 * decisions can be proved anywhere but on an emulator.
 *
 * **The seam is deliberately made of primitives, not of intentions.** `play()`, `pause()` and
 * `isPlaying()` are separate members rather than one `playPause()`, because a `playPause()` on the
 * interface would move the decision this class exists to make down into the un-testable adapter —
 * the "verified at a different layer from where it is applied" defect this project records.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

  private val playing = PlaybackState(
    isPlaying = true,
    isBuffering = false,
    mediaId = "song-1",
    title = "Track 1",
    artist = "Test Artist",
    albumTitle = "Test Album",
    artworkUri = "https://host/art-1",
    positionMs = 2_000L,
    durationMs = 5_000L,
    hasNext = true,
    hasPrevious = false,
  )

  /**
   * Records every call in order, with its argument. Order matters as much as the value: `prepare`
   * before `setMediaItems`, or `seekTo` before the finger lifts, are the shape of bug a
   * set-of-calls assertion cannot see.
   */
  private class FakePlaybackControls : PlaybackControls {
    val calls = mutableListOf<String>()

    private val published = MutableStateFlow(PlaybackState.NOTHING_PLAYING)
    override val state: StateFlow<PlaybackState> = published

    fun publish(playback: PlaybackState) {
      published.value = playback
    }

    /**
     * What the **player** answers right now, kept deliberately independent of [state]'s last
     * published snapshot so a test can set the two to disagree — see
     * `play pause asks the player what it is doing, not the last sampled snapshot`.
     */
    var playerIsPlaying = false

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
  }

  private val dispatcher = StandardTestDispatcher()

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  /**
   * [PlayerViewModel.uiState] is a `combine(...).stateIn(WhileSubscribed(..))`, so it produces
   * nothing at all without an active collector — the same reason `:feature:library`'s view-model
   * tests have a `warm` of their own.
   */
  private fun TestScope.warm(controls: FakePlaybackControls): PlayerViewModel {
    val viewModel = PlayerViewModel(controls)
    backgroundScope.launch { viewModel.uiState.collect {} }
    return viewModel
  }

  private fun PlayerViewModel.content(): PlayerUiState.Content =
    uiState.value as PlayerUiState.Content

  @Test
  fun `before anything has played the screen says nothing is playing`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)
    advanceUntilIdle()

    assertThat(viewModel.uiState.value).isEqualTo(PlayerUiState.NothingPlaying)
  }

  /**
   * Connecting is what starts the state flowing at all. Without it the screen renders
   * `NothingPlaying` forever while audio is audibly playing — a bug with no test to fail, since
   * every other assertion in this class publishes state through the fake directly.
   */
  @Test
  fun `constructing the view model connects to the session`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    warm(controls)
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("connect")
  }

  @Test
  fun `the screen renders whatever the connection reports, whole`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)

    controls.publish(playing)
    advanceUntilIdle()
    // The whole value, so no field can be dropped, defaulted or swapped on the way through.
    assertThat(viewModel.content().playback).isEqualTo(playing)

    // A second, disjoint observation: a `uiState` wired to a constant, or one that latched onto
    // the first state it saw, passes the assertion above and fails this one.
    val second = playing.copy(mediaId = "song-2", title = "Track 2", positionMs = 9_000L)
    controls.publish(second)
    advanceUntilIdle()
    assertThat(viewModel.content().playback).isEqualTo(second)
  }

  @Test
  fun `play pause pauses a playing player`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)
    controls.playerIsPlaying = true
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.playPause()
    advanceUntilIdle()

    // `containsExactly`, not `contains`: an implementation that called both would satisfy a
    // `contains("pause")` while making the button do nothing visible.
    assertThat(controls.calls).containsExactly("isPlaying", "pause")
  }

  @Test
  fun `play pause plays a paused player`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)
    controls.playerIsPlaying = false
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.playPause()
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("isPlaying", "play")
  }

  /**
   * The decision reads the **player**, not [PlaybackState.isPlaying] from the last published
   * snapshot. `PlaybackConnection` samples the position on a 250 ms ticker, so a second tap
   * arriving inside that window against a stale snapshot toggles the wrong way — the classic
   * "pause, then tap again, and it pauses again" bug.
   *
   * The two are set to disagree here, so an implementation reading `state.value.isPlaying` fails.
   */
  @Test
  fun `play pause asks the player what it is doing, not the last sampled snapshot`() =
    runTest(dispatcher) {
      val controls = FakePlaybackControls()
      val viewModel = warm(controls)
      // The snapshot still says "playing"; the player has already stopped.
      controls.publish(playing.copy(isPlaying = true))
      controls.playerIsPlaying = false
      advanceUntilIdle()
      controls.calls.clear()

      viewModel.playPause()
      advanceUntilIdle()

      assertThat(controls.calls).containsExactly("isPlaying", "play")
    }

  /**
   * Next and previous, each asserted to call *its own* transport method and not the other's. A
   * pair of one-line delegating methods is exactly where a copy-paste swap survives every
   * coverage gate: both run, both are 100% covered, and the buttons walk the queue backwards.
   */
  @Test
  fun `next and previous each ask for their own direction`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.next()
    advanceUntilIdle()
    assertThat(controls.calls).containsExactly("next")

    controls.calls.clear()
    viewModel.previous()
    advanceUntilIdle()
    assertThat(controls.calls).containsExactly("previous")
  }

  @Test
  fun `scrubbing moves the thumb and does not touch the player`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)
    controls.publish(playing)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.scrubTo(4_500L)
    advanceUntilIdle()

    assertThat(viewModel.content().displayPositionMs).isEqualTo(4_500L)
    assertThat(viewModel.content().isScrubbing).isTrue
    // Seeking on every drag pixel is what makes a seek bar stutter and the audio chatter.
    assertThat(controls.calls).isEmpty()
  }

  /**
   * A position tick arriving mid-drag must not move the thumb. This is the bug the whole
   * `displayPositionMs`/`isScrubbing` pair exists to prevent, and it is invisible in a screenshot:
   * on a device the thumb springs back to the playhead every 250 ms and the bar cannot be used.
   */
  @Test
  fun `a position tick during a drag does not move the thumb`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)
    controls.publish(playing)
    advanceUntilIdle()

    viewModel.scrubTo(4_500L)
    advanceUntilIdle()
    controls.publish(playing.copy(positionMs = 2_250L))
    advanceUntilIdle()

    assertThat(viewModel.content().displayPositionMs).isEqualTo(4_500L)
    // ...while the truth underneath is still the player's own, so releasing has something honest
    // to fall back to.
    assertThat(viewModel.content().playback.positionMs).isEqualTo(2_250L)
  }

  @Test
  fun `committing a scrub seeks to where the finger stopped`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)
    controls.publish(playing)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.scrubTo(4_500L)
    viewModel.commitScrub()
    advanceUntilIdle()
    assertThat(controls.calls).containsExactly("seekTo(4500)")

    // Two disjoint observations of the seek target: a `seekTo(4_500L)` constant, or a `seekTo` fed
    // from `playback.positionMs` instead of from the scrub, passes the first and fails this.
    controls.calls.clear()
    viewModel.scrubTo(1_200L)
    viewModel.commitScrub()
    advanceUntilIdle()
    assertThat(controls.calls).containsExactly("seekTo(1200)")
  }

  @Test
  fun `committing a scrub hands the thumb back to the player`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)
    controls.publish(playing)
    advanceUntilIdle()

    viewModel.scrubTo(4_500L)
    viewModel.commitScrub()
    advanceUntilIdle()

    assertThat(viewModel.content().isScrubbing).isFalse
    // Back on the player's own position -- not left frozen at 4_500 until the next tick, which
    // would render as a bar that jumps twice for one drag.
    assertThat(viewModel.content().displayPositionMs).isEqualTo(2_000L)
  }

  /**
   * `Slider`'s `onValueChangeFinished` fires on a plain tap as well as on a drag, and a tap that
   * moved nothing must not seek. Without the early return this seeks to whatever the previous
   * drag left behind — or, worse, to 0.
   */
  @Test
  fun `committing without a scrub touches nothing`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)
    controls.publish(playing)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.commitScrub()
    advanceUntilIdle()

    assertThat(controls.calls).isEmpty()
    assertThat(viewModel.content().isScrubbing).isFalse
  }

  /**
   * A `Slider` whose range starts at 0 cannot produce a negative, but a float-to-long conversion
   * of a value a hair under zero can, and `MediaController.seekTo` with a negative is undefined.
   */
  @Test
  fun `a scrub before the start of the track is clamped to zero`() = runTest(dispatcher) {
    val controls = FakePlaybackControls()
    val viewModel = warm(controls)
    controls.publish(playing)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.scrubTo(-40L)
    viewModel.commitScrub()
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("seekTo(0)")
  }
}
