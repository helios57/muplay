package app.muplay.testing

/** One audio file in the seeded corpus, as `ffprobe` reads it. */
data class ExpectedTrack(
  val path: String,
  val durationMs: Long,
  val title: String,
  val trackNumber: Int,
)

/** One chapter atom in a file, as `ffprobe` reads it. */
data class ExpectedChapter(val startMs: Long, val endMs: Long, val title: String)

/**
 * One book in the seeded corpus: its files in track order, and every chapter atom across them in
 * file order.
 */
data class ExpectedBook(
  val albumName: String,
  val authorName: String,
  val tracks: List<ExpectedTrack>,
  val chapters: List<ExpectedChapter>,
) {
  val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

/**
 * The committed fixture oracle, parsed from `/fixtures/books.tsv`.
 *
 * That file is derived from the committed audio by `ci/probe-chapters.sh`, which uses **ffprobe**
 * — a reader entirely independent of Media3. Task 3 asserts that Media3 agrees with it. Two
 * independent readers of the same bytes agreeing is evidence; a golden file recording what this
 * project's own code produced is not, and this project has paid for that distinction repeatedly.
 *
 * `ci/probe-chapters.sh --check` re-derives the table from the fixtures and diffs, in both CI
 * tiers, so the oracle cannot rot silently; `md5sum -c ci/fixtures.md5` in the same two jobs is
 * what stops the *fixtures* changing underneath it. Neither gate is decoration: both have been
 * run red on purpose (see task-1-report.md).
 *
 * The resource is on the JVM classpath, which means it is also packaged into the APK's Java
 * resources, so instrumented tests read it the same way.
 *
 * Parsed rather than transcribed on purpose: a hand-written copy of the table is a second truth
 * that drifts, and `ci/probe-chapters.sh --check` would have no way to notice.
 *
 * ### Durations are what is in the file, not what the seed script asked for
 *
 * The `.m4b` books come back as round numbers (21000 / 15000 / 12000 ms). The `.mp3` files do
 * not — 4049 / 6034 / 5042 — because libmp3lame pads to a whole 1152-sample frame and prepends an
 * encoder delay, and both ffprobe and Media3's `Mp3Extractor` report that untrimmed frame span as
 * the duration (the trim happens at the audio sink). There is no libmp3lame invocation that
 * produces 4000; rounding these to it here would be inventing a third answer neither reader gives.
 */
object BookFixtures {

  const val RESOURCE = "/fixtures/books.tsv"

  private const val TRACK = "track"
  private const val CHAPTER = "chapter"

  private val rows: List<List<String>> by lazy {
    val text = checkNotNull(BookFixtures::class.java.getResourceAsStream(RESOURCE)) {
      "$RESOURCE is not on the classpath. Run ci/probe-chapters.sh and commit the result."
    }.use { it.readBytes().decodeToString() }

    text.lineSequence()
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .map { it.split('\t') }
      .toList()
      .also { check(it.isNotEmpty()) { "$RESOURCE parsed to zero rows" } }
  }

  private val tracksByPath: List<ExpectedTrack> by lazy {
    rows.filter { it[0] == TRACK }
      .map {
        ExpectedTrack(
          path = it[1],
          durationMs = it[2].toLong(),
          title = it[3],
          trackNumber = it[4].toInt(),
        )
      }
  }

  private val chaptersByPath: Map<String, List<ExpectedChapter>> by lazy {
    rows.filter { it[0] == CHAPTER }
      .groupBy({ it[1] }) {
        ExpectedChapter(startMs = it[2].toLong(), endMs = it[3].toLong(), title = it[4])
      }
  }

  /**
   * The book whose files live under [directory], relative to `ci/fixtures`.
   *
   * Throws rather than returning an empty book when nothing is there: an [ExpectedBook] with no
   * tracks has a zero duration and no chapters, and every `containsExactly` written against it
   * would have to be an empty expectation — the vacuous-collection defect this whole corpus exists
   * to make impossible.
   */
  fun bookAt(albumName: String, authorName: String, directory: String): ExpectedBook {
    // Ordered by track number, then by path -- the order a listener expects a book's files in, and
    // the order `AudiobookRepository.chapterFiles` must reproduce (Task 4). The single-file books
    // carry no `track` tag, so they sort at trackNumber 0; with one file that is not a tie-break
    // anyone can observe, and the path tie-break is what keeps it deterministic if one ever gains
    // a second file.
    val tracks = tracksByPath.filter { it.path.startsWith("$directory/") }
      .sortedWith(compareBy({ it.trackNumber }, { it.path }))
    check(tracks.isNotEmpty()) { "no fixture tracks under $directory" }
    return ExpectedBook(
      albumName = albumName,
      authorName = authorName,
      tracks = tracks,
      chapters = tracks.flatMap { chaptersOf(it.path) },
    )
  }

  /** Every chapter atom in the file at [path], in file order. Empty for a chapterless file. */
  fun chaptersOf(path: String): List<ExpectedChapter> = chaptersByPath[path].orEmpty()

  val TEST_BOOK: ExpectedBook by lazy {
    bookAt("Test Book", "Test Author", "Audiobooks/Test Author/Test Book")
  }
  val SECOND_BOOK: ExpectedBook by lazy {
    bookAt("Second Book", "Second Author", "Audiobooks/Second Author/Second Book")
  }
  val TAIL_BOOK: ExpectedBook by lazy {
    bookAt("Tail Book", "Third Author", "Audiobooks/Third Author/Tail Book")
  }
  val MULTI_PART_BOOK: ExpectedBook by lazy {
    bookAt("Multi Part Book", "Fourth Author", "Audiobooks/Fourth Author/Multi Part Book")
  }

  /** Alphabetical by album name — the order `getAlbumList2(ALPHABETICAL_BY_NAME)` returns them in. */
  val ALL_BOOKS: List<ExpectedBook> by lazy { listOf(MULTI_PART_BOOK, SECOND_BOOK, TAIL_BOOK, TEST_BOOK) }

  val MUSIC_TRACKS: List<ExpectedTrack> by lazy {
    tracksByPath.filter { it.path.startsWith("Music/") }.sortedBy { it.trackNumber }
  }

  /** Every path the table knows about — the input to the "nothing is unaccounted for" assertion. */
  fun allTrackPaths(): List<String> = tracksByPath.map { it.path }

  /**
   * Every chapter row the table knows about, in file-then-file-order — the chapter half of that
   * same assertion, which [allTrackPaths] cannot make: a chapter row against a path no book claims
   * leaves the track list untouched.
   */
  fun allChapters(): List<ExpectedChapter> = chaptersByPath.values.flatten()
}
