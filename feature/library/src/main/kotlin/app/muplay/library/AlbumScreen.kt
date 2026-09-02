package app.muplay.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.component.Message
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.designsystem.theme.MuPlayTimecode
import app.muplay.model.Song

/**
 * One album, and its tracks.
 *
 * [albumId] is an ordinary parameter, not read from [AlbumViewModel]'s `SavedStateHandle` --
 * see [AlbumViewModel]'s own doc for why: verified on a real device that Navigation 3 populates
 * no such argument for a `NavKey`'s properties. [MuPlayApp][app.muplay.ui.MuPlayApp] passes the
 * `AlbumRoute` key's own `albumId` here, and the `LaunchedEffect` below is what forwards it into
 * the view model exactly once per distinct id.
 *
 * Both of those hops are gated by `AlbumRouteJourneyTest` (Tier 2) as of Task 9's review round 1
 * (N-6): it opens one album in each library and asserts each screen shows that album's own tracks
 * and none of the other's, so hardcoding the id at either hop -- here, or in `MuPlayApp`'s
 * `entry<AlbumRoute>` -- fails it. Measured, all three reverted:
 * `AlbumScreen(albumId = "al-constant")`, `viewModel.load("al-constant")` and
 * `AlbumRoute("al-constant")` each turn that journey red.
 *
 * **`LaunchedEffect(albumId)` -> `LaunchedEffect(Unit)` is an equivalent mutant here, and stays
 * `albumId` anyway.** Measured: that change passes the journey, and it must -- `AlbumRoute` is a
 * `data class` `NavKey`, so a different album is a different key, a different `NavEntry` and a
 * fresh composition, and this effect's key can never change *within* one composition under
 * `NavDisplay`. Nothing is missing from the tier; the mutation cannot change behaviour. The key is
 * still written as `albumId` because it is the value the effect depends on, and a future host that
 * did recompose this screen with a new id would silently keep showing the old album otherwise.
 *
 * ### The design pass
 *
 * The screen scrolls, which it did not before: a `Column` with no scroll modifier simply ran a
 * long album off the bottom of the phone with no way to reach the rest. The tracks themselves were
 * bare `Text`s -- a `bodyLarge` line box with `8.dp` above and below, so 40dp, under the 48dp
 * minimum -- with no numbering; they are now numbered rows of at least
 * [MuPlaySpacing.minTouchTarget], the number set in [MuPlayTimecode]'s monospaced face so a
 * two-digit track does not shove the titles sideways.
 *
 * Every visible string is unchanged, which matters more here than the layout does:
 * `AlbumRouteJourneyTest` asserts on the album name, the artist, three track titles and the exact
 * wording of [NOT_FOUND_LABEL], and `PlaybackJourneyTest` taps a track by its title.
 */
@Composable
fun AlbumScreen(
  albumId: String,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: AlbumViewModel = hiltViewModel(),
) {
  LaunchedEffect(albumId) { viewModel.load(albumId) }
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  when (uiState) {
    AlbumUiState.Loading -> Message(text = LOADING_LABEL, loading = true, modifier = modifier)
    AlbumUiState.NotFound -> Message(text = NOT_FOUND_LABEL, modifier = modifier)
    is AlbumUiState.Content -> {
      val content = uiState as AlbumUiState.Content
      // A scrolling `Column` and deliberately **not** a `LazyColumn`. An album's track list is tens
      // of rows, not thousands, so laziness buys nothing here and costs the one property four
      // `:app` journeys rely on: a `Column` composes every row whether or not it is on screen, so
      // `onNodeWithText("Track 3")` resolves the same way it did before this pass. What the screen
      // did *not* have was any way to reach a row past the fold at all -- a long book opened
      // through the album route simply ran off the bottom.
      Column(
        modifier = modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(
            start = MuPlaySpacing.gutter,
            end = MuPlaySpacing.gutter,
            top = MuPlaySpacing.md,
            bottom = MuPlaySpacing.xxl,
          ),
        verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
      ) {
        CoverArtImage(
          coverArtId = content.album.coverArtId,
          sizePx = COVER_DETAIL_PX,
          // Null: the name is rendered as text immediately below, and a graphic repeating it
          // reads the album out twice. `BookshelfScreen` makes the same call.
          contentDescription = null,
          urlProvider = viewModel::coverArtUrl,
          modifier = Modifier.size(COVER_DETAIL_DP.dp),
        )
        Text(
          text = content.album.name,
          style = MaterialTheme.typography.headlineSmall,
          modifier = Modifier.semantics { heading() },
        )
        content.album.artistName?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = MuPlaySpacing.sm),
          )
        }
        // The index is this row's position in the list being rendered, so the track that plays
        // is the track that was tapped -- see `LibraryScreen`'s own note. Play first, then
        // navigate, for the same `stateIn(WhileSubscribed)` reason.
        content.songs.forEachIndexed { index, song ->
          TrackRow(
            position = index + 1,
            song = song,
            onClick = {
              viewModel.play(index)
              onOpenPlayer()
            },
          )
        }
      }
    }
  }
}

/**
 * One track: its position in the album, then its title.
 *
 * The title is composed after the number and both merge into one node (`Modifier.clickable` merges
 * its descendants), so `onNodeWithText("Track 1")` still resolves to exactly one node per track and
 * tapping it plays that track. The number is set in [MuPlayTimecode]'s monospaced face for the
 * reason that style exists: proportional digits make a column of track numbers ragged.
 */
@Composable
private fun TrackRow(position: Int, song: Song, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .heightIn(min = MuPlaySpacing.minTouchTarget)
      .padding(vertical = MuPlaySpacing.sm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
  ) {
    Text(
      text = position.toString(),
      style = MaterialTheme.typography.bodyMedium.merge(MuPlayTimecode),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.End,
      modifier = Modifier.width(TRACK_NUMBER_WIDTH_DP.dp),
    )
    Text(
      text = song.title,
      style = MaterialTheme.typography.bodyLarge,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private const val LOADING_LABEL = "Loading…"

/**
 * Only ever shown after a lookup has actually come back empty -- see [AlbumUiState.NotFound].
 * `AlbumRouteJourneyTest` asserts this exact wording is *absent* on a healthy album, so it is a
 * contract as much as it is copy.
 */
private const val NOT_FOUND_LABEL = "That album is no longer in your library."

private const val COVER_DETAIL_PX = 512
private const val COVER_DETAIL_DP = 160
private const val TRACK_NUMBER_WIDTH_DP = 24
