package app.muplay.requests

import app.muplay.integrations.bindery.BinderyHttpException
import app.muplay.integrations.bindery.BinderyServer
import app.muplay.integrations.bindery.BinderyUnauthorizedException
import app.muplay.integrations.lidarr.LidarrHttpException
import app.muplay.integrations.lidarr.LidarrUnauthorizedException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Gathering the three observations a connection check decides from, against each service in turn.
 *
 * This is the half that could be subtly wrong without any test of the decision noticing: a probe
 * that called only Bindery's **unauthenticated** `/api/v1/health` would report `Ok` for a mistyped
 * key, and one that never called `ping` would report a rejected key for a server that is switched
 * off. Both are asserted here by what was *called*, not only by what came back.
 */
class ConnectionProbeTest {

  private val lidarr = FakeLidarrSource()
  private val bindery = FakeBinderySource()
  private val lidarrFactory = FakeLidarrSourceFactory(lidarr)
  private val binderyFactory = FakeBinderySourceFactory(bindery)

  private suspend fun observe(credentials: app.muplay.integrations.IntegrationCredentials) =
    observeConnection(credentials, lidarrFactory, binderyFactory)

  // ---- lidarr ----------------------------------------------------------------------------------

  @Test
  fun `a lidarr that answers ping and names itself is reachable and identified`() = runTest {
    lidarr.server = lidarrServer("Lidarr")

    val observation = observe(lidarrCredentials())

    assertThat(observation).isEqualTo(ConnectionObservation(reachable = true, identity = "Lidarr", failure = null))
    // Built from the credentials it was handed, which is the one thing a probe could get wrong
    // invisibly -- a client built from a stale or a default credential answers about the wrong host.
    assertThat(lidarrFactory.credentialsSeen).containsExactly(lidarrCredentials())
    assertThat(binderyFactory.credentialsSeen).isEmpty()
  }

  @Test
  fun `a lidarr whose ping fails is unreachable and the key is never even offered`() = runTest {
    lidarr.pingAnswer = false

    val observation = observe(lidarrCredentials())

    assertThat(observation.reachable).isFalse()
    assertThat(observation.failure).isNull()
    // `status()` is the authenticated call. Not making it is the point: there is nothing there to
    // authenticate against, and a 401 invented here would blame the user's key.
    assertThat(lidarr.statusCalls).isZero()
  }

  @Test
  fun `a lidarr that rejects the key is reachable, and carries the refusal`() = runTest {
    lidarr.statusFailWith = LidarrUnauthorizedException()

    val observation = observe(lidarrCredentials())

    assertThat(observation.reachable).isTrue()
    assertThat(observation.identity).isNull()
    assertThat(observation.failure).isInstanceOf(LidarrUnauthorizedException::class.java)
    // And end to end, through the decision this feeds.
    assertThat(
      ConnectionCheck.of(
        app.muplay.integrations.IntegrationService.LIDARR,
        observation.reachable,
        observation.identity,
        observation.failure,
      ),
    ).isEqualTo(ConnectionCheck.Unauthorized)
  }

  @Test
  fun `a sonarr at the lidarr address is reachable and names itself sonarr`() = runTest {
    // `/ping` is byte-identical across every Servarr application, so this is the single most likely
    // real mistake and the reason the identity check exists at all.
    lidarr.server = lidarrServer("Sonarr")

    val observation = observe(lidarrCredentials())

    assertThat(observation.identity).isEqualTo("Sonarr")
    assertThat(lidarr.pingCalls).isEqualTo(1)
  }

  @Test
  fun `any other lidarr failure is carried as itself`() = runTest {
    lidarr.statusFailWith = LidarrHttpException(status = 502)

    assertThat(observe(lidarrCredentials()).failure).isInstanceOf(LidarrHttpException::class.java)
  }

  // ---- bindery ---------------------------------------------------------------------------------

  @Test
  fun `a healthy bindery is only reported ok after an authenticated call`() = runTest {
    val observation = observe(binderyCredentials())

    assertThat(observation).isEqualTo(ConnectionObservation(reachable = true, identity = null, failure = null))
    // **The assertion that matters in this file.** `/api/v1/health` is unauthenticated -- measured,
    // it answers 200 with a wrong key and with none at all -- so a probe that stopped there would
    // tell a user with a mistyped key that everything was fine. `books` is the cheapest
    // authenticated call and one page of one row is all it needs.
    assertThat(bindery.pagesAsked).containsExactly(Triple(null, 1, 0))
    assertThat(lidarrFactory.credentialsSeen).isEmpty()
  }

  @Test
  fun `a bindery that rejects the key is reachable, and carries the refusal`() = runTest {
    bindery.booksFailWith = BinderyUnauthorizedException()

    val observation = observe(binderyCredentials())

    assertThat(observation.reachable).isTrue()
    assertThat(observation.failure).isInstanceOf(BinderyUnauthorizedException::class.java)
  }

  @Test
  fun `a bindery whose health call throws is unreachable`() = runTest {
    // Unlike Lidarr's `ping`, Bindery's `health` throws rather than returning a boolean, so "nothing
    // is listening" arrives here as an exception and must not be reported as a rejected key.
    bindery.healthFailWith = BinderyHttpException(status = 502)

    val observation = observe(binderyCredentials())

    assertThat(observation.reachable).isFalse()
    assertThat(observation.failure).isNull()
    assertThat(bindery.pagesAsked).isEmpty()
  }

  @Test
  fun `something else answering at that path is unreachable rather than ok`() = runTest {
    // `isBindery` is the weakest identity claim there is -- both fields present and `status` ok --
    // and it is still worth making: a proxy's default page or another service's health endpoint
    // must not produce a green tick.
    bindery.server = BinderyServer(status = "degraded", version = "v1.32.1")

    assertThat(observe(binderyCredentials()).reachable).isFalse()
    assertThat(bindery.pagesAsked).isEmpty()
  }

  @Test
  fun `a bindery health body with no version is not a bindery`() = runTest {
    // The other half of `isBindery`, so neither conjunct is the only one anybody exercises.
    bindery.server = BinderyServer(status = "ok", version = "")

    assertThat(observe(binderyCredentials()).reachable).isFalse()
  }

  // ---- cancellation ----------------------------------------------------------------------------

  @Test
  fun `cancelling the caller is never reported as a rejected key or as an unreachable server`() =
    runTest {
      // Three arms, one per `try` on this path, and all three are the same mistake: a user who backs
      // out of the setup screen while a probe is in flight would otherwise be told their key was
      // rejected, or their server unreachable, by a check that never finished. `SyncEngine`'s and
      // `RequestsRepository`'s own rule, met a third time.
      lidarr.statusFailWith = kotlinx.coroutines.CancellationException("the screen went away")
      assertThat(runCatching { observe(lidarrCredentials()) }.exceptionOrNull())
        .isInstanceOf(kotlinx.coroutines.CancellationException::class.java)

      bindery.healthFailWith = kotlinx.coroutines.CancellationException("the screen went away")
      assertThat(runCatching { observe(binderyCredentials()) }.exceptionOrNull())
        .isInstanceOf(kotlinx.coroutines.CancellationException::class.java)

      bindery.healthFailWith = null
      bindery.booksFailWith = kotlinx.coroutines.CancellationException("the screen went away")
      assertThat(runCatching { observe(binderyCredentials()) }.exceptionOrNull())
        .isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

  @Test
  fun `a lidarr that answers but does not name itself is still connected`() = runTest {
    // `appName` is `""` where Lidarr omitted it, and a client that treated "no name" as "not a
    // Lidarr" would refuse a working server. The identity check only fires on a name that is
    // present and different.
    assertThat(
      ConnectionCheck.of(
        app.muplay.integrations.IntegrationService.LIDARR,
        reachable = true,
        identity = null,
        failure = null,
      ),
    ).isEqualTo(ConnectionCheck.Ok("Lidarr"))
  }

  @Test
  fun `neither service's client is built for the other's credentials`() = runTest {
    observe(lidarrCredentials())
    observe(binderyCredentials())

    assertThat(lidarrFactory.credentialsSeen).hasSize(1)
    assertThat(binderyFactory.credentialsSeen).hasSize(1)
    assertThat(bindery.healthCalls).isEqualTo(1)
    assertThat(lidarr.pingCalls).isEqualTo(1)
  }
}
