package app.muplay.book

import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.component.Message
import app.muplay.designsystem.theme.BookVoice
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.designsystem.theme.MuPlayTimecode
import app.muplay.media.BookChapter
import app.muplay.media.SleepTimerController
import app.muplay.model.SleepTimerRequest
import app.muplay.model.SleepTimerState
import kotlin.math.abs

/**
 * The audiobook player -- a different instrument from `:feature:player`'s, not a mode of it.
 *
 * Next and previous mean **chapter**, a plus/minus thirty-second nudge matters more than a
 * scrubber, the speed control is used constantly, the sleep timer lives here, and the progress a
 * listener cares about is "three hours left in this book" rather than "1:42 of 4:03". Which of the
 * two players opens is decided in `:app` from `PlaybackState.isAudiobook` and nowhere else;
 * neither feature module knows the other exists.
 *
 * ### Three regions, and only the middle one scrolls
 *
 * **This screen used to be one `LazyColumn` with the transport as an `item` in it**, above the
 * chapter list. Scrolling to chapter twelve therefore scrolled play/pause off the top -- on the one
 * screen in this app that is used half asleep, in the dark, by somebody who wants to stop the
 * narrator and nothing else. It is now:
 *
 *  - a **fixed header**: the cover, the two titles, the chapter counter, how much of the book is
 *    left, the chapter bar and the chapter clock. Everything that answers "where am I";
 *  - a **scrolling middle**: speed, sleep timer, and the chapter list. Everything that is a choice;
 *  - a **fixed transport**: the five controls, on their own surface at the bottom of the screen,
 *    where a thumb already is.
 *
 * `BookPlayerContentTest.theTransportStaysReachableWithTheChapterListScrolledToItsEnd` is the
 * falsification: it scrolls a thirty-chapter book to the last row and asserts play/pause is still
 * displayed, which is false of the old layout and true of this one.
 *
 * ### The controls are icons, and every label constant survived unchanged
 *
 * This file used to say that every control was a text button "which is this project's convention",
 * and that switching to icons meant `material-icons-extended`, which is banned. `:core:designsystem`
 * now draws the ten glyphs this app needs, including the two -- the wind-back and wind-forward
 * rings -- that were the reason for the banned artifact. So `BACK_30_LABEL`, `PLAY_LABEL`,
 * `NEXT_CHAPTER_LABEL` and the rest are still the strings in `BookLabels.kt`, still not reworded,
 * and still the accessible name of the control they name: they are `contentDescription`s on icons
 * instead of the text of text buttons. A journey reads them with `onNodeWithContentDescription`
 * rather than `onNodeWithText`; that is a change of finder, not of contract.
 *
 * **The `30` inside the two nudge buttons is drawn as text over the ring and carries no semantics
 * of its own** (`clearAndSetSemantics { }`). Baking numerals into a vector path would have to be
 * redrawn the day the nudge becomes configurable, and letting the digits describe themselves would
 * make a screen reader say "Back 30 seconds, 30".
 *
 * What is deliberately still text: the speed (`Speed 1.4x` -- no glyph carries a number), the sleep
 * timer's own button (while a timer runs the button **is** the countdown), the presets, the chapter
 * counter and the clocks.
 */
@Composable
fun BookPlayerScreen(
  modifier: Modifier = Modifier,
  viewModel: BookPlayerViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  BookPlayerContent(
    state = state,
    onPlayPause = viewModel::playPause,
    onPreviousChapter = viewModel::previousChapter,
    onNextChapter = viewModel::nextChapter,
    onNudge = viewModel::nudge,
    onSpeed = viewModel::setSpeed,
    onChapter = viewModel::seekTo,
    onSleepPreset = { viewModel.startSleepTimer(SleepTimerRequest.Duration(it)) },
    onEndOfChapter = viewModel::endOfChapterTimer,
    onCancelTimer = viewModel::cancelSleepTimer,
    coverArtUrl = viewModel::coverArtUrl,
    modifier = modifier,
  )
}

@Composable
internal fun BookPlayerContent(
  state: BookPlayerUiState,
  onPlayPause: () -> Unit,
  onPreviousChapter: () -> Unit,
  onNextChapter: () -> Unit,
  onNudge: (Long) -> Unit,
  onSpeed: (Float) -> Unit,
  onChapter: (BookChapter) -> Unit,
  onSleepPreset: (Long) -> Unit,
  onEndOfChapter: () -> Unit,
  onCancelTimer: () -> Unit,
  coverArtUrl: suspend (String, Int) -> String,
  modifier: Modifier = Modifier,
) {
  BookVoice {
    when (state) {
      BookPlayerUiState.NothingPlaying -> Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Message(text = NOTHING_PLAYING_LABEL)
      }

      is BookPlayerUiState.Content -> Column(modifier = modifier.fillMaxSize()) {
        NowReading(state = state, coverArtUrl = coverArtUrl)

        // The only scrolling region. `contentPadding` rather than a padded parent so the chapter
        // rows' ripples reach the gutter and the list scrolls under the header edge to edge.
        LazyColumn(
          modifier = Modifier.weight(1f),
          contentPadding = PaddingValues(
            start = MuPlaySpacing.gutter,
            end = MuPlaySpacing.gutter,
            bottom = MuPlaySpacing.md,
          ),
        ) {
          // **First in the list, above the speed stepper, and inside the scrolling region.**
          //
          // The transport row below is pinned outside this `LazyColumn`, so putting the message here
          // costs it no height and cannot push it off a short screen -- the failure mode
          // `LibraryScreen` was fixed for in this same batch. First rather than anywhere else
          // because a listener whose book has stopped is looking at the top of the screen.
          state.failure?.let { failure ->
            item {
              // **No `onRetry`.** `Message`'s retry parameter is nullable precisely so a caller
              // can say "there is nothing useful to add here", and this is that case: the transport
              // row three inches below is permanently on screen and its play button already
              // re-prepares the player. A second "Try again" would be two affordances for one
              // action, and the one further from the thumb.
              Message(text = failure.message())
            }
          }
          item {
            SpeedStepper(
              speed = state.speed,
              onSpeed = onSpeed,
              modifier = Modifier.fillMaxWidth().padding(bottom = MuPlaySpacing.md),
            )
          }
          item {
            SleepTimerRow(
              timer = state.sleepTimer,
              onPreset = onSleepPreset,
              onEndOfChapter = onEndOfChapter,
              onCancel = onCancelTimer,
              modifier = Modifier.padding(bottom = MuPlaySpacing.sm),
            )
          }
          items(state.chapters, key = { it.index }) { chapter ->
            ChapterRow(
              chapter = chapter,
              // `chapterNumber` is one-based, `BookChapter.index` is not -- `chapterRowLabel` makes
              // the same `+ 1` for the same reason. Lighting the row a listener is inside is what
              // makes a pinned list worth pinning: it answers "which one is this" without a wait.
              isCurrent = chapter.index == state.chapterNumber - 1,
              onClick = { onChapter(chapter) },
            )
          }
        }

        Transport(
          isPlaying = state.isPlaying,
          hasFailed = state.failure != null,
          onPlayPause = onPlayPause,
          onPreviousChapter = onPreviousChapter,
          onNextChapter = onNextChapter,
          onNudge = onNudge,
        )
      }
    }
  }
}

/**
 * The fixed header: what is playing, and how much of it is left.
 *
 * **`formatRemaining` is set in `displaySmall`, in the audiobook amber, and it is the largest thing
 * on the screen.** That is the one deliberate piece of typographic weight in this module. "3 h 12 m
 * left" is the readout this whole application exists to keep honest -- a music player has no
 * equivalent of it -- and until this pass it was `labelMedium`, the same size as the chapter
 * counter, sharing a row with the chapter clock. The scale in `Type.kt` had a `displaySmall` in it
 * from the day it was written and no call site anywhere in the app; this is the number that earns
 * it.
 *
 * The chapter clock underneath stays small, monospaced and muted: it ticks once a second and is
 * read on purpose, not at a glance.
 */
@Composable
private fun NowReading(
  state: BookPlayerUiState.Content,
  coverArtUrl: suspend (String, Int) -> String,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(
        start = MuPlaySpacing.gutter,
        end = MuPlaySpacing.gutter,
        top = MuPlaySpacing.lg,
        bottom = MuPlaySpacing.md,
      ),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
  ) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      BookCover(
        coverArtId = state.coverArtId,
        sizePx = COVER_PLAYER_PX,
        contentDescription = BOOK_COVER_LABEL,
        urlProvider = coverArtUrl,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.size(COVER_PLAYER_DP.dp),
      )
    }

    Column(verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs)) {
      Text(
        text = state.bookTitle,
        style = MaterialTheme.typography.titleLarge,
        maxLines = TITLE_LINES,
        overflow = TextOverflow.Ellipsis,
        // The screen's subject, so TalkBack's "next heading" lands on it.
        modifier = Modifier.semantics { heading() },
      )
      // The chapter is the audiobook voice: the shelf, this line and "how much is left" are
      // the three places a listener looks, and they are the three things lit in `tertiary`.
      Text(
        text = state.chapterTitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.tertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      // Only once chapters are known. "Chapter 0 of 0" on a book whose extraction has not
      // returned is worse than saying nothing.
      if (state.chapterCount > 0) {
        Text(
          text = "Chapter ${state.chapterNumber} of ${state.chapterCount}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
      Text(
        text = formatRemaining(state.bookRemainingMs),
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.tertiary,
        maxLines = 1,
      )
      LinearProgressIndicator(
        progress = {
          // The guard is the `0` an unmeasured duration arrives as -- `x / 0f` is Infinity, and
          // an Infinity into a progress bar renders as full for a book that has not started.
          if (state.chapterDurationMs <= 0L) 0f
          else state.positionInChapterMs.toFloat() / state.chapterDurationMs
        },
        color = MaterialTheme.colorScheme.tertiary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT_DP.dp),
      )
      // One string rather than two nodes: it reads as a fraction ("where I am, out of how
      // long this chapter is"), and the journey asserts the whole sentence.
      Text(
        text = "${formatClock(state.positionInChapterMs)} / ${formatClock(state.chapterDurationMs)}",
        style = MaterialTheme.typography.labelSmall.merge(MuPlayTimecode),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * The five controls, pinned to the bottom of the screen.
 *
 * **One row, five controls, in the order a thumb expects them**: chapter back, thirty back, play,
 * thirty forward, chapter next. This used to be two rows -- chapters above, nudges and play below
 * -- which put the two most-used controls in different places and made the play button the same
 * size as everything else. Five icons fit across a 360dp phone with room to spare; five *words*
 * never did.
 *
 * Its own [Surface] in `surfaceContainer` rather than a rule above it: a control deck that is a
 * different tone from the page separates itself, and this file already spends its one rule on the
 * shelf's eyebrows.
 *
 * **Play/pause is `tertiary`.** The palette's whole thesis is that `primary` is the music voice and
 * `tertiary` the audiobook one, and until this pass the biggest, most-pressed control on the
 * audiobook player was drawn in the music colour by default -- `FilledIconButton` fills with
 * `primary` unless told otherwise, and the two nudges beside it tinted themselves
 * `secondaryContainer` for the same reason. Three unnamed defaults, all of them the wrong lamp.
 */
@Composable
private fun Transport(
  isPlaying: Boolean,
  hasFailed: Boolean,
  onPlayPause: () -> Unit,
  onPreviousChapter: () -> Unit,
  onNextChapter: () -> Unit,
  onNudge: (Long) -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = MuPlaySpacing.sm, vertical = MuPlaySpacing.md),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ChapterButton(MuPlayIcons.SkipPrevious, PREVIOUS_CHAPTER_LABEL, onPreviousChapter)
      NudgeButton(MuPlayIcons.RotateBack, BACK_30_LABEL) { onNudge(-NUDGE_MS) }
      PlayPauseButton(isPlaying = isPlaying, hasFailed = hasFailed, onClick = onPlayPause)
      NudgeButton(MuPlayIcons.RotateForward, FORWARD_30_LABEL) { onNudge(NUDGE_MS) }
      ChapterButton(MuPlayIcons.SkipNext, NEXT_CHAPTER_LABEL, onNextChapter)
    }
  }
}

/**
 * The one control a thumb finds without looking, and the one that changes what it means.
 *
 * The glyph swap is a **dip and return on a single node**: `phase` runs 0..1 with the state, and
 * the scale is derived from how far it is from either end, so the icon shrinks through the swap and
 * comes back. It is deliberately not a `Crossfade` or an `AnimatedContent`, and the reason is not
 * taste -- both of those hold the outgoing *and* the incoming child in the tree for the length of
 * the transition, which would put two transport glyphs on screen at once. `JourneyNavigation`'s
 * `pausePlayback` in `:app` asserts that exactly one pause control exists before it presses
 * anything, and this file has no business making that assertion race a frame clock.
 *
 * The `contentDescription` and the vector both come straight from [isPlaying] rather than from
 * `phase`: what the accessibility tree says is what the state is, with no window in which it is
 * mid-word.
 */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, hasFailed: Boolean, onClick: () -> Unit) {
  val phase = if (motionEnabled()) {
    val animated by animateFloatAsState(
      targetValue = if (isPlaying) 1f else 0f,
      animationSpec = tween(GLYPH_DIP_MS),
      label = "playPausePhase",
    )
    animated
  } else {
    // Not "animate to a duration of zero": with motion off the animation is not in the tree at all,
    // so there is nothing pending for a test or a screen reader to catch mid-flight.
    if (isPlaying) 1f else 0f
  }
  // 1 at either end, 0 half way -- so `scale` is full at rest in both states and pinched during
  // the swap, whichever direction it is going.
  val settled = abs(2f * phase - 1f)
  val scale = 1f - GLYPH_DIP * (1f - settled)

  FilledIconButton(
    onClick = onClick,
    modifier = Modifier.size(MuPlaySpacing.transportPrimary),
    colors = IconButtonDefaults.filledIconButtonColors(
      containerColor = MaterialTheme.colorScheme.tertiary,
      contentColor = MaterialTheme.colorScheme.onTertiary,
    ),
  ) {
    Icon(
      imageVector = if (isPlaying) MuPlayIcons.Pause else MuPlayIcons.Play,
      // On a failed player this button **retries** -- `BookPlayerViewModel.playPause` re-prepares
      // rather than calling `play()` into a `STATE_IDLE` player, which does nothing -- so its
      // accessible name has to say so. A listener told "Play" who then hears silence has been
      // misled by the label as well as by the app.
      contentDescription = when {
        hasFailed -> RETRY_PLAYBACK_LABEL
        isPlaying -> PAUSE_LABEL
        else -> PLAY_LABEL
      },
      modifier = Modifier
        .size(PRIMARY_GLYPH_DP.dp)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        },
    )
  }
}

/** Chapter back and forward: the quietest pair, at the ends of the row. */
@Composable
private fun ChapterButton(icon: ImageVector, label: String, onClick: () -> Unit) {
  IconButton(
    onClick = onClick,
    colors = IconButtonDefaults.iconButtonColors(
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
  ) {
    Icon(icon, contentDescription = label, modifier = Modifier.size(CHAPTER_GLYPH_DP.dp))
  }
}

/**
 * A thirty-second nudge: the ring, with `30` drawn inside it.
 *
 * The numerals are a `Text` with [clearAndSetSemantics], so the control has exactly one accessible
 * name -- the label the button already carried -- rather than a name plus a loose "30".
 *
 * `tertiaryContainer`, for the reason [Transport] gives: `FilledTonalIconButton`'s default is
 * `secondaryContainer`, which is the music voice spoken quietly, and these two sit either side of
 * the amber play button.
 */
@Composable
private fun NudgeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
  FilledTonalIconButton(
    onClick = onClick,
    modifier = Modifier.size(MuPlaySpacing.transportSecondary),
    colors = IconButtonDefaults.filledTonalIconButtonColors(
      containerColor = MaterialTheme.colorScheme.tertiaryContainer,
      contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = label, modifier = Modifier.size(NUDGE_GLYPH_DP.dp))
      Text(
        text = NUDGE_SECONDS,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clearAndSetSemantics { },
      )
    }
  }
}

/**
 * One chapter, at least [MuPlaySpacing.minTouchTarget] tall.
 *
 * The row used to be a bare `Text` with 12dp of vertical padding, which measures about 44dp -- under
 * the 48dp Android's own accessibility guidance and Material's `minimumInteractiveComponentSize`
 * both name, on a list somebody scrolls in the dark. `heightIn` is outside `clickable` so the
 * ripple and the hit rectangle are the tall one, not the text's own box.
 */
@Composable
private fun ChapterRow(chapter: BookChapter, isCurrent: Boolean, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = MuPlaySpacing.minTouchTarget)
      .clickable(onClick = onClick)
      .padding(vertical = MuPlaySpacing.xs),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = chapterRowLabel(chapter),
      style = MaterialTheme.typography.bodyMedium,
      color = if (isCurrent) {
        MaterialTheme.colorScheme.tertiary
      } else {
        MaterialTheme.colorScheme.onSurface
      },
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * The sleep timer, and **the layout defect an earlier pass came here to fix.**
 *
 * `SleepTimerRow` used to put the six presets and `End of chapter` into a plain, non-scrolling
 * `Row`. Seven controls is wider than any phone, so the last of them were clipped off the right
 * edge and could not be tapped at all. `BookPlayerContentTest` recorded that in its own prose --
 * it asserted `assertExists` rather than `assertIsDisplayed`, and reached `End of chapter` through
 * `performSemanticsAction(OnClick)` because `performClick` refuses a node that is not displayed,
 * with a comment saying the answer on a real phone was "very likely no". It was: on `muplay37`
 * (411dp wide) the row needs about 560dp.
 *
 * A `FlowRow` fixes it by **wrapping** rather than by scrolling, and the difference matters here.
 * A horizontally scrolling row hides options behind a gesture nobody knows to make; wrapping shows
 * a half-asleep listener all seven at once, which is the entire job of this control. Those tests
 * now assert `assertIsDisplayed` and use a real `performClick`, so they fail against the old
 * layout and pass against this one.
 *
 * Chips rather than buttons, and Material's chips are 32dp tall -- but `Surface`'s
 * `minimumInteractiveComponentSize` still gives each one a 48dp touch target, which is why the
 * vertical spacing below is [MuPlaySpacing.md] rather than [MuPlaySpacing.sm]: at 8dp the
 * neighbouring rows' touch targets would overlap.
 *
 * [animateContentSize] on the container, not on the `FlowRow`: the `FlowRow` does not *resize* when
 * the presets appear, it stops existing, and it is this column that grows and shrinks around it.
 * The animation is skipped entirely under reduced motion -- see [motionEnabled].
 */
@Composable
private fun SleepTimerRow(
  timer: SleepTimerState,
  onPreset: (Long) -> Unit,
  onEndOfChapter: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  Column(
    modifier = modifier.then(if (motionEnabled()) Modifier.animateContentSize() else Modifier),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // While a timer runs the button **is** the countdown, so how long is left is visible without
      // opening anything -- which is the whole affordance for somebody half asleep.
      OutlinedButton(onClick = { open = !open }) {
        Icon(
          MuPlayIcons.Moon,
          contentDescription = null,
          modifier = Modifier.size(INLINE_GLYPH_DP.dp),
        )
        Text(
          text = if (timer is SleepTimerState.Running) formatClock(timer.remainingMs) else SLEEP_TIMER_LABEL,
          modifier = Modifier.padding(start = MuPlaySpacing.sm),
        )
      }
      if (timer is SleepTimerState.Running) {
        IconButton(onClick = onCancel) {
          Icon(MuPlayIcons.Close, contentDescription = CANCEL_TIMER_LABEL)
        }
      }
    }
    if (open) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
        modifier = Modifier.fillMaxWidth(),
      ) {
        // `SleepTimerController.PRESETS`, not a list typed out here: the controller owns what a
        // preset is, and a second list is a second answer that drifts.
        SleepTimerController.PRESETS.forEach { preset ->
          SuggestionChip(
            onClick = {
              onPreset(preset)
              open = false
            },
            label = { Text("${preset / 60_000} min") },
          )
        }
        SuggestionChip(
          onClick = {
            onEndOfChapter()
            open = false
          },
          label = { Text(END_OF_CHAPTER_LABEL) },
        )
      }
    }
  }
}

/**
 * Whether this device wants motion at all.
 *
 * Android's accessibility "Remove animations" setting -- and developer options' animator scale --
 * both write `Settings.Global.ANIMATOR_DURATION_SCALE`, and `0` there is a user saying "no". Read
 * once per composition rather than trusted to the framework: Compose scales its own durations from
 * the same global, but a *duration* of zero still puts an animation in the tree for a frame, and
 * this file would rather the animation not exist. `remember`ed on the resolver, because it is a
 * device setting and not a per-frame one.
 *
 * Measured on `muplay37`: `adb shell settings get global animator_duration_scale` is **0**, so the
 * whole device tier runs with both of this screen's animations switched off and neither of them can
 * influence a test result.
 */
@Composable
private fun motionEnabled(): Boolean {
  val resolver = LocalContext.current.contentResolver
  return remember(resolver) {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, FULL_MOTION) > 0f
  }
}

/** The biggest cover this app asks for; it fills most of the header. */
private const val COVER_PLAYER_PX = 512
private const val COVER_PLAYER_DP = 220
private const val TITLE_LINES = 2
private const val PROGRESS_HEIGHT_DP = 6
private const val PRIMARY_GLYPH_DP = 32
private const val CHAPTER_GLYPH_DP = 26
private const val NUDGE_GLYPH_DP = 34
private const val INLINE_GLYPH_DP = 18

/** The play/pause pinch: how long, and how far in. Short enough to read as one press. */
private const val GLYPH_DIP_MS = 180
private const val GLYPH_DIP = 0.28f

/** What `ANIMATOR_DURATION_SCALE` reads when nobody has touched it. */
private const val FULL_MOTION = 1f

/**
 * The numerals inside the two nudge rings. Derived from [NUDGE_MS] so the drawing and the seek can
 * never disagree -- the label constants beside them are already derived from nothing else.
 */
private const val NUDGE_SECONDS_VALUE = NUDGE_MS / 1000L
private val NUDGE_SECONDS = NUDGE_SECONDS_VALUE.toString()
