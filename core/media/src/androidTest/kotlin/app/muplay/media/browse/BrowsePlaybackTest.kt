package app.muplay.media.browse

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.ShuffleRepository
import app.muplay.model.Song
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a tapped browse row becomes: a queue, and an index into it.
 *
 * The subject is `MuPlayLibraryCallback.onAddMediaItems`/`onSetMediaItems` over the **real**
 * `BrowseTreeRepository`, the **real** `QueueRepository` and a **real** in-memory Room database --
 * the same [BrowseGraph] the browse suite uses, so that "a tapped row plays the album it belongs
 * to" is a claim about the objects production installs rather than about a copy of them.
 *
 * ### What this reaches, and what it deliberately does not
 *
 * Reached: the two callbacks, the expansion rule underneath them, and the `MediaItem` construction
 * on the far side -- which is why this is an instrumented test at all. Spec sections 2 and 10 ban
 * Robolectric, and `androidx.media3.common.MediaItem` reaches `android.net.Uri`, an unimplemented
 * stub on the JVM.
 *
 * Not reached, on purpose: Media3's own dispatch and the player. The callbacks are invoked
 * directly, with the `ControllerInfo` a session's own stub would have built, exactly as
 * `ControllerAccessGateTest` drives `onConnect` -- and for the same reason: this file's subject is
 * the *answer*, and a `MediaSession` in the way would only decide whether a `NoOpPlayer` was
 * allowed to receive it. That a real controller's `setMediaItem` really does arrive here, and that
 * the answer really does reach a decoder, is `app.muplay.CarResumeJourneyTest`, over IPC, against
 * the real service and real audio.
 *
 * **No stream URL is asserted, and none can be.** `RecordingArtSource` builds `http://stream
 * .invalid/<id>` with no `u`, `s` or `t` parameter of any kind, so no failure message in this file
 * can print a Subsonic credential. See that class's own note.
 *
 * Method names are camelCase because `minSdk 26` compiles DEX 035, which forbids a space in any
 * `SimpleName` -- a backticked instrumented test does not dex at all.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class BrowsePlaybackTest {

  private lateinit var context: Context
  private lateinit var graph: BrowseGraph
  private lateinit var callback: MuPlayLibraryCallback
  private lateinit var session: MediaSession

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    graph = BrowseGraph.create(context)
    callback = graph.callback(context)
    onMain {
      session = MediaSession.Builder(context, InertPlayer())
        .setId("browse-playback-${System.nanoTime()}")
        .build()
    }
  }

  @After
  fun tearDown() {
    onMain {
      session.player.release()
      session.release()
    }
    callback.release()
    graph.close()
  }

  // ---- albums and tracks: one rule, two readings -----------------------------------------------

  @Test
  fun anAlbumRowBecomesItsWholeAlbumFromTheTop() {
    val played = setMediaItem("muplay/album/al-abbey")

    // `containsExactly`, never `contains`: the order is the album's own, and a queue assembled in
    // any other order is a different album to listen to.
    assertThat(played.mediaIds).containsExactly("tr-a1", "tr-a2", "tr-a3")
    assertThat(played.startIndex).isEqualTo(0)
  }

  @Test
  fun aTrackRowBecomesItsOwnAlbumPositionedAtItself() {
    // Three taps, three answers, and the queue is a property of the tap too: `tr-r1` is in a
    // different album from the other two. An index hardcoded to 0 passes the first and fails the
    // other two; a queue hardcoded to "the album of whatever was tapped first" fails the third.
    assertThat(listOf("tr-a1", "tr-a2", "tr-a3", "tr-r1").map { setMediaItem(it).startIndex })
      .containsExactly(0, 1, 2, 0)
    assertThat(setMediaItem("tr-a2").mediaIds).containsExactly("tr-a1", "tr-a2", "tr-a3")
    assertThat(setMediaItem("tr-r1").mediaIds).containsExactly("tr-r1", "tr-r2")
  }

  @Test
  fun aTrackRowThatIsAFileOfABookBecomesThatBookFromThatFile() {
    // The third and fourth rows of the plan's table are the same code path, and this is the
    // observation that says so: nothing here knows it is holding a book.
    val played = setMediaItem("bk-multi-p3")

    assertThat(played.mediaIds)
      .containsExactly("bk-multi-p1", "bk-multi-p2", "bk-multi-p3", "bk-multi-p4")
    assertThat(played.startIndex).isEqualTo(2)
  }

  // ---- books: the index is the file the listener was in ----------------------------------------

  @Test
  fun aBookRowStartsOnTheFileTheListenerWasLastIn() {
    // Four books, three distinct answers, and every wrong rule this fixture can express fails on
    // at least one of them:
    //
    //   bk-multi  rows on p3 (2000 ms) and p4 (1500 ms)  -> 2. "the last row in the list" answers
    //                                                       3; "the first row" answers 2 as well,
    //                                                       which is why bk-test is here too.
    //   bk-test   rows on p1 (5500 ms) and p2 (6000 ms)  -> 1. "the first row" answers 0.
    //   bk-alpha  rows on p1 and p2, same millisecond    -> 1. The tie goes to the later file, the
    //                                                       same way the Continue shelf breaks it.
    //   bk-nine   no row at all                          -> 0. Never -1, and never "the last file".
    val books = listOf("bk-multi", "bk-test", "bk-alpha", "bk-nine")
    assertThat(books.map { setMediaItem("muplay/book/$it").startIndex })
      .containsExactly(2, 1, 1, 0)

    // ...and the queue really is that book's own files, in order, rather than a queue of one.
    assertThat(setMediaItem("muplay/book/bk-multi").mediaIds)
      .containsExactly("bk-multi-p1", "bk-multi-p2", "bk-multi-p3", "bk-multi-p4")
  }

  @Test
  fun aBookWithNoStoredPositionAtAllStartsAtItsFirstFile() {
    // The withheld-progress fixture, so that "starts at 0" is observed once against a book that
    // *has* rows elsewhere in the shelf and once against a shelf with no rows at all -- otherwise
    // "no row" and "no reader of rows" are the same observation.
    val bare = BrowseGraph.create(context, withProgress = false)
    try {
      val bareCallback = bare.callback(context)
      try {
        val played = setMediaItem("muplay/book/bk-multi", bareCallback)
        assertThat(played.startIndex).isEqualTo(0)
        assertThat(played.mediaIds)
          .containsExactly("bk-multi-p1", "bk-multi-p2", "bk-multi-p3", "bk-multi-p4")
      } finally {
        bareCallback.release()
      }
    } finally {
      bare.close()
    }
  }

  @Test
  fun aBookIdIsScopedToTheAudiobookLibrariesTheUserTagged() {
    // `muplay/book/al-abbey` is a *music* album's id worn as a book. Spec section 4 leaves this app
    // exactly one mechanism -- the library role -- and an id a controller can type must not
    // bypass it. `bk-empty` is the other empty answer: a book row whose files the mirror has not
    // reached yet.
    assertThat(setMediaItem("muplay/book/al-abbey").mediaIds).isEmpty()
    assertThat(setMediaItem("muplay/book/bk-empty").mediaIds).isEmpty()
    // The control that keeps the two above from being vacuous: a real book id is playable.
    assertThat(setMediaItem("muplay/book/bk-nine").mediaIds).isNotEmpty
  }

  // ---- shuffle: spec section 1, reaching a car seat ---------------------------------------------

  @Test
  fun theShuffleRowShufflesTheLibraryItsOwnIdNames() {
    val musicSongs = songsOf("al-abbey")
    val bookFiles = songsOf("bk-multi")
    graph.artSource.randomSongsByLibrary[BrowseGraph.MUSIC_LIBRARY_ID] = musicSongs
    graph.artSource.randomSongsByLibrary[BrowseGraph.AUDIOBOOK_LIBRARY_ID] = bookFiles

    val music = setMediaItem("muplay/shuffle/${BrowseGraph.MUSIC_LIBRARY_ID}")
    val books = setMediaItem("muplay/shuffle/${BrowseGraph.AUDIOBOOK_LIBRARY_ID}")

    // Two libraries, two different queues. A shuffle row that passed a constant library id -- the
    // defect this asserts against -- answers the same list twice, and the music one would then be
    // full of chapters. `containsExactly`, because a shuffle's order is the order it is played in.
    assertThat(music.mediaIds).containsExactly("tr-a1", "tr-a2", "tr-a3")
    assertThat(books.mediaIds)
      .containsExactly("bk-multi-p1", "bk-multi-p2", "bk-multi-p3", "bk-multi-p4")
    assertThat(music.startIndex).isEqualTo(0)

    // ...and the id really did carry the library into the repository, at the size the browse tree
    // asks for rather than at whatever the source felt like answering.
    assertThat(graph.artSource.randomSongsCalls).containsExactly(
      BrowseGraph.MUSIC_LIBRARY_ID to ShuffleRepository.DEFAULT_SHUFFLE_SIZE,
      BrowseGraph.AUDIOBOOK_LIBRARY_ID to ShuffleRepository.DEFAULT_SHUFFLE_SIZE,
    )
  }

  @Test
  fun aShuffleThatDrawsAnOutOfScopeSongNeverQueuesIt() {
    // The server answering with something the mirror does not place in the requested library is
    // `ShuffleRepository`'s guard, and this is that guard observed from where a driver would meet
    // it. Without the book part in the draw, "the queue is music" is satisfied by a shuffle that
    // was never given anything else.
    graph.artSource.randomSongsByLibrary[BrowseGraph.MUSIC_LIBRARY_ID] =
      songsOf("al-abbey") + songsOf("bk-multi").first()

    assertThat(setMediaItem("muplay/shuffle/${BrowseGraph.MUSIC_LIBRARY_ID}").mediaIds)
      .containsExactly("tr-a1", "tr-a2", "tr-a3")
  }

  @Test
  fun aShuffleThatDrawsNothingIsNotPlayable() {
    // No seeded draw for either library. An empty queue reaches a player as an
    // IllegalArgumentException inside a ListenableFuture; nothing at all reaches a car as
    // "there is nothing here".
    assertThat(setMediaItem("muplay/shuffle/${BrowseGraph.MUSIC_LIBRARY_ID}").mediaIds).isEmpty()
  }

  // ---- what is not playable at all ---------------------------------------------------------------

  @Test
  fun aBrowsableOnlyRowIsNotPlayable() {
    val browsable = listOf(
      "muplay/root", "muplay/continue", "muplay/books", "muplay/albums", "muplay/artists",
      "muplay/libraries", "muplay/library/1", "muplay/artist/ar-bowie",
    )
    // Mapped and asserted as an exact list rather than with `allMatch`, which is vacuously true on
    // an empty collection -- this plan's own rule 3.
    assertThat(browsable.map { setMediaItem(it).mediaIds.size })
      .containsExactly(0, 0, 0, 0, 0, 0, 0, 0)
    // The control: the same call shape, on an id that *is* playable.
    assertThat(setMediaItem("muplay/album/al-abbey").mediaIds).hasSize(3)
  }

  @Test
  fun anIdThisBuildDoesNotRecogniseIsNotPlayable() {
    assertThat(setMediaItem("muplay/nonsense/7").mediaIds).isEmpty()
    assertThat(setMediaItem("muplay/album/does-not-exist").mediaIds).isEmpty()
    assertThat(setMediaItem("no-such-song").mediaIds).isEmpty()
  }

  // ---- the Assistant, reaching an app that is already connected ---------------------------------

  /**
   * `onSetMediaItems` carrying a **search query** instead of a usable id.
   *
   * This is what Media3 turns a legacy `playFromSearch` into when the Assistant reaches an app that
   * is already connected: one item, a placeholder media id, and the spoken words on
   * `requestMetadata`. `VoiceSearchJourneyTest` drives the *cold* half of the same feature (an
   * `ACTION_MEDIA_PLAY_FROM_SEARCH` intent into `MuPlaybackService.onStartCommand`) and
   * `BrowseSearchBrowserTest` calls `spokenQueue` directly -- neither reaches this branch, and
   * measured, **nothing in the repository did**: `MuPlayLibraryCallback$onSetMediaItems$2` read LINE
   * 10/12 = 0.8333 against a 0.90 floor and these were the two missing lines.
   *
   * The media id here is a real, expandable album row, and the query names a *different* thing. So
   * this pins the precedence the source comment claims -- the query is read **before** the browse-id
   * expansion, because that id is a placeholder -- rather than merely that a query is read at all.
   * Expanding the id instead would answer with Hunky Dory, and that is a different assertion.
   *
   * The expectation is read back out of the mirror rather than written down, so a corpus change
   * moves it instead of breaking it.
   */
  @Test
  fun aSpokenQueryBeatsThePlaceholderIdItArrivesWith() {
    val spoken = MediaItem.Builder()
      .setMediaId("muplay/album/al-hunky")
      .setRequestMetadata(
        MediaItem.RequestMetadata.Builder().setSearchQuery("Tail Book").build(),
      )
      .build()

    val played = onSetMediaItems(listOf(spoken), startIndex = 0)

    assertThat(played.mediaIds).containsExactlyElementsOf(songsOf("bk-tail").map { it.id })
    assertThat(played.mediaIds).doesNotContainAnyElementsOf(songsOf("al-hunky").map { it.id })
    // Like every other queue this class answers with: `MuPlayer` discards it and asks the resume
    // policy, which is why *"play my book"* said out loud resumes.
    assertThat(played.startPositionMs).isEqualTo(C.TIME_UNSET)
  }

  /**
   * The other arm of that answer: a spoken query the library cannot satisfy **at all**.
   *
   * `spokenQueue` falls back to "play something rather than nothing" whenever any playable row
   * exists (`BrowseSearchBrowserTest.aSpokenQueryThatMatchesNothingStillProducesAQueue`), so the
   * only way to make it return `null` is a library with nothing in it -- which is a real state, and
   * the one a listener reaches by asking the Assistant for music before the first sync has
   * finished. An empty queue is the answer; an exception on the future is rendered by Media3 as a
   * blank screen with no explanation.
   *
   * Builds its own graph rather than reusing the seeded one, and closes it here: the class fixture
   * is deliberately full, and emptying it would change every other test in this file.
   */
  @Test
  fun aSpokenQueryAnEmptyLibraryCannotAnswerIsAnEmptyQueueAndNotAnError() {
    val empty = BrowseGraph.create(
      context,
      withProgress = false,
      withAudiobooks = false,
      withMusic = false,
    )
    val emptyCallback = empty.callback(context)
    try {
      // No media id at all, which is what a placeholder really looks like on the wire.
      val spoken = MediaItem.Builder()
        .setRequestMetadata(
          MediaItem.RequestMetadata.Builder().setSearchQuery("anything at all").build(),
        )
        .build()

      val played = played(
        emptyCallback.onSetMediaItems(session, controller(), listOf(spoken), 0, 0L)
          .get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
      )

      assertThat(played.mediaIds).isEmpty()
      assertThat(played.startIndex).isZero
      assertThat(played.startPositionMs).isEqualTo(C.TIME_UNSET)
    } finally {
      emptyCallback.release()
      empty.close()
    }
  }

  // ---- the app's own queue passes through untouched -----------------------------------------------

  @Test
  fun anItemThatAlreadyCarriesItsOwnUriPassesThroughUnchanged() {
    // What `PlaybackLauncher` hands over: items Plan 3 built, complete with an authenticated URL in
    // their `localConfiguration`. Rebuilding them would discard the fields the caller computed, and
    // re-expanding this one would turn "play this shuffle" into "play the album track one came
    // from" -- which is why the queue length is asserted and not only the id.
    val alreadyPlayable = MediaItem.Builder()
      .setMediaId("tr-a2")
      .setUri(PASSTHROUGH_URI)
      .build()

    val resolved = addMediaItems(listOf(alreadyPlayable))

    assertThat(resolved.map { it.mediaId }).containsExactly("tr-a2")
    assertThat(resolved.single().localConfiguration?.uri?.toString()).isEqualTo(PASSTHROUGH_URI)
  }

  @Test
  fun theTwoKindsOfCallerAreToldApartWithinOneRequest() {
    // The same `tr-a2`, twice, differing in exactly one field. One passes through as a queue of
    // one; the other expands to its album. A resolver that keyed on anything else -- the id's
    // shape, the request's size, the caller -- cannot produce both answers here.
    val alreadyPlayable = MediaItem.Builder().setMediaId("tr-a2").setUri(PASSTHROUGH_URI).build()
    val browseRow = MediaItem.Builder().setMediaId("tr-a2").build()

    assertThat(addMediaItems(listOf(alreadyPlayable, browseRow)).map { it.mediaId })
      .containsExactly("tr-a2", "tr-a1", "tr-a2", "tr-a3")
  }

  @Test
  fun aCallerThatBroughtItsOwnQueueKeepsItsOwnIndex() {
    // `PlaybackLauncher.play(queue)` sends the whole queue and the index it chose. Nothing about
    // that request is a browse row, and the index must survive: this is the passthrough half of
    // Plan 3 Task 6's fix, asserted at the layer Plan 5 could break it from.
    val own = runBlocking { graph.queueRepository.mediaItems(queueOf("al-abbey", startIndex = 2)) }

    val played = onSetMediaItems(own, startIndex = 2)

    assertThat(played.mediaIds).containsExactly("tr-a1", "tr-a2", "tr-a3")
    assertThat(played.startIndex).isEqualTo(2)
  }

  @Test
  fun anAlreadyPlayableRequestIsAnsweredWithoutSuspendingAtAll() {
    // **A correctness requirement, not an optimisation, and it cost eleven red tests to learn.**
    //
    // `PlaybackLauncher.play` sends `setMediaItems`, `prepare` and `play` back to back, and Media3
    // does not hold the last two behind a pending future from the first. A future completed on
    // another dispatcher therefore lands *after* the player has already been prepared and played
    // empty, and the player then sits in `STATE_BUFFERING` for ever -- no exception, no player
    // error, no log. Measured on `muplay37`: 11 of `MuPlaybackServiceTest`'s 15 tests failed that
    // way, and all 15 passed with these callbacks removed entirely.
    //
    // `isDone` is read **before** anything waits on the future, which is the only moment at which
    // the two implementations differ. Media3's own default answers this case with
    // `Futures.immediateFuture(...)`; this asserts that ours still does.
    val own = runBlocking { graph.queueRepository.mediaItems(queueOf("al-abbey", startIndex = 1)) }

    assertThat(
      listOf(
        callback.onAddMediaItems(session, controller(), own).isDone,
        callback.onSetMediaItems(session, controller(), own, 1, 0L).isDone,
      ),
    ).containsExactly(true, true)
  }

  // ---- the position, and the failure ----------------------------------------------------------

  @Test
  fun everyAnswerCarriesAnUnsetStartPosition() {
    // A **control**, and it is labelled as one: spec section 3's guarantee is enforced by
    // `MuPlayer`, which discards whatever arrives here. This asserts that this file does not try,
    // on all three shapes of answer -- a browse row, a caller's own queue, and nothing at all.
    // Passing `startPositionMs` through instead would change no other assertion in this repository.
    val own = runBlocking { graph.queueRepository.mediaItems(queueOf("al-abbey", startIndex = 0)) }

    assertThat(
      listOf(
        setMediaItem("muplay/album/al-abbey").startPositionMs,
        onSetMediaItems(own, startIndex = 1, startPositionMs = 42_000L).startPositionMs,
        setMediaItem("muplay/artists").startPositionMs,
      ),
    ).containsExactly(C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET)
  }

  @Test
  fun aRepositoryFailureBecomesAnEmptyQueueRatherThanAnExceptionOnTheFuture() {
    // The database is closed under the repository, which is what a real failure looks like from
    // here. An exception on the future reaches Media3 as an `ExecutionException` it logs and
    // swallows: nothing plays, and the session is left in a state a controller cannot retry from.
    graph.database.close()

    val played = setMediaItem("muplay/album/al-abbey")
    assertThat(played.mediaIds).isEmpty()
    assertThat(played.startIndex).isEqualTo(0)
    assertThat(played.startPositionMs).isEqualTo(C.TIME_UNSET)

    assertThat(addMediaItems(listOf(MediaItem.Builder().setMediaId("tr-a1").build()))).isEmpty()
  }

  // ---- plumbing --------------------------------------------------------------------------------

  /** What a `MediaItemsWithStartPosition` says, in the three values this file asserts. */
  private data class Played(
    val mediaIds: List<String>,
    val startIndex: Int,
    val startPositionMs: Long,
  )

  /** One tapped browse row: a single item carrying nothing but its id, exactly as a car sends it. */
  private fun setMediaItem(
    mediaId: String,
    target: MuPlayLibraryCallback = callback,
  ): Played = played(
    target.onSetMediaItems(
      session,
      controller(),
      listOf(MediaItem.Builder().setMediaId(mediaId).build()),
      /* startIndex = */ 0,
      /* startPositionMs = */ 0L,
    ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
  )

  private fun onSetMediaItems(
    items: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long = 0L,
  ): Played = played(
    callback.onSetMediaItems(session, controller(), items, startIndex, startPositionMs)
      .get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
  )

  private fun addMediaItems(items: List<MediaItem>): List<MediaItem> =
    callback.onAddMediaItems(session, controller(), items).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

  private fun played(result: MediaSession.MediaItemsWithStartPosition) = Played(
    mediaIds = result.mediaItems.map { it.mediaId },
    startIndex = result.startIndex,
    startPositionMs = result.startPositionMs,
  )

  private fun songsOf(albumId: String): List<Song> =
    runBlocking { graph.browseRepository.songs(albumId).first() }

  private fun queueOf(albumId: String, startIndex: Int) =
    app.muplay.media.PlaybackQueue.of(songsOf(albumId), startIndex)

  /**
   * The `ControllerInfo` a real session's stub would have built for a trusted head unit.
   *
   * Neither callback reads it -- the surface a controller sees is decided in `onGetChildren`, and
   * what it may play is decided by `onConnect` before either of these is ever called -- but the
   * signature takes one, so it is the real object rather than a null.
   */
  private fun controller(): MediaSession.ControllerInfo =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      "com.google.android.projection.gearhead",
      Process.myPid() + 1,
      Process.myUid() + 1,
      1,
      1,
      /* isTrusted = */ true,
      Bundle.EMPTY,
      /* isPackageNameVerified = */ true,
    )

  private fun <T> onMain(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  /**
   * A player that does nothing, because a `MediaSession` needs one and this file never looks at it.
   *
   * Built over `SimpleBasePlayer` rather than an `ExoPlayer` for the reason
   * `PlayerConstructionTest` enforces: there is exactly one place in this module that builds a
   * player, and a test that built a second would be testing a copy of the production arrangement.
   */
  private class InertPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    override fun getState(): State =
      State.Builder().setAvailableCommands(Player.Commands.EMPTY).build()
  }

  private companion object {
    const val TIMEOUT_SECONDS = 20L

    /**
     * Carries no `u`, `s` or `t` parameter of any kind. The real thing an app-built item holds is
     * an authenticated Subsonic URL, and an AssertJ failure prints the value it saw.
     */
    const val PASSTHROUGH_URI = "http://passthrough.invalid/already-built"
  }
}
