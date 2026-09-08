package io.github.helios57.muplay.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.helios57.muplay.designsystem.theme.MuPlaySpacing
import io.github.helios57.muplay.model.LibraryRole

/**
 * The library selector.
 *
 * **`FlowRow`, not `Row`.** A `Row` clips rather than wraps, and library names here are not the
 * app's to choose -- they are whatever the person who set the server up typed, and the CI
 * container's two seeded ones ("Music", "Audiobooks") are the short case rather than the
 * representative one. A clipped chip is a library the user cannot select at all, on the one screen
 * whose job is selecting one.
 *
 * **One row, three tabs.** Albums, folders and playlists all draw this, because "which library am I
 * browsing" is one question with one answer -- `LibrarySelection` is a `@Singleton` for the same
 * reason -- and a second copy of this composable is how two tabs end up disagreeing about which
 * chip is lit. It used to be private to the albums screen, which is why the folders and playlists
 * tabs had no way to be filtered at all.
 *
 * **A chip is tinted by what its library holds, which is the tint the user chose in setup.**
 * `Color.kt` reserves `primary` for music and `tertiary` for audiobooks, and setup teaches that
 * pairing directly: its two chips are literally labelled "Tag as Music" (`primaryContainer`) and
 * "Tag as Audiobooks" (`tertiaryContainer`). This selector was a stock `FilterChip` with no
 * colours at all, so the first thing a user saw after being taught the pairing was the same
 * library in neither colour -- while the `Books` card a few rows below it *is* `tertiaryContainer`.
 * The continuity was closed for the door out of the music half and left open for the selector.
 *
 * Only the *selected* colours are named. An unselected chip is an outline in both voices, and
 * tinting the outlines would turn a row of libraries into a colour key nobody asked for.
 *
 * Callers draw this behind [LibraryFilterState.offersChoice] rather than it returning early on its
 * own: a screen that lays its rows out in a `LazyColumn` needs to know whether to emit an `item` at
 * all, and a composable that renders nothing still costs that item its inter-row spacing.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun LibraryChips(
  state: LibraryFilterState,
  onLibrarySelected: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
    modifier = modifier.fillMaxWidth(),
  ) {
    state.libraries.forEach { library ->
      val audiobooks = library.role == LibraryRole.AUDIOBOOKS
      FilterChip(
        selected = library.id == state.selectedLibraryId,
        onClick = { onLibrarySelected(library.id) },
        label = { Text(library.name) },
        colors = FilterChipDefaults.filterChipColors(
          selectedContainerColor = if (audiobooks) {
            MaterialTheme.colorScheme.tertiaryContainer
          } else {
            MaterialTheme.colorScheme.primaryContainer
          },
          selectedLabelColor = if (audiobooks) {
            MaterialTheme.colorScheme.onTertiaryContainer
          } else {
            MaterialTheme.colorScheme.onPrimaryContainer
          },
        ),
      )
    }
  }
}
