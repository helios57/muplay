package app.muplay.media.browse

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import app.muplay.database.BrowseTreeRepository
import app.muplay.media.ControllerAccessPolicy
import app.muplay.media.PlaybackQueue
import app.muplay.media.QueueRepository
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseNode
import app.muplay.model.browse.BrowsePaging
import app.muplay.model.browse.BrowseSelection
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The browse half of the media session -- what Android Auto, Wear OS and the Assistant read.
 *
 * Plan 3 Task 5 left `MediaLibrarySession.Callback`'s browse methods at their *"not supported"*
 * defaults rather than faking an empty root, and said why: "not supported" was true, an empty root
 * would have been a claim. This class is that deferral being paid off, and it is the **only** change
 * to `MuPlaybackService` in Tasks 1-7.
 *
 * **It carries the connection gate too, and that is not duplication.** This class replaces the
 * `LibraryCallback` Plan 3 Task 5 installed, and that class's whole body was [onConnect]. The
 * *rule* stays where it was -- in [ControllerAccessPolicy], reachable from the fast tier with no
 * Media3 type in its signature -- and what moved here is the two-line adapter that reads two
 * properties off a `ControllerInfo`. Losing it would re-open an exported session to every app on
 * the device; `ControllerAccessGateTest` drives this class's own [onConnect], so the gate is now
 * verified on exactly the object production installs rather than on a sibling of it.
 *
 * **Media3 makes the gate sequential with browsing.** `onGetLibraryRoot`, `onGetChildren` and
 * `onGetItem` are only ever called for a controller [onConnect] admitted, so the surface resolver's
 * input set is exactly what that gate passed. It follows that nothing below re-checks it.
 *
 * **The surface is resolved per request, never cached.** One session serves many controllers at
 * once -- the phone UI, a car head unit and the system's media controls can all be connected
 * simultaneously -- so a field holding "the surface" would give whichever connected second the
 * other one's tree.
 *
 * **`session.player` is never read, and Task 5 did not change that.** Plan 6 (casting) swaps the
 * session's player when audio moves to a speaker, and a cached reference would leave the browse
 * tree driving a player nothing is listening to. Task 4 expected [onAddMediaItems] and
 * [onSetMediaItems] to be the first readers; they are not, and that is better than the property
 * this note was written to protect. Both of them *answer* Media3 with items and an index and let
 * `MediaSessionImpl` apply them to whatever player the session holds at that moment, so there is no
 * reference to go stale -- and no `seekTo` either, which is what keeps spec section 3's
 * "no code path can set a position" true of this file.
 *
 * **`onSubscribe`/`notifyChildrenChanged` are deliberately not implemented.** A subscribing browser
 * is told when a folder's contents change; ours change when Plan 2's `SyncEngine` reconciles, which
 * is a background event a car cannot see happening. Implementing it means holding a subscription per
 * controller and pushing invalidations from a repository into a session -- a live-update path with
 * its own concurrency whose only observable effect is a list refreshing while parked. The cost is
 * that a car re-entering a folder sees new content and one already looking at it does not.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn`: `MediaLibrarySession` and `LibraryParams` are
// `@UnstableApi`, which the Kotlin compiler cannot see at all -- `check` fails much later, at
// `lintDebug`, with `UnsafeOptInUsageError`. See `MuPlayerFactory` for the full argument.
@OptIn(UnstableApi::class)
@Singleton
class MuPlayLibraryCallback @Inject constructor(
  private val treeRepository: BrowseTreeRepository,
  private val surfaceResolver: SurfaceResolver,
  private val queueRepository: QueueRepository,
) : MediaLibrarySession.Callback {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * Refuses a controller [ControllerAccessPolicy] does not accept, and otherwise leaves the decision
   * exactly where Media3 had it.
   *
   * **`onConnect` and not `onConnectAsync`, and that is a measurement.**
   * `MediaSessionImpl.onConnectOnHandler` calls `Callback.onConnect` *first* and only falls through
   * to `onConnectAsync` when the returned result is accepted **and** carries Media3's
   * `androidx.media3.session.CALLBACK_NOT_IMPLEMENTED` sentinel in its `sessionExtras` -- which is
   * precisely what the interface's own default `onConnect` returns. Overriding the async half alone
   * would leave the sync half answering first.
   *
   * The accepted arm is `super.onConnect(...)`, i.e. that sentinel, rather than a hand-built
   * `AcceptedResultBuilder(session, controller).build()`. The two are the same thing today, and
   * delegating means a legitimate controller keeps whatever Media3's default is -- including the
   * narrowing it already applies to an untrusted-but-accepted caller -- instead of this file
   * freezing a copy of it.
   */
  override fun onConnect(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
  ): MediaSession.ConnectionResult =
    if (ControllerAccessPolicy.accepts(controller.packageName, controller.isTrusted)) {
      super.onConnect(session, controller)
    } else {
      MediaSession.ConnectionResult.reject()
    }

  override fun onGetLibraryRoot(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val surface = surfaceResolver.surfaceOf(browser)
    // Synchronously, with no repository call at all: `onGetLibraryRoot` is the first thing a car
    // does on connect, and a root that waits on a database read is a car that shows a spinner
    // before it shows an app name.
    return immediate(LibraryResult.ofItem(BrowseItems.root(surface), params))
  }

  override fun onGetChildren(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    val surface = surfaceResolver.surfaceOf(browser)
    return future {
      val id = BrowseId.decode(parentId)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      val children: List<BrowseNode> = treeRepository.children(id, surface)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      // Paged *before* the artwork URLs are resolved, so a host asking for ten rows of a thousand
      // pays for ten -- and paged at all, because `MediaLibrarySessionImpl.verifyResultItems`
      // throws `IllegalStateException("Invalid size=.., pageSize=..")` on the session's own handler
      // for an over-long result, which is a process death rather than an error a controller sees.
      // Measured on the emulator; see `BrowsePaging`'s own note.
      val items = BrowsePaging.page(children, page, pageSize).map { node ->
        BrowseItems.of(node, treeRepository.artworkUri(node.artworkId))
      }
      LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
    }
  }

  override fun onGetItem(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val surface = surfaceResolver.surfaceOf(browser)
    return future {
      val id = BrowseId.decode(mediaId)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      if (id == BrowseId.Root) {
        return@future LibraryResult.ofItem(BrowseItems.root(surface), null)
      }
      val node = treeRepository.node(id, surface)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      LibraryResult.ofItem(BrowseItems.of(node, treeRepository.artworkUri(node.artworkId)), null)
    }
  }

  /**
   * Acknowledges a query and tells the browser how many results there are.
   *
   * Media3's contract is two calls -- this one, then [onGetSearchResult] for each page -- and this
   * implementation deliberately keeps **no cache** between them. The search runs against the local
   * mirror, so recomputing is a Room query; caching would be a map keyed by controller and query,
   * with an eviction policy and a staleness question every time Plan 2's `SyncEngine` reconciles.
   * See `BrowseTreeRepository.search`'s own note.
   *
   * `notifySearchResultChanged` happens **before** the future completes, and that ordering is the
   * one thing in this method that can be wrong without failing loudly: a browser that asks for page
   * 0 the instant its future resolves would otherwise race the notification and see nothing.
   *
   * A search box in a car is drawn by the host whether or not the app answers, so there is no way
   * to say "not supported" to it -- which is why an app with a browse tree and no search reads as
   * broken rather than as limited. A failed search is reported as **zero results**, not as an
   * error: the box is already on screen and "0" is what it is able to render.
   */
  override fun onSearch(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<Void>> {
    val settable = SettableFuture.create<LibraryResult<Void>>()
    scope.launch {
      val count = runCatching { treeRepository.search(query).size }.getOrDefault(0)
      session.notifySearchResultChanged(browser, query, count, params)
      settable.set(LibraryResult.ofVoid(params))
    }
    return settable
  }

  /**
   * One page of the result list, ordered and paged exactly the way [onGetChildren] orders and pages
   * a folder -- because a car renders both with the same content styles and the same row budget.
   *
   * Recomputed rather than read from whatever [onSearch] counted; see that method for why.
   */
  override fun onGetSearchResult(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
    val nodes = treeRepository.search(query)
    // Paged before the artwork URLs are resolved, and clamped -- see `onGetChildren` and
    // `BrowsePaging` for what an over-long result does to this process.
    val items = BrowsePaging.page(nodes, page, pageSize).map { node ->
      BrowseItems.of(node, treeRepository.artworkUri(node.artworkId))
    }
    LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
  }

  /**
   * The queue a **spoken** query should start, or `null` when there is nothing to play.
   *
   * Public because two callers reach the same decision by different routes, and a second copy of it
   * would be a second answer: [onSetMediaItems] serves the Assistant when this app is already
   * connected, and `MuPlaybackService.onStartCommand` serves it cold, from an
   * `ACTION_MEDIA_PLAY_FROM_SEARCH` intent. Both end here.
   *
   * The start position is `C.TIME_UNSET`, like every other queue this class answers with: `MuPlayer`
   * discards it and asks the resume policy, so *"play my book"* said out loud resumes for the same
   * reason tapping it in a car does.
   */
  suspend fun spokenQueue(query: String): MediaSession.MediaItemsWithStartPosition? {
    val selection = treeRepository.searchSelection(query) ?: return null
    return MediaSession.MediaItemsWithStartPosition(
      items(selection),
      selection.startIndex,
      C.TIME_UNSET,
    )
  }

  /**
   * Turns whatever a controller asked to play into items this player can actually stream.
   *
   * Two kinds of caller, and the difference is one field:
   *
   * - **This app's own UI** hands over items Plan 3 already built, complete with an authenticated
   *   `format=raw` URL in their `localConfiguration`. Those pass through **unchanged** -- rebuilding
   *   them would discard the very fields the caller computed, and re-expanding a track id would
   *   turn "play this shuffle" into "play the album the first shuffled track came from".
   * - **A car, a watch, the Assistant or the system's resumption row** hands over a bare `mediaId`
   *   and nothing else, because a browse row never had a `localConfiguration` to begin with. Those
   *   are expanded through the tree.
   *
   * **`localConfiguration` is the discriminator, and it does survive the controller hop --
   * measured, because the plan said the opposite.** `MediaControllerImplBase` bundles every item it
   * sends with `MediaItem.toBundleIncludeLocalConfiguration` (1.11.0 bytecode), which is why
   * Media3's own default `onAddMediaItems` returns the items unchanged when each carries one and
   * fails with `UnsupportedOperationException` when any does not -- exactly this split, and the
   * behaviour `PlaybackLauncher` has been relying on since Plan 3. So the field is a fact about the
   * *caller*, not an artefact of the wire.
   */
  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<List<MediaItem>> =
    if (mediaItems.all { it.localConfiguration != null }) {
      // **Synchronously, and that is a correctness requirement rather than an optimisation.**
      // See the note below `onSetMediaItems`'s note.
      immediate(mediaItems)
    } else {
      future(emptyList()) { resolve(mediaItems) }
    }

  /**
   * The same resolution, plus the **index** -- which is this plan's to choose and the resume
   * policy's not to.
   *
   * The returned start position is always `C.TIME_UNSET`. Plan 3's `MuPlayer` discards whatever
   * arrives here and asks `ResumePolicy` for the real one, which is the guarantee that *no code
   * path can set a wrong position*. This callback is a code path; it does not get one. Passing
   * `startPositionMs` through instead would be invisible to every test in this repository -- Media3
   * sends `0` for a fresh `setMediaItem` and `MuPlayer` discards it either way -- and that is the
   * seam working, not a hole in it.
   *
   * **A browse row is a single item with no `localConfiguration`.** Anything else -- a queue the
   * app built, several items at once -- keeps the index the caller asked for, because that caller
   * already knows its own queue. `PlaybackLauncher.play` is the one that does, and
   * `PlaybackJourneyTest` is what fails if this branch ever stops distinguishing them.
   */
  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    if (mediaItems.all { it.localConfiguration != null }) {
      // See the note below `onSetMediaItems`. Nothing here needs a repository, so nothing here may suspend.
      return immediate(
        MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, C.TIME_UNSET),
      )
    }
    return future(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)) {
      // The Assistant, with this app already connected: Media3 turns a legacy `playFromSearch` into
      // `onSetMediaItems` carrying a query on the item's request metadata and no usable media id at
      // all. Checked *before* the browse-id expansion, because that id is a placeholder.
      val spoken = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
      if (spoken != null) {
        return@future spokenQueue(spoken)
          ?: MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }

      val selection = mediaItems.singleOrNull()
        ?.takeIf { it.localConfiguration == null }
        ?.let { BrowseId.decode(it.mediaId) }
        ?.let { treeRepository.expand(it) }

      if (selection == null) {
        MediaSession.MediaItemsWithStartPosition(resolve(mediaItems), startIndex, C.TIME_UNSET)
      } else {
        MediaSession.MediaItemsWithStartPosition(items(selection), selection.startIndex, C.TIME_UNSET)
      }
    }
  }

  /**
   * Why the passthrough answers **synchronously**, measured rather than reasoned.
   *
   * `PlaybackLauncher.play` sends three commands back to back -- `setMediaItems`, `prepare`,
   * `play` -- and Media3 does **not** hold the second and third behind a pending future from the
   * first. `MediaSessionStub` completes the queue change through `handleMediaItemsWhenReady`, so a
   * future resolved on another dispatcher lands *after* `prepare()` and `play()` have already been
   * applied to an empty player. Media3's own default `onAddMediaItems` returns
   * `Futures.immediateFuture(...)` for exactly this reason, and matching it is what keeps the
   * ordering the rest of this app was built on.
   *
   * The cost of getting it wrong is silent: no exception, no player error, no log. The player sits
   * in `STATE_BUFFERING` with `isPlaying == false` for ever. Measured on `muplay37`: with the
   * unconditional `scope.launch` this method first had, **11 of `MuPlaybackServiceTest`'s 15 tests
   * failed** with `position never reached 1000ms; state=2 isPlaying=false error=null`, and all 15
   * passed with these two overrides removed entirely. No test in this plan would have caught it --
   * the browse path is asynchronous by necessity and works, because a car sends its own `prepare`
   * and `play` and the late queue is then prepared under a `playWhenReady` that is already true.
   *
   * So: **a request that needs no repository must not suspend.** An item that already carries its
   * own `localConfiguration` needs nothing looked up, which is the same field that decides whether
   * it is expanded at all.
   */


  /**
   * Every item, either passed through or expanded.
   *
   * `flatMap`, so one tapped row becomes a whole queue -- and so an id that names nothing playable
   * contributes **nothing** rather than a hole in the list. Media3 refuses a `MediaItem` with no
   * URI outright (`IllegalArgumentException` from the player), so "skip it" is the only answer that
   * leaves the rest of the request working.
   */
  private suspend fun resolve(mediaItems: List<MediaItem>): List<MediaItem> =
    mediaItems.flatMap { item ->
      // The one field that separates the two kinds of caller. Present means "already playable".
      if (item.localConfiguration != null) {
        listOf(item)
      } else {
        BrowseId.decode(item.mediaId)
          ?.let { treeRepository.expand(it) }
          ?.let { items(it) }
          .orEmpty()
      }
    }

  private suspend fun items(selection: BrowseSelection): List<MediaItem> =
    queueRepository.mediaItems(PlaybackQueue.of(selection.songs, selection.startIndex))

  /** Called from `MuPlaybackService.onDestroy`. */
  fun release() {
    scope.cancel()
  }

  private fun <T> immediate(value: T): ListenableFuture<T> =
    SettableFuture.create<T>().apply { set(value) }

  /**
   * Runs [block] off the session thread and answers Media3 with its result, or with [onFailure].
   *
   * The playback twin of [future] below, and it needs its own because the two answer different
   * shapes: a browse call has `LibraryResult.ofError` to say "that went wrong", and a playback call
   * has no error channel at all -- `onAddMediaItems` returns a bare list. An exception on the
   * future would reach Media3 as an `ExecutionException` it logs and swallows, so the failure
   * arrives as *nothing played, no reason given* either way; answering with an empty queue at least
   * leaves the session in a state a controller can retry from.
   *
   * **The throwable is not logged, and that is this module's standing rule rather than an
   * omission** -- `ConventionTest`'s *nothing in the media module logs*, which the plan's own
   * sketch of this method would have broken. Every `MediaItem` in scope here is one `toString()`
   * away from an authenticated Subsonic URL, and a queue that will not build is exactly the moment
   * somebody reaches for a log line.
   */
  private fun <T : Any> future(onFailure: T, block: suspend () -> T): ListenableFuture<T> {
    val settable = SettableFuture.create<T>()
    scope.launch {
      settable.set(runCatching { block() }.getOrElse { onFailure })
    }
    return settable
  }

  /**
   * Runs [block] off the session thread and answers Media3 with its result.
   *
   * A failure becomes `ERROR_UNKNOWN` rather than an exception on the future, deliberately: an
   * exception here is logged by Media3 and reaches a driver as an empty screen with no explanation,
   * whereas an error result is rendered as an error. `BrowseId.Track`'s own
   * `IllegalArgumentException` (Task 1) is the one this actually catches.
   *
   * **The throwable is not logged, and that is this module's standing rule rather than an
   * omission.** `ConventionTest`'s *nothing in the media module logs* holds it: every `MediaItem`
   * this class builds is one `toString()` away from an authenticated Subsonic URL, and a browse
   * failure is the moment somebody reaches for a log line.
   */
  // `T : Any`: Media3 declares `LibraryResult.ofError` as `<V> LibraryResult<V>` under
  // `@NonNull` defaults, so Kotlin reads `V` as non-nullable and a plain `<T>` here cannot
  // satisfy it. Both call sites pass a non-null payload type anyway.
  private fun <T : Any> future(
    block: suspend () -> LibraryResult<T>,
  ): ListenableFuture<LibraryResult<T>> {
    val future = SettableFuture.create<LibraryResult<T>>()
    scope.launch {
      // `<T>` spelled out: without it Kotlin infers `LibraryResult<T & Any>` from the platform
      // signature and the two arms of `getOrElse` no longer agree.
      val result: LibraryResult<T> = runCatching { block() }
        .getOrElse { LibraryResult.ofError<T>(SessionError.ERROR_UNKNOWN) }
      future.set(result)
    }
    return future
  }
}
