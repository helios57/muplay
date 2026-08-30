package app.muplay.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.RequestStatus
import app.muplay.integrations.requests.RequestCandidate

/**
 * Search either configured service, and see what has been asked for.
 *
 * **Renders nothing at all when nothing is configured**, and `:app` does not register this
 * destination in that state either -- see [RequestsUiState] and [RequestsRoute] for why both, rather
 * than either alone.
 *
 * No cover art, and that is a decision rather than an omission. The search is proxied through the
 * user's *own* Lidarr or Bindery precisely so that no third party learns what they are looking for;
 * fetching `covers.openlibrary.org` straight from the phone would hand that away again, one image
 * request at a time. `RequestCandidate.coverUrl` is carried and gated at the data layer for a
 * surface that can fetch it through the user's own server.
 */
@Composable
fun RequestsScreen(
  onOpenAlbum: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: RequestsViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  RequestsScreen(
    uiState = uiState,
    onQueryChange = viewModel::search,
    onRequest = viewModel::request,
    onForget = viewModel::forget,
    onOpenAlbum = onOpenAlbum,
    modifier = modifier,
  )
}

/** The stateless half, so the screen can be composed with no Hilt graph at all. */
@Composable
internal fun RequestsScreen(
  uiState: RequestsUiState,
  onQueryChange: (String) -> Unit,
  onRequest: (RequestCandidate) -> Unit,
  onForget: (String) -> Unit,
  onOpenAlbum: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  // `NotConfigured` renders NOTHING -- not an empty state and not a prompt. See `RequestsUiState`.
  val ready = uiState as? RequestsUiState.Ready ?: return

  LazyColumn(
    modifier = modifier.fillMaxSize().testTag("requests:root").padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      OutlinedTextField(
        value = ready.query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text(searchLabel(ready.services)) },
        modifier = Modifier.fillMaxWidth().testTag("requests:search"),
      )
    }

    ready.error?.let { error ->
      item {
        Text(
          text = error,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("requests:error"),
        )
      }
    }

    if (ready.searching) {
      item { Text(text = "Searching…", style = MaterialTheme.typography.bodyMedium) }
    }

    items(ready.results, key = { "${it.service.name}:${it.externalId}" }) { candidate ->
      CandidateRow(
        candidate = candidate,
        requested = ready.hasRequested(candidate),
        onRequest = { onRequest(candidate) },
      )
    }

    // One section per configured service, so a user with both can tell the two lists apart and a
    // user with one never sees the other's heading.
    ready.services.forEach { service ->
      val rows = ready.requests.filter { it.service == service }
      item(key = "section:${service.name}") {
        Text(
          text = service.displayName,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.testTag("requests:section:${service.name}"),
        )
      }
      items(rows, key = { it.id }) { request ->
        RequestRow(request = request, onForget = { onForget(request.id) }, onOpenAlbum = onOpenAlbum)
      }
    }
  }
}

/** What the search box asks for, named after what is actually configured. */
internal fun searchLabel(services: Set<IntegrationService>): String =
  "Search " + IntegrationService.entries.filter { it in services }.joinToString(" and ") { it.displayName }

@Composable
private fun CandidateRow(candidate: RequestCandidate, requested: Boolean, onRequest: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().testTag("requests:candidate:${candidate.externalId}"),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = candidate.title, style = MaterialTheme.typography.bodyLarge)
      Text(
        text = candidate.subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    // Still a button rather than a disabled control: Lidarr recognises its own duplicate and
    // Bindery upserts, so asking twice is harmless -- the label is information, not a refusal.
    TextButton(onClick = onRequest) { Text(if (requested) "Asked already" else "Request") }
  }
}

@Composable
private fun RequestRow(request: MediaRequest, onForget: () -> Unit, onOpenAlbum: (String) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().testTag("requests:row:${request.id}"),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = request.title, style = MaterialTheme.typography.bodyLarge)
      Text(
        text = statusLabel(request.status),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("requests:status:${request.id}"),
      )
    }
    // Only an `Arrived` row can be played, because only an `Arrived` row has an album id that
    // Navidrome has actually scanned. `Imported` is the service saying the files are on disk, which
    // is a whole scan cycle away from being playable -- see `RequestStatus`.
    (request.status as? RequestStatus.Arrived)?.let { arrived ->
      TextButton(onClick = { onOpenAlbum(arrived.albumId) }) { Text("Play") }
    }
    TextButton(onClick = onForget) { Text("Forget") }
  }
}

/**
 * One line per status, in the user's terms rather than either service's.
 *
 * `Imported` and `Arrived` say different things deliberately: "the service has the files" is not
 * "you can press play", and the gap between them is a whole Navidrome scan.
 */
internal fun statusLabel(status: RequestStatus): String = when (status) {
  RequestStatus.Requested -> "Asked for."
  is RequestStatus.Downloading ->
    status.percentComplete?.let { "Downloading, $it% done." } ?: "Downloading."
  RequestStatus.Imported -> "Downloaded. Waiting for your library to pick it up."
  is RequestStatus.Arrived -> "In your library."
  is RequestStatus.Failed -> status.reason
}
