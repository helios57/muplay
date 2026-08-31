package app.muplay.integrations.lidarr

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.lidarr.di.LidarrModule
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The two one-line pieces of wiring between `LidarrSourceFactory` and a real HTTP client.
 *
 * Neither has any logic, and that is exactly why they are here. `DefaultLidarrSourceFactory` and
 * `LidarrModule.provideLidarrSourceFactory` were both measured at **LINE 0/1** — exercised by
 * nothing at all — while every test in this module passed. "Obviously fine, exercised by nothing"
 * is a shape this project has found repeatedly, and this is the layer where a Lidarr provider
 * could be wired to a factory that builds something else entirely without a single assertion
 * moving.
 */
class LidarrWiringTest {

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

  /**
   * The production factory builds a client that really speaks to the given server with the given
   * key.
   *
   * Asserted through the wire rather than with `isInstanceOf(LidarrClient::class.java)`: the type
   * would be satisfied by a client built from *different* credentials, which is the defect that
   * matters here — `RequestsRepository` hands this factory the user's credentials and never
   * looks at what came back.
   */
  @Test
  fun `the default factory builds a client that talks to the given server with the given key`() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(readFixture("lidarr/system-status.json"))
        .build(),
    )
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    val credentials =
      IntegrationCredentials.Lidarr((url as BaseUrlResult.Valid).url, apiKey = API_KEY)

    val source = DefaultLidarrSourceFactory.create(credentials)
    val status = source.status()

    assertThat(status.appName).isEqualTo("Lidarr")
    val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(request).describedAs("a request from the factory-built client").isNotNull()
    assertThat(request!!.url.encodedPath).isEqualTo("/api/v1/system/status")
    // The credentials it was handed, not some other set -- and still not on the URL.
    assertThat(request.headers["X-Api-Key"]).isEqualTo(API_KEY)
    assertThat(request.url.toString()).doesNotContain(API_KEY)
  }

  /**
   * The Hilt binding names the production factory.
   *
   * `isSameAs`, not `isNotNull`: the whole content of this provider is *which* factory it returns,
   * and a provider returning some other `LidarrSourceFactory` — a lambda, a stub left behind by a
   * debugging session — would satisfy anything weaker.
   */
  @Test
  fun `the hilt module provides the production factory and not another one`() {
    assertThat(LidarrModule.provideLidarrSourceFactory()).isSameAs(DefaultLidarrSourceFactory)
  }

  private companion object {
    const val API_KEY = "0123456789abcdef0123456789abcdef"
    const val REQUEST_TIMEOUT_SECONDS = 10L
  }
}
