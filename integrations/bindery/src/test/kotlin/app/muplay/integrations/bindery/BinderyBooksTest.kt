package app.muplay.integrations.bindery

import app.muplay.integrations.RequestStatus
import app.muplay.integrations.bindery.BinderyTestServer.client
import app.muplay.integrations.bindery.BinderyTestServer.fixture
import app.muplay.integrations.bindery.BinderyTestServer.json
import app.muplay.integrations.bindery.BinderyTestServer.next
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What happened to the books this app asked for.
 *
 * `GET /api/v1/book` is the **only authenticated read** this client has, which makes it two things
 * at once: the status poll Task 9 runs, and the call a connection check has to make in order to
 * prove the key — because `GET /api/v1/health` is unauthenticated and cannot.
 *
 * It also returns an envelope where the search returns a bare array, and its `total` is the count
 * *before* `limit` applies. A client returning a bare list would let a caller read one page and
 * believe it had read the library.
 */
class BinderyBooksTest {

  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  private fun nextRequest(): RecordedRequest = next(server)

  // ---- the request ---------------------------------------------------------------------------

  /**
   * Every one of the three query parameters carries what it was given, at two values each.
   *
   * `limit` and `offset` in particular: a client that sent constants would page correctly on the
   * first call and then read the same page forever, which looks exactly like a library that has
   * stopped changing.
   */
  @Test
  fun `the status, limit and offset are each sent as given, at two values`() = runTest {
    server.enqueue(fixture("bindery/books-wanted.json"))
    client(server).books(status = "wanted", limit = 50, offset = 0)

    val first = nextRequest().url
    assertThat(first.encodedPath).isEqualTo("/api/v1/book")
    assertThat(first.queryParameter("status")).isEqualTo("wanted")
    assertThat(first.queryParameter("limit")).isEqualTo("50")
    assertThat(first.queryParameter("offset")).isEqualTo("0")
    // The exact query string, in order, so nothing is smuggled in beside them.
    assertThat(first.encodedQuery).isEqualTo("status=wanted&limit=50&offset=0")

    server.enqueue(fixture("bindery/books-imported.json"))
    client(server).books(status = "imported", limit = 7, offset = 21)

    val second = nextRequest().url
    assertThat(second.queryParameter("status")).isEqualTo("imported")
    assertThat(second.queryParameter("limit")).isEqualTo("7")
    assertThat(second.queryParameter("offset")).isEqualTo("21")
    assertThat(second.encodedQuery).isEqualTo("status=imported&limit=7&offset=21")
  }

  /**
   * A `null` status is **no `status` parameter at all**, not `status=` and not `status=null`.
   *
   * Retrofit omits a null `@Query`, and this is the assertion that pins it: measured against a real
   * instance, `GET /api/v1/book` with no parameter returns every book, `?status=` and
   * `?status=null` would both be unknown statuses — and an unknown status is answered `200` with
   * an **empty page**, so getting this wrong yields silence rather than a failure.
   */
  @Test
  fun `no status means the parameter is absent, not empty and not the word null`() = runTest {
    server.enqueue(fixture("bindery/books-all.json"))

    client(server).books(status = null, limit = 100, offset = 0)

    val url = nextRequest().url
    assertThat(url.queryParameter("status")).isNull()
    assertThat(url.encodedQuery).isEqualTo("limit=100&offset=0")
    assertThat(url.encodedQuery).doesNotContain("null")
  }

  // ---- the response --------------------------------------------------------------------------

  /**
   * Every field of every book, off the real payload, at four different rows.
   *
   * `containsExactly` on each column rather than a spot check on one row: a mapper that read the
   * right field from the wrong element, or that mapped one row and repeated it, satisfies any
   * assertion about a single book.
   */
  @Test
  fun `every book field is read from its own row, in the order the server sent them`() = runTest {
    server.enqueue(fixture("bindery/books-wanted.json"))

    val page = client(server).books(status = "wanted", limit = 100, offset = 0)

    assertThat(page.books).hasSize(4)
    assertThat(page.books.map { it.id }).containsExactly(6, 1, 5, 7)
    assertThat(page.books.map { it.foreignBookId })
      .containsExactly("OL258850W", "OL21745884W", "OL258758W", "OL362694W")
    assertThat(page.books.map { it.title }).containsExactly(
      "Othello",
      "Project Hail Mary",
      "The Merchant of Venice",
      "Twelfth Night, or What You Will",
    )
    assertThat(page.books.map { it.status })
      .containsExactly("wanted", "wanted", "wanted", "wanted")
    // The trap field, read back off a stored book: three audiobooks and one `both`, which is what
    // was asked for. A client that dropped `mediaType` on the way in gets `ebook` here.
    assertThat(page.books.map { it.mediaType })
      .containsExactly("audiobook", "audiobook", "audiobook", "both")
  }

  /**
   * The envelope's three numbers are read from the envelope, not invented.
   *
   * Two observations: a real page where `total` equals the item count, and a synthetic one where
   * it does not. The second is the case that matters — it is what a caller needs in order to know
   * there is another page — and it is the one a client that returned `books.size` as `total`
   * fails.
   */
  @Test
  fun `the page carries the server's own total, limit and offset`() = runTest {
    server.enqueue(fixture("bindery/books-all.json"))
    val whole = client(server).books(status = null, limit = 100, offset = 0)
    assertThat(whole.total).isEqualTo(7)
    assertThat(whole.limit).isEqualTo(100)
    assertThat(whole.offset).isEqualTo(0)
    assertThat(whole.books).hasSize(7)

    // A short page of a longer library: measured against a real instance, `?limit=2&offset=1` on
    // four books answers `{"total":4,"limit":2,"offset":1}` with two items.
    server.enqueue(
      json(
        """{"items":[{"id":3,"foreignBookId":"OL258902W","title":"Macbeth","status":"downloaded","mediaType":"audiobook"}],"total":4,"limit":2,"offset":1}""",
      ),
    )
    val partial = client(server).books(status = null, limit = 2, offset = 1)
    assertThat(partial.total).isEqualTo(4)
    assertThat(partial.limit).isEqualTo(2)
    assertThat(partial.offset).isEqualTo(1)
    // The point of the whole type: one item in hand and four in the library.
    assertThat(partial.books).hasSize(1)
    assertThat(partial.total).isNotEqualTo(partial.books.size)
  }

  /**
   * The status column of a real payload, mapped — the end-to-end version of
   * `BinderyStatusMapperTest`.
   *
   * The mapper is a pure function and is tested as one; this is the assertion that the client
   * actually hands it the field it thinks it does. A client that read `mediaType` into `status`
   * would leave every mapper test green.
   */
  @Test
  fun `the statuses of a real payload map onto this app's own`() = runTest {
    server.enqueue(fixture("bindery/books-all.json"))

    val page = client(server).books(status = null, limit = 100, offset = 0)

    assertThat(page.books).hasSize(7)
    assertThat(page.books.map { BinderyStatusMapper.map(it.status) }).containsExactly(
      RequestStatus.Imported,
      RequestStatus.Downloading(percentComplete = null),
      RequestStatus.Requested,
      RequestStatus.Requested,
      RequestStatus.Downloading(percentComplete = null),
      RequestStatus.Requested,
      RequestStatus.Requested,
    )
  }

  /**
   * A row with nothing to correlate on is dropped and the rest of the page survives.
   *
   * `id == 0` is treated as no id: SQLite's `AUTOINCREMENT` starts at 1, and `0` is exactly what a
   * search result carries — so it is the value a wrong parse produces rather than a hypothetical.
   */
  @Test
  fun `a row with no usable id or identifier is dropped and the page survives`() = runTest {
    server.enqueue(
      json(
        """
        {"items":[
          {"foreignBookId":"OL1W","title":"no id"},
          {"id":0,"foreignBookId":"OL2W","title":"zero id"},
          {"id":9,"title":"no foreign id"},
          {"id":11,"foreignBookId":"OL3W","title":"usable","status":"wanted","mediaType":"audiobook"}
        ],"total":4,"limit":100,"offset":0}
        """.trimIndent(),
      ),
    )

    val page = client(server).books(status = null, limit = 100, offset = 0)

    assertThat(page.books.map { it.id }).containsExactly(11)
    assertThat(page.books.single().foreignBookId).isEqualTo("OL3W")
    // `total` is still the server's, so the caller can see that rows went missing rather than
    // being told the library has one book in it.
    assertThat(page.total).isEqualTo(4)
  }

  /**
   * A row whose optional fields are absent or blank reads as empty strings, not as a value.
   *
   * The complement of the field test above, which observes every field carrying something. A
   * `title` that silently became a constant, or a `status` defaulted to `"wanted"`, is exactly the
   * shape that would leave a request row looking healthy while saying nothing true — and a blank
   * `foreignBookId` is dropped for the same reason an absent one is: there is nothing to correlate
   * on.
   */
  @Test
  fun `a row with fields omitted or blank reads as empty rather than as a value`() = runTest {
    server.enqueue(
      json(
        """
        {"items":[
          {"id":3,"foreignBookId":"OL3W"},
          {"id":4,"foreignBookId":"   ","title":"blank identifier","status":"wanted"}
        ],"total":2,"limit":100,"offset":0}
        """.trimIndent(),
      ),
    )

    val page = client(server).books(status = null, limit = 100, offset = 0)

    assertThat(page.books.map { it.id }).containsExactly(3)
    val only = page.books.single()
    assertThat(only.foreignBookId).isEqualTo("OL3W")
    assertThat(only.title).isEmpty()
    assertThat(only.status).isEmpty()
    assertThat(only.mediaType).isEmpty()
    // An empty status maps to the least-claiming member rather than to a failure.
    assertThat(BinderyStatusMapper.map(only.status)).isEqualTo(RequestStatus.Requested)
  }

  /** An empty library is an empty page, not a failure. */
  @Test
  fun `an empty page is empty rather than a failure`() = runTest {
    server.enqueue(json("""{"items":[],"total":0,"limit":100,"offset":0}"""))

    val page = client(server).books(status = "downloading", limit = 100, offset = 0)

    assertThat(page.books).isEmpty()
    assertThat(page.total).isZero()
  }

  /**
   * An envelope with no `items` key at all reads as an empty page rather than throwing.
   *
   * Measured shapes always carry the key, but a `null` there is what Go's `json.Marshal` produces
   * for a nil slice, and a caller cannot act on the difference.
   */
  @Test
  fun `an envelope with no items key reads as an empty page`() = runTest {
    server.enqueue(json("""{"total":0,"limit":100,"offset":0}"""))

    assertThat(client(server).books(status = null, limit = 100, offset = 0).books).isEmpty()
  }

  /**
   * This is the call that proves the key, so its `401` must arrive as one.
   *
   * A connection check calls [BinderySource.health] to find out that something is there and this
   * to find out that the key works; if a rejected key arrived here as anything but
   * [BinderyUnauthorizedException] the check would have no way to say so.
   */
  @Test
  fun `a rejected key on the one authenticated read is reported as unauthorized`() = runTest {
    server.enqueue(fixture("bindery/error-unauthorized.json", code = 401))

    val raised = runCatching {
      client(server).books(status = null, limit = 100, offset = 0)
    }.exceptionOrNull()

    assertThat(raised).isInstanceOf(BinderyUnauthorizedException::class.java)
  }
}
