package app.muplay.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.MuPlayDatabase
import app.muplay.model.Song
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
 * Parse once, then serve from Room.
 *
 * Two independent signals, because one of them is confounded and the other is not:
 *
 * * **HTTP requests that did not happen** — the same shape as Plan 3 Task 3's media-cache proof.
 *   Counting requests rather than timing anything matters: a cache that "felt fast" because the
 *   media cache had the bytes would still be re-parsing, and the parse is the expensive part.
 * * **The `chapter_scans` row's `scannedAtEpochMs`, under a clock that is moved between reads.**
 *   This is the signal the media cache cannot fake. `ChapterReader` fetches through a
 *   `CacheDataSource` over a real `SimpleCache`, so a *second* parse of the same file can be
 *   served entirely from disk and cost zero HTTP requests even with the chapter cache removed. A
 *   re-parse rewrites the scan row; a served-from-Room read does not touch it. See
 *   `task-3-report.md` for which of the two actually fired against the mutation.
 */
@RunWith(AndroidJUnit4::class)
class ChapterRepositoryTest {

  private lateinit var context: Context
  private lateinit var db: MuPlayDatabase
  private lateinit var repository: ChapterRepository
  private lateinit var cacheDir: File
  private lateinit var credentialFile: File
  private lateinit var songs: List<Song>
  private val requests = AtomicInteger()

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
    val reader = ChapterReader(context, MuPlayDataSourceFactory(counting, MediaCache.create(context, cacheDir)))

    // A real `SubsonicSourceProvider` over a real `CredentialStore`, with only the factory
    // substituted -- the construction `FixedSubsonicSourceProvider` exists for. The source it
    // yields is `RealTrackBytes`'s one client, so the URLs the repository builds are the same
    // authenticated URLs production builds.
    val (provider, file) = fixedSubsonicSourceProvider(context, RealTrackBytes.source())
    credentialFile = file
    songs = RealTrackBytes.bookSongs()
    repository = ChapterRepository(db.chapterDao(), reader, provider, clock)
  }

  @After
  fun tearDown() {
    db.close()
    cacheDir.deleteRecursively()
    credentialFile.delete()
  }

  private fun song(title: String): Song = songs.single { it.title == title }

  private fun scannedAt(title: String): Long? =
    runBlocking { db.chapterDao().findScan(song(title).id)?.scannedAtEpochMs }

  @Test
  fun aSecondReadOfTheSameBookIsServedFromRoomWithoutReParsing() = runBlocking {
    val first = repository.chaptersFor(song("Second Book"))
    val afterFirst = requests.get()

    clock.epochMillis = SECOND_READ_AT
    val second = repository.chaptersFor(song("Second Book"))

    assertThat(afterFirst).describedAs("the first read must actually go to the network").isPositive
    // The signal the byte cache cannot fake: a re-parse would rewrite the scan row with the clock's
    // new reading.
    assertThat(scannedAt("Second Book"))
      .describedAs("the scan row must still be the first read's, not a second parse's")
      .isEqualTo(FIRST_READ_AT)
    assertThat(requests.get() - afterFirst).describedAs("further HTTP requests").isZero
    // ...and it is the same answer, not an empty one. A cache that returned nothing would also
    // make zero requests and touch no scan row.
    assertThat(second.map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    assertThat(second).isEqualTo(first)
  }

  @Test
  fun aChapterlessFileIsProbedOnceAndThenRemembered() = runBlocking {
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
  fun oneBooksCachedChaptersAreNotAnotherBooksChapters() = runBlocking {
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
  fun forgettingAFileMakesTheNextReadParseItAgain() = runBlocking {
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
  fun readingAWholeBookAtOnceReturnsAMapKeyedByMediaId() = runBlocking {
    // Deliberately mixed: a four-chapter book, a two-chapter book and a chapterless file. A batch
    // read that returned one entry, that keyed by title, or that gave every key the same list
    // fails here and passes every single-song test above.
    val batch = listOf(song("Second Book"), song("Tail Book"), song("Part Two"))

    val byId = repository.chaptersFor(batch)

    assertThat(byId.keys).containsExactlyElementsOf(batch.map { it.id })
    assertThat(byId.values.map { it.size }).containsExactly(4, 2, 0)
    assertThat(byId.getValue(song("Tail Book").id).map { it.title }).containsExactly("Head", "Tail")
  }

  private companion object {
    const val FIRST_READ_AT = 1_700_000_000_000L
    const val SECOND_READ_AT = 1_700_000_555_000L
  }
}
