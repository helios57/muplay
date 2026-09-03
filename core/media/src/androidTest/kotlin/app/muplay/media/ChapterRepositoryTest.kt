package app.muplay.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.MuPlayDatabase
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parse once, then serve from Room.
 *
 * Two signals, and **only one of them discriminates** — which was measured, not assumed:
 *
 * * **The `chapter_scans` row's `scannedAtEpochMs`, under a clock the test moves between reads.**
 *   This is the assertion that catches a re-parse. A read served from Room touches no row; a
 *   re-parse rewrites the scan row with the clock's new reading.
 * * **HTTP requests that did not happen** — the plan's signal, the same shape as Plan 3 Task 3's
 *   media-cache proof. It is kept because "the first read really went to the network" is worth
 *   asserting, and it is **not** what proves the second read was cached.
 *
 * The measurement, on the emulator: delete the `findScan` short-circuit from
 * `ChapterRepository.chaptersFor` — so every call re-parses — and withhold the scan-row assertion
 * from `aSecondReadOfTheSameBookIsServedFromRoomWithoutReParsing`, and that test is **green**.
 * `ChapterReader` fetches through a `CacheDataSource` over a real `SimpleCache`, so the second
 * parse reads the same `moov` bytes off disk and issues no request at all. Counting requests
 * cannot tell "served from Room" from "re-parsed from the byte cache". With the scan assertion
 * in place the same mutation fails both cached-read tests. See `task-3-report.md`.
 *
 * The last three tests are about the other outcome -- **a probe that throws** -- which used to be
 * remembered nowhere, so a file the server could not serve was re-probed on every open at up to
 * thirty seconds each, forever. They use a second repository pointed at a port nothing is
 * listening on, and their discriminating signal is the same one as above from the other side:
 * requests that did **not** happen, which here is unambiguous because there is no cached-byte
 * path to a failure.
 */
@RunWith(AndroidJUnit4::class)
class ChapterRepositoryTest {

  // Every `@Test` below declares `: Unit` explicitly. `fun x() = runBlocking { .. }` infers its
  // return type from the block's last expression, and most AssertJ assertions return the assert
  // object -- JUnit 4 then refuses the whole class at load time with "Invalid test class ...
  // Method x() should be void", naming four methods and running none. Measured on the emulator;
  // the plan's listing for this class has the same shape. `isEmpty()` happens to return `void`,
  // which is why one of the five looked fine.


  private lateinit var context: Context
  private lateinit var db: MuPlayDatabase
  private lateinit var repository: ChapterRepository
  private lateinit var reader: ChapterReader
  private lateinit var cacheDir: File
  private lateinit var songs: List<Song>
  private val requests = AtomicInteger()

  /** Every DataStore file this class opens, deleted in `@After`. One per provider built. */
  private val credentialFiles = mutableListOf<File>()

  /**
   * A clock whose reading the test moves by hand.
   *
   * `Clock.fixed` cannot express "this read happened later than that one", which is the whole
   * point of the second signal above.
   */
  private class SettableClock(var epochMillis: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(epochMillis)
  }

  private val clock = SettableClock(FIRST_READ_AT)

  @Before
  fun setUp() = runBlocking {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    cacheDir = File(context.cacheDir, "chapter-repo-${System.nanoTime()}")

    val counting = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain -> requests.incrementAndGet(); chain.proceed(chain.request()) })
      .build()
    // A field rather than a local: `unreachableRepository` reuses this exact reader, because
    // `MediaCache.create` refuses a second `SimpleCache` over one directory and a second cache
    // directory would give the failing repository a byte cache the real one never warmed.
    reader = ChapterReader(context, MuPlayDataSourceFactory(counting, MediaCache.create(context, cacheDir)))

    // A real `SubsonicSourceProvider` over a real `CredentialStore`, with only the factory
    // substituted -- the construction `FixedSubsonicSourceProvider` exists for. The source it
    // yields is `RealTrackBytes`'s one client, so the URLs the repository builds are the same
    // authenticated URLs production builds.
    val (provider, file) = fixedSubsonicSourceProvider(context, RealTrackBytes.source())
    credentialFiles += file
    songs = RealTrackBytes.audiobookFiles()
    repository = ChapterRepository(db.chapterDao(), reader, provider, clock)
  }

  @After
  fun tearDown() {
    db.close()
    cacheDir.deleteRecursively()
    credentialFiles.forEach { it.delete() }
  }

  /**
   * The same repository, the same reader and the same byte cache, pointed at a dead port.
   *
   * A real failure rather than a substituted one: the URLs are built the way production builds
   * them and the throw comes out of `MetadataRetriever` through `ChapterReader`, which is the only
   * way to be sure the thing being remembered is the thing that actually happens. Nothing listens
   * on port 1, so the connection is refused rather than left to time out.
   */
  private fun unreachableRepository(): ChapterRepository {
    val (provider, file) = fixedSubsonicSourceProvider(
      context,
      SubsonicClient(SubsonicCredentials(DEAD_URL, "admin", "testpass")),
      baseUrl = DEAD_URL,
    )
    credentialFiles += file
    return ChapterRepository(db.chapterDao(), reader, provider, clock)
  }

  private fun song(title: String): Song = songs.single { it.title == title }

  private fun scannedAt(title: String): Long? =
    runBlocking { db.chapterDao().findScan(song(title).id)?.scannedAtEpochMs }

  @Test
  fun aSecondReadOfTheSameBookIsServedFromRoomWithoutReParsing(): Unit = runBlocking {
    val first = repository.chaptersFor(song("Second Book"))
    val afterFirst = requests.get()

    clock.epochMillis = SECOND_READ_AT
    val second = repository.chaptersFor(song("Second Book"))

    assertThat(afterFirst).describedAs("the first read must actually go to the network").isPositive
    // THE assertion. A re-parse rewrites the scan row with the clock's new reading; a read served
    // from Room does not touch it. Withhold this line and the mutation that removes the
    // short-circuit passes -- measured, see the class header.
    assertThat(scannedAt("Second Book"))
      .describedAs("the scan row must still be the first read's, not a second parse's")
      .isEqualTo(FIRST_READ_AT)
    // Carried as a measurement, not as the discriminator: the media cache satisfies it either way.
    assertThat(requests.get() - afterFirst).describedAs("further HTTP requests").isZero
    // ...and it is the same answer, not an empty one. A cache that returned nothing would also
    // make zero requests and touch no scan row.
    assertThat(second.map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    assertThat(second).isEqualTo(first)
  }

  @Test
  fun aChapterlessFileIsProbedOnceAndThenRemembered(): Unit = runBlocking {
    // The negative cache, which is the common case rather than the rare one: most audiobook files
    // in the world carry no chapter atoms. Without `chapter_scans` this file is re-probed over
    // HTTP every time a screen opens -- `find` returning nothing is ambiguous between "no
    // chapters" and "never looked".
    val first = repository.chaptersFor(song("Part One"))
    val afterFirst = requests.get()

    clock.epochMillis = SECOND_READ_AT
    val second = repository.chaptersFor(song("Part One"))

    assertThat(first).isEmpty()
    assertThat(afterFirst).isPositive
    assertThat(scannedAt("Part One"))
      .describedAs("a file with no chapters is still recorded as scanned, once")
      .isEqualTo(FIRST_READ_AT)
    assertThat(requests.get() - afterFirst).describedAs("further HTTP requests").isZero
    assertThat(second).isEmpty()
  }

  @Test
  fun oneBooksCachedChaptersAreNotAnotherBooksChapters(): Unit = runBlocking {
    // Two books, two answers, from one cache. With one book, "cached chapters for X" and "the
    // cached chapters" are the same query.
    repository.chaptersFor(song("Second Book"))
    repository.chaptersFor(song("Tail Book"))

    // Read back through the cache, not through the first call's return value: the point is what
    // Room hands back for one media id when another book's rows are in the same two tables.
    val second = repository.chaptersFor(song("Second Book"))
    val tail = repository.chaptersFor(song("Tail Book"))

    assertThat(second.map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    assertThat(tail.map { it.title }).containsExactly("Head", "Tail")
    // The times too: a `find` that ignored its `mediaId` would return all six rows for both, and
    // titles alone would not say which chapter each start belongs to.
    assertThat(second.map { it.startMs }).containsExactly(0L, 4_000L, 9_000L, 15_000L)
    assertThat(tail.map { it.startMs }).containsExactly(0L, 7_000L)
  }

  @Test
  fun forgettingAFileMakesTheNextReadParseItAgain(): Unit = runBlocking {
    repository.chaptersFor(song("Test Book"))
    val afterFirst = requests.get()

    repository.forget(song("Test Book").id)
    assertThat(scannedAt("Test Book")).describedAs("the scan row after forget").isNull()

    clock.epochMillis = SECOND_READ_AT
    val again = repository.chaptersFor(song("Test Book"))

    // The control for the two "served from Room" assertions above: if neither signal could move,
    // those assertions would be measuring nothing.
    assertThat(scannedAt("Test Book")).isEqualTo(SECOND_READ_AT)
    assertThat(again.map { it.title }).containsExactly("Chapter 1", "Chapter 2", "Chapter 3")
    // Recorded as a measurement rather than asserted: whether this goes back to the network
    // depends on whether the *media* cache still holds the bytes, which is not what `forget` is
    // about. See the class header.
    assertThat(requests.get()).describedAs("requests after forget").isGreaterThanOrEqualTo(afterFirst)
  }

  @Test
  fun readingAWholeBookAtOnceReturnsAMapKeyedByMediaId(): Unit = runBlocking {
    // Deliberately mixed: a four-chapter book, a two-chapter book and a chapterless file. A batch
    // read that returned one entry, that keyed by title, or that gave every key the same list
    // fails here and passes every single-song test above.
    val batch = listOf(song("Second Book"), song("Tail Book"), song("Part Two"))

    val byId = repository.chaptersFor(batch)

    assertThat(byId.keys).containsExactlyElementsOf(batch.map { it.id })
    assertThat(byId.values.map { it.size }).containsExactly(4, 2, 0)
    assertThat(byId.getValue(song("Tail Book").id).map { it.title }).containsExactly("Head", "Tail")
  }

  @Test
  fun aProbeThatThrowsIsRememberedSoTheNextOpenDoesNotPayItAgain(): Unit = runBlocking {
    // Before this, a file the server could not serve was re-probed every time a book screen
    // opened -- and `chaptersFor(List)` fails a whole book at its first bad file, so one such file
    // in a forty-file book cost up to thirty seconds and produced nothing, on every open, forever.
    val unreachable = unreachableRepository()

    val first = runCatching { unreachable.chaptersFor(song("Test Book")) }
    val afterFirst = requests.get()
    val second = runCatching { unreachable.chaptersFor(song("Test Book")) }

    assertThat(first.exceptionOrNull()).describedAs("the first probe").isNotNull
    assertThat(afterFirst).describedAs("the first probe must really try the server").isPositive
    // THE assertion. Unlike the cached-read tests above there is no byte-cache path that could
    // satisfy this by accident: a failure leaves nothing on disk to be served from.
    assertThat(requests.get() - afterFirst)
      .describedAs("requests the second open paid")
      .isZero
    // Still a failure, and still the caller's problem -- the screen needs to know.
    assertThat(second.exceptionOrNull()).describedAs("the second probe").isNotNull
    // And nothing durable was written. A `chapter_scans` row would mean "this file's chapters are
    // known", would outlive the outage that caused it, and would be indistinguishable from the
    // genuinely chapterless file that row exists to record.
    assertThat(scannedAt("Test Book")).describedAs("the scan row after a failure").isNull()
  }

  @Test
  fun forgettingTheFailuresSendsTheNextReadBackToTheServer(): Unit = runBlocking {
    // The healing path, and the one the book screen's "Try again" presses. Without it the
    // remembering would last the whole process and a server that came back could not be noticed
    // -- which is the failure mode a durable negative cache has, moved into memory.
    val unreachable = unreachableRepository()
    runCatching { unreachable.chaptersFor(song("Test Book")) }
    runCatching { unreachable.chaptersFor(song("Test Book")) }
    val afterRemembered = requests.get()

    unreachable.forgetFailures()
    runCatching { unreachable.chaptersFor(song("Test Book")) }

    assertThat(requests.get())
      .describedAs("requests after forgetting the failure")
      .isGreaterThan(afterRemembered)
  }

  @Test
  fun aCancelledReadIsNotRememberedAsAFailure(): Unit = runBlocking {
    // A listener leaving the book screen cancels the read. Remembering *that* as a failure would
    // poison the file for the rest of the process, and the next open would say "couldn't read the
    // chapters" about a server nobody had asked. This is what the separate
    // `catch (e: CancellationException)` in `chaptersFor` is for.
    //
    // **The cancellation is timed off the request counter, not off a clock, and the first version
    // of this test got that wrong.** It cancelled with `withTimeout(1)`, and the mutation that
    // deletes the `CancellationException` clause was measured GREEN against it: one millisecond
    // does not get past `findScan` and the stream-URL build, so the cancellation was landing
    // before `chapterReader.read` was ever entered and the clause under test never ran. Waiting
    // for a request to leave the device is what puts the cancellation *inside* the read, which is
    // the only place it can be confused with a failure.
    val before = requests.get()
    val job = launch(Dispatchers.Default) { repository.chaptersFor(song("Tail Book")) }
    withTimeout(READ_START_TIMEOUT_MS) {
      while (requests.get() == before) delay(POLL_MS)
    }
    job.cancelAndJoin()

    // The read is not remembered, so this one reaches the file and answers. With the clause
    // deleted it throws the remembered cancellation instead, and this line is what says so.
    val again = repository.chaptersFor(song("Tail Book"))
    assertThat(again.map { it.title }).containsExactly("Head", "Tail")
    assertThat(scannedAt("Tail Book")).describedAs("the scan row").isEqualTo(FIRST_READ_AT)
  }

  private companion object {
    const val FIRST_READ_AT = 1_700_000_000_000L
    const val SECOND_READ_AT = 1_700_000_555_000L

    /** Nothing listens here, so a stream request is refused rather than left to time out. */
    const val DEAD_URL = "http://localhost:1"

    /** How long to wait for the cancelled read to reach the network before giving up on it. */
    const val READ_START_TIMEOUT_MS = 30_000L
    const val POLL_MS = 1L
  }
}
