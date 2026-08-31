package app.muplay.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.designsystem.theme.MuPlayTimecode

/**
 * The full-screen player.
 *
 * **The transport controls are icons, and every one of them carries the label constant it used to
 * render as text.** `PLAY_LABEL`, `PAUSE_LABEL`, `NEXT_LABEL` and `PREVIOUS_LABEL` did not move and
 * did not change a character; they moved from `Text` to `Icon(contentDescription = ...)`, which is
 * the same accessible name reached through the property a screen reader actually reads for a
 * graphic control. So the Tier 2 journey still asserts the same strings — through
 * `onNodeWithContentDescription` rather than `onNodeWithText`, which is a change of *finder*, not
 * of contract. `:feature:castpicker`'s `CastButton` is the pattern this follows.
 *
 * What is deliberately **not** an icon: `NOTHING_PLAYING_LABEL`, `BUFFERING_LABEL` and the
 * "playing on" line are sentences a user reads, not controls a thumb finds, and the timecodes are
 * numbers no glyph can carry.
 *
 * The hierarchy is artwork, then three lines of type that differ by *scale* rather than by
 * repetition: title at `headlineSmall`, artist at `titleMedium` in the app's music colour, album at
 * `bodySmall` in the muted one. Before this pass all three were the same `Text` in three sizes that
 * happened to be near each other, and a now-playing screen where nothing dominates is one a user
 * has to read rather than glance at.
 *
 * Split into a stateful entry point and a **stateless** overload, the same shape `LibraryScreen`
 * already uses in this codebase — and here it is what makes the screen testable at all: the
 * stateless one takes a [PlayerUiState] and five lambdas, so `PlayerScreenTest` composes it on a
 * device against a state built by hand, with no media session, no Hilt graph and no server. The
 * overload is `internal` rather than `private` for exactly that reason.
 */
@Composable
fun PlayerScreen(
  modifier: Modifier = Modifier,
  castDeviceName: String? = null,
  castButton: @Composable () -> Unit = {},
  viewModel: PlayerViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  PlayerScreen(
    uiState = uiState,
    onPlayPause = viewModel::playPause,
    onNext = viewModel::next,
    onPrevious = viewModel::previous,
    onScrubTo = viewModel::scrubTo,
    onScrubFinished = viewModel::commitScrub,
    castDeviceName = castDeviceName,
    castButton = castButton,
    modifier = modifier,
  )
}

/**
 * @param castDeviceName the speaker playback is on, or `null` for the phone. A `String?` and a
 *   `@Composable` slot rather than a dependency on `:feature:castpicker`, and that is a module
 *   decision rather than a stylistic one: two feature modules that depend on each other is the
 *   first step towards neither being removable, and this plan's definition of done requires that
 *   dropping casting stays `git rm -r core/cast feature/castpicker`. `:app` supplies both.
 * @param castButton the control that opens the cast picker. Empty by default, so this screen
 *   renders correctly in a build with no casting in it at all.
 */
@Composable
internal fun PlayerScreen(
  uiState: PlayerUiState,
  onPlayPause: () -> Unit,
  onNext: () -> Unit,
  onPrevious: () -> Unit,
  onScrubTo: (Long) -> Unit,
  onScrubFinished: () -> Unit,
  castDeviceName: String? = null,
  castButton: @Composable () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  when (uiState) {
    PlayerUiState.NothingPlaying -> Box(
      modifier = modifier.fillMaxSize().padding(MuPlaySpacing.xxl),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = NOTHING_PLAYING_LABEL,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }

    is PlayerUiState.Content -> Column(
      modifier = modifier
        .fillMaxSize()
        .padding(horizontal = MuPlaySpacing.gutter, vertical = MuPlaySpacing.xl),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // `weight`, so the artwork is the thing that gives when the screen is short. Everything
      // below it has a fixed height and is the part a user must be able to reach; a square that
      // pushed the transport row off a small phone would be the wrong trade in the one place this
      // app cannot afford it. `aspectRatio` inside a bounded box takes the width unless the height
      // is the tighter constraint, so the art is square either way.
      Box(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = MuPlaySpacing.xl),
        contentAlignment = Alignment.Center,
      ) {
        Artwork(
          uri = uiState.playback.artworkUri,
          cacheKey = uiState.playback.mediaId,
          contentDescription = ARTWORK_DESCRIPTION,
          shape = MaterialTheme.shapes.large,
          modifier = Modifier.aspectRatio(1f),
        )
      }

      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs),
      ) {
        Text(
          text = uiState.playback.title.orEmpty(),
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = TITLE_LINES,
          overflow = TextOverflow.Ellipsis,
        )
        // The artist carries the app's music colour. It is the second thing a listener looks for
        // and the only one of the three lines that is also a *place* -- naming it in the accent is
        // what stops the block reading as one paragraph of metadata.
        Text(
          text = uiState.playback.artist.orEmpty(),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = uiState.playback.albumTitle.orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      // A player that shows nothing at all while the first chunk arrives reads as frozen, and a
      // long buffer on a slow connection is the moment a user reaches for the back button.
      if (uiState.playback.isBuffering) {
        StatusLine(BUFFERING_LABEL)
      }

      // Above the transport controls, and only while something is cast. A player that keeps
      // showing a play button and says nothing about where the sound is coming from is one whose
      // user reaches for the phone's own volume keys and hears nothing change.
      if (castDeviceName != null) {
        StatusLine(PLAYING_ON_PREFIX + castDeviceName)
      }

      Slider(
        value = uiState.displayPositionMs.toFloat(),
        onValueChange = { onScrubTo(it.toLong()) },
        onValueChangeFinished = onScrubFinished,
        // A zero-width range makes Slider throw; a track whose duration is not yet known renders a
        // full-width bar rather than crashing.
        valueRange = 0f..uiState.playback.durationMs.coerceAtLeast(1L).toFloat(),
        colors = SliderDefaults.colors(
          inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = MuPlaySpacing.md),
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Timecode(uiState.displayPositionMs)
        Timecode(uiState.playback.durationMs)
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = MuPlaySpacing.xl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Disabled, not hidden: a control that vanishes at the ends of a queue moves the two
        // beside it under the user's thumb.
        SecondaryTransport(
          icon = MuPlayIcons.SkipPrevious,
          label = PREVIOUS_LABEL,
          enabled = uiState.playback.hasPrevious,
          onClick = onPrevious,
        )
        PrimaryTransport(isPlaying = uiState.playback.isPlaying, onClick = onPlayPause)
        SecondaryTransport(
          icon = MuPlayIcons.SkipNext,
          label = NEXT_LABEL,
          enabled = uiState.playback.hasNext,
          onClick = onNext,
        )
        castButton()
      }
    }
  }
}

/**
 * The one heavy control on the screen: a filled circle, [MuPlaySpacing.transportPrimary] across,
 * carrying [PLAY_LABEL] or [PAUSE_LABEL] as its accessible name.
 *
 * Its size is the whole point. This is the control reached for without looking — from a pocket, in
 * the dark, one-handed — and it is found by mass and position rather than by being read.
 */
@Composable
private fun PrimaryTransport(isPlaying: Boolean, onClick: () -> Unit) {
  FilledIconButton(
    onClick = onClick,
    modifier = Modifier
      .padding(horizontal = MuPlaySpacing.md)
      .size(MuPlaySpacing.transportPrimary),
  ) {
    Icon(
      imageVector = if (isPlaying) MuPlayIcons.Pause else MuPlayIcons.Play,
      contentDescription = if (isPlaying) PAUSE_LABEL else PLAY_LABEL,
      modifier = Modifier.size(PRIMARY_GLYPH_DP.dp),
    )
  }
}

/**
 * Skip, either direction. An unfilled button so the primary action keeps the row's only mass, and
 * `IconButton`'s own 48dp minimum touch target so the 40dp glyph is still comfortably hittable.
 */
@Composable
private fun SecondaryTransport(
  icon: ImageVector,
  label: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  IconButton(
    onClick = onClick,
    enabled = enabled,
    colors = IconButtonDefaults.iconButtonColors(
      contentColor = MaterialTheme.colorScheme.onSurface,
    ),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      modifier = Modifier.size(SECONDARY_GLYPH_DP.dp),
    )
  }
}

/** Buffering, and where the sound is going: one line, one voice, one place on the screen. */
@Composable
private fun StatusLine(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.fillMaxWidth().padding(top = MuPlaySpacing.sm),
  )
}

/** Elapsed and total. Monospaced so the digits do not shuffle sideways as the position ticks. */
@Composable
private fun Timecode(millis: Long) {
  Text(
    text = formatDuration(millis),
    style = MaterialTheme.typography.labelSmall.merge(MuPlayTimecode),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * The strings, unchanged.
 *
 * They are still typed out again by the Tier 2 journey, deliberately, so that a wording change is
 * caught rather than silently followed. What moved in the design pass is only *which semantics
 * property carries them*: [PLAY_LABEL], [PAUSE_LABEL], [NEXT_LABEL] and [PREVIOUS_LABEL] are now
 * `contentDescription`s on icons rather than the text of text buttons, which is the accessible name
 * either way. Anything reading them with `onNodeWithText` has to read them with
 * `onNodeWithContentDescription` instead; nothing has to change the string.
 */
internal const val NOTHING_PLAYING_LABEL = "Nothing playing"
internal const val ARTWORK_DESCRIPTION = "Cover art"
internal const val BUFFERING_LABEL = "Buffering…"
internal const val PLAY_LABEL = "Play"
internal const val PAUSE_LABEL = "Pause"
internal const val NEXT_LABEL = "Next"
internal const val PREVIOUS_LABEL = "Previous"

/**
 * Prefixed onto the cast device's name, not a whole sentence with a placeholder, because the name
 * comes from a speaker a stranger named and this screen must not try to inflect around it. The
 * Tier 2 cast journey asserts the concatenation verbatim.
 */
internal const val PLAYING_ON_PREFIX = "Playing on "

/** Two lines of a long track name, then an ellipsis; three would crowd the transport row. */
private const val TITLE_LINES = 2
private const val PRIMARY_GLYPH_DP = 32
private const val SECONDARY_GLYPH_DP = 30

/**
 * A hairline progress rule. Used by the mini player, where a full slider would not fit and a
 * `LinearProgressIndicator` would bring a stop indicator and a track gap this bar has no room for.
 *
 * Drawn rather than composed from Material's own indicator because it must contribute **no**
 * semantics node: the bar it sits under already carries `MINI_PLAYER_LABEL`, and a second
 * describable child there is what makes a journey's `onNodeWithContentDescription` ambiguous.
 */
@Composable
internal fun ProgressRule(fraction: Float, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(PROGRESS_RULE_DP.dp)
      .clip(RoundedCornerShape(PROGRESS_RULE_DP.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth(fraction.coerceIn(0f, 1f))
        .fillMaxHeight()
        .background(MaterialTheme.colorScheme.primary),
    )
  }
}

private const val PROGRESS_RULE_DP = 3
