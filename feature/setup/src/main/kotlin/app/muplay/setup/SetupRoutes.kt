package app.muplay.setup

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The setup screen's destination: a server URL, a username, a password, and library tagging.
 *
 * **Declared in this module rather than in `:app`'s `ui/navigation/`, where it used to live**, for
 * the same reason `IntegrationsRoute` is declared in `:feature:requests`: [ServerSection] pushes
 * this key through `SettingsSection.Content`'s `(NavKey) -> Unit`, and a section composed inside
 * `:feature:settings` cannot name a type declared in `:app`.
 *
 * That move is what makes setup reachable a second time. Until it, this key existed only as
 * `MuPlayApp`'s start destination -- so the screen that owns the server address and the password
 * could be reached exactly once, on first run, and a user who mistyped a URL or changed servers had
 * no route to it from anywhere in the app.
 *
 * `@Serializable` because `rememberNavBackStack` saves keys with `rememberSaveable`.
 */
@Serializable
data object SetupRoute : NavKey
