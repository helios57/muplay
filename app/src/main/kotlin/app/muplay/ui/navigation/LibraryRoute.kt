package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The browse screen. `@Serializable` because `rememberNavBackStack` saves keys with `rememberSaveable`. */
@Serializable
data object LibraryRoute : NavKey
