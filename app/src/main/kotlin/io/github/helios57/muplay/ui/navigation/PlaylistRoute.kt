package io.github.helios57.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * One playlist's songs. [playlistId] is the server's own playlist id.
 *
 * Read at the `entry<PlaylistRoute> { route -> ... }` call site and passed as an ordinary
 * parameter, not through a `SavedStateHandle` -- Navigation 3 populates no argument from a key's
 * own properties. `AlbumViewModel`'s KDoc carries the crash transcript that established it.
 */
@Serializable
data class PlaylistRoute(val playlistId: String) : NavKey
