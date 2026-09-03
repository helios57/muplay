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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.component.Message
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
 * What is deliberately **not** an icon: `BUFFERING_LABEL` and the "playing on" line are sentences a
 * user reads, not controls a thumb finds, and the timecodes are numbers no glyph can carry.
 * `NOTHING_PLAYING_LABEL` is an empty state, so it is rendered by `:core:designsystem`'s shared
 * [Message] rather than by a `Text` this file styles for itself.
 *
 * **The screen has one vertical axis and everything sits on it.** Artwork, slider and the play
 * button share a centre line; the cast slot hangs off the trailing edge in its own box rather than
 * joining the transport row, because a control appended to an `Arrangement.Center` row pushes the
 * row's midpoint left by half its width — which is what put play/pause off the axis in
 * `play/screenshots/phone/06-now-playing.png`.
 *
 * The hierarchy is artwork, then three lines of type that differ by *scale* rather than by colour:
 * title at `headlineSmall`, artist at `titleMedium` (Medium weight), album at `bodySmall` in the
 * muted role. The artist used to be `primary` teal, which on a screen with no other links reads as
 * a hyperlink that does nothing when tapped — colour was doing a job that weight does better and
 * without the false affordance.
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
    onRetry = viewModel::retry,
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
  // Required, with no default. A defaulted `{}` would let a caller forget it and get a "Try again"
  // button that does nothing -- which `Message`'s own doc calls out as worse than no button at all,
  // and is why that component's `onRetry` is nullable rather than a defaulted no-op.
  onRetry: () -> Unit,
  castDeviceName: String? = null,
  castButton: @Composable () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  when (uiState) {
    // The app's one way of saying "nothing here". Before this it was a `Text` this file sized and
    // coloured itself, which is how four screens ended up with four empty states.
    PlayerUiState.NothingPlaying -> Box(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Message(text = NOTHING_PLAYING_LABEL)
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
      //
      // **`TopCenter`, not `Center`.** A weighted box that centres its child splits the leftover
      // height into two dead bands -- measured at roughly 310px above the art and 320px below it
      // on `muplay37` -- so the art floats in the middle of the screen and the metadata block sits
      // as far from its own title as the art does from the status bar. Top-aligning gives the
      // whole of that slack to one gap, above the metadata, which is the block that wants room.
      Box(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = MuPlaySpacing.xl),
        contentAlignment = Alignment.TopCenter,
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
          // What this screen is about, so a TalkBack user can jump straight to it with the
          // headings navigation gesture instead of swiping through the transport row first.
          modifier = Modifier.semantics { heading() },
        )
        // `onSurface` at `titleMedium`, which Type.kt sets at `FontWeight.Medium` -- weight and
        // scale carry the second line, not colour. It was `primary` (6.28:1 on the light surface,
        // and perfectly legible), and legibility was never the problem: teal text on a screen
        // whose only other teal is the play button reads as a link, and nothing happens when a
        // user taps it. `onSurface` measures 16.29:1 light and 14.44:1 dark.
        Text(
          text = uiState.playback.artist.orEmpty(),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
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

      // **Above the transport row, and it carries its own way out.**
      //
      // Nothing surfaced a playback error before this. A failed track published `isPlaying = false`
      // and nothing else, so it was indistinguishable from a pause -- and the play button a user
      // then pressed called `play()` on a player Media3 had already moved to `STATE_IDLE`, which
      // does nothing at all. Two silences stacked: the app did not say what happened, and the one
      // control that looked like it should help did not work.
      //
      // `Message`'s `onRetry`, which until now had **no caller anywhere in the app**. This is the
      // component's whole reason for having the parameter: an error a user can act on.
      uiState.playback.failure?.let { failure ->
        // No padding modifier of its own: `Message` brings 32dp above and below, which is the
        // point of using it rather than styling a `Text` here. The artwork above is `weight(1f)`,
        // so the height this costs comes out of the cover art and never out of the transport row --
        // and a smaller cover while playback is broken is the right way round.
        Message(text = failure.message(), onRetry = onRetry, retryLabel = RETRY_LABEL)
      }

      Slider(
        value = uiState.displayPositionMs.toFloat(),
        onValueChange = { onScrubTo(it.toLong()) },
        onValueChangeFinished = onScrubFinished,
        // A zero-width range makes Slider throw; a track whose duration is not yet known renders a
        // full-width bar rather than crashing.
        valueRange = 0f..uiState.playback.durationMs.coerceAtLeast(1L).toFloat(),
        colors = SliderDefaults.colors(inactiveTrackColor = inactiveTrackColor()),
        modifier = Modifier.fillMaxWidth().padding(top = MuPlaySpacing.md),
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Timecode(uiState.displayPositionMs)
        Timecode(uiState.playback.durationMs)
      }

      // **A `Box`, not a `Row`.** The three transport controls centre in the full width, so
      // play/pause lands on the same vertical axis as the artwork and the slider thumb's travel;
      // the cast slot is pinned to the trailing edge instead of being appended to the row. The old
      // shape put the slot *inside* an `Arrangement.Center` row, which moved every control left by
      // half the slot's width -- about 20dp -- and only when casting was in the build, so the
      // primary control sat on the axis in one variant and off it in the other.
      Box(
        modifier = Modifier.fillMaxWidth().padding(top = MuPlaySpacing.xl),
        contentAlignment = Alignment.Center,
      ) {
        Row(
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
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd)) { castButton() }
      }
    }
  }
}

/**
 * The colour of the part of the seek bar that has **not** been played, and the one colour on this
 * screen chosen by measurement rather than by role name.
 *
 * It was `surfaceVariant`, and in **light** mode that is `#DBE5E1` on the `#FBF9F5` surface:
 * **1.22:1**. A track a sighted user cannot find is a slider whose length -- and therefore how much
 * of the track is left -- is invisible. `outlineVariant` (`#BFC9C5`) is **1.61:1** there.
 *
 * In **dark** mode this changes nothing at all, and that is worth saying rather than hiding:
 * `MuPlayOutlineVariantDark` and `MuPlaySurfaceVariantDark` are the same `#3F4946`, so the dark
 * track was already at its best available **2.00:1** and only the light scheme was broken.
 *
 * Neither value reaches WCAG 1.4.11's 3:1 against the page, and no role in this scheme does while
 * still reading as a track rather than as a rule. What 1.4.11 actually governs on a slider is the
 * boundary that *carries the value* -- the edge between played and unplayed -- and that stays well
 * clear: `primary` against `outlineVariant` is **3.89:1** light and **5.52:1** dark. The change
 * therefore trades 5.12:1 -> 3.89:1 on a boundary that had margin to spare for 1.22:1 -> 1.61:1 on
 * one that had none.
 *
 * Two candidates were measured and rejected, and the rejections are the interesting half:
 *
 * - `secondaryContainer` (`#CCE8E1`) is **1.23:1** on the light surface -- one hundredth better
 *   than the colour being replaced. It is the obvious fix and it fixes nothing.
 * - `outline` (`#6F7976`) is **4.27:1** against the page and clears 1.4.11 outright, and it is the
 *   wrong answer: `primary` against it is **1.47:1**, so the played portion vanishes into the
 *   unplayed one and the bar stops reporting the position at all. A track that is easy to see and
 *   impossible to read is a worse slider than a faint one.
 *
 * A composable function rather than a constant because `MaterialTheme` is only readable from one,
 * and camelCase because it returns a value rather than emitting UI.
 */
@Composable
private fun inactiveTrackColor(): Color = MaterialTheme.colorScheme.outlineVariant

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
 * Skip, either direction. An unfilled button so the primary action keeps the row's only mass.
 *
 * Sized to [MuPlaySpacing.minTouchTarget] **explicitly** rather than left to `IconButton`'s own
 * default, which is 40dp of container relying on `minimumInteractiveComponentSize` to pad the touch
 * area out to 48dp. The padded version is hittable and measures as a 40dp box, so a rule -- or a
 * reviewer -- reading the layout cannot tell it from a genuinely undersized control. Saying 48
 * here makes the guarantee the code's rather than a default's.
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
    modifier = Modifier.size(MuPlaySpacing.minTouchTarget),
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

/**
 * The error message's action. "Try again" rather than "Retry": it is what the user is doing, and
 * it is the word `Message`'s own default already uses everywhere else the component appears.
 */
internal const val RETRY_LABEL = "Try again"

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
 *
 * Its unplayed half takes the same `outlineVariant` the seek bar's does, and for the same measured
 * reason: on the mini player's `surfaceContainer` the old `surfaceVariant` was **1.10:1** in light
 * mode -- a rule that is simply not there -- against **1.45:1** now. Dark is `#3F4946` either way
 * and stays at 1.76:1.
 */
@Composable
internal fun ProgressRule(fraction: Float, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(PROGRESS_RULE_DP.dp)
      .clip(RoundedCornerShape(PROGRESS_RULE_DP.dp))
      .background(MaterialTheme.colorScheme.outlineVariant),
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
