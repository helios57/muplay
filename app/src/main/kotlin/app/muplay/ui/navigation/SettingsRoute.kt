package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The settings screen.
 *
 * `@Serializable` because `rememberNavBackStack` saves keys with `rememberSaveable`.
 *
 * Plan 6 Task 12. It exists so that renderer-direct is somewhere a user can actually reach: Task 7
 * shipped a `CastRouter` whose failure message told the user about a setting that did not exist.
 */
@Serializable
data object SettingsRoute : NavKey
