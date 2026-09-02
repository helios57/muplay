package app.muplay.integrations.lidarr

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import java.util.concurrent.TimeUnit
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
 * went out — and the two that could be satisfied by a constant are each made twice at two
 * different values.
 *
 * The server is started and stopped by hand rather than with `@StartStop`, because that extension
 * ships in `mockwebserver3-junit5` and this module declares plain `mockwebserver3`: the JUnit 5
 * flavour carries a second `META-INF/LICENSE.md` that fails `mergeDebugAndroidTestJavaResource`,
 * and this module has an androidTest source set. Same pattern as `:core:network`'s own tests.
 */
class LidarrAuthTest {

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

  private fun clientWith(apiKey: String): LidarrClient {
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    return LidarrClient(
      IntegrationCredentials.Lidarr(
        baseUrl = (url as BaseUrlResult.Valid).url,
        apiKey = apiKey,
      ),
    )
  }

  private fun enqueueStatus() {
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(readFixture("lidarr/system-status.json"))
        .build(),
    )
  }

  /**
   * The next request the client actually sent, or a failed assertion if it sent none.
   *
   * Deliberately not the no-argument `takeRequest`: that one blocks forever on an empty queue, so
   * a client that stopped issuing a request at all -- the exact regression these wire assertions
   * exist to catch -- would hang the build rather than fail this test. Copied from
   * `:core:network`'s `SubsonicClientTest` for that reason.
   */
  private fun nextRequest(from: MockWebServer = server): RecordedRequest {
    val request = from.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(request)
      .describedAs("a request within %d s -- the client sent none", REQUEST_TIMEOUT_SECONDS)
      .isNotNull()
    return request!!
  }

  @Test
  fun `every request carries the api key in the X-Api-Key header`() = runTest {
    enqueueStatus()

    clientWith(FIRST_KEY).status()

    assertThat(nextRequest().headers["X-Api-Key"]).isEqualTo(FIRST_KEY)
  }

  @Test
  fun `the header carries whichever key the client was given`() = runTest {
    // The second observation. A hardcoded header value passes the test above, and a hardcoded
    // header *name* passes both -- which is why the name is read as a map key rather than
    // searched for anywhere in the request.
    enqueueStatus()

    clientWith(SECOND_KEY).status()

    assertThat(nextRequest().headers["X-Api-Key"]).isEqualTo(SECOND_KEY)
  }

  /**
   * `Startup.cs` sets `ReturnHttpNotAcceptable = true`, so a request that declares a media type
   * Lidarr cannot produce is answered **406** rather than with JSON. Measured against
   * `3.1.0.4875`: `Accept: application/xml` really does get a 406, while *no* `Accept` header at
   * all gets a 200 -- so this header is pinning the negotiation rather than rescuing a request
   * that would otherwise fail. Retrofit and OkHttp set no `Accept` of their own.
   */
  @Test
  fun `every request declares that it accepts json`() = runTest {
    enqueueStatus()

    clientWith(FIRST_KEY).status()

    assertThat(nextRequest().headers["Accept"]).contains("application/json")
  }

  /**
   * The constraint this whole module is named for: **the key never appears in a URL.**
   *
   * Lidarr *does* accept `?apikey=` -- measured, it answers such a request 200 -- so this is a
   * live wrong path and not a hypothetical one. On this side of the wire a query-string key would
   * appear in every recorded request, in every fixture captured with `curl -i`, in the message of
   * any `IOException` OkHttp raises (which names the full URL), and in the access log of every
   * reverse proxy between here and Lidarr.
   *
   * Asserted over **every** recorded request rather than the first, so an endpoint added by a
   * later task cannot quietly reintroduce it: the loop below inspects as many requests as the
   * client made, and `hasSize` pins how many that was.
   */
  @Test
  fun `no request this client makes carries the key on its url`() = runTest {
    enqueueStatus()
    server.enqueue(MockResponse.Builder().code(200).body("""{"status":"OK"}""").build())

    val client = clientWith(FIRST_KEY)
    client.status()
    client.ping()

    val requests = listOf(nextRequest(), nextRequest())
    val urls = requests.map { it.url.toString() }

    // Two requests were made and both are inspected. `hasSize(2)` first, because `allSatisfy` over
    // an empty list is vacuously true -- which is exactly the shape this project has shipped
    // before -- and `nextRequest()` failing loudly on an empty queue is the other half of the
    // guard.
    assertThat(urls).hasSize(2)
    // The positive half, and it is not decoration: a client that sent no requests, or sent them
    // all to one constant URL, satisfies both negatives below while asserting nothing. A negative
    // is only evidence once something positive establishes there was anything to search.
    assertThat(urls.map { it.substringAfter(server.url("/").toString()) })
      .containsExactly("api/v1/system/status", "ping")
    // ...and the key really was sent on both, in the one place it belongs. Without this, a client
    // that simply never authenticated passes this whole test with flying colours -- which is the
    // Plan 1 `authParams() = emptyMap()` defect, restated for a header.
    assertThat(requests).allSatisfy { assertThat(it.headers["X-Api-Key"]).isEqualTo(FIRST_KEY) }

    // The negatives the test is named for.
    assertThat(urls).allSatisfy { url -> assertThat(url).doesNotContain(FIRST_KEY) }
    assertThat(urls).allSatisfy { url -> assertThat(url).doesNotContain("apikey") }
  }

  /**
   * The other half of the same guarantee, and the one a URL assertion cannot make: **no failure
   * this client raises names the key.**
   *
   * An exception message is the single most likely way a secret escapes a process -- it is
   * interpolated into a bug report, uploaded by a crash reporter, and printed by whatever
   * `catch` block a later task writes. Each of the four [LidarrException] members is raised here
   * from a real response and its whole `toString()` searched.
   */
  @Test
  fun `no failure this client raises names the api key`() = runTest {
    val client = clientWith(FIRST_KEY)

    server.enqueue(MockResponse.Builder().code(401).build())
    server.enqueue(
      MockResponse.Builder().code(503).body(readFixture("lidarr/starting-up.json")).build(),
    )
    server.enqueue(
      MockResponse.Builder().code(400)
        .body(readFixture("lidarr/validation-error-empty-album.json")).build(),
    )
    server.enqueue(MockResponse.Builder().code(500).build())

    // `runCatching`, not `assertThatThrownBy`: AssertJ's `ThrowingCallable` is a Java SAM and a
    // lambda converted to one is not a coroutine body, so a suspend call inside it does not
    // compile. `:core:network`'s own client tests use this same form.
    val raised = (1..4).map { runCatching { client.status() }.exceptionOrNull() }

    // Positive first: four calls really did raise four `LidarrException`s. Without this, a
    // `status()` that quietly returned a default would leave every `doesNotContain` below true.
    assertThat(raised).hasSize(4)
    assertThat(raised).allSatisfy { assertThat(it).isInstanceOf(LidarrException::class.java) }
    assertThat(raised.map { it!!::class.java }).containsExactly(
      LidarrUnauthorizedException::class.java,
      LidarrStartingUpException::class.java,
      LidarrValidationException::class.java,
      LidarrHttpException::class.java,
    )
    assertThat(raised).allSatisfy { assertThat(it.toString()).doesNotContain(FIRST_KEY) }
  }

  /**
   * The hole the test above cannot see, and the reason it cannot: it raises every failure from a
   * **committed fixture**, and no fixture in this repository contains an API key.
   *
   * `LidarrValidationException` used to build its `message` from Lidarr's own `errorMessage`
   * strings. Nothing this client *sends* is interpolated there — that argument was true and is
   * still in the type's KDoc — but `errorMessage` is a channel the **server** controls, and a
   * proxy or a future release that quoted a request header back would put the key straight into
   * the one string a crash reporter and a bug report copy wholesale. `:integrations:bindery`
   * found the identical shape in `BinderyMessageException`; this is the same structural fix and
   * the observation that holds it.
   *
   * The server's sentence is still available, by name, so nothing is lost but the default.
   */
  @Test
  fun `a validation message that quotes the api key never reaches the exception message`() = runTest {
    val client = clientWith(FIRST_KEY)
    // A refusal that quotes the request's own header back. Contrived on purpose -- the point is
    // that this client cannot control whether a server does it, so the structure has to hold
    // regardless. Built from the constant rather than committed as a fixture, so the repository
    // never carries a file with a key-shaped string in it.
    server.enqueue(
      MockResponse.Builder().code(400).body(
        """[{"propertyName":"ApiKey","errorMessage":"the key $FIRST_KEY is not valid here",""" +
          """"errorCode":"NotEmptyValidator"}]""",
      ).build(),
    )

    val raised = runCatching { client.status() }.exceptionOrNull()

    // Positive first: the response really did raise this exception, and the key really did survive
    // into the parsed failure. Without both, every negative below is vacuous.
    assertThat(raised).isInstanceOf(LidarrValidationException::class.java)
    val validation = raised as LidarrValidationException
    assertThat(validation.failures.single().errorMessage).contains(FIRST_KEY)
    assertThat(validation.lidarrMessage).contains(FIRST_KEY)

    // The negatives the test is named for: the message, and everything derived from it.
    assertThat(validation.message).isEqualTo("Lidarr refused this request as invalid")
    assertThat(validation.message).doesNotContain(FIRST_KEY)
    assertThat(validation.toString()).doesNotContain(FIRST_KEY)
    assertThat(validation.stackTraceToString()).doesNotContain(FIRST_KEY)
  }

  /**
   * A `urlBase` server answers `/api/v1/...` with a **307** to `{urlBase}/api/v1/...`
   * (`UrlBaseMiddleware.cs`). Measured against a real Lidarr with `<UrlBase>/lidarr</UrlBase>`:
   * `307 Temporary Redirect`, `Location: /lidarr/api/v1/system/status` -- **relative**, not
   * absolute, which is why this test enqueues a relative `Location` too rather than the absolute
   * one that would have been the easy thing to write.
   *
   * OkHttp follows it and, because it is same-origin, keeps the `X-Api-Key` header. But "OkHttp
   * keeps auth headers on same-host redirects" is a claim about OkHttp, and this project does not
   * ship claims about libraries it has not observed.
   *
   * **What this test does NOT cover, and what it must not be read as covering.** It pins the
   * *same-origin* hop only. Until [LidarrAuthInterceptor] was scoped to the configured origin,
   * OkHttp carried `X-Api-Key` to a **cross-origin** redirect target too, and this test stayed
   * green through the whole of that: `RetryAndFollowUpInterceptor.buildRedirectRequest` strips
   * `Authorization` and nothing else, so a server answering `302 Location: https://evil.example/`
   * received the key. The test that covers that is
   * `a cross-origin redirect does not carry the api key to the other server`, and this one is its
   * other half -- the guard has to withhold the key off-origin *without* breaking the `urlBase`
   * hop, and neither test alone says both.
   */
  @Test
  fun `a urlBase redirect is followed with the key and the path intact`() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .code(307)
        .setHeader("Location", "/lidarr/api/v1/system/status")
        .build(),
    )
    enqueueStatus()

    clientWith(REDIRECT_KEY).status()

    val first = nextRequest()
    val second = nextRequest()
    assertThat(first.url.encodedPath).isEqualTo("/api/v1/system/status")
    assertThat(second.url.encodedPath).isEqualTo("/lidarr/api/v1/system/status")
    assertThat(second.headers["X-Api-Key"]).isEqualTo(REDIRECT_KEY)
    // ...and the redirected request still carries the key nowhere else. A redirect is exactly
    // where a query parameter would survive unnoticed.
    assertThat(second.url.toString()).doesNotContain(REDIRECT_KEY)
  }

  /**
   * `IntegrationBaseUrl` keeps the path verbatim -- Servarr's `urlBase` needs it -- so a client
   * configured at `http://host/lidarr/` must resolve `api/v1/system/status` *under* it rather than
   * replacing the last segment, which is what Retrofit does to a base URL with no trailing slash.
   * The guarantee is the type's; this proves the client actually benefits from it.
   */
  @Test
  fun `a base url with a path prefix resolves endpoints underneath it`() = runTest {
    enqueueStatus()
    val prefixed = IntegrationBaseUrl.parse(
      server.url("/lidarr").toString(),
      CleartextPolicy.Allowed,
    ) as BaseUrlResult.Valid

    LidarrClient(IntegrationCredentials.Lidarr(prefixed.url, FIRST_KEY)).status()

    assertThat(nextRequest().url.encodedPath).isEqualTo("/lidarr/api/v1/system/status")
  }

  /**
   * **The vulnerability this guard exists for.** A server the user configured -- or anyone able to
   * answer as it -- replies `302 Location: <somewhere else>`, and the client follows.
   *
   * Measured against the OkHttp this project resolves (5.5.0):
   * `RetryAndFollowUpInterceptor.buildRedirectRequest` strips exactly one header on a redirect
   * whose connection it cannot reuse, `Authorization`. `X-Api-Key` is not a name it knows, so an
   * application interceptor's copy of it is carried to the new origin verbatim. Lidarr's key is a
   * standing credential for the whole instance; Bindery's, next door, is admin-equivalent.
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
      val target = crossHost(elsewhere, "/api/v1/system/status")
      server.enqueue(
        MockResponse.Builder().code(302).setHeader("Location", target.toString()).build(),
      )
      elsewhere.enqueue(
        MockResponse.Builder()
          .code(200)
          .setHeader("Content-Type", "application/json")
          .body(readFixture("lidarr/system-status.json"))
          .build(),
      )

      clientWith(FIRST_KEY).status()

      val first = nextRequest()
      val second = nextRequest(elsewhere)

      // Positive controls first. Without them, a client that never authenticated at all, or that
      // never followed the redirect, would satisfy the negative below while proving nothing.
      assertThat(first.headers["X-Api-Key"]).isEqualTo(FIRST_KEY)
      assertThat(second.url.encodedPath).isEqualTo("/api/v1/system/status")
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
   * only the host would pass that one: `https://host:8686` and `https://host:9999` are different
   * servers, and on a machine hosting several self-hosted apps behind one name they are routinely
   * *different people's* servers.
   */
  @Test
  fun `a redirect to another port on the same host does not carry the api key`() = runTest {
    val elsewhere = MockWebServer()
    elsewhere.start()
    try {
      // `elsewhere.url(...)` verbatim: same host spelling as `server`, different port.
      val target = elsewhere.url("/api/v1/system/status")
      server.enqueue(
        MockResponse.Builder().code(302).setHeader("Location", target.toString()).build(),
      )
      elsewhere.enqueue(
        MockResponse.Builder()
          .code(200)
          .setHeader("Content-Type", "application/json")
          .body(readFixture("lidarr/system-status.json"))
          .build(),
      )

      clientWith(FIRST_KEY).status()

      nextRequest()
      val second = nextRequest(elsewhere)

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
   * A URL on [other] whose host is spelled the other way round from the one MockWebServer names
   * itself with.
   *
   * MockWebServer reports `localhost` or `127.0.0.1` depending on the host's resolver, and both
   * reach the same loopback socket, so neither spelling can be hardcoded. Whichever it chose, the
   * other one is a different `HttpUrl.host` for a reachable server -- which is what makes the test
   * above a cross-*host* redirect rather than only a cross-port one.
   */
  private fun crossHost(other: MockWebServer, path: String) =
    other.url(path).newBuilder()
      .host(if (other.url("/").host == "127.0.0.1") "localhost" else "127.0.0.1")
      .build()

  private companion object {
    /**
     * A 32-char lowercase hex string, the shape `ConfigFileProvider.cs` generates (a GUID with the
     * dashes removed) -- and not any real instance's key. The container this module's fixtures
     * were captured from generated its own, which is in none of them.
     */
    const val FIRST_KEY = "0123456789abcdef0123456789abcdef"
    const val SECOND_KEY = "ffffffffffffffffffffffffffffffff"
    const val REDIRECT_KEY = "aaaaaaaabbbbbbbbccccccccdddddddd"

    /**
     * Generous against requests that complete in single-digit milliseconds on an in-process
     * server, so it can only fire on a genuine absence, never on a slow machine.
     */
    const val REQUEST_TIMEOUT_SECONDS = 10L
  }
}
