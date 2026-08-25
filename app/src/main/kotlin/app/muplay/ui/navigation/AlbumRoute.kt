package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** One album's detail screen. [albumId] is the server's own stable album id. */
@Serializable
data class AlbumRoute(val albumId: String) : NavKey
