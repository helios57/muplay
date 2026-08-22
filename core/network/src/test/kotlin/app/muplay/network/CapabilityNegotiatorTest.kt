package app.muplay.network

import app.muplay.model.SubsonicCredentials
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TDD against `MockWebServer`, exactly like `SubsonicClientTest`: every `negotiate()` call below
 * round-trips through a real [SubsonicClient], the real Retrofit + kotlinx.serialization stack it
 * is built on, over a real OkHttp connection, to a real (mock) server socket. Nothing here fakes
 * [SubsonicClient] or [SubsonicApi].
 *
 * Every fixture reused here (`ping-success.json`, `ping-success-legacy-subsonic.json`,
 * `get-open-subsonic-extensions-*.json`) is already proven against the vendored OpenSubsonic spec
 * by `SubsonicClientTest`'s own `... fixture matches the OpenSubsonic spec` tests, which also cover
 * [SubsonicClient.getOpenSubsonicExtensions]'s own DTO-to-`Map` parsing directly. This class does
 * not repeat either kind of proof — it covers [CapabilityNegotiator]'s own orchestration: which of
 * the three tiers fires, and which of the two genuinely different degraded outcomes results, for a
 * given `ping`/`getOpenSubsonicExtensions` pair. See [CapabilityNegotiator]'s own documentation for
 * why those two degraded paths are distinct outcomes, not the same one reached two ways, and why a
 * transport failure is neither.
 */
class CapabilityNegotiatorTest {

  private lateinit var server: MockWebServer
  private lateinit var client: SubsonicClient
  private lateinit var negotiator: CapabilityNegotiator

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    server.start()
    client = SubsonicClient(SubsonicCredentials(server.url("/").toString(), "alice", "sesame"))
    negotiator = CapabilityNegotiator(client)
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  private fun enqueue(body: String, code: Int = 200) {
    server.enqueue(
      MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build(),
    )
  }

  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
      .use { it.readBytes().decodeToString() }

  // --- Tier 1: ping alone decides ---------------------------------------------------------------

  @Test
  fun `a plain Subsonic server negotiates to no extensions without attempting to fetch them`() = runTest {
    enqueue(fixture(PING_LEGACY_FIXTURE))

    val capabilities = negotiator.negotiate()

    assertThat(capabilities.isOpenSubsonic).isFalse()
    assertThat(capabilities.extensions).isEmpty()
    // Only the ping request was ever enqueued/served: negotiate() must not attempt
    // getOpenSubsonicExtensions against a server ping already reported as non-OpenSubsonic. If it
    // did, MockWebServer would have nothing queued to answer with and the call would fail loudly
    // rather than silently proceeding, but asserting the count directly is the more honest check.
    assertThat(server.requestCount).isEqualTo(1)
  }

  // --- Tier 3: a healthy OpenSubsonic server, extensions successfully negotiated ------------------

  @Test
  fun `an OpenSubsonic server negotiates its full advertised extension list`() = runTest {
    enqueue(fixture(PING_SUCCESS_FIXTURE))
    enqueue(fixture(EXTENSIONS_SUCCESS_FIXTURE))

    val capabilities = negotiator.negotiate()

    assertThat(capabilities.isOpenSubsonic).isTrue()
    assertThat(capabilities.extensions).containsExactlyInAnyOrderEntriesOf(
      mapOf(
        "transcodeOffset" to listOf(1),
        "formPost" to listOf(1),
        "songLyrics" to listOf(1, 2),
        "indexBasedQueue" to listOf(1),
        "transcoding" to listOf(1),
        "playbackReport" to listOf(1),
      ),
    )
    assertThat(server.requestCount).isEqualTo(2)
  }

  // The load-bearing test for the design point in the task brief: extension support is a list of
  // versions, not a boolean. Uses real, live-captured Navidrome data (see the task report), not a
  // synthetic fixture — transcodeOffset is genuinely advertised at v1 only.
  @Test
  fun `supports distinguishes versions - true at the advertised version, false at another`() = runTest {
    enqueue(fixture(PING_SUCCESS_FIXTURE))
    enqueue(fixture(EXTENSIONS_SUCCESS_FIXTURE))

    val capabilities = negotiator.negotiate()

    assertThat(capabilities.supports("transcodeOffset", 1)).isTrue()
    assertThat(capabilities.supports("transcodeOffset", 2)).isFalse()
    // songLyrics genuinely supports both v1 and v2 - not just "the first version advertised".
    assertThat(capabilities.supports("songLyrics", 1)).isTrue()
    assertThat(capabilities.supports("songLyrics", 2)).isTrue()
    assertThat(capabilities.supports("songLyrics", 3)).isFalse()
    // Confirmed directly against the live container (see the task report): despite third-party
    // claims, Navidrome 0.63.2 does not advertise apiKeyAuthentication at all.
    assertThat(capabilities.supports("apiKeyAuthentication")).isFalse()
  }

  // The other design point that must survive: supports(name) can never disagree with
  // supports(name, version) just because the server advertised an empty versions array — the
  // schema permits that, and a caller using the unversioned form as a pre-flight gate must not get
  // a contradiction.
  @Test
  fun `supports(name) agrees with supports(name, version) when the server advertises an empty versions array`() =
    runTest {
      enqueue(fixture(PING_SUCCESS_FIXTURE))
      enqueue(fixture(EXTENSIONS_EMPTY_VERSIONS_FIXTURE))

      val capabilities = negotiator.negotiate()

      assertThat(capabilities.supports("futureExtension")).isFalse()
      assertThat(capabilities.supports("futureExtension", 0)).isFalse()
      assertThat(capabilities.supports("futureExtension", 1)).isFalse()
    }

  // --- Tier 2, degraded: OpenSubsonic confirmed by ping, but the extensions call itself fails ----

  @Test
  fun `an OpenSubsonic server whose extensions call fails with a Subsonic-level error degrades to isOpenSubsonic true with no extensions`() =
    runTest {
      enqueue(fixture(PING_SUCCESS_FIXTURE))
      enqueue(fixture(EXTENSIONS_FAILED_FIXTURE))

      val capabilities = negotiator.negotiate()

      // Not "not OpenSubsonic": ping already established this server IS OpenSubsonic,
      // independently of whether it could answer which extensions it supports. Collapsing this
      // into the tier-1 result would make it indistinguishable from a legacy server.
      assertThat(capabilities.isOpenSubsonic).isTrue()
      assertThat(capabilities.extensions).isEmpty()
    }

  @Test
  fun `an OpenSubsonic server whose extensions call 404s degrades to isOpenSubsonic true with no extensions`() =
    runTest {
      enqueue(fixture(PING_SUCCESS_FIXTURE))
      enqueue("""{"error":"not found"}""", code = 404)

      val capabilities = negotiator.negotiate()

      assertThat(capabilities.isOpenSubsonic).isTrue()
      assertThat(capabilities.extensions).isEmpty()
    }

  // --- The failure that must NOT degrade: a genuine transport failure ---------------------------

  @Test
  fun `a transport failure on the extensions call propagates rather than degrading`() = runTest {
    enqueue(fixture(PING_SUCCESS_FIXTURE))
    enqueue("this is not json at all")

    val result = runCatching { negotiator.negotiate() }

    assertThat(result.isFailure).isTrue()
    // Must not degrade into a ServerCapabilities value at all, and specifically must not be
    // mistaken for a SubsonicException - "we could not ask" is not "the server said no", and only
    // the latter justifies degrading (see CapabilityNegotiator's own documentation).
    assertThat(result.exceptionOrNull()).isNotInstanceOf(SubsonicException::class.java)
  }

  // A ping-level failure (e.g. wrong credentials) is a different question from the server's
  // OpenSubsonic-ness entirely and must propagate untouched - negotiate() does not catch anything
  // around the ping() call itself.
  @Test
  fun `a Subsonic-level failure on ping itself propagates, not degrades to no extensions`() = runTest {
    enqueue(fixture(PING_FAILED_FIXTURE))

    val result = runCatching { negotiator.negotiate() }

    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SubsonicErrorException::class.java)
    assertThat((error as SubsonicErrorException).code).isEqualTo(40)
  }

  private companion object {
    const val PING_SUCCESS_FIXTURE = "ping-success.json"
    const val PING_FAILED_FIXTURE = "ping-failed-wrong-credentials.json"
    const val PING_LEGACY_FIXTURE = "ping-success-legacy-subsonic.json"
    const val EXTENSIONS_SUCCESS_FIXTURE = "get-open-subsonic-extensions-success.json"
    const val EXTENSIONS_FAILED_FIXTURE = "get-open-subsonic-extensions-failed.json"
    const val EXTENSIONS_EMPTY_VERSIONS_FIXTURE = "get-open-subsonic-extensions-empty-versions.json"
  }
}
