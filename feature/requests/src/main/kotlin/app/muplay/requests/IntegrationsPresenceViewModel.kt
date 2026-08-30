package app.muplay.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.integrations.requests.ConfiguredServices
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Whether this app has **any** integration configured at all.
 *
 * One boolean, for the one caller that needs it: `:app` decides from this whether the requests
 * destination is registered in the navigation graph, and a destination that exists is reachable by a
 * restored back stack whether or not anything links to it.
 *
 * It lives here rather than in `:app` deliberately. The two lines that read the store and map it are
 * the kind that measure zero on `:app`'s bundle floor -- which only the emulator job can evaluate --
 * and here they are gated on the fast tier alongside everything else this feature decides.
 *
 * The initial value is **`false`**, so the requests destination is absent for the frame before
 * DataStore has answered rather than appearing and then vanishing. Fail-closed is the right
 * direction for a destination: absent-then-present is a screen arriving, present-then-absent is a
 * `NavDisplay` with a key it has no entry for.
 */
@HiltViewModel
class IntegrationsPresenceViewModel @Inject constructor(
  services: ConfiguredServices,
) : ViewModel() {

  val anyConfigured: StateFlow<Boolean> =
    services.configured()
      .map { it.isNotEmpty() }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
