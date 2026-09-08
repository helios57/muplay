package io.github.helios57.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * One folder in the library's file tree. [path] is `""` for the library root.
 *
 * A key per folder, pushed onto the back stack, so the system back gesture walks back up the tree.
 * The alternative -- one destination holding the current path in its own state -- needs a bespoke
 * "up" control, and that control is then a second answer to "where does back go" that can disagree
 * with the first.
 */
@Serializable
data class FolderRoute(val path: String) : NavKey
