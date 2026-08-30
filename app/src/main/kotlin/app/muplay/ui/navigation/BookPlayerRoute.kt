package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The audiobook player.
 *
 * A `data object` for the same reason [PlayerRoute] is: the player shows whatever the session is
 * playing, so an id here would be a second source of truth about that and the one that went stale
 * would be the one on screen.
 *
 * Which of the two players opens is decided in `MuPlayApp` from `PlaybackState.isAudiobook`, and
 * nowhere else -- `:feature:book` and `:feature:player` do not know about each other.
 */
@Serializable
data object BookPlayerRoute : NavKey
