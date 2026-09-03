package app.muplay.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.component.Message
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.model.Album
import app.muplay.model.Song

@Composable
fun LibraryScreen(
  onAlbumClick: (String) -> Unit,
  onOpenPlayer: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenBookshelf: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LibraryViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  LibraryScreen(
    uiState = uiState,
    onLibrarySelected = viewModel::selectLibrary,
    onQueryChanged = viewModel::search,
    onShuffle = viewModel::shuffle,
    onRefresh = viewModel::refresh,
    onAlbumClick = onAlbumClick,
    // Play first, then navigate. The order is not cosmetic: `playShuffled` reads `uiState.value`,
    // and navigating away first is what would let `stateIn(WhileSubscribed)` drop the state out
    // from under it.
    onShuffledSongClick = { index ->
      viewModel.playShuffled(index)
      onOpenPlayer()
    },
    coverArtUrl = viewModel::coverArtUrl,
    onOpenSettings = onOpenSettings,
    onOpenBookshelf = onOpenBookshelf,
    modifier = modifier,
  )
}

/**
 * The browse screen: pick a library, search it, shuffle it, or open an album — and the one door to
 * the audiobook half of the app.
 *
 * ### What the design pass changed, and why each change is a defect rather than a preference
 *
 * **An album row is the tap target.** Every row used to be followed by a full-width
 * `Button("Open")` *underneath* it, so a shelf of four albums was four identical filled pills and
 * the row itself did nothing when tapped — which is the first thing anybody tries. The row is now
 * a tonal card with `Modifier.clickable`, and [OPEN_LABEL] survives as a quiet word at its trailing
 * edge in the accent colour. That word is deliberate and not decoration: it is the string five
 * `:app` journeys and the store-screenshot generator find an album row by, and it also tells a
 * first-time user what the row does. Keeping the label while moving the action is what let this
 * change land without editing a single test.
 *
 * **`Books` is a door, painted in the audiobook voice.** It used to be a full-width
 * `OutlinedButton`, which on a phone reads as a disabled section header — a hairline rectangle with
 * a word centred in it. It is now a `tertiaryContainer` card with a line of supporting copy, and
 * `tertiary` is the one colour on this screen that is not `primary`. That is the palette's own
 * semantics doing real work rather than being decorative: `Color.kt` reserves `primary` for music
 * and `tertiary` for audiobooks, so the single control that leaves the music half announces where
 * it goes before it is read.
 *
 * **Everything is on the scale.** `MuPlaySpacing` (including the shared [MuPlaySpacing.gutter]),
 * `MuPlayShapes` and `Type.kt`'s styles, where this file previously wrote `16.dp` and `8.dp` by
 * hand and set no styles at all. Every tappable row is now at least
 * [MuPlaySpacing.minTouchTarget]; a shuffled row was previously a `bodyLarge` line box (24dp) with
 * `8.dp` above and below it, which is 40dp -- under the 48dp both Android's accessibility guidance
 * and Material's own `minimumInteractiveComponentSize` ask for.
 *
 * ### The strings on this screen are a contract, and the layout around them is not
 *
 * `BrowseJourneyTest`, `AlbumRouteJourneyTest`, `ScopedShuffleJourneyTest`, `FirstRunJourneyTest`,
 * `PlaybackJourneyTest`, `TranscodeSeekJourneyTest`, `AudiobookResumeJourneyTest` and
 * `StoreScreenshotsTest` all find nodes here by exact visible text. Two of them go further and read
 * *positions*: `PlaybackJourneyTest.openTheMusicAlbum` pairs an [OPEN_LABEL] node with the album
 * name nearest it vertically, and its `shuffledRows` takes every clickable text node between the
 * [SHUFFLE_HEADING] heading and the topmost [OPEN_LABEL] to be a shuffled row.
 *
 * Both survive this pass because of one property worth stating: **`Modifier.clickable` merges its
 * descendants' semantics** (measured in this repository — see `PlaybackJourneyTest.shuffledRows`'s
 * note about the mini player). So a clickable row is *one* node carrying every string inside it,
 * which means an album row still resolves to exactly one [OPEN_LABEL] node, that node is now
 * centred on the album name rather than a row below it (the pairing distance goes to zero rather
 * than merely staying small), and a shuffled row still answers `SemanticsProperties.Text.first()`
 * with its title because the title is composed first.
 */
@Composable
private fun LibraryScreen(
  uiState: LibraryUiState,
  onLibrarySelected: (Int) -> Unit,
  onQueryChanged: (String) -> Unit,
  onShuffle: () -> Unit,
  onRefresh: () -> Unit,
  onAlbumClick: (String) -> Unit,
  onShuffledSongClick: (Int) -> Unit,
  coverArtUrl: suspend (String, Int) -> String,
  onOpenSettings: () -> Unit,
  onOpenBookshelf: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // ONE `LazyColumn` for the whole screen, and this is a defect fix rather than a refactor.
  //
  // The header used to be a `Column` -- chips, search, Shuffle, Books, the maintenance row -- with
  // the album `LazyColumn` nested at the bottom, and the shuffled tracks rendered into that outer
  // `Column` with `forEachIndexed`. A shuffle draws up to `DEFAULT_SHUFFLE_SIZE` = 100 rows of
  // ~48dp, so one tap on "Shuffle this library" pushed roughly ninety rows and every album off the
  // bottom of a container that **could not scroll**. The library became unreachable until the user
  // navigated away and back.
  //
  // Nothing saw it. The seeded corpus is four tracks, so a shuffle here draws four rows and fits --
  // which is why no journey and no store screenshot has ever rendered the broken case. It is a
  // defect that only exists on a real library.
  //
  // Everything is an `item` now, so the header scrolls away with the content. That is the second
  // benefit: the primary action used to sit under ~300dp of always-visible furniture, at the far
  // end of a thumb's reach on the one screen used while walking.
  LazyColumn(
    modifier = modifier.padding(
      start = MuPlaySpacing.gutter,
      end = MuPlaySpacing.gutter,
      top = MuPlaySpacing.md,
    ),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
    contentPadding = PaddingValues(bottom = MuPlaySpacing.lg),
  ) {
    when (uiState) {
      LibraryUiState.Loading -> item { Message(text = LOADING_LABEL, loading = true) }
      LibraryUiState.NoLibraries ->
        // Distinct from "this library is empty": the fix is finishing setup, not syncing.
        item { Message(text = NO_LIBRARIES_LABEL) }
      is LibraryUiState.Content -> {
        // `FlowRow`, not `Row`. A `Row` clips rather than wraps, and library names here are not
        // the app's to choose -- they are whatever the person who set the server up typed, and this
        // container's two seeded ones ("Music", "Audiobooks") are the short case rather than the
        // representative one. A clipped chip is a library the user cannot select at all, on the one
        // screen whose job is selecting one.
        item { LibraryChips(uiState, onLibrarySelected) }

        item {
          OutlinedTextField(
            value = uiState.query,
            onValueChange = onQueryChanged,
            label = { Text(SEARCH_LABEL) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
          )
        }

        // The primary action, and the only filled control on the screen: full width so its label
        // sets on one line. It used to be a third of a row, which is what wrapped
        // "Shuffle this library" onto two lines in the published store screenshot.
        item {
          Button(
            onClick = onShuffle,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(vertical = MuPlaySpacing.md),
            modifier = Modifier.fillMaxWidth().heightIn(min = MuPlaySpacing.minTouchTarget),
          ) {
            Text(text = SHUFFLE_LABEL, style = MaterialTheme.typography.titleSmall)
          }
        }

        // Plan 4 Task 9. The only route to the audiobook shelf, and therefore to the whole
        // audiobook engine -- which shipped complete, gated, and reachable from no screen at all.
        //
        // `onClick = onOpenBookshelf` and not `onClick = { onOpenBookshelf() }`: a lambda body is
        // a line this module's LINE floor then requires a *click* to cover, and no journey on the
        // browse screen clicks it. The card renders on every journey that reaches this screen, so
        // the lines it adds are covered lines.
        item {
          Surface(
            onClick = onOpenBookshelf,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier
                .heightIn(min = MuPlaySpacing.minTouchTarget)
                .padding(horizontal = MuPlaySpacing.lg, vertical = MuPlaySpacing.md),
              verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs),
            ) {
              Text(text = BOOKS_LABEL, style = MaterialTheme.typography.titleMedium)
              Text(text = BOOKS_SUPPORTING_LABEL, style = MaterialTheme.typography.bodySmall)
            }
          }
        }

        // Maintenance, not navigation: the two things a user does to the app rather than to the
        // music. Text buttons, so they sit under the two doors above without competing with them.
        item {
          Row(horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
            // The only way a user has to pick up a change made on the server after the app started.
            TextButton(onClick = onRefresh) { Text(REFRESH_LABEL) }
            // Plan 6 Task 12. The only route to the settings screen, and therefore the only way a
            // user reaches the renderer-direct switch.
            TextButton(onClick = onOpenSettings) { Text(SETTINGS_LABEL) }
          }
        }

        // `onSurfaceVariant`, not `error`. All four of this string's values are *states* -- checking,
        // the server is mid-scan, the server was unreachable, or nothing to say -- and three of them
        // are ordinary. Painting "the server is scanning" red tells the user something is broken
        // when nothing is.
        uiState.syncMessage?.let {
          item {
            Text(
              text = it,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        if (uiState.shuffled.isNotEmpty()) {
          item { SectionHeader(SHUFFLE_HEADING) }
          // `itemsIndexed`, and the index is the row's own position in the very list this screen
          // is rendering -- so "the third row" and "the third song of the shuffle" cannot drift
          // apart. Passing `song.id` and having the view model look it up again would be a second
          // lookup to get wrong.
          //
          // Keyed on the id, and the key is prefixed. A `LazyColumn` key must be unique across the
          // WHOLE list, and this list also renders albums; a bare id would collide the moment a
          // track and an album shared one. That exact collision crashed the requests screen once
          // (`Key "LIDARR:mb-album-1" was already used`), so the prefixes are not decoration.
          itemsIndexed(uiState.shuffled, key = { _, song -> "shuffled:" + song.id }) { index, song ->
            ShuffledRow(song = song, onClick = { onShuffledSongClick(index) })
          }
          if (uiState.discardedOutOfScope > 0) {
            item {
              Text(
                text = "${uiState.discardedOutOfScope} tracks were outside this library and were skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        if (uiState.albums.isEmpty()) {
          item { Text(text = EMPTY_LIBRARY_LABEL, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
          items(uiState.albums, key = { "album:" + it.id }) { album ->
            AlbumRow(album = album, coverArtUrl = coverArtUrl, onClick = { onAlbumClick(album.id) })
          }
        }
      }
    }
  }
}

/** The library selector. Wraps, because a library's name is whoever-set-the-server-up's business. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LibraryChips(uiState: LibraryUiState.Content, onLibrarySelected: (Int) -> Unit) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
    modifier = Modifier.fillMaxWidth(),
  ) {
    uiState.libraries.forEach { library ->
      FilterChip(
        selected = library.id == uiState.selectedLibraryId,
        onClick = { onLibrarySelected(library.id) },
        label = { Text(library.name) },
      )
    }
  }
}

/**
 * An eyebrow over a section: sentence case, `labelMedium`, wide tracking, muted — the same rule
 * `BookshelfScreen` follows, so the two shelves read as one app.
 *
 * `heading()`, so TalkBack's heading navigation can jump the shuffle result rather than swiping
 * through every row above it. **Not** `text.uppercase()`: [SHUFFLE_HEADING] is what
 * `ScopedShuffleJourneyTest` waits to appear and disappear ten times over, and transforming the
 * string would break every finder while a screen reader spelled the result out letter by letter.
 */
@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier
      .padding(top = MuPlaySpacing.sm, bottom = MuPlaySpacing.xs)
      .semantics { heading() },
  )
}

/**
 * One song of the shuffle result.
 *
 * The title is composed **first** on purpose: `Modifier.clickable` merges this row into a single
 * semantics node, and `PlaybackJourneyTest.shuffledRows` reads each row's identity as
 * `SemanticsProperties.Text.first()`. Composing the artist above the title would silently make
 * that journey assert on artist names.
 */
@Composable
private fun ShuffledRow(song: Song, onClick: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .heightIn(min = MuPlaySpacing.minTouchTarget)
      .padding(vertical = MuPlaySpacing.sm),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs),
  ) {
    Text(
      text = song.title,
      style = MaterialTheme.typography.titleSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    song.artistName?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * One album on the shelf: a card, its cover, its name over its artist, and [OPEN_LABEL].
 *
 * The click is on the `Row` inside the `Surface` rather than on the `Surface` itself only because
 * the two are equivalent here and this is the shape `BookshelfScreen` already uses.
 *
 * The cover carries no `contentDescription`: the row renders the album's name as text three
 * millimetres away, and a graphic repeating it would read the album out twice and make an
 * `onNodeWithContentDescription` in a journey ambiguous. `BookshelfScreen` and `MiniPlayer` make
 * the same call for the same reason.
 */
@Composable
private fun AlbumRow(album: Album, coverArtUrl: suspend (String, Int) -> String, onClick: () -> Unit) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .clickable(onClick = onClick)
        .heightIn(min = MuPlaySpacing.minTouchTarget)
        .padding(MuPlaySpacing.md),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
    ) {
      CoverArtImage(
        coverArtId = album.coverArtId,
        sizePx = COVER_THUMBNAIL_PX,
        contentDescription = null,
        urlProvider = coverArtUrl,
        modifier = Modifier.size(COVER_THUMBNAIL_DP.dp),
      )
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs),
      ) {
        Text(
          text = album.name,
          style = MaterialTheme.typography.titleSmall,
          maxLines = ALBUM_TITLE_LINES,
          overflow = TextOverflow.Ellipsis,
        )
        album.artistName?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      // The word, not a button. See this file's own header: the row is the target now, and this
      // is both the affordance that says so and the string every journey finds a row by.
      Text(
        text = OPEN_LABEL,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

private const val SEARCH_LABEL = "Search this library"
private const val SHUFFLE_LABEL = "Shuffle this library"

/** `internal`, not `private`: [LibraryViewModel]'s scan-in-progress message names this control, and
 *  a message that names a button by a string typed twice is a message that drifts. */
internal const val REFRESH_LABEL = "Refresh library"

/**
 * Plan 6 Task 12. The label on the only route to the settings screen.
 *
 * `internal`, like [REFRESH_LABEL], so `:app`'s journey can find the button by the same string the
 * screen renders rather than by a copy of it.
 */
internal const val SETTINGS_LABEL = "Settings"

/**
 * Plan 4 Task 9. The label on the only route to the audiobook shelf.
 *
 * `internal`, like the two above, so this module's own tests can find the button by the string the
 * screen renders. `:feature:book` declares its own `BOOKSHELF_TITLE` with the same text and they
 * are deliberately two constants: a journey duplicates a string rather than sharing it, so a
 * wording change is caught rather than silently followed.
 */
internal const val BOOKS_LABEL = "Books"

/**
 * What is behind the [BOOKS_LABEL] door, in one line.
 *
 * Every clause is a thing the audiobook half actually does — `BookScreen` lists chapters,
 * `SpeedStepper` sets the rate, and `ProgressWriter` stores the position — because a door into an
 * unvisited half of an app is exactly where a promise the app does not keep would go unnoticed.
 * The same rule `LibraryUiState.syncMessage`'s own doc states for its four wordings.
 */
private const val BOOKS_SUPPORTING_LABEL = "Chapters, playback speed, and where you left off."

private const val LOADING_LABEL = "Loading your library…"
private const val NO_LIBRARIES_LABEL =
  "No libraries yet. Finish setup to choose what each library is for."
private const val SHUFFLE_HEADING = "Shuffled"
private const val EMPTY_LIBRARY_LABEL = "Nothing here yet."
private const val OPEN_LABEL = "Open"
private const val COVER_THUMBNAIL_PX = 128
private const val COVER_THUMBNAIL_DP = 56
private const val ALBUM_TITLE_LINES = 2
