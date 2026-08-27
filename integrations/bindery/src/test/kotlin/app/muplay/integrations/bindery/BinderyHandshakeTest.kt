package app.muplay.integrations.bindery

import app.muplay.integrations.bindery.BinderyTestServer.FIRST_KEY
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
 * The handshake, and the thing it deliberately does **not** prove.
 *
 * `GET /api/v1/health` is **unauthenticated**. Measured against `v1.32.1`: it answers
 * `200 {"status":"ok","version":"v1.32.1"}` with a wrong `X-Api-Key`, and with none at all. That
 * is the sharpest trap in this module after `mediaType`, because it is invisible from the client
 * side — the request goes out with the key on it, the response comes back 200, and everything
 * looks like a working credential check.
 *
 * A configuration screen that called only this would tell a user with a mistyped key that their
 * connection was fine, and every later call would then fail. [BinderySource.books] is the cheapest
 * authenticated call and is what proves the key; this file's job is to make sure nothing here
 * claims otherwise.
 */
class BinderyHandshakeTest {

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
  fun `the handshake reads both fields from the body, not from constants`() = runTest {
    server.enqueue(fixture("bindery/health.json"))
    val real = client(server).health()
    assertThat(real.status).isEqualTo("ok")
    assertThat(real.version).isEqualTo("v1.32.1")
    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/health")

    // The second observation of both fields. A client that hardcoded either -- and `"ok"` is
    // exactly the constant a lazy implementation reaches for -- passes the assertions above.
    server.enqueue(json("""{"status":"degraded","version":"v2.0.0"}"""))
    val other = client(server).health()
    assertThat(other.status).isEqualTo("degraded")
    assertThat(other.version).isEqualTo("v2.0.0")
  }

  @Test
  fun `a health body with both fields omitted maps to empty strings`() = runTest {
    server.enqueue(json("{}"))

    val server0 = client(server).health()

    assertThat(server0.status).isEmpty()
    assertThat(server0.version).isEmpty()
    assertThat(server0.isBindery).isFalse()
  }

  /**
   * [BinderyServer.isBindery] is the weaker of the two claims available, deliberately.
   *
   * Bindery's health body carries no application name — there is nothing here to play the part
   * `appName` plays for Lidarr — so the strongest honest statement is "both fields were present
   * and `status` was ok". Something else serving `{"status":"ok"}` at that path satisfies it, and
   * the authenticated call is what settles that.
   *
   * Four observations, so neither half of the `&&` is dead: ok with a version, ok without one, not
   * ok with a version, and a different casing.
   */
  @Test
  fun `isBindery requires both an ok status and a version`() = runTest {
    val bodies = listOf(
      """{"status":"ok","version":"v1.32.1"}""",
      """{"status":"ok","version":""}""",
      """{"status":"starting","version":"v1.32.1"}""",
      """{"status":"OK","version":"v1.32.1"}""",
    )
    bodies.forEach { server.enqueue(json(it)) }

    val source = client(server)
    val verdicts = bodies.map { source.health().isBindery }

    assertThat(verdicts).containsExactly(true, false, false, true)
  }

  /**
   * **The trap, stated as an assertion: a wrong key still gets a 200 here.**
   *
   * `health()` cannot fail on a bad credential because the endpoint does not check one, so this
   * test drives it with a key the server never validates and requires it to succeed — the opposite
   * of what a reader would expect a "handshake" test to assert, and the whole point.
   *
   * The second half is the discriminating one: the *same* wrong key on the authenticated read is a
   * `401`. Two calls, one credential, two outcomes — which is exactly the shape a connection check
   * has to be built out of.
   */
  @Test
  fun `health succeeds with a key the server would reject, and the authenticated read does not`() = runTest {
    // Bindery does not look at the header here, so a 200 is what a real instance sends whatever
    // the key is.
    server.enqueue(fixture("bindery/health.json"))
    server.enqueue(fixture("bindery/error-unauthorized.json", code = 401))

    val source = client(server, apiKey = "not-a-real-key")

    assertThat(source.health().isBindery).isTrue()
    val raised = runCatching {
      source.books(status = null, limit = 100, offset = 0)
    }.exceptionOrNull()
    assertThat(raised).isInstanceOf(BinderyUnauthorizedException::class.java)

    // ...and the key really was on both requests, so "health succeeded" is about the endpoint
    // rather than about a client that forgot to authenticate.
    val requests = listOf(nextRequest(), nextRequest())
    assertThat(requests.map { it.url.encodedPath })
      .containsExactly("/api/v1/health", "/api/v1/book")
    assertThat(requests).allSatisfy {
      assertThat(it.headers["X-Api-Key"]).isEqualTo("not-a-real-key")
    }
  }

  /**
   * A successful response with no body at all is a failure naming the status, not a
   * `NullPointerException`.
   *
   * A `204`, or a proxy that stripped the body, is what produces this.
   */
  @Test
  fun `a successful response with no body fails naming the status`() = runTest {
    server.enqueue(MockResponse.Builder().code(204).build())

    val raised = runCatching { client(server, FIRST_KEY).health() }.exceptionOrNull()

    assertThat(raised).isInstanceOf(BinderyHttpException::class.java)
    assertThat((raised as BinderyHttpException).status).isEqualTo(204)
  }
}
