package app.muplay.media.browse

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.media.NoOpPlayer
import app.muplay.model.browse.BrowseExtras
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseSurfaces
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search, read by a **real `MediaBrowser`** from a **real `MediaLibrarySession`** carrying the
 * **real `MuPlayLibraryCallback`**, over a real Room mirror.
 *
 * Same construction as [BrowseTreeBrowserTest] and for the same reasons -- see that file's header
 * for what this plumbing reaches and what it does not. What is asserted here that a browse test
 * cannot assert is the one claim search adds: **books rank above music, and that is a decision
 * rather than a tie-break.**
 *
 * ### How the fixture can tell those two rules apart
 *
 * A result set is not an ordering, and this project has shipped exactly that defect before. So the
 * query below (`"a"`) is chosen so that every plausible *other* rule gives a different first row:
 *
 *  * sorted by title end to end, `"Abbey Road"` -- a **music album** -- comes first;
 *  * music-first gives `"Abbey Road"` first;
 *  * the mirror's own return order gives `"Abbey Road"` first, because
 *    `BrowseRepository.search` is called per library and the albums come back by `sortName`.
 *
 * Only "books first" puts `"Alpha Book"` at the top, and `bookRankingIsNotAlphabetical` asserts
 * the property that makes that distinction real rather than asserting it in a comment.
 *
 * ### The other half of this task: what a *spoken* query plays
 *
 * `MuPlayLibraryCallback.spokenQueue` is the one function both spoken paths reach -- the
 * Assistant's `onSetMediaItems` when this app is connected, and `MuPlaybackService.onStartCommand`
 * when it is not. It builds real `MediaItem`s, which is why it is tested here rather than on the
 * JVM: `MediaItem` reaches `android.net.Uri` and cannot be built off a device.
 *
 * **No stream URL is asserted or printed anywhere below.** The queue's items carry one; only their
 * media ids are ever read. See [RecordingArtSource] for why even the fake's URLs are credential-free.
 *
 * Method names are camelCase: `minSdk 26` compiles DEX 035, which forbids a space in a `SimpleName`.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class BrowseSearchBrowserTest {

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
        .setId("browse-search-${System.nanoTime()}")
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

  // ---- the ordering claim ----------------------------------------------------------------------

  @Test
  fun searchResultsAreBooksThenAlbumsThenArtistsThenTracks() {
    // The whole list, exactly, in order. `containsExactly` and never `contains`: a result *set*
    // cannot see a wrong order, which is the defect this test exists for.
    assertThat(searchTitles(BrowseSurfaces.HINT_CAR, MIXED_QUERY)).containsExactly(
      // books, alphabetically -- the same order the Books tab uses
      "Alpha Book", "Beta Book", "Gamma Book", "Multi Part Book", "Tail Book",
      // then music albums, by the mirror's sort name
      "Abbey Road",
      // then artists
      "David Bowie", "The Beatles",
      // then tracks
      "Changes", "Eleanor Rigby", "Oh! Darling", "Taxman",
    )
  }

  @Test
  fun bookRankingIsNotAlphabeticalAndNotTheMirrorsOwnOrder() {
    // The assertion that makes the one above discriminating rather than decorative. Without this,
    // a `searchNodes` that simply sorted every row by title would pass a `containsExactly` written
    // from *its* output and nobody would know the ordering claim was never tested.
    val titles = searchTitles(BrowseSurfaces.HINT_CAR, MIXED_QUERY)
    // Which rows are books is read from the Books tab rather than counted here: a fixture change
    // moves the count and would leave a written-down one asserting the wrong split silently.
    val shelf = awaitResult(browser(BrowseSurfaces.HINT_CAR)) {
      it.getChildren("muplay/books", 0, Int.MAX_VALUE, null)
    }.value.orEmpty().mapNotNull { it.mediaMetadata.title?.toString() }
    val books = titles.filter { it in shelf }
    val music = titles.filterNot { it in shelf }

    // The fixture really does contain both kinds, or everything below is vacuous.
    assertThat(books).isNotEmpty
    assertThat(music).isNotEmpty
    // ...and it really can tell the two rules apart: at least one music row sorts *before* the
    // first book row, so "books first" and "sorted by title" are different answers here.
    assertThat(music.filter { it < books.first() })
      .describedAs("music rows that sort before the first book row; without one of these, " +
        "\"books first\" and \"alphabetical\" are the same list and this suite proves nothing")
      .isNotEmpty
    // Every book is above every piece of music. Not "the first row is a book" -- that is satisfied
    // by one book followed by music followed by another book.
    assertThat(titles.indexOfFirst { it in music }).isGreaterThan(titles.indexOfLast { it in books })
  }

  @Test
  fun everySurfaceGetsTheSameSearchResults() {
    // Plan 5 Task 4 measured that CAR (4 root tabs) and WATCH (3) are strict *prefixes* of PHONE
    // (5) and identical below the root, so no surface can see anything PHONE lacks. Search must not
    // break that: if it did, the connection hint would become a way to reach content, and
    // `BrowseSurfaces` would need `isPackageNameVerified()` rather than a package-name check.
    //
    // Asserted as three exact lists rather than as "the same size".
    val car = searchTitles(BrowseSurfaces.HINT_CAR, MIXED_QUERY)
    val watch = searchTitles(BrowseSurfaces.HINT_WATCH, MIXED_QUERY)
    val phone = searchTitles(null, MIXED_QUERY)

    assertThat(car).containsExactlyElementsOf(phone)
    assertThat(watch).containsExactlyElementsOf(phone)
    assertThat(phone).isNotEmpty
  }

  // ---- the two-call contract -------------------------------------------------------------------

  @Test
  fun onSearchReportsTheCountOnGetSearchResultThenReturns() {
    val recorder = SearchRecorder()
    val browser = browser(BrowseSurfaces.HINT_CAR, recorder)

    val result = awaitResult(browser) { it.search(MIXED_QUERY, null) }

    assertThat(result.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
    // The notification landed **before** the future resolved. Media3 delivers both to the
    // controller's own thread in the order the session produced them, so a `notifySearchResultChanged`
    // moved after `future.set` is not visible here at all -- which is the whole point: a browser
    // that asks for page 0 the instant its future resolves would race it.
    assertThat(recorder.queries).containsExactly(MIXED_QUERY)
    // ...and the count is the real one, not zero and not a constant: it equals what
    // `onGetSearchResult` then returns, and the two are computed by separate calls with no cache
    // between them.
    assertThat(recorder.counts)
      .containsExactly(searchResult(browser, MIXED_QUERY, 0, Int.MAX_VALUE).size)
    assertThat(recorder.counts.single()).isGreaterThan(1)
  }

  @Test
  fun aSearchWithNoMatchesIsAnEmptyListAndNotAnError() {
    val recorder = SearchRecorder()
    val browser = browser(BrowseSurfaces.HINT_CAR, recorder)

    val acknowledged = awaitResult(browser) { it.search(NO_MATCH_QUERY, null) }
    val page = awaitResult(browser) { it.getSearchResult(NO_MATCH_QUERY, 0, Int.MAX_VALUE, null) }

    assertThat(acknowledged.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(page.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(page.value.orEmpty()).isEmpty()
    assertThat(recorder.counts).containsExactly(0)
  }

  @Test
  fun searchResultsArePagedTheSameWayChildrenAre() {
    val browser = browser(BrowseSurfaces.HINT_CAR)
    val all = searchResult(browser, MIXED_QUERY, 0, Int.MAX_VALUE)
    check(all.size > PAGE_SIZE * 2) { "the fixture must produce at least three pages of $PAGE_SIZE" }

    val pages = (0..2).map { searchResult(browser, MIXED_QUERY, it, PAGE_SIZE) }

    // Every page is the slice it names, and the pages concatenate back to the whole list -- so a
    // paging rule that ignored `page`, or that re-sorted inside a page, fails here.
    assertThat(pages[0]).containsExactlyElementsOf(all.take(PAGE_SIZE))
    assertThat(pages[1]).containsExactlyElementsOf(all.drop(PAGE_SIZE).take(PAGE_SIZE))
    assertThat(pages[2]).containsExactlyElementsOf(all.drop(PAGE_SIZE * 2).take(PAGE_SIZE))
    assertThat(pages.flatten()).containsExactlyElementsOf(all.take(PAGE_SIZE * 3))
    // A page past the end is empty rather than an error or a wrapped first page. `Int.MAX_VALUE` as
    // a page size with a non-zero page is the value that overflows `page * pageSize` into a
    // negative index -- see `BrowsePaging`.
    assertThat(searchResult(browser, MIXED_QUERY, 99, PAGE_SIZE)).isEmpty()
    assertThat(searchResult(browser, MIXED_QUERY, 1, Int.MAX_VALUE)).isEmpty()
  }

  // ---- what a result row actually carries --------------------------------------------------------

  @Test
  fun aSearchResultRowIsTheSameRowTheBrowseTabDraws() {
    val browser = browser(BrowseSurfaces.HINT_CAR)
    val fromSearch = awaitResult(browser) { it.getSearchResult(BOOK_QUERY, 0, Int.MAX_VALUE, null) }
      .value.orEmpty().single { it.mediaId == MULTI_PART_BOOK_ID }
    val fromTab = awaitResult(browser) { it.getChildren("muplay/books", 0, Int.MAX_VALUE, null) }
      .value.orEmpty().single { it.mediaId == MULTI_PART_BOOK_ID }

    // Field by field, and every one of these is a field a car head unit renders. A search row built
    // by a second construction path drifts from the tab's row, and the symptom is a book whose
    // title or progress pip differs between two screens.
    assertThat(fromSearch.mediaMetadata.title?.toString())
      .isEqualTo(fromTab.mediaMetadata.title?.toString())
    assertThat(fromSearch.mediaMetadata.subtitle?.toString())
      .isEqualTo(fromTab.mediaMetadata.subtitle?.toString())
    assertThat(fromSearch.mediaMetadata.mediaType).isEqualTo(fromTab.mediaMetadata.mediaType)
    assertThat(fromSearch.mediaMetadata.artworkUri).isEqualTo(fromTab.mediaMetadata.artworkUri)
    assertThat(fromSearch.mediaMetadata.isPlayable).isEqualTo(fromTab.mediaMetadata.isPlayable)
    assertThat(fromSearch.mediaMetadata.isBrowsable).isEqualTo(fromTab.mediaMetadata.isBrowsable)
    // The completion pip specifically, because it is the field a "search returns a plain row"
    // shortcut would silently drop -- and it is read from the row, not compared to itself.
    val extras = requireNotNull(fromSearch.mediaMetadata.extras)
    assertThat(extras.getInt(BrowseExtras.COMPLETION_STATUS))
      .isEqualTo(BrowseExtras.STATUS_PARTIALLY_PLAYED)
    assertThat(extras.getDouble(BrowseExtras.COMPLETION_PERCENTAGE)).isStrictlyBetween(0.0, 1.0)
    // Not vacuous: the same field on a book nobody has started reads NOT_PLAYED.
    val unstarted = awaitResult(browser) { it.getSearchResult(BOOK_QUERY, 0, Int.MAX_VALUE, null) }
      .value.orEmpty().single { it.mediaId == "muplay/book/bk-nine" }
    assertThat(requireNotNull(unstarted.mediaMetadata.extras).getInt(BrowseExtras.COMPLETION_STATUS))
      .isEqualTo(BrowseExtras.STATUS_NOT_PLAYED)
  }

  @Test
  fun searchIsScopedByLibraryRoleAndNeverCrossesIt() {
    val browser = browser(BrowseSurfaces.HINT_CAR)

    // A book matches as a **book** id and never as an album id, even though the row it came from is
    // an album in the mirror -- spec section 4's rule, on the search path. `muplay/album/bk-alpha`
    // would be an id that expands through the music path and bypasses the library role entirely.
    assertThat(searchIds(browser, "Alpha Book")).containsExactly("muplay/book/bk-alpha")

    // An artist row that exists **in the audiobook library** is never a search result: the artists
    // asked are the Music libraries' artists, structurally. "Ann Author" is seeded precisely so
    // that this is an assertion about a row that exists and is excluded.
    assertThat(searchTitles(BrowseSurfaces.HINT_CAR, "Ann Author")).isEmpty()

    // ...and the same query really does find that artist through the shelf's own tab, so the
    // emptiness above is scoping rather than a query that matches nothing.
    assertThat(searchTitles(BrowseSurfaces.HINT_CAR, "Bowie")).containsExactly("David Bowie")
  }

  @Test
  fun noShuffleRowIsEverASearchResult() {
    // The Albums *tab* puts one shuffle row per Music library above the albums, and `albumsNodes`
    // and `artistChildren` are one line apart in `BrowseTree`. A shuffle row is not a result for
    // anything a user typed, and in a car it would be the first row they took.
    val ids = searchIds(browser(BrowseSurfaces.HINT_CAR), MIXED_QUERY)

    assertThat(ids).isNotEmpty
    assertThat(ids).noneMatch { it.startsWith("muplay/shuffle/") }
    // The control: the tab that *should* carry one still does.
    assertThat(
      awaitResult(browser(BrowseSurfaces.HINT_CAR)) { it.getChildren("muplay/albums", 0, Int.MAX_VALUE, null) }
        .value.orEmpty().map(MediaItem::mediaId),
    ).anyMatch { it.startsWith("muplay/shuffle/") }
  }

  // ---- what a spoken query plays -----------------------------------------------------------------

  @Test
  fun aSpokenExactTitleBeatsAnEarlierPartialMatch() {
    // "Tail Book" is contained in nothing else, but *every* book here contains "Book" and
    // "Alpha Book" sorts first -- so a `searchSelection` with no exact-match tier answers Alpha.
    assertThat(spokenIds("Tail Book")).containsExactlyElementsOf(filesOf("bk-tail"))
    // The contrast, from the same fixture: the bare word picks the first book, which is the tier
    // below. Two observations of one rule, and they disagree.
    assertThat(spokenIds("Book")).containsExactlyElementsOf(filesOf("bk-alpha"))
  }

  @Test
  fun aSpokenQueryTheMirrorCannotMatchStillFindsTheBook() {
    // What a speech recogniser actually hands over. `LIKE '%Tail, Book!%'` matches nothing at all,
    // so this only works because `searchSelection` falls back to the whole shelf and
    // `PlayFromSearch.normalise` strips the punctuation. Three spellings, one answer.
    assertThat(listOf("tail book", "  TAIL   book  ", "Tail, Book!").map { spokenIds(it) })
      .containsExactly(filesOf("bk-tail"), filesOf("bk-tail"), filesOf("bk-tail"))
  }

  @Test
  fun aSpokenQueryThatMatchesNothingStillProducesAQueue() {
    // A car that answers a spoken request with silence has done nothing, and the app is what gets
    // blamed. The last tier: the first playable thing on the shelf.
    assertThat(spokenIds(NO_MATCH_QUERY)).containsExactlyElementsOf(filesOf("bk-alpha"))
  }

  @Test
  fun aSpokenQuerySkipsARowTheMirrorHoldsNoFilesFor() {
    // `bk-empty` is an exact title match, is playable as a row, and expands to nothing -- an album
    // whose songs a sync has not reached. Taking the best *match* rather than the best match that
    // really expands answers this with silence.
    assertThat(runBlocking { graph.treeRepository.expand(BrowseId.Book("bk-empty")) })
      .describedAs("the fixture's unexpandable row; without it this test cannot fail")
      .isNull()

    assertThat(spokenIds("Empty Book")).containsExactlyElementsOf(filesOf("bk-alpha"))
  }

  @Test
  fun aSpokenQueryCanReachMusicAsWellAsBooks() {
    // Books rank first in a *list*, but a spoken exact title still reaches an album -- otherwise
    // "books first" would quietly mean "books only" and no music could ever be asked for by name.
    assertThat(spokenIds("Hunky Dory")).containsExactly("tr-h1")
  }

  @Test
  fun aSpokenQueryStartsTheBookAtTheFileItWasLeftIn() {
    // The seam this whole plan turns on, reached by voice: the caller picks the index, the resume
    // policy picks the second. `bk-multi`'s most recent row is on part three of four.
    val queue = requireNotNull(runBlocking { callback.spokenQueue("Multi Part Book") })

    assertThat(queue.mediaItems.map(MediaItem::mediaId)).containsExactlyElementsOf(filesOf("bk-multi"))
    assertThat(queue.startIndex).isEqualTo(2)
    // Never a position: `MuPlayer` discards whatever arrives and asks the policy, which is what
    // makes "no code path sets a wrong position" true of this one too.
    assertThat(queue.startPositionMs).isEqualTo(C.TIME_UNSET)
  }

  // ---- plumbing ---------------------------------------------------------------------------------

  private fun spokenIds(query: String): List<String> =
    requireNotNull(runBlocking { callback.spokenQueue(query) }) { "nothing was queued for '$query'" }
      .mediaItems
      .map(MediaItem::mediaId)

  /** The mirror's own file ids for a book, read back rather than written down. */
  private fun filesOf(bookId: String): List<String> =
    runBlocking { graph.browseRepository.songs(bookId).first() }.map { it.id }

  private fun searchTitles(hint: String?, query: String): List<String> {
    val browser = browser(hint)
    awaitResult(browser) { it.search(query, null) }
    return searchResult(browser, query, 0, Int.MAX_VALUE)
  }

  private fun searchResult(
    browser: MediaBrowser,
    query: String,
    page: Int,
    pageSize: Int,
  ): List<String> =
    awaitResult(browser) { it.getSearchResult(query, page, pageSize, null) }
      .value.orEmpty()
      .mapNotNull { it.mediaMetadata.title?.toString() }

  private fun searchIds(browser: MediaBrowser, query: String): List<String> =
    awaitResult(browser) { it.getSearchResult(query, 0, Int.MAX_VALUE, null) }
      .value.orEmpty()
      .map(MediaItem::mediaId)

  private fun browser(hint: String?, listener: MediaBrowser.Listener? = null): MediaBrowser {
    val hints = Bundle().apply { hint?.let { putString(BrowseSurfaces.HINT_KEY, it) } }
    val built = onMain {
      MediaBrowser.Builder(context, session.token)
        .setConnectionHints(hints)
        .apply { listener?.let(::setListener) }
        .buildAsync()
    }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    browsers += built
    return built
  }

  private fun <T> awaitResult(
    browser: MediaBrowser,
    call: (MediaBrowser) -> ListenableFuture<LibraryResult<T>>,
  ): LibraryResult<T> = onMain { call(browser) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

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

  /** Records what `notifySearchResultChanged` told this browser, in order. */
  private class SearchRecorder : MediaBrowser.Listener {
    val queries = mutableListOf<String>()
    val counts = mutableListOf<Int>()

    override fun onSearchResultChanged(
      browser: MediaBrowser,
      query: String,
      itemCount: Int,
      params: LibraryParams?,
    ) {
      queries += query
      counts += itemCount
    }
  }

  private companion object {
    const val TIMEOUT_SECONDS = 20L

    /**
     * A single letter, chosen so that every ordering rule other than "books first" answers
     * differently -- see this class's own header.
     *
     * It matches five of the nine books, one of the three music albums, both artists and four of
     * the six tracks, so all four groups are non-empty and none of them is the whole list.
     */
    const val MIXED_QUERY = "a"

    const val BOOK_QUERY = "Book"
    const val NO_MATCH_QUERY = "zzzz nothing matches this"
    const val PAGE_SIZE = 3
    const val MULTI_PART_BOOK_ID = "muplay/book/bk-multi"
  }
}
