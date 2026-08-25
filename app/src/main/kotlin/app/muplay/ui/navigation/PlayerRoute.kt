package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The full-screen player's destination.
 *
 * `@Serializable` for the same reason every other route is: `rememberNavBackStack` saves the back
 * stack through `rememberSaveable`, which needs a `KSerializer` per key.
 *
 * A `data object`, not a `data class`: the player always shows whatever the session is playing, so
 * there is nothing to carry here. An id would be a second source of truth about the current track,
 * and the one that went stale would be the one on screen.
 */
@Serializable
data object PlayerRoute : NavKey
