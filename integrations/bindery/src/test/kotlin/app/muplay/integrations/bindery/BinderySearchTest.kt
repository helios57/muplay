package app.muplay.integrations.bindery

import app.muplay.integrations.bindery.BinderyTestServer.client
import app.muplay.integrations.bindery.BinderyTestServer.fixture
import app.muplay.integrations.bindery.BinderyTestServer.json
import app.muplay.integrations.bindery.BinderyTestServer.next
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Finding a book: the request Bindery's own documentation gets wrong, and the response shape the
 * plan got wrong.
 *
 * Both halves of this file exist because something written down was not true. The docs say the
 * search parameter is `q`; the handler reads `term`, and `q` is a 400. The plan expected
 * `authorName` and `foreignAuthorId` top-level on an element; they are nested under an `author`
 * object that is absent on **22 of 40** real results. Neither would have been caught by any
 * assertion about whether a request was sent or a response parsed.
 */
class BinderySearchTest {

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

  // ---- the request -----------------------------------------------------------------------

  /**
   * **The reason this client works and one written from Bindery's documentation does not.**
   *
   * Measured against `v1.32.1`: `?term=…` answers 200 with a bare array, and `?q=…` answers
   * **400** `{"error":"term parameter required"}` — byte-identical to the answer for a request
   * with no parameter at all, so the wrong parameter is indistinguishable from a missing one and
   * has to be pinned at the request rather than diagnosed from the response.
   */
  @Test
  fun `the search parameter is term, and never q`() = runTest {
    server.enqueue(json("[]"))
    client(server).searchBooks("project hail mary")

    val url = nextRequest().url
    assertThat(url.encodedPath).isEqualTo("/api/v1/search/book")
    assertThat(url.queryParameter("term")).isEqualTo("project hail mary")
    assertThat(url.queryParameter("q")).isNull()
    // The exact query string: the space really was encoded rather than sent raw, and nothing else
    // was sent beside the term.
    assertThat(url.encodedQuery).isEqualTo("term=project%20hail%20mary")

    // The second observation, so the term is not a constant.
    server.enqueue(json("[]"))
    client(server).searchBooks("dune")
    val second = nextRequest().url
    assertThat(second.queryParameter("term")).isEqualTo("dune")
    assertThat(second.encodedQuery).isEqualTo("term=dune")
  }

  // ---- the response ----------------------------------------------------------------------

  /**
   * `GET /api/v1/search/book` returns an **array**; `GET /api/v1/book` returns
   * `{items,total,limit,offset}`. Two shapes on one service, and reading either with the other's
   * reader yields an empty list rather than an error — a silent wrong answer with no failure
   * anywhere.
   *
   * Both directions are asserted: the array parses, and the envelope handed to the array reader
   * does **not** quietly parse as an empty list. The second half is the one that discriminates.
   */
  @Test
  fun `the search response is a bare array, not an envelope`() = runTest {
    server.enqueue(fixture("bindery/search-book.json"))
    val fromArray = client(server).searchBooks("project hail mary")
    // Positive first: the real array really did parse into candidates, so the negative below is
    // about the shape rather than about a parser that never works.
    assertThat(fromArray).hasSize(40)

    // The envelope shape, fed to the search. If this client read the search with the book list's
    // reader, this would succeed and return four candidates.
    server.enqueue(fixture("bindery/books-wanted.json"))
    val raised = runCatching { client(server).searchBooks("project hail mary") }.exceptionOrNull()
    assertThat(raised).isNotNull()
    assertThat(raised).isNotInstanceOf(BinderyException::class.java)
  }

  /**
   * The whole candidate, off the real payload, at two elements — one carrying every field and one
   * carrying none of the optional ones.
   *
   * The two elements are chosen for what they differ in rather than for being convenient: element
   * 0 is an Open Library result with a nested `author` and a real cover; element 1 is an Open
   * Library result for a near-identical title with **no `author` object at all**, which is the
   * majority case (22 of these 40) and the one the plan did not expect to exist.
   */
  @Test
  fun `every candidate field is read from its own element`() = runTest {
    server.enqueue(fixture("bindery/search-book.json"))

    val candidates = client(server).searchBooks("project hail mary")

    assertThat(candidates).hasSize(40)
    val first = candidates[0]
    assertThat(first.foreignBookId).isEqualTo("OL21745884W")
    assertThat(first.title).isEqualTo("Project Hail Mary")
    assertThat(first.authorName).isEqualTo("Andy Weir")
    assertThat(first.foreignAuthorId).isEqualTo("OL7234434A")
    assertThat(first.coverUrl)
      .isEqualTo("https://covers.openlibrary.org/b/id/11200092-L.jpg")
    // `asin` is top-level on a Bindery book and arrives as `""` on every element of a real
    // search. Blank collapses to null so a surface has one kind of nothing rather than two.
    assertThat(first.asin).isNull()

    val second = candidates[1]
    assertThat(second.foreignBookId).isEqualTo("OL45858094W")
    assertThat(second.title).isEqualTo("Project Hail Mary: A Novel")
    // The field pair the plan expected top-level. Absent here, and absent on the majority of real
    // results -- a client reading them off the element rather than off the nested `author` object
    // would have found `null` on all forty and never noticed.
    assertThat(second.authorName).isNull()
    assertThat(second.foreignAuthorId).isNull()
    assertThat(second.coverUrl).isNull()

    // The counts, as one exact statement about the real payload: eighteen of forty carry an
    // author, thirteen carry a cover, none carries an ASIN. A mapper that read the author fields
    // from the wrong place, or that defaulted them to `""`, moves at least one of these.
    assertThat(candidates.count { it.authorName != null }).isEqualTo(18)
    assertThat(candidates.count { it.foreignAuthorId != null }).isEqualTo(18)
    assertThat(candidates.count { it.coverUrl != null }).isEqualTo(13)
    assertThat(candidates.count { it.asin != null }).isEqualTo(0)
  }

  /**
   * **Order is a property.** A search result list is ranked, and the top hit is the one the user
   * will pick; a mapper that sorted, reversed or grouped would leave every field assertion above
   * green.
   */
  @Test
  fun `candidates keep the order the server sent them in`() = runTest {
    server.enqueue(fixture("bindery/search-book.json"))

    val candidates = client(server).searchBooks("project hail mary")

    assertThat(candidates.take(3).map { it.foreignBookId })
      .containsExactly("OL21745884W", "OL45858094W", "dnb:1401655076")
    assertThat(candidates.take(3).map { it.title }).containsExactly(
      "Project Hail Mary",
      "Project Hail Mary: A Novel",
      """A Moment: from "Project Hail Mary"""",
    )
  }

  /**
   * `foreignBookId` is namespaced and this client keeps it verbatim.
   *
   * `gb:` is Google Books, `hc:` Hardcover, `dnb:` the Deutsche Nationalbibliothek, and an
   * unprefixed value means Open Library. Measured, one real search returns twenty of each of two
   * providers in a single array — so a client that stripped a prefix, or that treated the id as an
   * integer, would collide two different books from two different providers under one request row.
   */
  @Test
  fun `a namespaced foreign book id survives verbatim`() = runTest {
    server.enqueue(fixture("bindery/search-book.json"))

    val ids = client(server).searchBooks("project hail mary").map { it.foreignBookId }

    assertThat(ids).hasSize(40)
    assertThat(ids.count { it.startsWith("dnb:") }).isEqualTo(20)
    assertThat(ids.count { !it.contains(":") }).isEqualTo(20)
    assertThat(ids).contains("dnb:1401655076")
  }

  /**
   * An element with no `foreignBookId` is dropped rather than offered.
   *
   * `POST /api/v1/author/book` answers `400 {"error":"foreignBookId required"}` without one, so
   * such a candidate could not be asked for — offering it would put a row in the list that fails
   * the moment it is tapped. Dropping one keeps every other element usable, where failing the
   * whole parse would lose all of them.
   */
  @Test
  fun `an element with no foreign book id is dropped and the rest survive`() = runTest {
    server.enqueue(
      json(
        """
        [
          {"title":"no id at all","author":{"authorName":"Nobody"}},
          {"foreignBookId":"","title":"blank id"},
          {"foreignBookId":"OL1W","title":"usable"}
        ]
        """.trimIndent(),
      ),
    )

    val candidates = client(server).searchBooks("t")

    // Exactly the survivor, named. `hasSize(1)` alone would be satisfied by a parser that kept the
    // wrong one.
    assertThat(candidates.map { it.foreignBookId }).containsExactly("OL1W")
    assertThat(candidates.single().title).isEqualTo("usable")
  }

  /**
   * An element that is not an object at all — a bare string or a number in the array — is dropped
   * rather than crashing the whole search.
   */
  @Test
  fun `a non-object element is dropped rather than failing the whole search`() = runTest {
    server.enqueue(json("""["not an object", 42, {"foreignBookId":"OL1W","title":"usable"}]"""))

    assertThat(client(server).searchBooks("t").map { it.foreignBookId }).containsExactly("OL1W")
  }

  /**
   * A field whose type is not a string is read as absent rather than as its rendering.
   *
   * `JsonPrimitive.content` would return `"7"` for a numeric title and `"true"` for a boolean one;
   * `isString` is what keeps a surface from displaying a number as a book's name.
   */
  @Test
  fun `a non-string field is read as absent, not as its rendering`() = runTest {
    server.enqueue(
      json("""[{"foreignBookId":"OL1W","title":7,"asin":true,"imageUrl":9,"author":{"authorName":1,"foreignAuthorId":2}}]"""),
    )

    val candidate = client(server).searchBooks("t").single()

    assertThat(candidate.foreignBookId).isEqualTo("OL1W")
    assertThat(candidate.title).isEmpty()
    assertThat(candidate.asin).isNull()
    assertThat(candidate.coverUrl).isNull()
    assertThat(candidate.authorName).isNull()
    assertThat(candidate.foreignAuthorId).isNull()
  }

  /**
   * A blank string is read as nothing; a present one as itself.
   *
   * Both halves in one payload, because either alone is satisfied by a constant: an element whose
   * optional fields are all `""` — which is what a real DNB result looks like — beside one where
   * every one of them carries a value. `asin` in particular has **no** non-blank observation
   * anywhere in the real fixture (all forty results carry `""`), so without this row a client that
   * always returned `null` for it would be indistinguishable from one that read it.
   */
  @Test
  fun `a blank optional field is read as nothing and a present one as itself`() = runTest {
    server.enqueue(
      json(
        """
        [
          {"foreignBookId":"OL1W","title":"blanks","asin":"","imageUrl":"",
           "author":{"authorName":"","foreignAuthorId":""}},
          {"foreignBookId":"OL2W","title":"values","asin":"B08GB58KD5",
           "imageUrl":"https://covers.openlibrary.org/b/id/1.jpg",
           "author":{"authorName":"Andy Weir","foreignAuthorId":"OL7234434A"}}
        ]
        """.trimIndent(),
      ),
    )

    val candidates = client(server).searchBooks("t")

    assertThat(candidates).hasSize(2)
    val blank = candidates[0]
    assertThat(blank.asin).isNull()
    assertThat(blank.coverUrl).isNull()
    // A blank *nested* author is not the same shape as an absent `author` object, and both have to
    // collapse to the same nothing -- otherwise a surface has two kinds of "no author".
    assertThat(blank.authorName).isNull()
    assertThat(blank.foreignAuthorId).isNull()

    val valued = candidates[1]
    assertThat(valued.asin).isEqualTo("B08GB58KD5")
    assertThat(valued.coverUrl).isEqualTo("https://covers.openlibrary.org/b/id/1.jpg")
    assertThat(valued.authorName).isEqualTo("Andy Weir")
    assertThat(valued.foreignAuthorId).isEqualTo("OL7234434A")
  }

  /**
   * `raw` is the element exactly as it arrived, not a re-serialisation of the modelled fields.
   *
   * Unlike Lidarr's, this is never posted back — Bindery's add takes a small typed body — so it
   * exists for a surface that has to let a user tell forty near-identical results apart. That is
   * only worth anything if the fields this client does not model are still in there.
   */
  @Test
  fun `the raw element survives with every field this client does not model`() = runTest {
    server.enqueue(fixture("bindery/search-book.json"))

    val raw = client(server).searchBooks("project hail mary").first().raw

    // Thirty-one top-level fields on a real element; this client models five of them.
    assertThat(raw.keys).hasSize(31)
    assertThat(raw.keys).contains(
      "description", "genres", "releaseDate", "metadataProvider", "narrator", "durationSeconds",
    )
    assertThat((raw["metadataProvider"] as JsonPrimitive).content).isEqualTo("openlibrary")
    assertThat((raw["releaseDate"] as JsonPrimitive).content).isEqualTo("2021-01-01T00:00:00Z")
  }

  /**
   * An empty result set is an empty list, not a failure.
   *
   * Bindery answers `200 []` for a term nothing matched, which is a normal outcome rather than an
   * error — and the client must not turn it into one.
   */
  @Test
  fun `a search that matched nothing is an empty list rather than a failure`() = runTest {
    server.enqueue(json("[]"))

    assertThat(client(server).searchBooks("qqqqzzzz")).isEmpty()
  }

  /**
   * A `400` from the search carries Bindery's own sentence out to the caller.
   *
   * This is the response a client written from the documentation gets on every search, so the
   * message being legible is what would tell whoever hit it what had happened.
   */
  @Test
  fun `a rejected search reports the status and bindery's own message`() = runTest {
    server.enqueue(fixture("bindery/error-missing-term.json", code = 400))

    val raised = runCatching { client(server).searchBooks("x") }.exceptionOrNull()

    assertThat(raised).isInstanceOf(BinderyMessageException::class.java)
    assertThat((raised as BinderyMessageException).status).isEqualTo(400)
    assertThat(raised.binderyMessage).isEqualTo("term parameter required")
    // ...and the server's sentence is on a named field, never on `message`. A crash reporter
    // serialises `toString()` and nothing else, and this client cannot know what a server will
    // echo -- see `BinderyMessageException`, which was written this way because a test caught the
    // obvious version putting the API key there.
    assertThat(raised.message).isEqualTo("Bindery refused this request (HTTP 400)")
    assertThat(raised.toString()).doesNotContain("term parameter required")
  }
}
