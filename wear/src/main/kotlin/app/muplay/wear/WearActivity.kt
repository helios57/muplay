package app.muplay.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint

/**
 * The watch's one activity.
 *
 * One, and not a navigation graph: spec section 9 fixes Navigation 3 for this app and
 * `androidx.wear.compose:compose-navigation` is built on Navigation **2**, so the watch does
 * neither. Task 9 gives [WearApp] a `List<BrowseId>` back stack and Wear Compose Material3's own
 * `SwipeToDismissBox` -- which is what predictive back *is* on a watch -- and this task deliberately
 * stops at somewhere to host it.
 */
@AndroidEntryPoint
class WearActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { WearApp() }
  }
}
