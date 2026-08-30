package app.muplay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.media.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Which player screen the mini player opens.
 *
 * **This exists because `MuPlayApp` had no playback state of its own.** It was a pure navigation
 * shell plus a start-destination decision, and `MiniPlayer` resolves its own `PlayerViewModel`
 * internally -- so `:app` could see that something was playing but not *what*. Plan 4 Task 9's
 * plan assumed a `playbackState` here; there was none.
 *
 * It reads [PlaybackConnection] directly rather than through a seam, unlike every view model in
 * `feature/`, and that is deliberate: there is one expression here and it is
 * `PlaybackState.isAudiobook`, which is itself gated on `:core:media`'s fast tier at BRANCH.
 * A seam would add an interface and an adapter to make a `map` testable.
 *
 * `false` is the right initial value and not merely a safe one: before anything is loaded
 * `NOTHING_PLAYING.mediaType` is `MEDIA_TYPE_MIXED`, so `isAudiobook` is false for the same reason
 * the flow will say it is.
 */
@HiltViewModel
class PlayerDestinationViewModel @Inject constructor(
  connection: PlaybackConnection,
) : ViewModel() {

  val isAudiobook: StateFlow<Boolean> = connection.state
    .map { it.isAudiobook }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = false,
    )

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
