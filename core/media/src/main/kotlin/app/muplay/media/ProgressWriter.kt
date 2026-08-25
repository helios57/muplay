package app.muplay.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Writes `media_progress` at spec §3's seven persistence points, plus a ticker.
 *
 * The seven, and what each one catches:
 *
 * 1. `onPlayWhenReadyChanged` -- the user pressing pause, **and** an audio-focus loss pausing for
 *    them. One callback, both causes.
 * 2. `onIsPlayingChanged(false)` -- anything that stopped playback that point 1 did not see.
 * 3. `onPositionDiscontinuity` -- a seek, or a track boundary. Writes the position of the item
 *    being *left*, read from `oldPosition`, which is the only place that number still exists once
 *    the player has moved on. **`DISCONTINUITY_REASON_SILENCE_SKIP` is ignored**: silence skipping
 *    (Plan 4) moves the position without the listener having moved, and recording it would inch a
 *    book forward every time it skipped a pause.
 * 4. `onMediaItemTransition` -- stamps the newly-current item, so a "recently played" list is right
 *    even if the listener stops immediately.
 * 5. `onPlaybackStateChanged` at `STATE_IDLE` or `STATE_ENDED`.
 * 6. the ticker, every [TICK_MS].
 * 7. [flushBlocking], called from `MuPlaybackService.onDestroy` -- deliberately blocking, because a
 *    coroutine launched into a scope that is about to be cancelled writes nothing.
 *
 * ### Every write is a read-modify-write, and that is the trap in this table
 *
 * `media_progress` carries `speed`, `skipSilence` and `gainDb`. The first two belong to Plan 4 and
 * this class never writes them; `gainDb` **is** written, as of Task 11 -- stamped from the playing
 * item's own ReplayGain tags, and falling back to the stored value for an untagged item rather than
 * erasing it. Constructing a fresh `MediaProgressEntity` here and upserting it
 * would reset a listener's per-book speed **every five seconds** -- a data-loss bug that no test of
 * this class's own three fields could ever see. `ProgressWriterTest` asserts exactly that.
 *
 * `isFinished` gets the same treatment in the other direction: it is set to `true` at `STATE_ENDED`
 * and otherwise **preserved**, never written as `false`. A ticker that wrote `false` would un-finish
 * a completed book on the next accidental tap. "Un-finish on replay" is real behaviour and it is
 * Plan 4's, at the point it has a UI to express it.
 *
 * ### Book positions are local and never leave the device
 *
 * Nothing here talks to a server. `media_progress` is the only destination, and Subsonic's own
 * `savePlayQueue`/`scrobble` endpoints are deliberately not called from this class or from anything
 * it reaches: spec §3's promise is that a listener's place in a book is theirs and local. The one
 * thing that would violate it is a network call added here, which is why the class has no
 * `SubsonicClient` and no `:core:network` dependency to make one with.
 *
 * ### Threading, and why the writes leave the caller's dispatcher
 *
 * Every player read ([captureCurrent], [flushBlocking], and the gain each of them hands to [write])
 * happens on the thread the callback arrived on -- the player's application thread -- because that
 * is the only thread a `Player` may be read from. That rule is why `gainDb` is a **parameter** of
 * [write] rather than something it reads: an earlier draft read `player.currentMediaItem` inside
 * the `withContext` below and two device tests failed with
 * `IllegalStateException: Player is accessed on the wrong thread`. The database work then runs on [Dispatchers.IO] under [writeLock], and both halves of that
 * matter:
 *
 *  * the **lock** makes the read-modify-write atomic against itself. Without it, a ticker that has
 *    read `isFinished = false` and suspended can be overtaken by the `STATE_ENDED` write and then
 *    overwrite it with `false` -- un-finishing a book at the exact moment it finished. On a
 *    five-second track the two fire in the same millisecond, so this is not a theoretical
 *    interleaving.
 *  * the **dispatcher** is what stops [flushBlocking] deadlocking on that lock. `onDestroy` runs on
 *    the main thread; a lock held by a coroutine that needs the main thread to resume could never
 *    be released while `runBlocking` is holding it.
 *
 * No test in this plan discriminates the lock -- a deterministic interleaving test would need a
 * seam into Room's dispatcher that does not exist here -- and that is recorded rather than papered
 * over. See task-8b-report.md.
 *
 * ### The player is held in a field, on purpose
 *
 * Plan 6 replaces the local `Player` with a remote renderer and needs **one** writer to follow the
 * switch, not two racing for the same row. It will add `fun attach(player: Player)`; this plan does
 * not, because an unused method is untested weight. What this plan owes it is that `attach` stays
 * *possible* -- so the ticker reads [player] on every tick rather than closing over the constructor
 * argument, and [start] and [flushBlocking] keep their meaning if it is ever repointed.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn` -- see `MuPlayerFactory` for the full argument.
// The annotated member here is `Player.PositionInfo.mediaItem`, which is `@UnstableApi` even though
// `Player` and `PositionInfo` are not. The Kotlin compiler cannot see that annotation at all: this
// file compiled clean and `check` failed at `lintDebug` with `UnsafeOptInUsageError`, forty
// minutes after the code was written, which is exactly the failure mode CLAUDE.md records.
@OptIn(UnstableApi::class)
class ProgressWriter(
  player: Player,
  private val dao: MediaProgressDao,
  private val clock: Clock,
  private val scope: CoroutineScope,
) : Player.Listener {

  /**
   * Deliberately a `var` and deliberately read on every use. See the class documentation: this is
   * the whole of what Plan 6's `attach` needs from this plan.
   */
  private var player: Player = player

  private val writeLock = Mutex()

  private var ticker: Job? = null

  fun start() {
    player.addListener(this)
    ticker = scope.launch {
      while (true) {
        delay(TICK_MS)
        captureCurrent(finished = false)
      }
    }
  }

  fun stop() {
    ticker?.cancel()
    ticker = null
    player.removeListener(this)
  }

  // 1
  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
    captureCurrent(finished = false)

  // 2
  override fun onIsPlayingChanged(isPlaying: Boolean) {
    if (!isPlaying) captureCurrent(finished = false)
  }

  // 3
  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
  ) {
    if (reason == Player.DISCONTINUITY_REASON_SILENCE_SKIP) return
    // One null check and not two: `mediaItem?.mediaId ?: return` reads the same but emits a second
    // branch on `mediaId`, which is non-null on every `MediaItem` and so can never take its other
    // arm. An uncoverable branch is not safety -- it is a reader's false impression that the case
    // was thought about, and this module deletes them rather than excusing them (see
    // `ContentTypeSwitcher` and `PlaybackConnection`'s ticker).
    val leaving = oldPosition.mediaItem ?: return
    // `oldPosition`, never `player.currentPosition`: by the time this arrives the player is already
    // at the *new* position, so reading it here would write the destination of the seek onto the
    // row of the item that was left. The **gain** comes off the same `oldPosition.mediaItem` for
    // exactly the same reason -- `player.currentMediaItem` already names the new one, and the row
    // being written belongs to the old.
    val gainDb = gainDbOf(leaving)
    scope.launch { write(leaving.mediaId, oldPosition.positionMs, finished = false, gainDb) }
  }

  // 4
  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
    captureCurrent(finished = false)

  // 5
  override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
      captureCurrent(finished = playbackState == Player.STATE_ENDED)
    }
  }

  /**
   * 7. Blocking on purpose -- see the class documentation.
   *
   * Called from `MuPlaybackService.onDestroy`, where [scope] is about to be cancelled and a
   * launched coroutine would therefore write nothing at all.
   */
  fun flushBlocking() {
    val current = player.currentMediaItem ?: return
    val positionMs = player.currentPosition
    val gainDb = gainDbOf(current)
    runBlocking { write(current.mediaId, positionMs, finished = false, gainDb) }
  }

  /**
   * The read-modify-write. Public because the write-shape tests drive it directly, and because
   * every persistence point above is a thin wrapper over it.
   *
   * [gainDb] is **passed in rather than read here**, and that is not a style choice. This body runs
   * on [Dispatchers.IO]; a `Player` may only be read from the thread it was built on, and reading
   * `player.currentMediaItem` from inside this function throws `IllegalStateException: Player is
   * accessed on the wrong thread` -- measured on `muplay37`, in two tests, on the first run after
   * the gain stamp was added. So every caller reads it on the thread its callback arrived on, the
   * same rule this class already follows for the position, and a discontinuity reads it off the
   * item it is *leaving* rather than the one the player has already moved to.
   *
   * `null` means "the item carries no ReplayGain tag", not "zero decibels" -- see the fallback
   * chain below.
   */
  suspend fun write(mediaId: String, positionMs: Long, finished: Boolean, gainDb: Float?) {
    withContext(Dispatchers.IO) {
      writeLock.withLock {
        val existing = dao.find(mediaId)
        dao.upsert(
          MediaProgressEntity(
            mediaId = mediaId,
            // `C.TIME_UNSET` is negative and a player with no timeline reports it; a negative
            // position in the table would sort ahead of every real one.
            positionMs = positionMs.coerceAtLeast(0L),
            // Only ever set, never cleared. See the class documentation.
            isFinished = finished || (existing?.isFinished ?: false),
            lastPlayedAtEpochMs = clock.millis(),
            // Columns this class does not own the *meaning* of. `speed` and `skipSilence` are
            // the audiobook plan's and stay preserved-not-written.
            speed = existing?.speed ?: DEFAULT_SPEED,
            skipSilence = existing?.skipSilence ?: DEFAULT_SKIP_SILENCE,
            // `gainDb` is this plan's, as of Task 11: a **record of the gain the item was played
            // at**, so the row stops being decoration and Plan 5's watch snapshot carries something
            // true. One authority (the file's own tags, mirrored onto the `MediaItem`), one writer
            // (this class, because Plan 6 needs exactly one following the player switch).
            //
            // Still a fallback chain and not an unconditional write: an untagged item has no gain
            // to record, and overwriting a row with `DEFAULT_GAIN_DB` in that case would erase a
            // value a *previous, tagged* play had legitimately stored. Preserved, then defaulted.
            gainDb = gainDb ?: existing?.gainDb ?: DEFAULT_GAIN_DB,
          ),
        )
      }
    }
  }

  /**
   * One item's ReplayGain decision, or `null` when it carries none.
   *
   * Takes the item rather than reading the player, so that each caller hands over the item whose
   * row it is about to write -- which for a discontinuity is the one being *left*, not the one the
   * player has already moved to.
   *
   * `containsKey` before `getFloat`: see `MediaItems.KEY_REPLAY_GAIN_DB` for why an absent key and
   * not a sentinel is the encoding.
   */
  private fun gainDbOf(item: MediaItem?): Float? =
    item?.mediaMetadata?.extras
      ?.takeIf { it.containsKey(MediaItems.KEY_REPLAY_GAIN_DB) }
      ?.getFloat(MediaItems.KEY_REPLAY_GAIN_DB)

  /**
   * Reads where the player is **now** and persists it.
   *
   * The read is outside the coroutine on purpose: it has to happen on the thread this callback
   * arrived on, and by the time a launched coroutine ran the player could be somewhere else.
   */
  private fun captureCurrent(finished: Boolean) {
    val current = player.currentMediaItem ?: return
    val positionMs = player.currentPosition
    val gainDb = gainDbOf(current)
    scope.launch { write(current.mediaId, positionMs, finished, gainDb) }
  }

  companion object {
    /** Spec §3 asks for 5-10 s. Five: cheap, and the most a crash can cost is five seconds. */
    const val TICK_MS = 5_000L

    /**
     * What a row that does not exist yet starts from. Consumed **by name** from Plan 4, which reads
     * them as the values a book starts at before a listener has changed anything, and by the
     * read-modify-write above for the same row.
     */
    const val DEFAULT_SPEED = 1.0f
    const val DEFAULT_GAIN_DB = 0.0f
    const val DEFAULT_SKIP_SILENCE = false
  }
}
