package app.muplay.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.inspector.MetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.Chapter
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.testing.BookFixtures
import app.muplay.testing.ExpectedBook
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **This class closes the one thing spike S3 left open**, and spec sections 5 and 12 both name it:
 * chapter extraction had never been run against a real Navidrome `format=raw` URL, only against a
 * hand-rolled Python server. Everything below runs against the pinned container.
 *
 * Two things are asserted, and the second is the unusual one:
 *
 * 1. `ChapterReader` returns exactly what **ffprobe** reads out of the same bytes. ffprobe is an
 *    oracle independent of Media3; a golden file recording what Media3 returned last time is not.
 *    The oracle is `core/testing`'s `books.tsv`, re-derived and diffed by `ci/probe-chapters.sh
 *    --check` in both CI tiers.
 * 2. The **wrong** wiring is asserted to produce the **wrong** answer. Spike S3's central finding
 *    is that `MetadataRetriever.Builder(...).build()` without `setMediaSourceFactory` silently
 *    returns `C.TIME_UNSET` end times and drops `chap` chapters, with no exception and no log.
 *    `theBareRetrieverBuilderReturnsUnusableEndTimesAndThisIsWhyTheFactoryIsMandatory` builds the
 *    broken retriever by hand and asserts it is broken, so the footgun is a permanent executable
 *    record rather than a comment somebody can delete.
 *
 * **That second test is test-side, so it cannot detect the line being deleted from production
 * code — and neither, measured, can the oracle tests.** Deleting `setMediaSourceFactory` from
 * `ChapterReader` left all six of this class's original tests green on the emulator, because
 * `ChapterAssembly` fills a missing end from the next chapter's start and every chapter in the
 * seeded corpus abuts its neighbour. Two tests here exist because of that measurement and are the
 * only two that fail on it: `theLastChaptersEndComesFromTheFileAndNotFromTheDurationTheCallerPassed`
 * and `everyChapterProbeIsFetchedThroughTheInjectedDataSourceFactory`. See task-3-report.md.
 *
 * **Which fixture property makes the title assertions able to fail.** `Test Book`'s three chapter
 * atoms are literally titled `Chapter 1`/`Chapter 2`/`Chapter 3` — which is exactly the string
 * `BookTimeline` invents for an *untitled* chapter. On that book alone, "the reader returned the
 * file's titles" and "the reader returned nothing and something downstream numbered them" are the
 * same observation. `Second Book` (`Prologue`/`The Long Middle`/`A Turn`/`Epilogue`) and
 * `Tail Book` (`Head`/`Tail`) are what make it an assertion. The same goes for the times:
 * `Test Book`'s chapters are three equal 5000 ms spans, so `index * 5000` passes; `Second Book`'s
 * are 4000/5000/6000/6000 and `Tail Book`'s are 7000/5000, and neither is any arithmetic series.
 */
@RunWith(AndroidJUnit4::class)
class ChapterReaderTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var reader: ChapterReader
  private lateinit var songs: List<Song>

  /**
   * Requests that reached **this app's** OkHttp client.
   *
   * Not decoration: it is one of the two assertions that can see `setMediaSourceFactory` being
   * deleted. Without that line the retriever falls back to `DefaultDataSource.Factory`, which
   * fetches over `HttpURLConnection` and never touches this client, this project's media cache or
   * `RequestedUriDataSource` — the wrapper that keeps a credential-bearing redirect target out of
   * `exoplayer_internal.db`.
   */
  private val requests = AtomicInteger()

  @Before
  fun setUp() = runBlocking {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "chapters-${System.nanoTime()}")
    val counting = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain -> requests.incrementAndGet(); chain.proceed(chain.request()) })
      .build()
    reader = ChapterReader(
      context,
      MuPlayDataSourceFactory(counting, MediaCache.create(context, cacheDir)),
    )
    songs = RealTrackBytes.bookSongs()
  }

  @After
  fun tearDown() {
    cacheDir.deleteRecursively()
  }

  private fun song(title: String): Song = songs.single { it.title == title }

  private fun read(title: String): List<Chapter> = runBlocking {
    val song = song(title)
    reader.read(
      mediaId = song.id,
      uri = RealTrackBytes.source().streamUrl(
        song.id,
        StreamFormat.forSuffix(song.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS),
      ),
      contentDurationMs = song.durationSeconds * 1_000L,
    )
  }

  /**
   * Every file of [book], each against **its own** row set in the ffprobe oracle.
   *
   * File by file rather than book by book so that a multi-file book is covered by the same
   * assertion as a single-file one, and so `index` means "position in this file" throughout.
   */
  private fun assertMatchesOracle(book: ExpectedBook) {
    for (track in book.tracks) {
      val expected = BookFixtures.chaptersOf(track.path)
      val actual = read(track.title)

      // Exact lists, in order, field by field. Titles alone would pass for a reader that returned
      // the right names with wrong times; start times alone would pass for one that lost the
      // titles; ends alone would pass for one that lost both.
      assertThat(actual.map { it.startMs })
        .describedAs("${book.albumName} / ${track.title} chapter starts")
        .containsExactlyElementsOf(expected.map { it.startMs })
      assertThat(actual.map { it.endMs })
        .describedAs("${book.albumName} / ${track.title} chapter ends")
        .containsExactlyElementsOf(expected.map { it.endMs })
      assertThat(actual.map { it.title })
        .describedAs("${book.albumName} / ${track.title} chapter titles")
        .containsExactlyElementsOf(expected.map { it.title })
      assertThat(actual.map { it.index })
        .describedAs("${book.albumName} / ${track.title} chapter indices")
        .containsExactlyElementsOf(expected.indices.toList())
    }
  }

  @Test
  fun theThreeChapterFaststartBookMatchesFfprobe() {
    assertMatchesOracle(BookFixtures.TEST_BOOK)
  }

  @Test
  fun theFourChapterBookWithUnequalChaptersMatchesFfprobe() {
    // The second observation, and the one that breaks `index * 5000`. Test Book alone cannot
    // distinguish a reader from a constant.
    assertMatchesOracle(BookFixtures.SECOND_BOOK)
  }

  @Test
  fun aNonFaststartBookServedByNavidromeStillYieldsItsChapters() {
    // Spike S3's tail-Range finding, against Navidrome rather than against a Python script. If
    // this fails, the answer is in the server's response headers for a Range request into the
    // last kilobyte -- Task 1's live test asserts those, so read that failure first.
    assertMatchesOracle(BookFixtures.TAIL_BOOK)
  }

  @Test
  fun aFileWithNoChapterAtomsReturnsNoChaptersRatherThanOne() {
    // `Multi Part Book`'s parts. "No chapters" must be an empty list, not a fabricated whole-file
    // chapter -- a fabricated one would make every chapterless file look chaptered and would put a
    // chapter title on a music track.
    assertMatchesOracle(BookFixtures.MULTI_PART_BOOK)
    assertThat(read("Part One")).isEmpty()
    assertThat(read("Part Two")).isEmpty()
  }

  @Test
  fun everyChapterInTheCorpusCarriesAPopulatedEndTime() {
    // The direct assertion on the footgun's symptom, across every file at once. `C.TIME_UNSET` is
    // `Long.MIN_VALUE + 1`; if any end is negative, `setMediaSourceFactory` is missing.
    val all = BookFixtures.ALL_BOOKS.flatMap { book -> book.tracks.flatMap { read(it.title) } }

    // Not `isNotEmpty`: the exact number the oracle says the corpus holds, so a reader that
    // returned one chapter per book -- or none for three of the four -- cannot pass this line.
    assertThat(all)
      .describedAs("chapters read across the whole corpus")
      .hasSize(BookFixtures.ALL_BOOKS.sumOf { it.chapters.size })
    assertThat(all.map { it.endMs })
      .describedAs("every end time populated -- C.TIME_UNSET is negative")
      .allSatisfy { assertThat(it).isPositive }
    assertThat(all.map { it.durationMs })
      .describedAs("every chapter runs for a positive time")
      .allSatisfy { assertThat(it).isPositive }
    assertThat(all.none { it.endMs == C.TIME_UNSET }).isTrue
  }

  @Test
  fun theLastChaptersEndComesFromTheFileAndNotFromTheDurationTheCallerPassed() {
    // **The assertion that proves the end times came out of the bytes.** Every other assertion in
    // this class is satisfied by a reader that read no end times at all, and that is a measurement
    // rather than a worry: with `setMediaSourceFactory` deleted from `ChapterReader`, all six of
    // the original tests here stayed green on the emulator. The reason is the corpus. Every book
    // in it has chapters that abut — 0/5000/10000/15000 — so "the end Media3 read" and "the next
    // chapter's start" are the same number for every chapter but the last, and the last one's
    // fallback is `contentDurationMs`, which for these whole-second fixtures is exactly the
    // container's own answer too.
    //
    // Passing a duration that is deliberately wrong breaks the tie. If the ends came from the
    // file, the last chapter still ends at 15000; if they came from the fallback chain, it ends
    // at 999999 and this line says so.
    val song = song("Test Book")
    val chapters = runBlocking {
      reader.read(song.id, RealTrackBytes.source().streamUrl(song.id, StreamFormat.Raw), 999_999L)
    }

    assertThat(chapters.map { it.endMs })
      .describedAs("ends read from the file, against a caller-supplied duration that is nonsense")
      .containsExactlyElementsOf(BookFixtures.TEST_BOOK.chapters.map { it.endMs })
  }

  @Test
  fun everyChapterProbeIsFetchedThroughTheInjectedDataSourceFactory() {
    // The second thing `setMediaSourceFactory` decides, and the one that has nothing to do with
    // chapter times: which HTTP stack the probe uses. Deleting the line sends the fetch through
    // `HttpURLConnection` instead — no media cache, no `RequestedUriDataSource`, no `User-Agent`,
    // and none of this project's timeouts — with no signal anywhere.
    val before = requests.get()

    read("Tail Book")

    assertThat(requests.get() - before)
      .describedAs("requests that reached this app's own OkHttp client")
      .isPositive
  }

  @Test
  fun theBareRetrieverBuilderReturnsUnusableEndTimesAndThisIsWhyTheFactoryIsMandatory() {
    // Spike S3's central finding, executable. This is deliberately NOT a test of production code:
    // it builds the broken retriever by hand and asserts it is broken, so that "the factory is
    // required" is a fact this suite re-checks rather than a comment somebody can delete.
    //
    // If this test starts failing because the bare builder now works, Media3 fixed the bug --
    // delete the test, update spec section 5, and say so loudly. Do not weaken it.
    val testBook = song("Test Book")
    val uri = RealTrackBytes.source().streamUrl(testBook.id, StreamFormat.Raw)
    val ends = MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
      val groups = retriever.retrieveTrackGroups().get(30, TimeUnit.SECONDS)
      (0 until groups.length).flatMap { i ->
        val group = groups.get(i)
        (0 until group.length).flatMap { j ->
          val metadata = group.getFormat(j).metadata
          (0 until (metadata?.length() ?: 0)).mapNotNull { k ->
            (metadata!!.get(k) as? androidx.media3.extractor.metadata.Chapter)?.endTimeMs
          }
        }
      }
    }

    assertThat(ends).describedAs("the bare builder found chapters at all").isNotEmpty
    assertThat(ends).describedAs("every end time unset, exactly as spike S3 measured")
      .containsOnly(C.TIME_UNSET)
    // ...and the wired reader, over the same URL, does not.
    assertThat(read("Test Book").map { it.endMs })
      .containsExactlyElementsOf(BookFixtures.TEST_BOOK.chapters.map { it.endMs })
  }
}
