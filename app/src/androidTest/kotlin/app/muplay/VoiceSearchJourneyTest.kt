package app.muplay

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.media.MuPlaybackService
import app.muplay.model.browse.BrowseSurfaces
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search from a car's search box, and playback from the Assistant's **cold-start intent**, against
 * the real service, the real Navidrome container and real decoded audio.
 *
 * The second half is the one worth reading. The intent built below is byte-for-byte what the
 * Assistant sends -- the literal action string and the literal `query` extra, asserted as literals
 * so a wrong platform constant in the service cannot pass -- and it is delivered to the exported
 * service by `startService`. What that proves is that the handler runs and that **audio advances**
 * afterwards. What it does **not** prove, and no gate in this repository does, is that Google's
 * Assistant really sends that action with that extra to this service, or anything about speech
 * recognition; Task 11 carries both as a manual check with a written `adb` line and says out loud
 * that they are not gated.
 *
 * ### Why the manifest half is a build-time gate and not this file's business
 *
 * The intent below names the component explicitly, so it reaches the service whether or not the
 * `<intent-filter>` exists -- which means **removing the filter leaves both intent tests green**.
 * That is a real gap and it is closed elsewhere rather than pretended away here:
 * `verify<Variant>Manifest` requires `android.media.action.MEDIA_PLAY_FROM_SEARCH` in AGP's own
 * merged manifest through `AUTOMOTIVE_DECLARATIONS`, and `ConventionTest`'s *a declared
 * play-from-search filter must have a handler and a gate entry* holds the filter, the handler and
 * that entry to one answer. The run-time half is here; the declaration half is `check`'s.
 *
 * ### No count over the seeded corpus is written down
 *
 * Every title and id below is read from the browse tree at run time. The corpus grew from three
 * music files to four while this plan was being written, and a test that had counted would have
 * been wrong rather than red. The one thing written down is the *query*, and the fixture's ability
 * to tell "books first" from "sorted by title" is asserted rather than assumed --
 * see [theCarsSearchBoxPutsBooksAboveMusic].
 *
 * **No stream URL is read, asserted or printed anywhere in this file.**
 *
 * Method names are camelCase: `minSdk 26` compiles DEX 035, which forbids a space in a `SimpleName`.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSearchJourneyTest {

  /**
   * The app itself, walked to a settled library screen.
   *
   * [reachLibraryScreen] is what establishes the credentials, the two `LibraryRole` tags and a
   * committed sync; the browse tree reads the mirror, so without it every assertion here is
   * vacuous. It also puts the app in the foreground, which is what makes `startService` legal.
   */
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  private lateinit var context: Context
  private lateinit var browser: MediaBrowser

  /** Extra browsers built to read the same tree as another surface; released with the car one. */
  private val extraBrowsers = mutableListOf<MediaBrowser>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    composeRule.reachLibraryScreen()
    val hints = Bundle().apply { putString(BrowseSurfaces.HINT_KEY, BrowseSurfaces.HINT_CAR) }
    browser = onMain {
      MediaBrowser.Builder(context, MuPlaybackService.sessionToken(context))
        .setConnectionHints(hints)
        .buildAsync()
    }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }

  @After
  fun tearDown() {
    // Guarded: `setUp` can fail before `browser` is assigned, and an `@After` that throws on an
    // uninitialised `lateinit` replaces the real failure with its own.
    if (::browser.isInitialized) {
      onMain {
        browser.stop()
        browser.clearMediaItems()
        browser.release()
        extraBrowsers.forEach(MediaBrowser::release)
      }
    }
    extraBrowsers.clear()
  }

  // ---- the car's search box ----------------------------------------------------------------------

  @Test
  fun theCarsSearchBoxPutsBooksAboveMusic() {
    val titles = searchTitles(SHARED_QUERY)
    val shelf = childTitles("muplay/books")
    val books = titles.filter { it in shelf }
    val music = titles.filterNot { it in shelf }

    // Both kinds are present, or every ordering claim below is vacuous.
    assertThat(books).describedAs("book rows for '$SHARED_QUERY'").isNotEmpty
    assertThat(music).describedAs("music rows for '$SHARED_QUERY'").isNotEmpty
    // ...and the fixture can tell "books first" from "sorted by title": at least one music row
    // sorts *before* the first book row, so the two rules give different lists here. Without this,
    // a `searchNodes` that just sorted everything alphabetically would satisfy the assertion below
    // and nobody would know the ordering was never tested.
    assertThat(music.filter { it < books.first() })
      .describedAs(
        "music rows sorting before the first book row -- without one of these, \"books first\" " +
          "and \"alphabetical\" are the same list and this test proves nothing",
      )
      .isNotEmpty

    // Every book above every piece of music. Not "the first row is a book", which is satisfied by
    // a book, then music, then another book.
    assertThat(titles.indexOfFirst { it in music })
      .describedAs("first music row in $titles")
      .isGreaterThan(titles.indexOfLast { it in books })
    // The whole list, in order, derived: books alphabetically, then the music rows.
    assertThat(titles).containsExactlyElementsOf(books + music)
  }

  @Test
  fun everySurfaceSeesTheSameSearchResults() {
    // Task 4 measured that the CAR and WATCH roots are strict *prefixes* of PHONE and identical
    // below it, so no surface can reach anything PHONE lacks. Search must not break that, or a
    // connection hint becomes a way to reach content and `BrowseSurfaces` needs a verified package
    // name rather than a package-name check.
    val car = searchTitles(SHARED_QUERY)
    val watch = searchTitles(SHARED_QUERY, BrowseSurfaces.HINT_WATCH)
    val phone = searchTitles(SHARED_QUERY, hint = null)

    assertThat(car).containsExactlyElementsOf(phone)
    assertThat(watch).containsExactlyElementsOf(phone)
    assertThat(phone).isNotEmpty
  }

  @Test
  fun aSearchWithNoMatchesIsAnEmptyListAndNotAnError() {
    val acknowledged = awaitResult { it.search(NO_MATCH_QUERY, null) }
    val page = awaitResult { it.getSearchResult(NO_MATCH_QUERY, 0, Int.MAX_VALUE, null) }

    assertThat(acknowledged.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(page.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(page.value.orEmpty()).isEmpty()
  }

  @Test
  fun searchResultsArePagedTheSameWayChildrenAre() {
    val all = searchTitles(SHARED_QUERY)
    check(all.size >= 2) { "the seeded corpus must match '$SHARED_QUERY' at least twice" }

    val first = awaitResult { it.getSearchResult(SHARED_QUERY, 0, 1, null) }.value.orEmpty()
    val second = awaitResult { it.getSearchResult(SHARED_QUERY, 1, 1, null) }.value.orEmpty()

    assertThat(first.mapNotNull { it.mediaMetadata.title?.toString() }).containsExactly(all[0])
    assertThat(second.mapNotNull { it.mediaMetadata.title?.toString() }).containsExactly(all[1])
  }

  // ---- the Assistant's cold-start intent ----------------------------------------------------------

  @Test
  fun theAssistantsColdStartIntentMakesAudioAdvance() {
    // The book asked for, and the files it should queue -- both read from the tree rather than
    // written down.
    val book = books().first()
    val files = childIds(book.mediaId)

    startFromSearch(titleOf(book))

    // Not "the service was started". Not "a queue was set". A cursor that moved, over real time.
    awaitPositionAtLeast(400L)
    val before = cursor()
    Thread.sleep(1_500)
    assertThat(cursor())
      .describedAs("the playback cursor after 1.5 s of real audio, starting from $before")
      .isGreaterThan(before)

    // ...and it played the book that was asked for, not the first thing in the library. The queue
    // is the book's own files, in order.
    assertThat(onMain { (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId } })
      .containsExactlyElementsOf(files)
  }

  @Test
  fun theIntentIsTheOneTheAssistantSends() {
    // Literals, not constants: this is the only assertion in the repository that would fail if the
    // service branched on the wrong platform constant, because the service uses the constant and
    // this uses the string.
    val intent = playFromSearchIntent("anything")

    assertThat(intent.action).isEqualTo("android.media.action.MEDIA_PLAY_FROM_SEARCH")
    assertThat(intent.action).isEqualTo(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
    assertThat(intent.getStringExtra("query")).isEqualTo("anything")
    assertThat(intent.getStringExtra(SearchManager.QUERY)).isEqualTo("anything")
  }

  @Test
  fun aSpokenTitleTheMirrorCannotMatchStillPlaysThatBook() {
    // What a speech recogniser hands over: no capitals, no punctuation. `LIKE '%<that>%'` matches
    // nothing at all, so this passes only because the selection falls back to the whole shelf and
    // normalises before comparing. The **specific** book is the assertion; "something played" is
    // the next test's.
    val book = books().first()
    val spoken = titleOf(book).lowercase() + "!"
    val files = childIds(book.mediaId)

    startFromSearch(spoken)

    awaitPositionAtLeast(400L)
    assertThat(onMain { (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId } })
      .containsExactlyElementsOf(files)
  }

  @Test
  fun aSpokenQueryThatMatchesNothingStillPlaysSomethingRatherThanNothing() {
    startFromSearch(NO_MATCH_QUERY)

    // The last tier. A car that answers a spoken request with silence has done nothing, and the
    // app is what gets blamed.
    assertThat(awaitPositionAtLeast(400L)).isGreaterThanOrEqualTo(400L)
    assertThat(onMain { browser.mediaItemCount }).isGreaterThan(0)
  }

  // ---- plumbing ------------------------------------------------------------------------------------

  private fun startFromSearch(query: String) {
    onMain {
      browser.stop()
      browser.clearMediaItems()
    }
    context.startService(playFromSearchIntent(query))
  }

  private fun playFromSearchIntent(query: String): Intent =
    // The literal action and the literal extra key, exactly as the Assistant sends them.
    Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply {
      component = ComponentName(context, MuPlaybackService::class.java)
      putExtra("query", query)
    }

  private fun books(): List<MediaItem> = children("muplay/books")

  private fun titleOf(item: MediaItem): String =
    requireNotNull(item.mediaMetadata.title?.toString()) { "a browse row with no title" }

  /**
   * Where playback is, as one monotonically increasing number.
   *
   * The queue index folded in above the position rather than the position alone: the seeded parts
   * are four to six seconds long, so a queue that crosses into the next file inside a wait resets
   * the position to near zero, and a bare `isGreaterThan` on it would fail against a player that is
   * working perfectly. [CURSOR_STRIDE] is an hour, which is far longer than any seeded file, so the
   * two components cannot collide. Nothing a stalled player does moves either of them.
   */
  private fun cursor(): Long =
    onMain { browser.currentMediaItemIndex * CURSOR_STRIDE + browser.currentPosition }

  private fun searchTitles(
    query: String,
    hint: String? = BrowseSurfaces.HINT_CAR,
  ): List<String> {
    val target = if (hint == BrowseSurfaces.HINT_CAR) browser else connect(hint)
    awaitResult(target) { it.search(query, null) }
    return awaitResult(target) { it.getSearchResult(query, 0, Int.MAX_VALUE, null) }
      .value.orEmpty()
      .mapNotNull { it.mediaMetadata.title?.toString() }
  }

  private fun connect(hint: String?): MediaBrowser {
    val hints = Bundle().apply { hint?.let { putString(BrowseSurfaces.HINT_KEY, it) } }
    return onMain {
      MediaBrowser.Builder(context, MuPlaybackService.sessionToken(context))
        .setConnectionHints(hints)
        .buildAsync()
    }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).also { extraBrowsers += it }
  }

  private fun children(parentId: String): List<MediaItem> =
    awaitResult { it.getChildren(parentId, 0, Int.MAX_VALUE, null) }.value.orEmpty()

  private fun childIds(parentId: String): List<String> = children(parentId).map(MediaItem::mediaId)

  private fun childTitles(parentId: String): List<String> =
    children(parentId).mapNotNull { it.mediaMetadata.title?.toString() }

  private fun <T> awaitResult(
    call: (MediaBrowser) -> ListenableFuture<LibraryResult<T>>,
  ): LibraryResult<T> = awaitResult(browser, call)

  private fun <T> awaitResult(
    target: MediaBrowser,
    call: (MediaBrowser) -> ListenableFuture<LibraryResult<T>>,
  ): LibraryResult<T> = onMain { call(target) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

  private fun awaitPositionAtLeast(positionMs: Long): Long {
    val deadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1_000
    while (SystemClock.elapsedRealtime() < deadline) {
      val position = onMain { browser.currentPosition }
      if (position >= positionMs) return position
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
      "position never reached ${positionMs}ms; item=${onMain { browser.currentMediaItem?.mediaId }} " +
        "count=${onMain { browser.mediaItemCount }} state=${onMain { browser.playbackState }} " +
        "isPlaying=${onMain { browser.isPlaying }} error=${onMain { browser.playerError }}",
    )
  }

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

  private companion object {
    const val TIMEOUT_SECONDS = 40L
    const val POLL_MILLIS = 50L

    /** One hour, comfortably longer than any seeded file -- see `cursor`. */
    const val CURSOR_STRIDE = 3_600_000L

    /**
     * A query the seeded corpus answers with **both** a book and music, where the music sorts
     * first alphabetically.
     *
     * `ci/seed-fixtures.sh` puts "Test Book" in the Audiobooks library and "Test Album" by "Test
     * Artist" in Music, so `Test Album < Test Artist < Test Book` by title and "books first" is the
     * only rule that puts the book at the top. The test asserts that property of the fixture rather
     * than trusting this paragraph.
     */
    const val SHARED_QUERY = "Test"

    const val NO_MATCH_QUERY = "zzzz nothing matches this"
  }
}
