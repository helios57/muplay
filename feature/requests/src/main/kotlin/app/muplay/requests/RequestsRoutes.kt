package app.muplay.requests

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The integrations screen: what is set up, and the form that sets one up.
 *
 * **Always registered**, because a user has to be able to turn the feature on. That is the same
 * category of thing as the server-URL field -- present because the app has to be configurable, not
 * because a feature is on -- and it is the *only* always-present piece of this plan's UI.
 *
 * Declared in this module rather than in `:app`'s `ui/navigation/`, where every other route lives.
 * `IntegrationsSection` pushes this key through `SettingsSection.Content`'s `(NavKey) -> Unit`, and
 * a section composed inside `:feature:settings` cannot name a type declared in `:app`.
 *
 * `@Serializable` because `rememberNavBackStack` saves keys with `rememberSaveable`.
 */
@Serializable
data object IntegrationsRoute : NavKey

/**
 * The requests screen: search either configured service, and see what has been asked for.
 *
 * **Registered by `:app` only while at least one service is configured**, and not
 * registered-and-hidden. A destination that exists is reachable by a restored back stack and by a
 * stale navigation event, and either would land a user who runs neither service on a screen for a
 * feature they do not have. `RequestsUiState.NotConfigured` is the same decision one layer down.
 */
@Serializable
data object RequestsRoute : NavKey
