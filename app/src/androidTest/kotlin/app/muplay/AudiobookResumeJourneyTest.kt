package app.muplay

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.media.PlaybackConnection
import app.muplay.testing.BookFixtures
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The reason this application exists, as a test.**
 *
 * Spec §1: *"Real audiobook resume. Every book remembers its own exact position and keeps it across
 * an intervening music session."* Every unit test underneath that sentence can be satisfied by a
 * component that is correct in isolation and unwired; this is the only thing in the repository that
 * walks a listener from the library screen into a book, out to music, and back — through the real
 * Hilt graph, the real service, real audio focus and a real screen.
 *
 * Until it existed, `:app` had never navigated to `BookshelfRoute` or `BookRoute` at all: both keys
 * measured **0 lines**, which is what the `:app` bundle floor was one line short of clearing.
 *
 * ### How the resume assertion is made exact rather than approximate
 *
 * "Resumed" is a *number*, and there are three plausible-looking wrong ones — zero, the start of the
 * enclosing chapter, and the stored position with the wrong smart-rewind band subtracted. So the
 * journey pins the band by construction instead of widening the assertion to admit all of them:
 *
 *  * The position is read out of the app's own `media_progress` row, **read-only**. An on-screen
 *    `0:06` is a formatted string that could come from anywhere; `positionMs` is the number the
 *    resume path will consume. Seeding it from here would be testing this test's arithmetic.
 *  * `SmartRewind`'s table is banded on *how long the book was away*, and the book's row stops being
 *    stamped the moment music takes the session over. [awaitAwayOfAtLeast] therefore holds the walk
 *    back until the away time is safely inside the 15 s – 1 min band, whose rewind is exactly
 *    [REWIND_UNDER_A_MINUTE_MS], and the band is then **asserted at the moment of landing** so a
 *    machine slow enough to fall out of it says so instead of failing on the position.
 *  * [REWIND_UNDER_A_MINUTE_MS] is typed out here rather than imported from `SmartRewind`. An
 *    expectation computed by the code under test is an expectation that follows it into a mutation;
 *    this one goes red when the table changes, which is what makes the journey a gate on the rewind
 *    as well as on the resume.
 *
 * The landing itself is sampled every [LANDING_POLL_MILLIS] from the tap, and the **first** sighting
 * of the book as the current item is what is asserted on: `setMediaItems(items, index, position)` is
 * one call, so the queue and the resolved position arrive together, and a player that is still
 * buffering sits on that position rather than drifting past it. The window is
 * `[expected, expected + 1.5 s)` — tight enough that the no-rewind answer (`expected + 2 s`) and the
 * one-band-too-far answer (`expected − 3 s`) both fail.
 *
 * Every "it resumed" claim is paired with "and then it kept going". A player parked on the right
 * number, silent, satisfies the first alone — and so does one that seeked and failed to decode.
 *
 * Method and class names are camelCase: `minSdk 26` compiles DEX 035, which forbids a space in any
 * `SimpleName`, and a backticked name fails at `dexBuilderDebugAndroidTest`.
 *
 * **No stream URL and no cover-art URL is read or asserted anywhere here.** Both carry `u`, `s=salt`
 * and `t=md5(password+salt)`, and an AssertJ failure prints the value it saw.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookResumeJourneyTest {

  /** Ordered before the activity launches; without it the media notification is silently not posted. */
  @get:Rule(order = 0)
  val notificationPermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

  @get:Rule(order = 1)
  val composeRule = createAndroidComposeRule<MainActivity>()

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private var connection: PlaybackConnection? = null

  /**
   * Leaves nothing playing.
   *
   * Not tidiness: these tests share one instrumentation process, and a journey that measured
   * "the audio advanced" against a queue the *previous* test started would be exactly the vacuous
   * gate this class exists to avoid. No `lateinit` is read here — a teardown that throws replaces
   * the real failure with its own, which has already misled a lane in this repository.
   */
  @After
  fun tearDown() {
    val open = connection ?: onMain { PlaybackConnection(context, appArtworkUrls()) }
    connection = null
    // From the test thread: `controller()` hops to the main Looper itself, and a `runBlocking`
    // there would block the very Looper it is waiting on.
    val controller = runBlocking { open.controller() }
    onMain {
      controller.stop()
      controller.clearMediaItems()
      open.release()
    }
  }

  /**
   * **The original complaint.** Play a book, leave it mid-chapter, listen to music, come back — and
   * the book is exactly where it was, less the rewind its absence is worth.
   */
  @Test
  fun aBookResumesAtTheExactSecondItWasLeftAfterMusicInBetween() {
    val controller = connectController()

    // ---- 1. Play a book, and leave it mid-chapter ------------------------------------------
    composeRule.reachLibraryScreen()
    composeRule.openBookshelf()
    composeRule.openBookNamed(BOOK_TITLE)
    // From the top, deliberately: whatever an earlier test in this process left for this book,
    // the number this journey asserts on is one this journey produced.
    composeRule.onNodeWithText(START_OVER_LABEL).performClick()

    val bookMediaId = awaitCurrentItemTitled(controller, BOOK_TITLE)
    awaitOnMain("the book to decode past ${LEAVE_AFTER_MS}ms") {
      controller.currentPosition > LEAVE_AFTER_MS
    }
    composeRule.pausePlayback()
    awaitOnMain("playback to stop") { !controller.isPlaying }

    // ---- 2. Listen to music -----------------------------------------------------------------
    composeRule.pressBackToLibraryScreen()
    playTheFirstMusicTrack()
    val musicMediaId = awaitCurrentItemTitled(controller, MUSIC_TRACK_TITLE)
    assertThat(musicMediaId)
      .describedAs("music must actually be what is playing")
      .isNotEqualTo(bookMediaId)

    // Only now is the book's row final, and the reason is worth writing down because it cost a
    // device run to find. `ProgressWriter`'s ticker keeps stamping the **current** item every five
    // seconds, paused or not, so for as long as the book is still loaded its row can be rewritten
    // a few hundred milliseconds later than the pause that appeared to settle it -- measured here
    // as `expected:<6773L> but was:<6785L>` against a read taken 1.5 s after the pause. The instant
    // music becomes the current item nothing writes the book's row again, so this is the first
    // moment at which "where the listener left the book" is a number rather than a moving target.
    val left = settledRow(bookMediaId)
    // Mid-chapter, and not on a boundary: that is what makes "resumed exactly" and "resumed at the
    // chapter start" two different numbers rather than one.
    assertThat(left.positionMs)
      .describedAs("left inside \"${MIDDLE_CHAPTER.title}\" (${MIDDLE_CHAPTER.startMs}..${MIDDLE_CHAPTER.endMs})")
      .isStrictlyBetween(MIDDLE_CHAPTER.startMs, MIDDLE_CHAPTER.endMs)

    awaitOnMain("real music audio to advance") { controller.currentPosition > MUSIC_AUDIO_MS }
    composeRule.pausePlayback()

    // ---- 3. Come back to the book ------------------------------------------------------------
    composeRule.pressBackToLibraryScreen()
    composeRule.openBookshelf()
    // The shelf now says the listener is part-way through something. This header only renders for a
    // book with progress, so it is the shelf agreeing with the row read above.
    composeRule.onNodeWithText(CONTINUE_LISTENING_LABEL).assertIsDisplayed()
    composeRule.openBookNamed(BOOK_TITLE)

    // Spec §3's whole claim — "two pointer lists over one progress table" — observed rather than
    // argued, across the whole music session and the walk back. `media_progress` is one table; a
    // music session that shared the book's row, or reset it, fails here and nowhere else in the
    // repository.
    val stamped = requireRow(bookMediaId)
    assertThat(stamped.positionMs)
      .describedAs("an intervening music session must not move the book's row")
      .isEqualTo(left.positionMs)
    awaitAwayOfAtLeast(stamped.lastPlayedAtEpochMs, AWAY_FLOOR_MS)

    // ---- 4. It resumes at the stored second, less this absence's rewind ----------------------
    val expected = left.positionMs - REWIND_UNDER_A_MINUTE_MS
    composeRule.onNodeWithText(RESUME_LABEL).performClick()
    val landing = awaitLandingOn(controller, bookMediaId)

    val awayMs = landing.atEpochMs - stamped.lastPlayedAtEpochMs
    assertThat(awayMs)
      .describedAs("the book must have been away long enough to be in one known rewind band")
      .isGreaterThanOrEqualTo(AWAY_BAND_LOW_MS)
      .isLessThan(AWAY_BAND_HIGH_MS)

    assertThat(landing.positionMs)
      .describedAs(
        "resumed at, having been left at ${left.positionMs}ms and away for ${awayMs}ms " +
          "(expected exactly ${expected}ms: the stored position less a ${REWIND_UNDER_A_MINUTE_MS}ms rewind)",
      )
      .isGreaterThanOrEqualTo(expected)
      .isLessThan(expected + LANDING_SLACK_MS)

    // ---- 5. And it kept going ---------------------------------------------------------------
    // A player parked on the right number is not a book that resumed.
    awaitOnMain("the resumed book to decode past where it landed") {
      controller.currentPosition > landing.positionMs + RESUMED_AUDIO_MS
    }
  }

  /**
   * The assertion one book cannot make.
   *
   * With a single book, *"this book resumed at its own position"* and *"it resumed at the only
   * position stored"* are the same program. Two books, left far enough apart that neither number
   * could be mistaken for the other, are what make the claim per-**book** rather than per-app.
   */
  @Test
  fun twoBooksKeepTwoPlacesAtOnce() {
    val controller = connectController()
    composeRule.reachLibraryScreen()
    composeRule.openBookshelf()

    // The first book, left late.
    composeRule.openBookNamed(BOOK_TITLE)
    composeRule.onNodeWithText(START_OVER_LABEL).performClick()
    val lateId = awaitCurrentItemTitled(controller, BOOK_TITLE)
    awaitOnMain("the first book to decode past ${LEAVE_LATE_AFTER_MS}ms") {
      controller.currentPosition > LEAVE_LATE_AFTER_MS
    }
    composeRule.pausePlayback()

    // The other one, left early. Loading it is also what freezes the first book's row — see the
    // note in the journey above on why a row is only final once its item stops being current.
    composeRule.pressBackToLibraryScreen()
    composeRule.openBookshelf()
    composeRule.openBookNamed(OTHER_BOOK_TITLE)
    composeRule.onNodeWithText(START_OVER_LABEL).performClick()
    val earlyId = awaitCurrentItemTitled(controller, OTHER_BOOK_TITLE)
    assertThat(earlyId).describedAs("two books, two files").isNotEqualTo(lateId)
    awaitOnMain("the second book to decode past ${LEAVE_EARLY_AFTER_MS}ms") {
      controller.currentPosition > LEAVE_EARLY_AFTER_MS
    }
    composeRule.pausePlayback()
    awaitOnMain("the second book to stop") { !controller.isPlaying }

    val lateLeftAt = settledRow(lateId).positionMs
    val earlyLeftAt = requireRow(earlyId).positionMs

    // Far enough apart that no rewind this journey can reach could carry one onto the other.
    assertThat(lateLeftAt)
      .describedAs("the two books must be left at clearly different places")
      .isGreaterThan(earlyLeftAt + PLACES_APART_MS)

    // Back to the first book: its own place, not the other's, and not the beginning.
    composeRule.pressBackToLibraryScreen()
    composeRule.openBookshelf()
    composeRule.openBookNamed(BOOK_TITLE)
    composeRule.onNodeWithText(RESUME_LABEL).performClick()

    val landing = awaitLandingOn(controller, lateId)
    assertThat(landing.positionMs)
      .describedAs("the first book was left at ${lateLeftAt}ms, the second at ${earlyLeftAt}ms")
      .isGreaterThan(earlyLeftAt + PLACES_APART_MS - WITHIN_AN_HOUR_REWIND_MS)
      .isLessThanOrEqualTo(lateLeftAt)
    awaitOnMain("the resumed book to decode past where it landed") {
      controller.currentPosition > landing.positionMs + RESUMED_AUDIO_MS
    }
  }

  /**
   * **A listening position is local, and never reaches the server** — spec §§2, 4, 11, asserted at
   * Navidrome rather than at this app's own source.
   *
   * `:core:media`'s `LocalOnlyProgressTest` asserts that the client has no way to *send* a position.
   * This asserts that after a whole listening session Navidrome holds none, which is a genuinely
   * independent observation: a `savePlayQueue` or `createBookmark` added anywhere on the play path
   * fails here and passes there.
   *
   * The **positive control** is what makes it a gate rather than a wish. The test writes a bookmark
   * of its own, over raw HTTP, for the very media id it is about to assert about, confirms the query
   * can see it, and deletes it. Without that, "no bookmark" is equally satisfied by a query that
   * never worked, by a wrong endpoint name, and by an authentication failure.
   *
   * Scoped to **this book's own media id** rather than to the whole response, on purpose: the
   * container is shared with every other agent working in this repository, and an assertion that no
   * bookmark exists anywhere would be a gate on other people's suites.
   */
  @Test
  fun nothingAboutAListeningPositionEverReachesTheServer() {
    val controller = connectController()
    composeRule.reachLibraryScreen()
    composeRule.openBookshelf()
    composeRule.openBookNamed(BOOK_TITLE)
    composeRule.onNodeWithText(START_OVER_LABEL).performClick()
    val bookMediaId = awaitCurrentItemTitled(controller, BOOK_TITLE)

    // The control, first: prove the question can come back "yes" for this exact id.
    subsonic("createBookmark", "id" to bookMediaId, "position" to CONTROL_POSITION_MS)
    assertThat(subsonic("getBookmarks"))
      .describedAs("the positive control: a bookmark this test wrote must be visible")
      .contains(CONTROL_POSITION_MS)
    subsonic("deleteBookmark", "id" to bookMediaId)
    assertThat(subsonic("getBookmarks"))
      .describedAs("the control must be removable, or the assertion below proves nothing")
      .doesNotContain(CONTROL_POSITION_MS)

    // Now a real listening session: audio, a pause, and a stored position.
    awaitOnMain("the book to decode past ${LEAVE_AFTER_MS}ms") {
      controller.currentPosition > LEAVE_AFTER_MS
    }
    composeRule.pausePlayback()
    awaitOnMain("playback to stop") { !controller.isPlaying }
    assertThat(settledRow(bookMediaId).positionMs)
      .describedAs("the position must really have been stored locally, or nothing was under test")
      .isGreaterThan(LEAVE_AFTER_MS)

    assertThat(subsonic("getBookmarks"))
      .describedAs("this app must never create a server-side bookmark")
      .doesNotContain(bookMediaId)
    assertThat(subsonic("getPlayQueue"))
      .describedAs("this app must never save a server-side play queue")
      .doesNotContain(bookMediaId)
  }

  // ---- the walk ---------------------------------------------------------------------------------

  /** Music, from the library screen: the seeded album, opened, and its first track tapped. */
  private fun playTheFirstMusicTrack() {
    composeRule.onNodeWithText(MUSIC_LIBRARY_NAME).performClick()
    composeRule.waitUntil("the seeded music album", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_ALBUM_NAME).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(OPEN_LABEL).performClick()
    composeRule.waitUntil("the album's track list", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_TRACK_TITLE).notTheMiniPlayer().fetchSemanticsNodes()
        .isNotEmpty()
    }
    // This journey always arrives here with a book in the mini player -- that is the whole point
    // of it -- so the row has to be told apart from the bar. See `JourneyNavigation`.
    composeRule.onNodeWithTextOutsideMiniPlayer(MUSIC_TRACK_TITLE).performClick()
  }

  // ---- the app's own row ------------------------------------------------------------------------

  /**
   * The row for [mediaId] once it has stopped moving.
   *
   * `ProgressWriter` keeps stamping the current item every five seconds even while paused, so a
   * single read taken the instant after a tap can catch a value the ticker is about to replace.
   * Two equal reads [STABLE_MILLIS] apart is the cheapest signal that the writer has settled, and
   * it needs no knowledge of the tick interval.
   */
  private fun settledRow(mediaId: String): MediaProgressEntity {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    var last: MediaProgressEntity? = null
    var unchangedSince = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
      val row = runBlocking { journeyProgressDao().find(mediaId) }
      if (row != null && row.positionMs > 0L && row.positionMs == last?.positionMs) {
        if (System.currentTimeMillis() - unchangedSince >= STABLE_MILLIS) return row
      } else {
        unchangedSince = System.currentTimeMillis()
      }
      last = row
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
      "no settled media_progress row for $mediaId; last saw ${last?.positionMs}",
    )
  }

  /** Read-only, and thrown rather than null: a helper returning null would make callers vacuous. */
  private fun requireRow(mediaId: String): MediaProgressEntity =
    runBlocking { journeyProgressDao().find(mediaId) }
      ?: throw AssertionError("the app stored no media_progress row for $mediaId")

  /**
   * Holds the walk back until the book has been away for [floorMs].
   *
   * This is what puts the resume inside one known `SmartRewind` band instead of leaving the
   * expected number a function of how fast the machine happened to be. The book's row stops being
   * stamped the moment music takes the session over, so this measures from the row itself rather
   * than from a wall clock this test started.
   */
  private fun awaitAwayOfAtLeast(stampedAtEpochMs: Long, floorMs: Long) {
    while (System.currentTimeMillis() - stampedAtEpochMs < floorMs) {
      Thread.sleep(POLL_MILLIS)
    }
  }

  // ---- the session ------------------------------------------------------------------------------

  private data class Landing(val positionMs: Long, val atEpochMs: Long)

  /**
   * The **first** position the session publishes with [mediaId] current, and when it was seen.
   *
   * Sampled fast and taken once, never waited for: `MuPlayer` resolves the resume inside
   * `setMediaItems`, so the queue and the position arrive in one update, and the earliest sighting
   * is the seek's own target rather than somewhere real time carried playback to. That distinction
   * is the whole reason this repository's five-second fixtures have their own note in `CLAUDE.md`.
   */
  private fun awaitLandingOn(controller: MediaController, mediaId: String): Landing {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      val position: Long? = onMain {
        if (controller.currentMediaItem?.mediaId == mediaId) controller.currentPosition else null
      }
      if (position != null) return Landing(position, System.currentTimeMillis())
      Thread.sleep(LANDING_POLL_MILLIS)
    }
    throw AssertionError("the session never made $mediaId current")
  }

  /**
   * The media id of the item the session is playing once its title is [title].
   *
   * Looked up from the session rather than resolved from a title through the mirror:
   * `currentMediaItem.mediaId` is exactly the `media_progress` primary key, so the journey never
   * needs to know how anything is stored, and it stays a black-box walk through what a user sees
   * plus what the app persisted.
   */
  private fun awaitCurrentItemTitled(controller: MediaController, title: String): String {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    var last: String? = null
    while (System.currentTimeMillis() < deadline) {
      val item = onMain { controller.currentMediaItem }
      last = item?.mediaMetadata?.title?.toString()
      if (item != null && last == title) return item.mediaId
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("the session never made \"$title\" current; last saw \"$last\"")
  }

  private fun connectController(): MediaController {
    val open = connection ?: onMain { PlaybackConnection(context, appArtworkUrls()) }.also { connection = it }
    // From the test thread, never inside `runOnMainSync`: `controller()` hops to the main Looper
    // itself and a `runBlocking` there would deadlock against it.
    return runBlocking { open.controller() }
  }

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

  private fun awaitOnMain(description: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      if (onMain(condition)) return
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("timed out waiting for $description")
  }

  // ---- the server, asked directly ---------------------------------------------------------------

  /**
   * A raw Subsonic call made by the **test**, never through the app's client.
   *
   * Independence is the point: a question asked through `SubsonicClient` would be answered by the
   * same code the assertion is about.
   */
  private fun subsonic(command: String, vararg params: Pair<String, String>): String {
    val url = HttpUrl.Builder().scheme("http").host("localhost").port(SERVER_PORT)
      .addPathSegments("rest/$command.view")
      .addQueryParameter("u", USERNAME)
      .addQueryParameter("p", PASSWORD)
      .addQueryParameter("v", SUBSONIC_VERSION)
      .addQueryParameter("c", CLIENT_NAME)
      .addQueryParameter("f", "json")
      .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
      .build()
    return httpClient.newCall(Request.Builder().url(url).build()).execute()
      .use { checkNotNull(it.body).string() }
  }

  private val httpClient = OkHttpClient()

  private companion object {
    /**
     * The seeded corpus, **derived** from `ci/fixtures/books.tsv` via `BookFixtures` rather than
     * written down. A hardcoded corpus fact is what reddened this repository's whole device tier
     * the day a fourth music fixture landed; the titles and chapter boundaries below all move with
     * the fixtures.
     */
    val BOOK = BookFixtures.SECOND_BOOK
    val BOOK_TITLE: String = BOOK.albumName
    val OTHER_BOOK_TITLE: String = BookFixtures.TEST_BOOK.albumName

    /**
     * The chapter this journey leaves the book inside — the second, which neither starts at zero
     * nor ends at the file's end, so a resume that answered either would be visibly wrong.
     */
    val MIDDLE_CHAPTER = BOOK.chapters[1]

    val MUSIC_TRACK_TITLE: String = BookFixtures.MUSIC_TRACKS.first().title

    // Literal strings the real screens render, duplicated from the production code rather than
    // shared with it: a journey is a black-box walk through what a user sees, and a shared constant
    // would let a wording change pass unnoticed. Same stance as every other journey here.
    const val START_OVER_LABEL = "Start from the beginning"
    const val RESUME_LABEL = "Resume"
    const val CONTINUE_LISTENING_LABEL = "Continue listening"
    const val MUSIC_LIBRARY_NAME = "Music"
    const val MUSIC_ALBUM_NAME = "Test Album"
    const val OPEN_LABEL = "Open"

    /**
     * Where the book is left: inside [MIDDLE_CHAPTER] (4 000..9 000 ms of a 21-second file) and
     * comfortably clear of both its edges, so neither the pause's own latency nor the rewind can
     * carry the number onto a boundary.
     */
    const val LEAVE_AFTER_MS = 5_500L

    /**
     * Where [twoBooksKeepTwoPlacesAtOnce] leaves its two books.
     *
     * No chapter constraint applies there, so the late one goes deliberately deeper than the
     * journey above leaves it: the two places have to be further apart than the largest rewind
     * reachable inside this journey's own wall clock, or "came back to its own place" and "came
     * back to the other book's" stop being different assertions.
     */
    const val LEAVE_LATE_AFTER_MS = 9_000L
    const val LEAVE_EARLY_AFTER_MS = 1_200L

    /** More than [WITHIN_AN_HOUR_REWIND_MS], so the two places cannot be confused for each other. */
    const val PLACES_APART_MS = 6_000L

    /**
     * `SmartRewind`'s rewind for an absence between a minute and an hour — the largest band
     * [twoBooksKeepTwoPlacesAtOnce] can reach, since its whole walk back takes seconds.
     */
    const val WITHIN_AN_HOUR_REWIND_MS = 5_000L

    /**
     * `SmartRewind`'s rewind for an absence between 15 seconds and a minute.
     *
     * **Typed out, not imported.** An expectation computed by the code under test follows that code
     * into a mutation; this number goes red when the table changes, which is what makes this journey
     * a gate on the rewind band as well as on the resume itself.
     */
    const val REWIND_UNDER_A_MINUTE_MS = 2_000L

    /** How long the book is kept away, so the band above is the one that applies. */
    const val AWAY_FLOOR_MS = 20_000L

    /** The band's own edges, asserted at the moment of landing rather than assumed. */
    const val AWAY_BAND_LOW_MS = 15_000L
    const val AWAY_BAND_HIGH_MS = 60_000L

    /**
     * How far past the exact expected position the first sighting may be.
     *
     * Smaller than [REWIND_UNDER_A_MINUTE_MS] would make the no-rewind answer indistinguishable;
     * this admits it and nothing more, so `expected + 2 000` — a resume that ignored the rewind —
     * still fails.
     */
    const val LANDING_SLACK_MS = 1_500L

    /** Enough decoded audio that a parked player cannot pass for a resumed one. */
    const val RESUMED_AUDIO_MS = 1_500L
    const val MUSIC_AUDIO_MS = 1_500L

    /** Two equal reads this far apart mean `ProgressWriter` has settled. */
    const val STABLE_MILLIS = 1_500L

    const val TIMEOUT_MILLIS = 30_000L
    const val POLL_MILLIS = 100L

    /** Fast, because the value being caught is the seek's target before audio moves it. */
    const val LANDING_POLL_MILLIS = 20L

    const val SERVER_PORT = 4533
    const val SUBSONIC_VERSION = "1.16.1"
    const val CLIENT_NAME = "AudiobookResumeJourneyTest"

    /** Distinctive enough that finding it in a response is not a coincidence. */
    const val CONTROL_POSITION_MS = "424242"
  }
}
