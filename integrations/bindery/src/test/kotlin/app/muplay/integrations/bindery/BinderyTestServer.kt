package app.muplay.integrations.bindery

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat

/**
 * The pieces every wire test in this module needs, in one place.
 *
 * The server is started and stopped by hand rather than with `@StartStop`, because that extension
 * ships in `mockwebserver3-junit5` and this module declares plain `mockwebserver3`: the JUnit 5
 * flavour carries a second `META-INF/LICENSE.md` that fails `mergeDebugAndroidTestJavaResource`,
 * and this module has an androidTest source set. Same pattern as `:core:network`'s own tests and
 * as `:integrations:lidarr`'s.
 */
internal object BinderyTestServer {

  /**
   * Generous against requests that complete in single-digit milliseconds on an in-process server,
   * so it can only fire on a genuine absence, never on a slow machine.
   */
  const val REQUEST_TIMEOUT_SECONDS = 10L

  /**
   * Shaped like a real Bindery key: **64 lowercase hex characters**, measured off a running
   * `v1.32.1` whose generated key is 32 random bytes hex-encoded and lives in its own `settings`
   * table under `auth.api_key`. Not any real instance's key — the container these fixtures were
   * captured from generated its own, which is in none of them.
   */
  const val FIRST_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  const val SECOND_KEY = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

  fun client(server: MockWebServer, apiKey: String = FIRST_KEY): BinderyClient {
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    return BinderyClient(
      IntegrationCredentials.Bindery(
        baseUrl = (url as BaseUrlResult.Valid).url,
        apiKey = apiKey,
      ),
    )
  }

  fun json(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder()
      .code(code)
      .setHeader("Content-Type", "application/json")
      .body(body)
      .build()

  fun fixture(path: String, code: Int = 200): MockResponse = json(readFixture(path), code)

  /**
   * The next request the client actually sent, or a failed assertion if it sent none.
   *
   * Deliberately not the no-argument `takeRequest`: that one blocks forever on an empty queue, so
   * a client that stopped issuing a request at all — the exact regression these wire assertions
   * exist to catch — would hang the build rather than fail the test. Copied from `:core:network`'s
   * `SubsonicClientTest` for that reason.
   */
  fun next(server: MockWebServer): RecordedRequest {
    val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(request)
      .describedAs("a request within %d s -- the client sent none", REQUEST_TIMEOUT_SECONDS)
      .isNotNull()
    return request!!
  }
}
