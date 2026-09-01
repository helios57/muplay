package app.muplay.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.muplay.designsystem.theme.MuPlaySpacing

/**
 * The one way this app says "nothing here", "still loading" or "that did not work".
 *
 * Written because there were four ways and three of them were a bare top-left `Text`. A
 * self-hosted player meets these states constantly and for ordinary reasons -- the server is
 * asleep, the URL has a typo, the library really is empty, the book has no chapter atom -- so they
 * are not edge cases, they are a large fraction of what a user sees. A top-left sentence in
 * `bodyMedium` reads as a rendering accident; the same sentence centred with room around it reads
 * as the app answering.
 *
 * **Deliberately not an error type.** It takes a string, because the caller knows what happened and
 * this composable's job is to look the same whoever calls it. Anything cleverer would need every
 * screen to agree on a taxonomy, which is how a shared component becomes a thing people route
 * around.
 *
 * [onRetry] is nullable rather than defaulted to a no-op: a retry button that does nothing is worse
 * than no button, and a nullable parameter makes "there is nothing useful to retry" a decision the
 * caller states rather than one it forgets.
 *
 * The text carries no `contentDescription` and no `testTag`. It is real text, so TalkBack reads it
 * and a Compose finder matches it by the same string the caller passed -- which is what keeps this
 * usable from the device journeys that find nodes by their visible words.
 */
@Composable
fun Message(
  text: String,
  modifier: Modifier = Modifier,
  loading: Boolean = false,
  onRetry: (() -> Unit)? = null,
  retryLabel: String = "Try again",
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = MuPlaySpacing.xl, vertical = MuPlaySpacing.xxl),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.lg),
  ) {
    if (loading) {
      // Sized to the text it sits above rather than the default 40dp: a spinner larger than the
      // sentence it explains reads as the subject of the screen, and it is not.
      CircularProgressIndicator(
        modifier = Modifier.size(MuPlaySpacing.xl),
        strokeWidth = 2.dp,
      )
    }
    Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (onRetry != null) {
      TextButton(onClick = onRetry) { Text(retryLabel) }
    }
  }
}
