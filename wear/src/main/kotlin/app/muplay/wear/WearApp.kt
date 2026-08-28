package app.muplay.wear

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * Placeholder. **Task 9 replaces this file entirely**; it exists so [WearActivity] compiles and so
 * that the module's Compose configuration is exercised by a real composable rather than by nothing.
 *
 * `androidx.wear.compose.material3`, not `androidx.compose.material3`: they are different artifact
 * families with the same package leaf, and a watch must use this one.
 */
@Composable
fun WearApp() {
  MaterialTheme { AppScaffold { Text(text = "MuPlay") } }
}
