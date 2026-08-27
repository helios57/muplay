package app.muplay.integrations.bindery

import app.muplay.integrations.bindery.BinderyTestServer.client
import app.muplay.integrations.bindery.BinderyTestServer.fixture
import app.muplay.integrations.bindery.BinderyTestServer.json
import app.muplay.integrations.bindery.BinderyTestServer.next
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Asking Bindery for a book — where asking *is* acquiring.
 *
 * **The subject is the body that went out.** `assertThat(server.requestCount).isEqualTo(1)` is
 * satisfied by a client that POSTs an empty object; every assertion here reads the recorded
 * request's body, parses it, and pins a specific field at **two different values**.
 *
 * The central one is [the media type is always sent, and is audiobook by default]. `mediaType`
 * defaults to `ebook` **server-side** — measured, a POST omitting it answers `201` with
 * `"mediaType":"ebook"` on the created book — so an omitted field yields a happy-looking request
 * row and an EPUB that Navidrome will never scan. The request would then sit at `Imported`
 * forever and never become `Arrived`, with nothing anywhere saying why.
 */
class BinderySubmitTest {

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

  /**
   * The recorded request's body as JSON.
   *
   * `mockwebserver3` 5.5.0 exposes the body as a **nullable Okio `ByteString`** on
   * `RecordedRequest`, not a `Buffer`, so this is `body?.utf8().orEmpty()`. `ByteString` does not
   * consume, so the same body can be read repeatedly; a GET's body is `null`, which is why every
   * assertion below establishes the body is non-empty before parsing it.
   */
  private fun bodyText(request: RecordedRequest): String = request.body?.utf8().orEmpty()

  private fun bodyOf(request: RecordedRequest): JsonObject {
    val text = bodyText(request)
    // A body that never arrived parses as nothing and would leave every field assertion below
    // failing for the wrong reason -- or, worse, an `orEmpty()` fallback silently passing a
    // `containsKey` check that was written as a negative.
    assertThat(text).describedAs("the POST body that went out").isNotEmpty()
    return Json.parseToJsonElement(text).jsonObject
  }

  private fun candidate(
    foreignBookId: String = "OL21745884W",
    authorName: String? = "Andy Weir",
    foreignAuthorId: String? = "OL7234434A",
  ) = BinderyBookCandidate(
    foreignBookId = foreignBookId,
    title = "Project Hail Mary",
    authorName = authorName,
    foreignAuthorId = foreignAuthorId,
    asin = null,
    coverUrl = null,
    raw = JsonObject(emptyMap()),
  )

  private fun enqueueCreated() = server.enqueue(fixture("bindery/add-book-created.json", code = 201))

  // ---- the body ------------------------------------------------------------------------------

  /**
   * **The trap.** `mediaType` is always on the wire, and it carries what the caller asked for.
   *
   * Two observations, because one is satisfied by a constant — and the constant a careless
   * implementation reaches for is `"audiobook"`, since that is what this app wants nine times out
   * of ten. The second observation is `BOTH`, which no default and no plausible hardcoding
   * produces.
   */
  @Test
  fun `the media type is always sent, and is audiobook by default`() = runTest {
    enqueueCreated()
    client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, searchOnAdd = true)

    val first = bodyOf(nextRequest())
    assertThat(first["mediaType"]!!.jsonPrimitive.content).isEqualTo("audiobook")

    // The second observation, so the field is not a constant.
    enqueueCreated()
    client(server).submitBook(candidate(), BinderyMediaType.BOTH, searchOnAdd = true)
    assertThat(bodyOf(nextRequest())["mediaType"]!!.jsonPrimitive.content).isEqualTo("both")

    // The third, and the one that proves the enum's own wire value is used rather than its name
    // lowercased: `EBOOK` -> `ebook` would survive that, `BOTH` -> `both` would too, but a client
    // that sent the enum name unchanged fails all three.
    enqueueCreated()
    client(server).submitBook(candidate(), BinderyMediaType.EBOOK, searchOnAdd = true)
    assertThat(bodyOf(nextRequest())["mediaType"]!!.jsonPrimitive.content).isEqualTo("ebook")
  }

  /**
   * The same field, from the other direction: it is **never absent**.
   *
   * The assertion above pins the value; this one pins the presence. They come apart under exactly
   * the mutation that matters — a `mediaType` given a Kotlin default and therefore omitted by
   * kotlinx.serialization — because an absent key makes `body["mediaType"]` null and a test that
   * only read its content would fail with a `NullPointerException` rather than with a message
   * naming the defect.
   */
  @Test
  fun `the media type key is never missing from the body`() = runTest {
    listOf(BinderyMediaType.EBOOK, BinderyMediaType.AUDIOBOOK, BinderyMediaType.BOTH)
      .forEach { mediaType ->
        enqueueCreated()
        client(server).submitBook(candidate(), mediaType, searchOnAdd = false)
        val body = bodyOf(nextRequest())
        assertThat(body).describedAs("body for %s", mediaType).containsKey("mediaType")
      }
  }

  /**
   * **The assertion the plan's brief singles out.** Not "a request was submitted": the body, read
   * back and asserted at two different identifiers.
   *
   * `foreignBookId` is what the user asked for, what the request row stores as its `externalId`,
   * and what every later correlation runs on. A constant here produces a `201`, a happy request
   * row, and the wrong book.
   */
  @Test
  fun `the body carries the book identifier that was asked for, not a constant`() = runTest {
    enqueueCreated()
    client(server).submitBook(candidate("OL21745884W"), BinderyMediaType.AUDIOBOOK, true)
    assertThat(bodyOf(nextRequest())["foreignBookId"]!!.jsonPrimitive.content)
      .isEqualTo("OL21745884W")

    // A second, namespaced id -- a different provider as well as a different book, so a client
    // that parsed or normalised the identifier fails here too.
    enqueueCreated()
    client(server).submitBook(candidate("dnb:1401655076"), BinderyMediaType.AUDIOBOOK, true)
    assertThat(bodyOf(nextRequest())["foreignBookId"]!!.jsonPrimitive.content)
      .isEqualTo("dnb:1401655076")
  }

  /** `searchOnAdd` reaches the wire as a boolean, at both values. */
  @Test
  fun `searchOnAdd carries whichever value it was given`() = runTest {
    enqueueCreated()
    client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, searchOnAdd = true)
    assertThat(bodyOf(nextRequest())["searchOnAdd"]!!.jsonPrimitive.boolean).isTrue()

    enqueueCreated()
    client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, searchOnAdd = false)
    val body = bodyOf(nextRequest())
    // Present *and* false. A client that omitted the field when false would pass a
    // `!= true` assertion and leave Bindery to apply its own default.
    assertThat(body).containsKey("searchOnAdd")
    assertThat(body["searchOnAdd"]!!.jsonPrimitive.boolean).isFalse()
  }

  /**
   * The author fields are passed through from the candidate, at two values each — and are
   * **omitted entirely** when the candidate has none.
   *
   * Measured against `v1.32.1`: a POST carrying only `foreignAuthorId` answers 201, a POST
   * carrying only `authorName` answers 201, and a POST carrying neither answers **422** with
   * `{"error":"Author metadata unavailable for this result. …"}`. So sending both when both are
   * known is right, and sending `"authorName": null` instead of omitting it is a shape this client
   * has never observed the server accept.
   */
  @Test
  fun `the author fields are passed through and are omitted when absent`() = runTest {
    enqueueCreated()
    client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)
    val first = bodyOf(nextRequest())
    assertThat(first["authorName"]!!.jsonPrimitive.content).isEqualTo("Andy Weir")
    assertThat(first["foreignAuthorId"]!!.jsonPrimitive.content).isEqualTo("OL7234434A")

    enqueueCreated()
    client(server).submitBook(
      candidate(authorName = "Ursula K. Le Guin", foreignAuthorId = "OL27349A"),
      BinderyMediaType.AUDIOBOOK,
      true,
    )
    val second = bodyOf(nextRequest())
    assertThat(second["authorName"]!!.jsonPrimitive.content).isEqualTo("Ursula K. Le Guin")
    assertThat(second["foreignAuthorId"]!!.jsonPrimitive.content).isEqualTo("OL27349A")

    // The majority case: 22 of 40 real search results carry no `author` object at all.
    enqueueCreated()
    client(server).submitBook(
      candidate(authorName = null, foreignAuthorId = null),
      BinderyMediaType.AUDIOBOOK,
      true,
    )
    val third = bodyOf(nextRequest())
    assertThat(third).doesNotContainKey("authorName")
    assertThat(third).doesNotContainKey("foreignAuthorId")
    // ...and the rest of the body is intact, so "omitted" is not "the body fell apart".
    assertThat(third["foreignBookId"]!!.jsonPrimitive.content).isEqualTo("OL21745884W")
    assertThat(third["mediaType"]!!.jsonPrimitive.content).isEqualTo("audiobook")
  }

  /**
   * The body carries these five keys and nothing else.
   *
   * An exact key set rather than five `containsKey`s: it is the only form that catches a field
   * added by accident — a `title`, a `monitored`, a debugging flag — reaching a server whose
   * handler this project does not control. Bindery ignores unknown keys today; a future release
   * that validates them would reject every add this app makes.
   */
  @Test
  fun `the body carries exactly the five fields bindery's handler reads`() = runTest {
    enqueueCreated()

    client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, searchOnAdd = true)

    assertThat(bodyOf(nextRequest()).keys).containsExactlyInAnyOrder(
      "foreignBookId", "foreignAuthorId", "authorName", "mediaType", "searchOnAdd",
    )
  }

  // ---- the request ---------------------------------------------------------------------------

  /**
   * The endpoint. `POST /api/v1/author/book`, undocumented, and the path really does say `author`
   * for a call that creates a book — Bindery's handler adds the book and its author together.
   */
  @Test
  fun `the add posts to the author slash book endpoint with a json body`() = runTest {
    enqueueCreated()

    client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)

    val request = nextRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.url.encodedPath).isEqualTo("/api/v1/author/book")
    // No query string at all: Bindery rejects a query-string key on a mutation, so anything here
    // would be both useless and a leak.
    assertThat(request.url.encodedQuery).isNull()
    assertThat(request.headers["Content-Type"]).contains("application/json")
  }

  // ---- the response --------------------------------------------------------------------------

  /**
   * The `201` carries the created book, and this client reads every field of it.
   *
   * Measured against `v1.32.1`, and it is the fact that decided `submitBook`'s return type: the
   * response body **does** carry the created book's real database id, so a caller has something
   * durable to correlate on and Task 9 does not have to re-list the library to find out what it
   * just created.
   */
  @Test
  fun `a created book comes back with the id bindery assigned it`() = runTest {
    enqueueCreated()

    val book = client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)

    assertThat(book.id).isEqualTo(1)
    assertThat(book.foreignBookId).isEqualTo("OL21745884W")
    assertThat(book.title).isEqualTo("Project Hail Mary")
    assertThat(book.status).isEqualTo("wanted")
    // The trap field, read back: this is the only place a caller can ever see that what was
    // acquired is not what was asked for.
    assertThat(book.mediaType).isEqualTo("audiobook")
  }

  /** The second observation of the same five fields, so none of them is a constant. */
  @Test
  fun `a second created book comes back with its own values`() = runTest {
    server.enqueue(
      json(
        """{"id":42,"foreignBookId":"dnb:1401655076","title":"Der Astronaut","status":"downloading","mediaType":"both"}""",
        code = 201,
      ),
    )

    val book = client(server).submitBook(candidate(), BinderyMediaType.BOTH, true)

    assertThat(book.id).isEqualTo(42)
    assertThat(book.foreignBookId).isEqualTo("dnb:1401655076")
    assertThat(book.title).isEqualTo("Der Astronaut")
    assertThat(book.status).isEqualTo("downloading")
    assertThat(book.mediaType).isEqualTo("both")
  }

  /**
   * **A `201` with no usable id is a loud failure, not a book with `id = 0`.**
   *
   * `0` is not a hypothetical value here: it is what *every* search result carries, so it is
   * precisely the number a wrong parse produces. A `BinderyBook(id = 0)` would put a row in the
   * request store that every later poll looks up under an id no book has.
   *
   * Three shapes, all of which a server or a proxy can produce, and the exception carries the
   * status that really came back rather than a hardcoded `201`.
   */
  @Test
  fun `a created response with no usable id fails loudly rather than returning zero`() = runTest {
    val bodies = listOf(
      "{}",
      """{"id":0,"foreignBookId":"OL1W"}""",
      """{"id":7}""",
    )
    bodies.forEach { server.enqueue(json(it, code = 201)) }

    val raised = bodies.map {
      runCatching {
        client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)
      }.exceptionOrNull()
    }

    assertThat(raised).hasSize(3)
    assertThat(raised).allSatisfy { assertThat(it).isInstanceOf(BinderyHttpException::class.java) }
    // The status that really came back, not the 201 this endpoint is measured to return.
    assertThat(raised.map { (it as BinderyHttpException).status }).containsExactly(201, 201, 201)
  }

  /**
   * A duplicate add is a **`201` carrying the original id**, not a refusal.
   *
   * Measured by posting the same body twice against a real `v1.32.1`: both answered `201` with
   * `"id":1`. Bindery upserts. That is the whole reason `submitBook` returns a book rather than a
   * sealed outcome the way `LidarrSource.submitAlbum` does — there is no already-added state to
   * tell apart from a real refusal, so modelling one would be a state the server does not have.
   */
  @Test
  fun `asking twice is a success carrying the same id, not a refusal`() = runTest {
    enqueueCreated()
    enqueueCreated()

    val source = client(server)
    val first = source.submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)
    val second = source.submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)

    assertThat(second.id).isEqualTo(first.id)
    assertThat(second.foreignBookId).isEqualTo(first.foreignBookId)
  }

  /**
   * Every refusal Bindery can produce for an add, each as its own status and its own sentence.
   *
   * All four came off a real instance in one afternoon, and each is a different thing to tell a
   * user: the key is wrong; the field is missing; the media type is not one of three; the author
   * cannot be resolved and here is what to do about it; the metadata provider does not have this
   * book. **The status is carried beside the text** rather than deduced from it, because the text
   * is a human sentence a release may reword and the status is the machine-readable half.
   */
  @Test
  fun `each refusal keeps its own status and bindery's own sentence`() = runTest {
    server.enqueue(fixture("bindery/error-unauthorized.json", code = 401))
    server.enqueue(fixture("bindery/error-bad-media-type.json", code = 400))
    server.enqueue(fixture("bindery/error-author-unavailable.json", code = 422))
    server.enqueue(fixture("bindery/error-book-not-found.json", code = 502))

    val source = client(server)
    val raised = (1..4).map {
      runCatching {
        source.submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)
      }.exceptionOrNull()
    }

    // Positive first: four calls really did raise. A `submitBook` that swallowed failures and
    // returned a default book would leave every assertion below unreached.
    assertThat(raised).hasSize(4)
    assertThat(raised).allSatisfy { assertThat(it).isInstanceOf(BinderyException::class.java) }
    assertThat(raised.map { it!!::class.java }).containsExactly(
      BinderyUnauthorizedException::class.java,
      BinderyMessageException::class.java,
      BinderyMessageException::class.java,
      BinderyMessageException::class.java,
    )
    assertThat(raised.drop(1).map { (it as BinderyMessageException).status })
      .containsExactly(400, 422, 502)
    assertThat(raised.drop(1).map { (it as BinderyMessageException).binderyMessage })
      .containsExactly(
        "mediaType must be 'ebook', 'audiobook', or 'both'",
        "Author metadata unavailable for this result. Add the author manually first " +
          "(Authors → Add Author by name), then try again.",
        "look up book metadata: get book OL-does-not-exist-9999W: not found",
      )
  }

  /**
   * A failure body this client cannot read degrades to a bare status rather than quoting rubbish
   * at the user.
   *
   * A reverse proxy's HTML error page carrying a 502 is not Bindery speaking, and showing its
   * markup in a dialogue would be worse than saying nothing.
   */
  @Test
  fun `a failure body that is not bindery's shape degrades to a bare status`() = runTest {
    server.enqueue(
      mockwebserver3.MockResponse.Builder()
        .code(502)
        .setHeader("Content-Type", "text/html")
        .body("<html><body><h1>502 Bad Gateway</h1></body></html>")
        .build(),
    )

    val raised = runCatching {
      client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)
    }.exceptionOrNull()

    assertThat(raised).isInstanceOf(BinderyHttpException::class.java)
    assertThat((raised as BinderyHttpException).status).isEqualTo(502)
    assertThat(raised.message).doesNotContain("html")
  }

  /**
   * A **successful** response with no body at all fails naming the status.
   *
   * A `204` is the one success Retrofit hands back with a null body, and a reverse proxy that
   * answers one for an add is a real deployment shape. Without this, `response.body()!!` would be a
   * `NullPointerException` from inside a coroutine — a crash with no name on it, where this is a
   * failure that says which status arrived.
   */
  @Test
  fun `a successful add with no body at all fails naming the status`() = runTest {
    server.enqueue(mockwebserver3.MockResponse.Builder().code(204).build())

    val raised = runCatching {
      client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)
    }.exceptionOrNull()

    assertThat(raised).isInstanceOf(BinderyHttpException::class.java)
    assertThat((raised as BinderyHttpException).status).isEqualTo(204)
  }

  /**
   * A JSON failure body with no `error` key degrades to a bare status too.
   *
   * The third of the three ways a body can fail to carry a message — not JSON, JSON with a blank
   * `error`, and JSON with no `error` at all — and the one an `ErrorBody` whose field was
   * non-nullable would have turned into a parse failure instead.
   */
  @Test
  fun `a json failure body with no error key degrades to a bare status`() = runTest {
    server.enqueue(json("""{"detail":"something else entirely"}""", code = 503))

    val raised = runCatching {
      client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)
    }.exceptionOrNull()

    assertThat(raised).isInstanceOf(BinderyHttpException::class.java)
    assertThat((raised as BinderyHttpException).status).isEqualTo(503)
    assertThat(raised.message).doesNotContain("something else entirely")
  }

  /**
   * A `{"error": ""}` body is treated as no message at all.
   *
   * An empty string handed to a surface is a dialogue with no text in it — the "two ways to say
   * nothing" shape this module collapses everywhere else.
   */
  @Test
  fun `a blank error message degrades to a bare status`() = runTest {
    server.enqueue(json("""{"error":""}""", code = 500))

    val raised = runCatching {
      client(server).submitBook(candidate(), BinderyMediaType.AUDIOBOOK, true)
    }.exceptionOrNull()

    assertThat(raised).isInstanceOf(BinderyHttpException::class.java)
    assertThat((raised as BinderyHttpException).status).isEqualTo(500)
  }
}
