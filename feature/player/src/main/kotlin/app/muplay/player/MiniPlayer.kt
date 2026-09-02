package app.muplay.player

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing

/**
 * The bar above the library. Occupies **nothing at all** when nothing is playing — an empty bar
 * taking 64dp off a browse screen is worse than no bar.
 *
 * Three things it does that a "now playing" strip usually does not, and each of them is a feature
 * rather than a finish:
 *
 * - The play control is an icon carrying [PLAY_LABEL]/[PAUSE_LABEL] as its `contentDescription`
 *   (see `PlayerScreen`'s header for why that is the same accessible name and not a weaker one).
 * - The bar draws a **hairline of progress along its bottom edge**. That line is the only place in
 *   the app that answers "how far into this am I" without opening anything, and it costs two
 *   pixels. It is drawn by [ProgressRule] rather than by `LinearProgressIndicator` for a semantics
 *   reason rather than a visual one: this bar's own `contentDescription` is the handle every
 *   journey uses to find it, and a describable child inside it would make
 *   `onNodeWithContentDescription` ambiguous. See that function.
 * - It **arrives and leaves**, rather than appearing between two frames. See [MiniPlayer]'s body.
 *
 * Same stateful/stateless split as `PlayerScreen`, and for the same reason: the stateless overload
 * is what `MiniPlayerTest` composes on a device without a media session.
 */
@Composable
fun MiniPlayer(
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: PlayerViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  MiniPlayer(
    uiState = uiState,
    onOpenPlayer = onOpenPlayer,
    onPlayPause = viewModel::playPause,
    modifier = modifier,
  )
}

@Composable
internal fun MiniPlayer(
  uiState: PlayerUiState,
  onOpenPlayer: () -> Unit,
  onPlayPause: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val content = uiState as? PlayerUiState.Content

  // The bar has to keep drawing the track it is carrying *while it leaves*, and by the time it is
  // leaving the state that named that track is already `NothingPlaying`. Holding the last content
  // is what makes the exit a transition rather than a disappearance. `visible` still follows the
  // live state, so this never shows a bar for a track that has stopped -- only for one frame's
  // worth of animation on its way out. `MiniPlayerTest.theBarLeavesWhenPlaybackStops` pins that.
  var lastContent by remember { mutableStateOf<PlayerUiState.Content?>(null) }
  if (content != null) {
    lastContent = content
  }

  AnimatedVisibility(
    visible = content != null,
    modifier = modifier,
    // **Height, not position.** This composable is a `Scaffold`'s `bottomBar`, so growing the bar's
    // own height is what moves the list above it out of the way; a slide would draw the bar over
    // content whose padding had already jumped. In at [ENTER_MILLIS], out slightly faster -- a
    // control that is arriving deserves to be noticed and one that is leaving does not.
    //
    // Before this the bar was swapped in and out between two frames, which on a browse screen
    // reads as the list flinching.
    enter = if (animationsEnabled()) {
      fadeIn(tween(ENTER_MILLIS)) + expandVertically(tween(ENTER_MILLIS))
    } else {
      EnterTransition.None
    },
    exit = if (animationsEnabled()) {
      fadeOut(tween(EXIT_MILLIS)) + shrinkVertically(tween(EXIT_MILLIS))
    } else {
      ExitTransition.None
    },
  ) {
    val shown = content ?: lastContent
    if (shown != null) {
      MiniPlayerBar(content = shown, onOpenPlayer = onOpenPlayer, onPlayPause = onPlayPause)
    }
  }
}

/**
 * Whether this device wants motion at all.
 *
 * "Remove animations" in Android's accessibility settings — and the developer-options animator
 * scale beside it — set `ANIMATOR_DURATION_SCALE` to `0`, and a user who has turned that on has
 * asked for it, often because motion makes them ill. Read once and remembered: it is a global
 * setting that changes about once in an install's life, and re-reading `Settings.Global` on every
 * recomposition of a bar that recomposes once a second would be a content-resolver call per frame.
 */
@Composable
private fun animationsEnabled(): Boolean {
  val resolver = LocalContext.current.contentResolver
  return remember(resolver) {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
  }
}

/** The bar itself, once there is something to put on it. */
@Composable
private fun MiniPlayerBar(
  content: PlayerUiState.Content,
  onOpenPlayer: () -> Unit,
  onPlayPause: () -> Unit,
) {
  val spoken = nowPlayingDescription(content.playback.title, content.playback.artist)

  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceContainer,
    tonalElevation = 3.dp,
  ) {
    Column {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          // The whole bar opens the player, which is what a user reaches for; the button inside it
          // keeps its own click, so tapping play does not also navigate.
          .clickable(onClick = onOpenPlayer)
          .semantics {
            contentDescription = MINI_PLAYER_LABEL
            // **`stateDescription`, and this is an accessibility bug fix rather than a nicety.**
            // A `contentDescription` on a *merging* node replaces the text of everything beneath
            // it for a screen reader, so this bar announced "Now playing, button" and a blind user
            // could not find out what was playing without opening the full player. TalkBack reads
            // the name and then the state, so this adds the track instead of competing with it --
            // and it leaves [MINI_PLAYER_LABEL], which every journey in `:app` finds this bar by
            // and which `PlaybackJourneyTest.notTheMiniPlayer` filters on, byte-for-byte
            // unchanged. Unset rather than blank when the session has named neither field, because
            // an empty state description is a pause TalkBack reads out as nothing at all.
            if (spoken != null) {
              stateDescription = spoken
            }
          }
          .padding(MuPlaySpacing.sm),
      ) {
        Artwork(
          uri = content.playback.artworkUri,
          cacheKey = content.playback.mediaId,
          // Null: the bar as a whole is already labelled, and a second description here would read
          // the same element out twice.
          contentDescription = null,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.size(THUMBNAIL_DP.dp),
        )
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(horizontal = MuPlaySpacing.md),
        ) {
          Text(
            text = content.playback.title.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = content.playback.artist.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        FilledTonalIconButton(
          onClick = onPlayPause,
          modifier = Modifier.size(MuPlaySpacing.minTouchTarget),
        ) {
          Icon(
            imageVector = if (content.playback.isPlaying) MuPlayIcons.Pause else MuPlayIcons.Play,
            contentDescription = if (content.playback.isPlaying) PAUSE_LABEL else PLAY_LABEL,
            modifier = Modifier.size(GLYPH_DP.dp),
          )
        }
      }
      // A track whose duration is not yet known reads as zero rather than as full: `x / 0` would
      // be a division by zero and `x / 0f` an Infinity, and an Infinity into a width fraction is a
      // bar that claims a track just starting is over.
      ProgressRule(
        fraction = content.playback.durationMs
          .takeIf { it > 0L }
          ?.let { content.displayPositionMs.toFloat() / it }
          ?: 0f,
      )
    }
  }
}

/**
 * What the bar tells a screen reader it is carrying, or `null` when the session has named neither
 * the track nor who made it.
 *
 * Spoken only. It is not drawn anywhere, and no journey asserts it — the two visible strings are
 * still the title and the artist in their own `Text`s, which is what `onNodeWithText` finds.
 */
private fun nowPlayingDescription(title: String?, artist: String?): String? =
  listOfNotNull(title?.takeIf(String::isNotBlank), artist?.takeIf(String::isNotBlank))
    .takeIf { it.isNotEmpty() }
    ?.joinToString(SPOKEN_SEPARATOR)

/** The bar's own accessible name, and the handle Task 10's journey taps to open the player. */
internal const val MINI_PLAYER_LABEL = "Now playing"

/** Reads as "Now playing. Fixture Title by Fixture Artist." */
private const val SPOKEN_SEPARATOR = " by "

private const val THUMBNAIL_DP = 48
private const val GLYPH_DP = 22

/**
 * Material's own "enter the screen" and "exit the screen" durations, named rather than inlined so
 * the pair reads as one decision. Out is shorter than in on purpose: a bar arriving is information
 * and a bar leaving is only tidying up.
 */
private const val ENTER_MILLIS = 220
private const val EXIT_MILLIS = 160
