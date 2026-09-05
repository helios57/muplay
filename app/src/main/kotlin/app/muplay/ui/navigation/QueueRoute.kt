package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The play queue's destination.
 *
 * A `data object` for the same reason [PlayerRoute] is one: the queue screen shows the media
 * session's timeline, so there is nothing for the key to carry and an index here would be a second
 * answer to "what is playing" — the stale one being the one on screen.
 *
 * Reached only from the player, which is where "what plays next" belongs. It is deliberately not a
 * top-level tab: a queue is a thing you look at while something is playing, and a fifth item in the
 * navigation bar would cost every browse screen a fifth of its width for a screen that says
 * "Nothing is queued yet." until the first track starts.
 */
@Serializable
data object QueueRoute : NavKey
