package app.muplay.integrations.bindery

import app.muplay.integrations.bindery.BinderyTestServer.FIRST_KEY
import app.muplay.integrations.bindery.BinderyTestServer.SECOND_KEY
import app.muplay.integrations.bindery.BinderyTestServer.client
import app.muplay.integrations.bindery.BinderyTestServer.fixture
import app.muplay.integrations.bindery.BinderyTestServer.json
import app.muplay.integrations.bindery.BinderyTestServer.next
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * How this client authenticates, over a real HTTP server.
 *
 * **The subject is the request, not the response.** Plan 1 proved by mutation that an
 * `authParams()` returning an empty map left every response assertion in the codebase green, and
 * this is the same class of value: a header that is absent produces a 401 that a test written
 * around a canned 200 never sees. Every assertion below reads a `RecordedRequest` — the bytes that
 * went out — and every value that could be satisfied by a constant is observed twice, at two
 * different values.
 *
 * **The stakes are higher here than next door.** Bindery's key is instance-wide and always treated
 * as admin: its `middleware.go` scopes it to nothing, and its `users` table has no `api_key` column
 * — verified against a real database after all 75 of its migrations had applied, so the README's
 * "per-account API key" claim is false on the shipped schema.
 */
class BinderyAuthTest {

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

  @Test
  fun `every request carries the key in the X-Api-Key header, at two values`() = runTest {
    server.enqueue(fixture("bindery/health.json"))
    client(server, FIRST_KEY).health()
    assertThat(nextRequest().headers["X-Api-Key"]).isEqualTo(FIRST_KEY)

    // The second observation. A hardcoded header *value* passes the assertion above, and a
    // hardcoded header *name* passes both -- which is why the name is read as a map key rather
    // than searched for anywhere in the request.
    server.enqueue(fixture("bindery/health.json"))
    client(server, SECOND_KEY).health()
    assertThat(nextRequest().headers["X-Api-Key"]).isEqualTo(SECOND_KEY)
  }

  /**
   * Bindery answers JSON regardless of `Accept` — measured, a request with no `Accept` header at
   * all is answered `200 application/json` — so this header is not rescuing a request that would
   * otherwise fail. It pins the negotiation to the one media type this client's converter can
   * read, in one place, so that a new endpoint cannot forget it and an intermediary cannot move it.
   */
  @Test
  fun `every request declares that it accepts json`() = runTest {
    server.enqueue(fixture("bindery/health.json"))

    client(server).health()

    assertThat(nextRequest().headers["Accept"]).contains("application/json")
  }

  /**
   * The constraint this whole module is named for: **the key never appears in a URL** — asserted
   * across **every endpoint this client has**, not just the first.
   *
   * Bindery *does* accept `?apikey=` on a GET, so this is a live wrong path rather than a
   * hypothetical one — and on a **mutation it refuses it outright**, measured:
   * `POST /api/v1/author/book?apikey=…` answers `401 {"error":"unauthorized"}`. So a query-string
   * client could search and list and would then fail at the one call that matters, which is the
   * rarest and worst kind of wrong: it works until it is used.
   *
   * On this side of the wire a query-string key would appear in every recorded request, in every
   * fixture captured with `curl -i`, in the message of any `IOException` OkHttp raises (which names
   * the full URL), and in the access log of every reverse proxy between here and Bindery.
   *
   * Every non-auth header is searched too, not only the URL: a key copied onto `Authorization`, or
   * onto a bespoke `X-Debug-Key` by a future edit, is the same leak by a different route.
   */
  @Test
  fun `no request this client makes carries the key on its url`() = runTest {
    server.enqueue(fixture("bindery/health.json"))
    server.enqueue(fixture("bindery/search-book.json"))
    server.enqueue(fixture("bindery/add-book-created.json", code = 201))
    server.enqueue(fixture("bindery/books-wanted.json"))

    val source = client(server, FIRST_KEY)
    source.health()
    source.searchBooks("a term with spaces")
    source.submitBook(candidate("OL21745884W"), BinderyMediaType.AUDIOBOOK, searchOnAdd = true)
    source.books(status = "wanted", limit = 50, offset = 0)

    val requests = List(4) { nextRequest() }

    // Positive first, and it is not decoration: a client that sent no requests, or sent them all
    // to one constant URL, satisfies every negative below while asserting nothing. `next()`
    // failing loudly on an empty queue is the other half of the guard.
    assertThat(requests).hasSize(4)
    assertThat(requests.map { it.url.encodedPath }).containsExactly(
      "/api/v1/health",
      "/api/v1/search/book",
      "/api/v1/author/book",
      "/api/v1/book",
    )
    assertThat(requests.map { it.method }).containsExactly("GET", "GET", "POST", "GET")
    // ...and the key really was sent on all four, in the one place it belongs. Without this, a
    // client that simply never authenticated passes this whole test with flying colours -- which
    // is the Plan 1 `authParams() = emptyMap()` defect, restated for a header.
    assertThat(requests).allSatisfy { assertThat(it.headers["X-Api-Key"]).isEqualTo(FIRST_KEY) }
    assertThat(requests).allSatisfy {
      assertThat(it.headers["Accept"]).contains("application/json")
    }

    // The negatives the test is named for, over the whole request.
    assertThat(requests).allSatisfy { request ->
      assertThat(request.url.toString()).doesNotContain(FIRST_KEY)
      assertThat(request.url.toString().lowercase()).doesNotContain("apikey")
      assertThat(request.url.encodedQuery.orEmpty()).doesNotContain(FIRST_KEY)
      assertThat(request.url.encodedPath).doesNotContain(FIRST_KEY)
      assertThat(request.url.encodedFragment).isNull()
      assertThat(request.url.username).isEmpty()
      assertThat(request.url.password).isEmpty()
      assertThat(request.headers.names().filter { it != "X-Api-Key" })
        .allSatisfy { name -> assertThat(request.headers[name]).doesNotContain(FIRST_KEY) }
    }

    // The exact query strings, so nothing is smuggled in beside the parameters that belong there.
    // `isNull()` on the two that have none is the stronger claim: `doesNotContain` would be
    // satisfied by a query string full of something else.
    assertThat(requests.map { it.url.encodedQuery }).containsExactly(
      null,
      "term=a%20term%20with%20spaces",
      null,
      "status=wanted&limit=50&offset=0",
    )
    // The POST body is the one place a key could hide that no URL assertion reaches.
    assertThat(requests[2].body?.utf8().orEmpty()).doesNotContain(FIRST_KEY)
    // ...and it is not empty, so the assertion above had something to search.
    assertThat(requests[2].body?.utf8().orEmpty()).contains("OL21745884W")
  }

  /**
   * The other half of the same guarantee, and the one a URL assertion cannot make: **no failure
   * this client raises names the key.**
   *
   * An exception message is the single most likely way a secret escapes a process — it is
   * interpolated into a bug report, uploaded by a crash reporter, and printed by whatever
   * `catch` block a later task writes. Each of the three [BinderyException] members is raised here
   * from a real response and its whole `toString()` searched.
   *
   * **The response bodies are the key itself**, which makes this the strongest form of the
   * assertion: even a client that merely echoed what the server sent would fail it.
   */
  @Test
  fun `no failure this client raises names the api key`() = runTest {
    val source = client(server, FIRST_KEY)

    server.enqueue(MockResponse.Builder().code(401).body(FIRST_KEY).build())
    server.enqueue(json("""{"error":"$FIRST_KEY is not a valid key"}""", code = 422))
    server.enqueue(MockResponse.Builder().code(500).body(FIRST_KEY).build())

    // `runCatching`, not `assertThatThrownBy`: AssertJ's `ThrowingCallable` is a Java SAM and a
    // lambda converted to one is not a coroutine body, so a suspend call inside it does not
    // compile. `:core:network`'s own client tests use this same form.
    val raised = (1..3).map { runCatching { source.health() }.exceptionOrNull() }

    // Positive first: three calls really did raise three `BinderyException`s. Without this, a
    // `health()` that quietly returned a default would leave every `doesNotContain` below true.
    assertThat(raised).hasSize(3)
    assertThat(raised).allSatisfy { assertThat(it).isInstanceOf(BinderyException::class.java) }
    assertThat(raised.map { it!!::class.java }).containsExactly(
      BinderyUnauthorizedException::class.java,
      BinderyMessageException::class.java,
      BinderyHttpException::class.java,
    )
    assertThat(raised).allSatisfy { assertThat(it.toString()).doesNotContain(FIRST_KEY) }
    // The one that found the defect: `BinderyMessageException` carries Bindery's own sentence, so
    // writing it as `Exception(binderyMessage)` -- the obvious way, and the way the neighbouring
    // Lidarr module writes its validation exception -- puts whatever the server said into
    // `toString()`. Here the server said the key. The field is still populated; it is simply not
    // the exception's message.
    assertThat((raised[1] as BinderyMessageException).binderyMessage).contains(FIRST_KEY)
    assertThat(raised[1]!!.message).isEqualTo("Bindery refused this request (HTTP 422)")
  }

  /**
   * A `401` is reported as *rejected*, never as *wrong* or *missing*.
   *
   * Measured against `v1.32.1`: a wrong `X-Api-Key`, no `X-Api-Key` at all, and a valid key
   * supplied as `?apikey=` on a mutation all answer with the byte-identical
   * `401 {"error":"unauthorized"}` in `fixtures/bindery/error-unauthorized.json`. Nothing in that
   * response says which, so a message claiming to know would be a guess presented as a fact and
   * would send someone to regenerate a key that was never the problem.
   *
   * Note what this specifically refuses: turning Bindery's own `"unauthorized"` into the user-facing
   * text. It is not wrong, but it is not actionable either, and the `401` is the one failure whose
   * remedy is specific enough to say in this app's own words.
   */
  @Test
  fun `a rejected key is reported as rejected, not as wrong or missing`() = runTest {
    server.enqueue(fixture("bindery/error-unauthorized.json", code = 401))

    val raised = runCatching { client(server).health() }.exceptionOrNull()

    assertThat(raised).isInstanceOf(BinderyUnauthorizedException::class.java)
    val message = raised!!.message.orEmpty()
    assertThat(message).isEqualTo("Bindery rejected this API key")
    assertThat(message.lowercase()).doesNotContain("missing")
    assertThat(message.lowercase()).doesNotContain("wrong")
    assertThat(message.lowercase()).doesNotContain("invalid")
  }

  /**
   * `IntegrationBaseUrl` keeps the path verbatim — a reverse-proxied Bindery is the deployment
   * this app pushes users towards, because a release build refuses cleartext — so a client
   * configured at `https://host/bindery/` must resolve `api/v1/health` *under* it rather than
   * replacing the last segment, which is what Retrofit does to a base URL with no trailing slash.
   * The guarantee is the type's; this proves the client actually benefits from it.
   */
  @Test
  fun `a base url with a path prefix resolves endpoints underneath it`() = runTest {
    server.enqueue(fixture("bindery/health.json"))
    val prefixed = app.muplay.integrations.IntegrationBaseUrl.parse(
      server.url("/bindery").toString(),
      app.muplay.integrations.CleartextPolicy.Allowed,
    ) as app.muplay.integrations.BaseUrlResult.Valid

    BinderyClient(
      app.muplay.integrations.IntegrationCredentials.Bindery(prefixed.url, FIRST_KEY),
    ).health()

    assertThat(nextRequest().url.encodedPath).isEqualTo("/bindery/api/v1/health")
  }

  /**
   * **The vulnerability this guard exists for, and it is worse here than next door.** A server the
   * user configured -- or anyone able to answer as it -- replies `302 Location: <somewhere else>`,
   * and the client follows. Bindery's key is instance-wide and always treated as admin, so what
   * leaks is not "read access to a music library" but the whole instance.
   *
   * Measured against the OkHttp this project resolves (5.5.0):
   * `RetryAndFollowUpInterceptor.buildRedirectRequest` strips exactly one header on a redirect
   * whose connection it cannot reuse, `Authorization`. `X-Api-Key` is not a name it knows, so an
   * application interceptor's copy of it is carried to the new origin verbatim.
   *
   * Two servers, and the second is addressed by the **other spelling of the loopback host** as
   * well as on its own port, so the two origins differ by host and not only by port. Both halves
   * are asserted rather than assumed -- a test that accidentally redirected to the *same* origin
   * would pass this while proving nothing.
   */
  @Test
  fun `a cross-origin redirect does not carry the api key to the other server`() = runTest {
    val elsewhere = MockWebServer()
    elsewhere.start()
    try {
      val target = crossHost(elsewhere, "/api/v1/health")
      server.enqueue(
        MockResponse.Builder().code(302).setHeader("Location", target.toString()).build(),
      )
      elsewhere.enqueue(fixture("bindery/health.json"))

      client(server, FIRST_KEY).health()

      val first = nextRequest()
      val second = next(elsewhere)

      // Positive controls first. Without them, a client that never authenticated at all, or that
      // never followed the redirect, would satisfy the negative below while proving nothing.
      assertThat(first.headers["X-Api-Key"]).isEqualTo(FIRST_KEY)
      assertThat(second.url.encodedPath).isEqualTo("/api/v1/health")
      // ...and the redirect really did cross an origin, in both of the ways it can.
      assertThat(second.url.host).isNotEqualTo(server.url("/").host)
      assertThat(second.url.port).isNotEqualTo(server.url("/").port)

      // The assertion this test exists for.
      assertThat(second.headers["X-Api-Key"]).isNull()
      // ...and it did not escape onto the URL on the way past either.
      assertThat(second.url.toString()).doesNotContain(FIRST_KEY)
    } finally {
      elsewhere.close()
    }
  }

  /**
   * The same guarantee for a redirect that differs **only by port** on the identical host.
   *
   * Separate from the test above because an origin is a three-part tuple and a guard that compared
   * only the host would pass that one: `https://host:8787` and `https://host:9999` are different
   * servers, and on a machine hosting several self-hosted apps behind one name they are routinely
   * *different people's* servers.
   */
  @Test
  fun `a redirect to another port on the same host does not carry the api key`() = runTest {
    val elsewhere = MockWebServer()
    elsewhere.start()
    try {
      // `elsewhere.url(...)` verbatim: same host spelling as `server`, different port.
      val target = elsewhere.url("/api/v1/health")
      server.enqueue(
        MockResponse.Builder().code(302).setHeader("Location", target.toString()).build(),
      )
      elsewhere.enqueue(fixture("bindery/health.json"))

      client(server, FIRST_KEY).health()

      nextRequest()
      val second = next(elsewhere)

      // The positive control that makes this test different from the one above: the host really is
      // the same, so only the port can have decided the outcome.
      assertThat(second.url.host).isEqualTo(server.url("/").host)
      assertThat(second.url.port).isNotEqualTo(server.url("/").port)

      assertThat(second.headers["X-Api-Key"]).isNull()
    } finally {
      elsewhere.close()
    }
  }

  /**
   * The other half of the guard, and the half that makes it a *scoping* rule rather than a ban:
   * a redirect that stays on the configured origin still carries the key.
   *
   * This is not hypothetical here. `IntegrationBaseUrl` keeps a `urlBase` path verbatim because a
   * reverse-proxied Bindery is the deployment a release build pushes users towards, and a proxy
   * that normalises a path answers a **relative** `Location` on the same origin -- which is why
   * this test enqueues a relative one rather than the absolute URL that would have been easier to
   * write. A guard that dropped the key here would break every proxied install.
   */
  @Test
  fun `a same-origin redirect is followed with the key intact`() = runTest {
    server.enqueue(
      MockResponse.Builder().code(307).setHeader("Location", "/bindery/api/v1/health").build(),
    )
    server.enqueue(fixture("bindery/health.json"))

    client(server, SECOND_KEY).health()

    val first = nextRequest()
    val second = nextRequest()
    assertThat(first.url.encodedPath).isEqualTo("/api/v1/health")
    assertThat(second.url.encodedPath).isEqualTo("/bindery/api/v1/health")
    assertThat(second.headers["X-Api-Key"]).isEqualTo(SECOND_KEY)
    // ...and the redirected request still carries the key nowhere else. A redirect is exactly
    // where a query parameter would survive unnoticed.
    assertThat(second.url.toString()).doesNotContain(SECOND_KEY)
  }

  /**
   * A URL on [other] whose host is spelled the other way round from the one MockWebServer names
   * itself with.
   *
   * MockWebServer reports `localhost` or `127.0.0.1` depending on the host's resolver, and both
   * reach the same loopback socket, so neither spelling can be hardcoded. Whichever it chose, the
   * other one is a different `HttpUrl.host` for a reachable server -- which is what makes the
   * cross-origin test above a cross-*host* redirect rather than only a cross-port one.
   */
  private fun crossHost(other: MockWebServer, path: String) =
    other.url(path).newBuilder()
      .host(if (other.url("/").host == "127.0.0.1") "localhost" else "127.0.0.1")
      .build()

  private fun candidate(foreignBookId: String) = BinderyBookCandidate(
    foreignBookId = foreignBookId,
    title = "Project Hail Mary",
    authorName = "Andy Weir",
    foreignAuthorId = "OL7234434A",
    asin = null,
    coverUrl = null,
    raw = kotlinx.serialization.json.JsonObject(emptyMap()),
  )
}
