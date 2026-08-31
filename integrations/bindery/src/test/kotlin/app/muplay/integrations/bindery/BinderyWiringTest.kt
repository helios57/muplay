package app.muplay.integrations.bindery

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.bindery.BinderyTestServer.FIRST_KEY
import app.muplay.integrations.bindery.BinderyTestServer.REQUEST_TIMEOUT_SECONDS
import app.muplay.integrations.bindery.BinderyTestServer.fixture
import app.muplay.integrations.bindery.di.BinderyModule
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The two one-line pieces of wiring between `BinderySourceFactory` and a real HTTP client.
 *
 * Neither has any logic, and that is exactly why they are here. Their `:integrations:lidarr`
 * counterparts were both measured at **LINE 0/1** — exercised by nothing at all — while every
 * other test in that module passed. "Obviously fine, exercised by nothing" is a shape this project
 * has found repeatedly, and this is the layer where a Bindery provider could be wired to a factory
 * that builds something else entirely without a single assertion moving.
 */
class BinderyWiringTest {

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
   * Asserted through the wire rather than with `isInstanceOf(BinderyClient::class.java)`: the type
   * would be satisfied by a client built from *different* credentials, which is the defect that
   * matters here — `RequestsRepository` hands this factory the user's credentials and never
   * looks at what came back.
   */
  @Test
  fun `the default factory builds a client that talks to the given server with the given key`() = runTest {
    server.enqueue(fixture("bindery/health.json"))
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    val credentials =
      IntegrationCredentials.Bindery((url as BaseUrlResult.Valid).url, apiKey = FIRST_KEY)

    val source = DefaultBinderySourceFactory.create(credentials)
    val health = source.health()

    assertThat(health.version).isEqualTo("v1.32.1")
    val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(request).describedAs("a request from the factory-built client").isNotNull()
    assertThat(request!!.url.encodedPath).isEqualTo("/api/v1/health")
    // The credentials it was handed, not some other set -- and still not on the URL.
    assertThat(request.headers["X-Api-Key"]).isEqualTo(FIRST_KEY)
    assertThat(request.url.toString()).doesNotContain(FIRST_KEY)
  }

  /**
   * The Hilt binding names the production factory.
   *
   * `isSameAs`, not `isNotNull`: the whole content of this provider is *which* factory it returns,
   * and a provider returning some other `BinderySourceFactory` — a lambda, a stub left behind by a
   * debugging session — would satisfy anything weaker.
   */
  @Test
  fun `the hilt module provides the production factory and not another one`() {
    assertThat(BinderyModule.provideBinderySourceFactory()).isSameAs(DefaultBinderySourceFactory)
  }
}
