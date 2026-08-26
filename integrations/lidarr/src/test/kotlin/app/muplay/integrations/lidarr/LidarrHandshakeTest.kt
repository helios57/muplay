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
 * What "is this a working Lidarr" means, and what each way it can fail is called.
 *
 * The distinctions here are the point. A 401 that reads as "unreachable", or a Sonarr that reads
 * as a working Lidarr, are both silent-wrong-answers — the failure class this project ranks worst.
 *
 * Every response body below is either a fixture captured off a real Lidarr `3.1.0.4875` or a
 * deliberately synthetic one written to be the *second* observation of a value: a fixture alone
 * cannot tell a mapped field from a constant, because the constant a lazy implementation would
 * return is the fixture's own value.
 */
class LidarrHandshakeTest {

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

  private fun client(): LidarrClient {
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    return LidarrClient(
      IntegrationCredentials.Lidarr((url as BaseUrlResult.Valid).url, apiKey = "k"),
    )
  }

  private fun json(code: Int, body: String) =
    MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

  private fun nextRequest(): RecordedRequest {
    val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(request)
      .describedAs("a request within %d s -- the client sent none", REQUEST_TIMEOUT_SECONDS)
      .isNotNull()
    return request!!
  }

  @Test
  fun `status reports every field the configuration screen needs`() = runTest {
    server.enqueue(json(200, readFixture("lidarr/system-status.json")))

    val status = client().status()

    // Field by field, against the values a real Lidarr 3.1.0.4875 sent. `appName` is the one the
    // configuration screen branches on and `urlBase` is the one a proxied install needs.
    assertThat(status.appName).isEqualTo("Lidarr")
    assertThat(status.instanceName).isEqualTo("Lidarr")
    assertThat(status.version).isEqualTo("3.1.0.4875")
    assertThat(status.urlBase).isEqualTo("")
    // `"none"` on a fresh install: `AuthenticationMethod` in config.xml. Read as a String rather
    // than an enum because `JsonStringEnumConverter` is built with `allowIntegerValues = true`,
    // so the same field can legally arrive as a number.
    assertThat(status.authentication).isEqualTo("none")
    assertThat(status.isLidarr).isTrue()
  }

  @Test
  fun `status reads the values from the body, not from constants`() = runTest {
    // The second observation of every mapped field. Without this, a `status()` that returned a
    // fixed `LidarrServer` passes the test above -- which is round four of this project's defect
    // history, applied to a new client. Every value here differs from the fixture's.
    server.enqueue(
      json(
        200,
        """
        {"appName":"Sonarr","instanceName":"Media","version":"9.9.9.9","urlBase":"/lidarr",
         "authentication":"forms"}
        """.trimIndent(),
      ),
    )

    val status = client().status()

    assertThat(status.appName).isEqualTo("Sonarr")
    assertThat(status.instanceName).isEqualTo("Media")
    assertThat(status.version).isEqualTo("9.9.9.9")
    assertThat(status.urlBase).isEqualTo("/lidarr")
    // `forms`, not the fixture's `none`. Measured with `authentication = "none"` hardcoded into
    // the mapping: this test stayed GREEN because the synthetic body originally repeated the
    // fixture's own value, so the "second observation" was not a second observation at all. The
    // only test that caught it was the omitted-fields one, by accident. Both real values
    // (`none` on a fresh install, `forms` once a user sets a password) are now used, once each.
    assertThat(status.authentication).isEqualTo("forms")
    // And the identity check is a real comparison, not a constant `true`. This is the whole reason
    // the handshake is `system/status` and not `ping`: a user who pastes their Sonarr URL into the
    // Lidarr field must not get a green tick.
    assertThat(status.isLidarr).isFalse()
  }

  /**
   * Lidarr's serializer omits null-valued fields entirely
   * (`DefaultIgnoreCondition = WhenWritingNull`), so a response can legitimately arrive missing
   * any of these. They map to `""` rather than failing the parse -- and a screen that had to tell
   * "absent" from "empty" would be splitting a hair Lidarr has already collapsed, since `urlBase`
   * on an unproxied install is `""` rather than absent.
   */
  @Test
  fun `a status body with every optional field omitted maps to empty strings`() = runTest {
    server.enqueue(json(200, "{}"))

    val status = client().status()

    assertThat(status.appName).isEmpty()
    assertThat(status.instanceName).isEmpty()
    assertThat(status.version).isEmpty()
    assertThat(status.urlBase).isEmpty()
    assertThat(status.authentication).isEmpty()
    assertThat(status.isLidarr).isFalse()
  }

  @Test
  fun `the request goes to api v1 system status`() = runTest {
    server.enqueue(json(200, readFixture("lidarr/system-status.json")))

    client().status()

    val request = nextRequest()
    assertThat(request.url.encodedPath).isEqualTo("/api/v1/system/status")
    // The method too: `RestController` maps GET and POST at overlapping paths, and a client that
    // sent the wrong verb to the right path is a defect no path assertion can see.
    assertThat(request.method).isEqualTo("GET")
  }

  /**
   * 401 is the same answer for a missing key and a wrong one. Measured against
   * `3.1.0.4875-ls40`: a request with `X-Api-Key: wrong` and a request with no `X-Api-Key` at all
   * both produce `HTTP/1.1 401 Unauthorized` with `Content-Length: 0` and no body — byte-identical.
   * The message must therefore not claim to know which.
   */
  @Test
  fun `a 401 is an unauthorized failure whose message does not overclaim`() = runTest {
    server.enqueue(MockResponse.Builder().code(401).build())

    val raised = runCatching { client().status() }.exceptionOrNull()

    assertThat(raised).isInstanceOf(LidarrUnauthorizedException::class.java)
    assertThat(raised).hasMessageContaining("rejected")
    // Lidarr cannot tell us the key is *wrong* rather than missing, so this client must not say
    // so. Both wordings a hurried implementation reaches for are refused by name.
    assertThat(raised).hasMessageNotContaining("incorrect")
    assertThat(raised).hasMessageNotContaining("wrong")
  }

  /**
   * A container that has just restarted answers every API call with 503 and this exact body
   * (`StartingUpMiddleware.cs`). It is a normal transient state, not a configuration error, and a
   * client that reported it as "cannot reach Lidarr" would send the user to check their firewall.
   *
   * `starting-up.json` is the one fixture in this module **not** captured from a running instance,
   * and that is recorded here rather than hidden: the window is a few seconds wide and Kestrel
   * does not accept connections until after `StartingUpMiddleware` has stopped answering. A
   * hundred and ten polls against a restarting container went straight from connection-refused to
   * 200 without ever observing a 503. The body is written from the middleware's own source.
   */
  @Test
  fun `a 503 starting-up body is its own failure, distinct from any other 503`() = runTest {
    server.enqueue(json(503, readFixture("lidarr/starting-up.json")))

    assertThat(runCatching { client().status() }.exceptionOrNull())
      .isInstanceOf(LidarrStartingUpException::class.java)

    // ...and a 503 that is *not* Lidarr starting up -- a reverse proxy with no upstream, say --
    // must not be mistaken for one. Two observations of the same status code, discriminated by
    // body: without the second, a client that mapped every 503 to StartingUp passes the first.
    server.enqueue(MockResponse.Builder().code(503).body("<html>502 Bad Gateway</html>").build())

    assertThat(runCatching { client().status() }.exceptionOrNull())
      .isInstanceOf(LidarrHttpException::class.java)

    // And a 503 carrying well-formed JSON that says something else is also not a starting-up:
    // the discriminator is the message, not "the body parsed".
    server.enqueue(json(503, """{"errorMessage":"Upstream connect error"}"""))

    assertThat(runCatching { client().status() }.exceptionOrNull())
      .isInstanceOf(LidarrHttpException::class.java)
  }

  /**
   * A **successful** response with no body at all. Retrofit hands back a `Response` whose
   * `body()` is null for a 204/205, and `call()`'s `?: throw` is what stops that becoming a
   * `NullPointerException` from inside the client.
   *
   * Written because the sweep found it: the mutation `response.body() ?: throw …` → `body()!!`
   * left the entire suite **green**. No endpoint in Task 4 can produce a 204, so this was an arm
   * with no observation at all — and Tasks 5-7 add `POST`s that can. An uncovered guard is a
   * guard that gets deleted as dead code.
   */
  @Test
  fun `a successful response with no body is a failure carrying the status`() = runTest {
    server.enqueue(MockResponse.Builder().code(204).build())

    val raised = runCatching { client().status() }.exceptionOrNull()

    assertThat(raised).isInstanceOf(LidarrHttpException::class.java)
    // 204, not a constant: the guard has to carry the code it actually saw.
    assertThat((raised as LidarrHttpException).status).isEqualTo(204)
  }

  @Test
  fun `any other unsuccessful status is a plain http failure carrying the code`() = runTest {
    // Two codes, so `status` is not a constant.
    server.enqueue(MockResponse.Builder().code(404).build())

    val notFound = runCatching { client().status() }.exceptionOrNull()
    assertThat(notFound).isInstanceOf(LidarrHttpException::class.java)
    assertThat((notFound as LidarrHttpException).status).isEqualTo(404)

    server.enqueue(MockResponse.Builder().code(500).build())

    val serverError = runCatching { client().status() }.exceptionOrNull()
    assertThat(serverError).isInstanceOf(LidarrHttpException::class.java)
    assertThat((serverError as LidarrHttpException).status).isEqualTo(500)
  }

  /**
   * A 400 carries a bare JSON **array** of FluentValidation failures, not an object
   * (`LidarrErrorPipeline.cs`). This fixture is the real one, captured from
   * `POST /api/v1/album -d '{}'` against `3.1.0.4875`, and it settles a row the plan listed as
   * *not established*: a failure element carries `propertyName`, `errorMessage`, `severity`,
   * `errorCode`, `formattedMessageArguments` and `formattedMessagePlaceholderValues` — and **no**
   * `attemptedValue` or `isWarning`.
   *
   * Task 4 does not POST anything. This path is still Task 4's because `call()` maps it, and an
   * arm of `call()` that no test reaches is an arm Task 6 would inherit unproven.
   */
  @Test
  fun `a 400 is a validation failure carrying lidarr's own property names and messages`() = runTest {
    server.enqueue(json(400, readFixture("lidarr/validation-error-empty-album.json")))

    val raised = runCatching { client().status() }.exceptionOrNull()

    assertThat(raised).isInstanceOf(LidarrValidationException::class.java)
    val failures = (raised as LidarrValidationException).failures
    // Both elements, in order, both fields of each -- not `hasSize(2)`, which a parser that
    // produced two empty failures would satisfy.
    assertThat(failures.map { it.propertyName }).containsExactly("ForeignAlbumId", "Artist")
    assertThat(failures[0].errorMessage).isEqualTo("'Foreign Album Id' must not be empty.")
    assertThat(failures[1].errorMessage).isEqualTo("'Artist' must not be empty.")
    // This one is not an already-added: the duplicate message is a different string entirely.
    assertThat(raised.isAlreadyAdded).isFalse()
  }

  /**
   * A duplicate add arrives as a 400 with `"This album has already been added."` and **no**
   * machine-readable code beside it (`AlbumExistsValidator.GetDefaultMessageTemplate`), which is
   * why [LidarrValidationException.isAlreadyAdded] matches on the message. Task 6 branches on it.
   */
  @Test
  fun `an already-added validation failure is recognised by its message`() = runTest {
    server.enqueue(
      json(400, """[{"propertyName":"ForeignAlbumId","errorMessage":"This album has already been added."}]"""),
    )

    val raised = runCatching { client().status() }.exceptionOrNull()

    assertThat((raised as LidarrValidationException).isAlreadyAdded).isTrue()
  }

  /**
   * A 400 whose body is not a FluentValidation array at all — a reverse proxy's HTML error page —
   * must still reach the caller as something it can render, not as a `SerializationException` from
   * inside the client.
   */
  @Test
  fun `a 400 that is not a validation array degrades to an empty failure list`() = runTest {
    server.enqueue(MockResponse.Builder().code(400).body("<html>Bad Request</html>").build())

    val raised = runCatching { client().status() }.exceptionOrNull()

    assertThat(raised).isInstanceOf(LidarrValidationException::class.java)
    assertThat((raised as LidarrValidationException).failures).isEmpty()
    assertThat(raised.isAlreadyAdded).isFalse()
  }

  /**
   * `ping` exists for exactly one job: distinguishing "nothing is listening" from "something is
   * listening and rejecting our key". It is unauthenticated, so it answers the first question
   * without the second interfering.
   */
  @Test
  fun `ping is unauthenticated, unversioned, and true only for an OK body`() = runTest {
    server.enqueue(json(200, readFixture("lidarr/ping-ok.json")))

    assertThat(client().ping()).isTrue()
    // Not `/api/v1/ping`: PingController maps `/ping` at the application root.
    assertThat(nextRequest().url.encodedPath).isEqualTo("/ping")

    // A 200 that is not an OK ping -- a captive portal, a proxy error page served with 200 -- is
    // not a Lidarr. Without this, `ping` returning `true` for any 200 passes the assertion above.
    server.enqueue(json(200, """{"status":"Error"}"""))
    assertThat(client().ping()).isFalse()

    // A 200 with no `status` field at all is also not an OK ping, and reaches a different arm:
    // `body()?.status` is null rather than a mismatching string.
    server.enqueue(json(200, "{}"))
    assertThat(client().ping()).isFalse()
  }

  @Test
  fun `ping is false rather than throwing for any unsuccessful status`() = runTest {
    // `ping`'s whole value is being a question that always has an answer. A throw here would make
    // the configuration screen's "is anything there at all?" branch need a try/catch of its own.
    server.enqueue(MockResponse.Builder().code(500).build())
    assertThat(client().ping()).isFalse()

    // 401 in particular: `/ping` is `[AllowAnonymous]`, so a 401 here means something that is not
    // a Lidarr is answering, and it must still be a `false` rather than the unauthorized
    // exception `status()` would raise.
    server.enqueue(MockResponse.Builder().code(401).build())
    assertThat(client().ping()).isFalse()
  }

  /**
   * The transport-failure arm: nothing is listening at all. `ping` swallows it -- that is the
   * `runCatching` in the client -- while `status()` lets it propagate, because "we could not ask"
   * is not "Lidarr said no" and the sealed [LidarrException] hierarchy deliberately has no member
   * for it.
   */
  @Test
  fun `a dead server is a false ping and a non-Lidarr throwable from status`() = runTest {
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    val client = LidarrClient(
      IntegrationCredentials.Lidarr((url as BaseUrlResult.Valid).url, apiKey = "k"),
    )
    server.close()

    assertThat(client.ping()).isFalse()

    val raised = runCatching { client.status() }.exceptionOrNull()
    assertThat(raised).isNotNull()
    assertThat(raised).isNotInstanceOf(LidarrException::class.java)
  }

  private companion object {
    const val REQUEST_TIMEOUT_SECONDS = 10L
  }
}
