package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The first-run setup screen's destination. `@Serializable`, matching every Navigation 3 recipe
 * that uses `rememberNavBackStack`/`entryProvider`: the back stack is restored via
 * `rememberSaveable`, which needs each [NavKey] to be serializable to survive a configuration
 * change or process death.
 */
@Serializable
data object SetupRoute : NavKey
