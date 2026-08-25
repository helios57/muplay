package app.muplay.media.browse

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.BrowseTreeRepository
import app.muplay.media.NoOpPlayer
import app.muplay.media.QueueRepository
import app.muplay.model.browse.BrowseExtras
import app.muplay.model.browse.BrowseSurfaces
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The browse tree, read by a **real `MediaBrowser`** from a **real `MediaLibrarySession`** carrying
 * the **real `MuPlayLibraryCallback`**, over Media3's own controller/session plumbing.
 *
 * ### What this reaches, exactly, and what it does not
 *
 * Reached: `MediaBrowser.getLibraryRoot/getChildren/getItem` -> `MediaControllerImplBase` ->
 * `IMediaSession` -> `MediaLibrarySessionImpl` -> this project's callback -> `BrowseTreeRepository`
 * -> a **real Room database** -> back through `LibraryResult.toBundle()` and the `MediaItem`
 * bundling Media3 does on every library result. Every id, title, subtitle, media type, duration and
 * extras `Bundle` asserted below made that round trip; none of it is read off an object the test
 * handed to itself. The connection hints that select a surface are the same mechanism `:wear` uses
 * in production, which is what makes the car and watch branches exercisable with no car and no
 * watch -- Android Auto is projection, so this code path runs on a phone in production too.
 *
 * Not reached, and stated rather than implied:
 *
 *   * **The service.** `MuPlaybackService` is `@AndroidEntryPoint` and needs a `@HiltAndroidApp`
 *     host, which a library module's self-instrumenting APK does not have; the cross-process
 *     journey through the exported service belongs in `:app`'s instrumented suite, which this lane
 *     does not own. What that adds over this file is process boundary and Hilt graph assembly --
 *     not a browse decision.
 *   * **`SyncEngine`.** The tree reads the mirror and never the server, so the mirror is seeded
 *     directly (see [BrowseGraph]). Filling the mirror from Navidrome is Plan 2's, and tested there.
 *
 * Method names are camelCase because `minSdk 26` compiles DEX 035, which forbids a space in any
 * `SimpleName` -- a backticked instrumented test does not dex at all.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class BrowseTreeBrowserTest {

  private lateinit var context: Context
  private lateinit var graph: BrowseGraph
  private lateinit var callback: MuPlayLibraryCallback
  private lateinit var session: MediaLibrarySession
  private val browsers = mutableListOf<MediaBrowser>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    graph = BrowseGraph.create(context)
    callback = graph.callback(context)
    onMain {
      session = MediaLibrarySession.Builder(context, NoOpPlayer(), callback)
        // Unique per test: Media3 refuses a second session with an id already in use in the
        // process, and this suite builds one per test method.
        .setId("browse-tree-${System.nanoTime()}")
        .build()
    }
  }

  @After
  fun tearDown() {
    onMain {
      browsers.forEach(MediaBrowser::release)
      session.player.release()
      session.release()
    }
    browsers.clear()
    callback.release()
    graph.close()
  }

  // ---- the root ------------------------------------------------------------------------------

  @Test
  fun theRootIsBrowsableAndCarriesTheContentStyleTheSurfaceAskedFor() {
    val car = awaitItem(browser(BrowseSurfaces.HINT_CAR)) { it.getLibraryRoot(null) }
    val watch = awaitItem(browser(BrowseSurfaces.HINT_WATCH)) { it.getLibraryRoot(null) }

    assertThat(car.mediaId).isEqualTo("muplay/root")
    assertThat(car.mediaMetadata.isBrowsable).isTrue
    assertThat(car.mediaMetadata.isPlayable).isFalse
    assertThat(car.mediaMetadata.title?.toString()).isEqualTo("MuPlay")
    // Two surfaces, one assertion: a root whose extras were a constant answers the same for both.
    assertThat(
      listOf(car, watch).map {
        requireNotNull(it.mediaMetadata.extras).getInt(BrowseExtras.CONTENT_STYLE_BROWSABLE)
      },
    ).containsExactly(BrowseExtras.STYLE_GRID, BrowseExtras.STYLE_LIST)
    assertThat(requireNotNull(car.mediaMetadata.extras).getBoolean(BrowseExtras.CONTENT_STYLE_SUPPORTED))
      .isTrue
  }

  @Test
  fun eachSurfaceReceivesItsOwnRootChildrenInOrder() {
    // The assertion this whole plan turns on. Three surfaces, three exact ordered lists, read over
    // real Media3 plumbing. `containsExactly`, never `contains` -- the order is what a driver reads.
    assertThat(childIds(BrowseSurfaces.HINT_CAR, "muplay/root")).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums", "muplay/artists",
    )
    assertThat(childIds(BrowseSurfaces.HINT_WATCH, "muplay/root")).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums",
    )
    assertThat(childIds(hint = null, parentId = "muplay/root")).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums", "muplay/artists", "muplay/libraries",
    )
  }

  @Test
  fun theCarRootNeverExceedsTheFourTabsAHostWillRender() {
    // A fifth root child is dropped by Android Auto silently. The number is `BrowseSurface`'s own
    // constant rather than a literal 4 here, so the two cannot drift.
    assertThat(childIds(BrowseSurfaces.HINT_CAR, "muplay/root").size)
      .isLessThanOrEqualTo(app.muplay.model.browse.BrowseSurface.MAX_CAR_ROOT_TABS)
  }

  // ---- Continue: the one shelf whose length is a property of the surface -----------------------

  @Test
  fun theContinueShelfIsMostRecentFirstAndIsCutToTheSurfacesOwnLimit() {
    // Six started books, a car limit of eight and a watch limit of five: the car list is the whole
    // shelf and the watch list is its first five. Both exact and ordered, so a shelf sorted by
    // title, by id, or not at all fails, and so does a limit read from the wrong surface.
    assertThat(childIds(BrowseSurfaces.HINT_CAR, "muplay/continue")).containsExactly(
      "muplay/book/bk-second",
      "muplay/book/bk-test",
      "muplay/book/bk-alpha",
      "muplay/book/bk-beta",
      "muplay/book/bk-gamma",
      "muplay/book/bk-multi",
    )
    assertThat(childIds(BrowseSurfaces.HINT_WATCH, "muplay/continue")).containsExactly(
      "muplay/book/bk-second",
      "muplay/book/bk-test",
      "muplay/book/bk-alpha",
      "muplay/book/bk-beta",
      "muplay/book/bk-gamma",
    )
    // The finished book is absent from both, which is the promise "Continue" makes.
    assertThat(childIds(hint = null, parentId = "muplay/continue"))
      .doesNotContain("muplay/book/bk-tail")
  }

  @Test
  fun aContinueRowSaysHowMuchOfThatParticularBookIsLeft() {
    // Six distinct remaining times from six distinct positions. A subtitle built from a constant --
    // or from the *file's* position instead of the book's -- fails on `bk-test`, whose stored row
    // is 20 s into part two of a three-part book and whose book position is therefore 120 s.
    assertThat(childTitles(BrowseSurfaces.HINT_CAR, "muplay/continue", { subtitleOf() }))
      .containsExactly(
        "Cy Chapter · 2 min left",
        "Bea Bookwright · 8 min left",
        "Eve Reader · 3 min left",
        "Fay Speaker · 2 min left",
        "Gil Voice · 2 min left",
        "Dee Narrator · 3 min left",
      )
  }

  // ---- Books ---------------------------------------------------------------------------------

  @Test
  fun theBooksTabListsEveryBookAlphabeticallyWhichIsNotTheContinueOrder() {
    val books = childIds(BrowseSurfaces.HINT_CAR, "muplay/books")

    assertThat(books).containsExactly(
      "muplay/book/bk-alpha",
      "muplay/book/bk-beta",
      "muplay/book/bk-gamma",
      "muplay/book/bk-multi",
      "muplay/book/bk-nine",
      "muplay/book/bk-second",
      "muplay/book/bk-tail",
      "muplay/book/bk-test",
    )
    // The two orders are asserted to *differ*, not merely each to be right: one `sortedBy` shared
    // by both shelves would satisfy either list on its own.
    assertThat(books).isNotEqualTo(childIds(BrowseSurfaces.HINT_CAR, "muplay/continue"))
    assertThat(childTitles(BrowseSurfaces.HINT_CAR, "muplay/books")).containsExactly(
      "Alpha Book", "Beta Book", "Gamma Book", "Multi Part Book",
      "Ninth Book", "Second Book", "Tail Book", "Test Book",
    )
  }

  @Test
  fun aBookCarriesTheCompletionExtrasACarDrawsItsProgressPipFrom() {
    val books = children(BrowseSurfaces.HINT_CAR, "muplay/books")

    // All three statuses appear, in a known order, so no single constant satisfies this.
    assertThat(books.map { requireNotNull(it.mediaMetadata.extras).getInt(BrowseExtras.COMPLETION_STATUS) })
      .containsExactly(
        BrowseExtras.STATUS_PARTIALLY_PLAYED,
        BrowseExtras.STATUS_PARTIALLY_PLAYED,
        BrowseExtras.STATUS_PARTIALLY_PLAYED,
        BrowseExtras.STATUS_PARTIALLY_PLAYED,
        BrowseExtras.STATUS_NOT_PLAYED,
        BrowseExtras.STATUS_PARTIALLY_PLAYED,
        BrowseExtras.STATUS_FULLY_PLAYED,
        BrowseExtras.STATUS_PARTIALLY_PLAYED,
      )
    // "The key is present" and "the other key is absent" are different claims, and the second is
    // the one that proves the branch in `BrowseExtras.forNode`.
    assertThat(
      books.map { requireNotNull(it.mediaMetadata.extras).containsKey(BrowseExtras.COMPLETION_PERCENTAGE) },
    ).containsExactly(true, true, true, true, false, true, false, true)
    // Six distinct fractions: a percentage that was one constant passes both assertions above.
    assertThat(
      books
        .map { requireNotNull(it.mediaMetadata.extras) }
        .filter { it.containsKey(BrowseExtras.COMPLETION_PERCENTAGE) }
        .map { it.getDouble(BrowseExtras.COMPLETION_PERCENTAGE) },
    ).containsExactly(0.1, 0.2, 0.3, 0.5, 0.25, 0.2)
  }

  @Test
  fun anUnheardShelfCarriesNoPercentageAtAll() {
    // The same tree with the progress rows withheld. Without this, "only a partially played item
    // carries a percentage" is observed at one value of the branch and the `NOT_PLAYED` arm is
    // reached by exactly one book.
    val unheard = BrowseGraph.create(context, withProgress = false)
    val unheardCallback = unheard.callback(context)
    try {
      val nodes = runCatchingBrowse(unheardCallback)
      assertThat(nodes.map { requireNotNull(it.mediaMetadata.extras).getInt(BrowseExtras.COMPLETION_STATUS) })
        .isNotEmpty
        .allSatisfy { assertThat(it).isEqualTo(BrowseExtras.STATUS_NOT_PLAYED) }
      assertThat(
        nodes.map { requireNotNull(it.mediaMetadata.extras).containsKey(BrowseExtras.COMPLETION_PERCENTAGE) },
      ).isNotEmpty.allSatisfy { assertThat(it).isFalse }
      // And the Continue shelf is empty rather than absent: nothing has been started.
      assertThat(childIdsOf(unheardCallback, "muplay/continue")).isEmpty()
    } finally {
      unheardCallback.release()
      unheard.close()
    }
  }

  @Test
  fun aMultiFileBooksChildrenAreItsPartsInOrderUnderTheirBareServerIds() {
    // A track's mediaId is the **bare** server id with no `muplay/` prefix, so that Android Auto's
    // now-playing highlight -- which compares the row's id with the session's current item -- keeps
    // working. Asserted here rather than only in `BrowseIdTest`, because this is the wire.
    assertThat(childIds(BrowseSurfaces.HINT_CAR, "muplay/book/bk-test"))
      .containsExactly("bk-test-p1", "bk-test-p2", "bk-test-p3")
    assertThat(childTitles(BrowseSurfaces.HINT_CAR, "muplay/book/bk-test", { subtitleOf() }))
      .containsExactly("Part 1 of 3", "Part 2 of 3", "Part 3 of 3")
  }

  @Test
  fun aOneFileBookIsNotAFolderRatherThanAnEmptyOne() {
    // "Not a folder" and "an empty folder" read differently in a car, and only an error result can
    // say the first. `bk-tail` has one file; `bk-nine` has two and must still open.
    assertThat(childrenResult(hint = null, parentId = "muplay/book/bk-tail").resultCode)
      .isNotEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(childIds(hint = null, parentId = "muplay/book/bk-nine"))
      .containsExactly("bk-nine-p1", "bk-nine-p2")
  }

  // ---- music, and the scoping rule -------------------------------------------------------------

  @Test
  fun theAlbumsTabOffersShuffleFirstAndThenOnlyMusicLibraryAlbums() {
    // Spec section 4's scoping rule, as an exact ordered list. Every audiobook in the fixture is
    // also an album row in the mirror, so a tree that asked every library instead of only the Music
    // ones would put eight books in this list and fail here.
    assertThat(childIds(BrowseSurfaces.HINT_CAR, "muplay/albums")).containsExactly(
      "muplay/shuffle/1",
      "muplay/album/al-abbey",
      "muplay/album/al-hunky",
      "muplay/album/al-revolver",
    )
    assertThat(childTitles(BrowseSurfaces.HINT_CAR, "muplay/albums").first())
      .isEqualTo("Shuffle Music")
  }

  @Test
  fun theArtistsTabHoldsOnlyMusicLibraryArtists() {
    // The audiobook library carries an artist row of its own (`ar-narrator`), so this is an
    // assertion about a row that exists and is excluded rather than about one that was never there.
    assertThat(childIds(hint = null, parentId = "muplay/artists"))
      .containsExactly("muplay/artist/ar-bowie", "muplay/artist/ar-beatles")
  }

  @Test
  fun anArtistsChildrenAreThatArtistsAlbumsAndNobodyElses() {
    assertThat(childIds(hint = null, parentId = "muplay/artist/ar-beatles"))
      .containsExactly("muplay/album/al-abbey", "muplay/album/al-revolver")
    assertThat(childIds(hint = null, parentId = "muplay/artist/ar-bowie"))
      .containsExactly("muplay/album/al-hunky")
    // An artist that exists only in the audiobook library is not reachable at all.
    assertThat(childrenResult(hint = null, parentId = "muplay/artist/ar-narrator").resultCode)
      .isNotEqualTo(LibraryResult.RESULT_SUCCESS)
  }

  @Test
  fun anAudiobookLibraryIsNotOfferedAShuffleRow() {
    // Spec section 1, over real IPC: shuffle must never be able to pull a chapter into a music
    // session, and on a surface with no UI that is the absence of a row.
    val musicIds = childIds(hint = null, parentId = "muplay/library/1")
    val bookIds = childIds(hint = null, parentId = "muplay/library/2")

    assertThat(musicIds.first()).isEqualTo("muplay/shuffle/1")
    assertThat(bookIds.filter { it.startsWith("muplay/shuffle/") }).isEmpty()
    assertThat(bookIds).hasSize(8)
  }

  @Test
  fun anAlbumsChildrenAreItsTracksInDiscAndTrackOrder() {
    // Deliberately not alphabetical in the fixture: sorted by title this would be
    // "Come Together", "Oh! Darling", "Something".
    assertThat(childTitles(hint = null, parentId = "muplay/album/al-abbey"))
      .containsExactly("Come Together", "Something", "Oh! Darling")
  }

  // ---- getItem -------------------------------------------------------------------------------

  @Test
  fun getItemAnswersForAKnownNodeAndRefusesAnUnknownOne() {
    val books = awaitResult(browser(null)) { it.getItem("muplay/books") }
    val root = awaitResult(browser(null)) { it.getItem("muplay/root") }
    val shuffle = awaitResult(browser(null)) { it.getItem("muplay/shuffle/1") }
    val nonsense = awaitResult(browser(null)) { it.getItem("muplay/nosuchkind/1") }
    val bookLibraryShuffle = awaitResult(browser(null)) { it.getItem("muplay/shuffle/2") }
    val missingAlbum = awaitResult(browser(null)) { it.getItem("muplay/album/al-nope") }

    assertThat(books.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(books.value?.mediaMetadata?.title?.toString()).isEqualTo("Books")
    assertThat(root.value?.mediaId).isEqualTo("muplay/root")
    assertThat(shuffle.value?.mediaMetadata?.title?.toString()).isEqualTo("Shuffle Music")
    // Three different failures, so "refuses" is not one constant: an id that names no kind, an id
    // that names a real kind but no existing row, and an id whose row exists but whose *node* does
    // not (an audiobook library has no shuffle).
    assertThat(nonsense.resultCode).isNotEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(bookLibraryShuffle.resultCode).isNotEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(missingAlbum.resultCode).isNotEqualTo(LibraryResult.RESULT_SUCCESS)
  }

  @Test
  fun getItemAnswersForABareTrackIdTheWayAPersistedRecentSendsItBack() {
    // A track id has no `muplay/` prefix at all (see `BrowseId.Track`), so this is also the
    // assertion that `onGetItem` does not require one -- the id a car stored last week comes back
    // exactly like this.
    val part = awaitResult(browser(null)) { it.getItem("bk-test-p2") }
    val missing = awaitResult(browser(null)) { it.getItem("no-such-song") }

    assertThat(part.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(part.value?.mediaId).isEqualTo("bk-test-p2")
    assertThat(part.value?.mediaMetadata?.title?.toString()).isEqualTo("Test Book Part 2")
    // 200 s, i.e. that part's own duration and not the book's.
    assertThat(part.value?.mediaMetadata?.durationMs).isEqualTo(200_000L)
    assertThat(missing.resultCode).isNotEqualTo(LibraryResult.RESULT_SUCCESS)
  }

  @Test
  fun getItemAndGetChildrenAgreeOnWhatANodeLooksLike() {
    // Two construction paths for one node is how a row's title in a list comes to differ from its
    // title on its own screen. `BrowseTreeRepository.node` builds through the same functions the
    // parent's children came from; this is the assertion that keeps it that way.
    val fromList = children(hint = null, parentId = "muplay/books").single { it.mediaId == "muplay/book/bk-test" }
    val onItsOwn = requireNotNull(awaitResult(browser(null)) { it.getItem("muplay/book/bk-test") }.value)

    assertThat(onItsOwn.mediaId).isEqualTo(fromList.mediaId)
    assertThat(onItsOwn.mediaMetadata.title?.toString()).isEqualTo(fromList.mediaMetadata.title?.toString())
    assertThat(onItsOwn.subtitleOf()).isEqualTo(fromList.subtitleOf())
    assertThat(onItsOwn.mediaMetadata.durationMs).isEqualTo(fromList.mediaMetadata.durationMs)
    assertThat(requireNotNull(onItsOwn.mediaMetadata.extras).getInt(BrowseExtras.COMPLETION_STATUS))
      .isEqualTo(requireNotNull(fromList.mediaMetadata.extras).getInt(BrowseExtras.COMPLETION_STATUS))
  }

  // ---- paging --------------------------------------------------------------------------------

  @Test
  fun childrenArePagedAndThePagesTileTheList() {
    val all = childIds(hint = null, parentId = "muplay/books")
    val firstPage = childIds(hint = null, parentId = "muplay/books", page = 0, pageSize = 3)
    val secondPage = childIds(hint = null, parentId = "muplay/books", page = 1, pageSize = 3)
    val lastPage = childIds(hint = null, parentId = "muplay/books", page = 2, pageSize = 3)
    val pastEnd = childIds(hint = null, parentId = "muplay/books", page = 9, pageSize = 3)

    assertThat(all).hasSize(8)
    assertThat(firstPage).isEqualTo(all.take(3))
    assertThat(secondPage).isEqualTo(all.drop(3).take(3))
    // The last page is short, which is the case a `subList(from, from + pageSize)` gets wrong.
    assertThat(lastPage).isEqualTo(all.drop(6))
    assertThat(pastEnd).isEmpty()
    assertThat(firstPage).isNotEqualTo(secondPage)
  }

  @Test
  fun aPageSizeOfIntMaxValueIsAnsweredRatherThanOverflowing() {
    // What a client that wants everything actually sends. Page 0 is the whole list; page 1 at that
    // size is where `page * pageSize` goes negative in Int arithmetic and `subList` throws inside
    // the future -- which a car renders as an unexplained empty screen either way, so the empty
    // list here is asserted alongside the full one rather than on its own.
    assertThat(childIds(hint = null, parentId = "muplay/books", page = 0, pageSize = Int.MAX_VALUE))
      .hasSize(8)
    assertThat(childIds(hint = null, parentId = "muplay/books", page = 1, pageSize = Int.MAX_VALUE))
      .isEmpty()
  }

  @Test
  fun artworkIsResolvedOncePerReturnedRowAndNotOncePerRowInTheFolder() {
    // Paging happens before artwork resolution. A host asking for three rows of eight pays for
    // three; the other ordering is eight cover-art URL builds per page turn, on a car's own
    // timeout. Counted through the source the tree actually calls.
    graph.artSource.coverArtCalls.clear()
    childIds(hint = null, parentId = "muplay/books", page = 0, pageSize = 3)

    assertThat(graph.artSource.coverArtCalls).hasSize(3)
    assertThat(graph.artSource.coverArtCalls.map { it.first })
      .containsExactly("cov-bk-alpha", "cov-bk-beta", "cov-bk-gamma")
  }

  @Test
  fun anItemsArtworkUriIsBuiltFromItsOwnCoverArtIdAtTheQueuesSize() {
    val uris = children(hint = null, parentId = "muplay/albums")
      .map { it.mediaMetadata.artworkUri?.toString() }

    // The shuffle row has no cover art at all and must not acquire one; the three albums each get
    // their own. A mapping that passed a constant art id would fail on the second value.
    assertThat(uris).containsExactly(
      null,
      "http://art.invalid/cov-al-abbey/512",
      "http://art.invalid/cov-al-hunky/512",
      "http://art.invalid/cov-al-revolver/512",
    )
  }

  @Test
  fun theBrowseArtworkSizeIsTheQueuesOwn() {
    // `BrowseTreeRepository` cannot import `QueueRepository` -- `:core:media` depends on
    // `:core:database` and not the other way round -- so the constant is copied. This source set is
    // the only one that can see both, and a drift means two cover-art cache entries per album.
    assertThat(BrowseTreeRepository.ARTWORK_SIZE_PX).isEqualTo(QueueRepository.ARTWORK_SIZE_PX)
  }

  // ---- plumbing ------------------------------------------------------------------------------

  private fun browser(hint: String?): MediaBrowser {
    val hints = Bundle().apply { hint?.let { putString(BrowseSurfaces.HINT_KEY, it) } }
    val built = onMain {
      MediaBrowser.Builder(context, session.token).setConnectionHints(hints).buildAsync()
    }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    browsers += built
    return built
  }

  private fun childrenResult(
    hint: String?,
    parentId: String,
    page: Int = 0,
    pageSize: Int = Int.MAX_VALUE,
  ) = awaitResult(browser(hint)) { it.getChildren(parentId, page, pageSize, null) }

  private fun children(
    hint: String?,
    parentId: String,
    page: Int = 0,
    pageSize: Int = Int.MAX_VALUE,
  ): List<MediaItem> = childrenResult(hint, parentId, page, pageSize).value.orEmpty()

  private fun childIds(
    hint: String?,
    parentId: String,
    page: Int = 0,
    pageSize: Int = Int.MAX_VALUE,
  ) = children(hint, parentId, page, pageSize).map(MediaItem::mediaId)

  private fun childTitles(
    hint: String?,
    parentId: String,
    of: MediaItem.() -> String? = { mediaMetadata.title?.toString() },
  ) = children(hint, parentId).map(of)

  /** The same three calls, made against a second graph without going through a second session. */
  private fun runCatchingBrowse(other: MuPlayLibraryCallback): List<MediaItem> =
    other.onGetChildren(session, ownController(), "muplay/books", 0, Int.MAX_VALUE, null)
      .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).value.orEmpty()

  private fun childIdsOf(other: MuPlayLibraryCallback, parentId: String): List<String> =
    other.onGetChildren(session, ownController(), parentId, 0, Int.MAX_VALUE, null)
      .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).value.orEmpty().map(MediaItem::mediaId)

  /**
   * A real `ControllerInfo` for this process, through Media3's own `@VisibleForTesting` factory --
   * the same one `DefaultSurfaceResolverTest` uses. Needed only by the two helpers above, which
   * drive a *second* graph's callback directly rather than standing up a second session for it.
   */
  private fun ownController() =
    androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      context.packageName,
      android.os.Process.myPid(),
      android.os.Process.myUid(),
      /* libraryVersion = */ 2,
      /* interfaceVersion = */ 2,
      /* isTrusted = */ true,
      Bundle.EMPTY,
      /* isPackageNameVerified = */ true,
    )

  private fun <T> awaitResult(
    browser: MediaBrowser,
    call: (MediaBrowser) -> ListenableFuture<LibraryResult<T>>,
  ): LibraryResult<T> =
    // Called on the main thread (Media3 controllers are thread-confined), awaited on the test
    // thread.
    onMain { call(browser) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

  private fun awaitItem(
    browser: MediaBrowser,
    call: (MediaBrowser) -> ListenableFuture<LibraryResult<MediaItem>>,
  ) = requireNotNull(awaitResult(browser, call).value) { "no item returned" }

  private fun <T> onMain(block: () -> T): T {
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private companion object {
    const val TIMEOUT_SECONDS = 30L

    fun MediaItem.subtitleOf(): String? = mediaMetadata.subtitle?.toString()
  }
}
