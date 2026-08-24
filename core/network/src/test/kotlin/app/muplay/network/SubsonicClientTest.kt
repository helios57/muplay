package app.muplay.network

import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.SubsonicCredentials
import app.muplay.testing.OpenApiFixtureValidator
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TDD against `MockWebServer`: real HTTP over a real socket, through the real Retrofit +
 * kotlinx.serialization stack [SubsonicClient] is built on. Nothing here fakes [SubsonicApi] or
 * substitutes a parser — every `client.ping()`/`client.getMusicFolders()` call below round-trips
 * through an actual `Retrofit` instance built by `SubsonicClient.buildApi`, over an actual OkHttp
 * connection, to an actual (mock) server socket.
 *
 * Every fixture under `fixture(...)` — except [MUSIC_FOLDER_MISSING_NAME_FIXTURE], see its own
 * note below — is a response captured directly from a running `deluan/navidrome:0.63.2` container
 * (the exact image and version `ci/navidrome.compose.yml` pins), via `curl` against `/rest/ping`
 * and `/rest/getMusicFolders` with valid, wrong, and missing credentials, not hand-invented JSON.
 * [OpenApiFixtureValidator.assertValid] proves each one against the vendored OpenSubsonic spec
 * before the rest of this class trusts it as a stand-in for a real server response — see the
 * `... fixture matches the OpenSubsonic spec` tests.
 *
 * The load-bearing test is "a status failed body becomes a typed error, not a success": Subsonic
 * answers a failed command with `HTTP 200`, not a 4xx/5xx — confirmed on the wire, not just read
 * off the spec (the captured wrong-password fixture below has no HTTP-level indication of failure
 * at all). A client that trusts the transport status over the body would read that as success,
 * including on the one command (`ping`) most likely to be a login attempt. The two tests right
 * after it ("status failed with no error object ...", "an error object with status ok ...") are
 * synthetic, deliberately non-compliant bodies that prove the failure check is a real `OR`, not
 * just an `AND` that happens to pass on a compliant server.
 */
class SubsonicClientTest {

  private lateinit var server: MockWebServer
  private lateinit var client: SubsonicClient

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    server.start()
    client = SubsonicClient(SubsonicCredentials(server.url("/").toString(), "alice", "sesame"))
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

  // --- Fixtures proven against the external oracle before anything below trusts them ---------

  @Test
  fun `ping success fixture matches the OpenSubsonic spec`() {
    OpenApiFixtureValidator.assertValid("/rest/ping", fixture(PING_SUCCESS_FIXTURE))
  }

  @Test
  fun `ping failed fixture matches the OpenSubsonic spec`() {
    OpenApiFixtureValidator.assertValid("/rest/ping", fixture(PING_FAILED_FIXTURE))
  }

  @Test
  fun `getMusicFolders success fixture matches the OpenSubsonic spec`() {
    OpenApiFixtureValidator.assertValid("/rest/getMusicFolders", fixture(MUSIC_FOLDERS_SUCCESS_FIXTURE))
  }

  // Spec-derived, not server-captured: the MusicFolder schema makes `name` optional
  // (`required: ["id"]` only), but Navidrome's own admin UI requires every library to have a name,
  // so no real capture exercises a nameless folder. Still a real, spec-permitted shape, not an
  // invented one — the missing field is exactly what the spec says is allowed to be missing.
  @Test
  fun `getMusicFolders missing-name fixture matches the OpenSubsonic spec`() {
    OpenApiFixtureValidator.assertValid("/rest/getMusicFolders", fixture(MUSIC_FOLDER_MISSING_NAME_FIXTURE))
  }

  @Test
  fun `getOpenSubsonicExtensions success fixture matches the OpenSubsonic spec`() {
    OpenApiFixtureValidator.assertValid(
      "/rest/getOpenSubsonicExtensions",
      fixture(EXTENSIONS_SUCCESS_FIXTURE),
    )
  }

  // Synthetic, not server-captured: confirmed empirically (see Task 5's report) that Navidrome's
  // real getOpenSubsonicExtensions endpoint requires no credentials at all (its spec entry
  // declares "security": []) and so never actually produces a failure response — this fixture
  // reuses the shared, spec-defined SubsonicFailureResponse shape (the same one
  // ping-failed-wrong-credentials.json exercises for a different command) as a stand-in for a
  // hypothetical OpenSubsonic-compliant server that does reject this call.
  @Test
  fun `getOpenSubsonicExtensions failed fixture matches the OpenSubsonic spec`() {
    OpenApiFixtureValidator.assertValid(
      "/rest/getOpenSubsonicExtensions",
      fixture(EXTENSIONS_FAILED_FIXTURE),
    )
  }

  // Spec-derived, not server-captured: the OpenSubsonicExtension schema places no `minItems` on
  // `versions`, so an empty array is a valid — if never actually observed from live Navidrome —
  // shape. Backs ServerCapabilities.supports's empty-array guarantee (see CapabilityNegotiatorTest).
  @Test
  fun `getOpenSubsonicExtensions empty-versions fixture matches the OpenSubsonic spec`() {
    OpenApiFixtureValidator.assertValid(
      "/rest/getOpenSubsonicExtensions",
      fixture(EXTENSIONS_EMPTY_VERSIONS_FIXTURE),
    )
  }

  // Synthetic, not server-captured: every real Navidrome ping in this suite reports
  // "openSubsonic": true regardless of client id or requested protocol version (confirmed
  // empirically against the real container — see Task 5's report), so a "plain Subsonic server"
  // response cannot be captured live from it. `openSubsonic: false` is still a fully spec-valid
  // value (a required boolean field, not a required-true one), so this fixture is spec-derived
  // rather than invented, and `type`/`serverVersion` use a distinct, non-Navidrome value so it
  // cannot be mistaken for a real Navidrome capture.
  @Test
  fun `ping success legacy-subsonic fixture matches the OpenSubsonic spec`() {
    OpenApiFixtureValidator.assertValid("/rest/ping", fixture(PING_LEGACY_FIXTURE))
  }

  // --- SubsonicClient behavior, using those same proven fixtures as the server's response -----

  @Test
  fun `ping parses server identity`() = runTest {
    enqueue(fixture(PING_SUCCESS_FIXTURE))

    val info = client.ping()

    assertThat(info.type).isEqualTo("navidrome")
    assertThat(info.apiVersion).isEqualTo("1.16.1")
    assertThat(info.serverVersion).isEqualTo("0.63.2 (be10f89c)")
    assertThat(info.isOpenSubsonic).isTrue()
  }

  @Test
  fun `a status failed body becomes a typed error, not a success`() = runTest {
    enqueue(fixture(PING_FAILED_FIXTURE)) // HTTP 200, "status":"failed", error.code 40

    val result = runCatching { client.ping() }

    assertThat(result.isFailure).isTrue()
    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SubsonicErrorException::class.java)
    assertThat((error as SubsonicErrorException).code).isEqualTo(40)
  }

  // The two tests below are deliberately non-compliant with the OpenSubsonic schema (which
  // requires "failed" + error together) — synthetic, not server captures, and OpenApiFixtureValidator
  // would (correctly) reject both as fixtures. They exist to prove the OR, not the AND: detecting
  // failure on `error != null` OR `status == "failed"` independently, exactly so a non-compliant
  // server or a proxy that mangles one of the two still cannot read as success.

  @Test
  fun `status failed with no error object still becomes a typed error`() = runTest {
    enqueue("""{"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}""")

    val result = runCatching { client.ping() }

    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SubsonicErrorException::class.java)
    // No error.code to read, so the generic-error fallback (0) is used rather than inventing one.
    assertThat((error as SubsonicErrorException).code).isEqualTo(0)
  }

  @Test
  fun `an error object with status ok still becomes a typed error`() = runTest {
    enqueue(
      """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true,"error":{"code":50,"message":"Not authorized"}}}""",
    )

    val result = runCatching { client.ping() }

    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SubsonicErrorException::class.java)
    assertThat((error as SubsonicErrorException).code).isEqualTo(50)
  }

  @Test
  fun `an unparseable body does not read as a Subsonic error`() = runTest {
    enqueue("this is not json at all")

    val result = runCatching { client.ping() }

    assertThat(result.isFailure).isTrue()
    // Must not degrade into any SubsonicException, and specifically not SubsonicErrorException —
    // "we could not ask" and "the server said no" are different failures, and only the second one
    // is a Subsonic-level error.
    assertThat(result.exceptionOrNull()).isNotInstanceOf(SubsonicException::class.java)
  }

  @Test
  fun `an HTTP-level failure becomes a typed SubsonicHttpException`() = runTest {
    enqueue("""{"error":"forbidden"}""", code = 403)

    val result = runCatching { client.ping() }

    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SubsonicHttpException::class.java)
    assertThat((error as SubsonicHttpException).status).isEqualTo(403)
  }

  @Test
  fun `getMusicFolders maps libraries with role UNASSIGNED`() = runTest {
    enqueue(fixture(MUSIC_FOLDERS_SUCCESS_FIXTURE))

    val libraries = client.getMusicFolders()

    assertThat(libraries).containsExactly(
      MusicLibrary(id = 1, name = "Music", role = LibraryRole.UNASSIGNED),
      MusicLibrary(id = 2, name = "Audiobooks", role = LibraryRole.UNASSIGNED),
    )
  }

  @Test
  fun `a musicFolder without a name gets a stable fallback`() = runTest {
    enqueue(fixture(MUSIC_FOLDER_MISSING_NAME_FIXTURE))

    val libraries = client.getMusicFolders()

    assertThat(libraries).containsExactly(
      MusicLibrary(id = 7, name = "Library 7", role = LibraryRole.UNASSIGNED),
    )
  }

  @Test
  fun `ping reports isOpenSubsonic false for a legacy Subsonic server`() = runTest {
    enqueue(fixture(PING_LEGACY_FIXTURE))

    val info = client.ping()

    assertThat(info.isOpenSubsonic).isFalse()
    assertThat(info.type).isEqualTo("libresonic")
  }

  @Test
  fun `getOpenSubsonicExtensions maps every advertised extension to its versions`() = runTest {
    enqueue(fixture(EXTENSIONS_SUCCESS_FIXTURE))

    val extensions = client.getOpenSubsonicExtensions()

    assertThat(extensions).containsExactlyInAnyOrderEntriesOf(
      mapOf(
        "transcodeOffset" to listOf(1),
        "formPost" to listOf(1),
        "songLyrics" to listOf(1, 2),
        "indexBasedQueue" to listOf(1),
        "transcoding" to listOf(1),
        "playbackReport" to listOf(1),
      ),
    )
    // Confirmed directly against the live container (see Task 5's report): despite third-party
    // claims, Navidrome 0.63.2 does not advertise this extension.
    assertThat(extensions).doesNotContainKey("apiKeyAuthentication")
  }

  @Test
  fun `getOpenSubsonicExtensions surfaces a Subsonic-level failure as a typed error`() = runTest {
    enqueue(fixture(EXTENSIONS_FAILED_FIXTURE))

    val result = runCatching { client.getOpenSubsonicExtensions() }

    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SubsonicErrorException::class.java)
    assertThat((error as SubsonicErrorException).code).isEqualTo(0)
  }

  @Test
  fun `getOpenSubsonicExtensions preserves an advertised extension's empty versions array`() = runTest {
    enqueue(fixture(EXTENSIONS_EMPTY_VERSIONS_FIXTURE))

    val extensions = client.getOpenSubsonicExtensions()

    assertThat(extensions).containsExactly(entry("futureExtension", emptyList()))
  }

  // --- Defensive handling of a non-compliant server, and buildApi's own trailing-slash edge case
  //     (Task 7's real, measured branch-coverage floor is what surfaced every gap below — each one
  //     is a genuine, previously-untested path in SubsonicClient's own code, not a synthetic
  //     exercise added purely to move a number.) -------------------------------------------------

  // The OpenSubsonic spec requires "type"/"serverVersion"/"version"/"openSubsonic" on every
  // response (see SubsonicResponseBody's own doc), so OpenApiFixtureValidator would (correctly)
  // reject a captured fixture missing them — this body is deliberately synthetic and non-compliant,
  // the same pattern the two "status failed ..." tests above already use, to prove SubsonicClient's
  // own defensive fallbacks (orEmpty()/?:) rather than assume every real server honours the spec.
  @Test
  fun `ping tolerates a non-compliant response missing every optional field`() = runTest {
    enqueue("""{"subsonic-response":{"status":"ok"}}""")

    val info = client.ping()

    assertThat(info.type).isEmpty()
    assertThat(info.serverVersion).isEmpty()
    assertThat(info.apiVersion).isEmpty()
    assertThat(info.isOpenSubsonic).isFalse()
  }

  // "musicFolders" absent entirely (as opposed to present but empty) is a stronger claim than the
  // spec makes about a compliant getMusicFolders response, but SubsonicClient's own `?.` chain
  // treats it the same as "no folders" rather than throwing an NPE — this proves that fallback
  // directly, synthetic and non-compliant for the same reason as the test above.
  @Test
  fun `getMusicFolders tolerates a response missing the musicFolders object entirely`() = runTest {
    enqueue(
      """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}""",
    )

    val libraries = client.getMusicFolders()

    assertThat(libraries).isEmpty()
  }

  // Same defensive fallback as the two tests above, for getOpenSubsonicExtensions's own orEmpty().
  @Test
  fun `getOpenSubsonicExtensions tolerates a response missing the extensions field entirely`() = runTest {
    enqueue(
      """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}""",
    )

    val extensions = client.getOpenSubsonicExtensions()

    assertThat(extensions).isEmpty()
  }

  // --- What goes out on the wire ----------------------------------------------------------------
  //
  // Everything above this line asserts what the client does with a *response*. Until these tests
  // existed, nothing in the entire build asserted what it *sends* -- `grep -rn takeRequest` across
  // the repository returned nothing -- and MockWebServer's default dispatcher answers any request
  // with the next queued response regardless of path, method or query string, so a real socket
  // proved the response was parsed and said nothing about the request. Two mutations, both
  // measured before these tests were written:
  //
  //   - `SubsonicAuth.authParams(...) = emptyMap()` (zero credentials on the wire): all 81 JVM
  //     tests and the whole Tier 1 coverage gate stayed green. Only the live container caught it.
  //   - `@GET("rest/getOpenSubsonicExtensions")` -> `@GET("rest/thisEndpointDoesNotExist")`:
  //     *nothing in the build* caught it, live job included, because `LiveNavidromeTest` covers
  //     only ping and getMusicFolders and that command has no production caller yet.
  //
  // ...while `:core:network` reported 100% branch coverage against a green 0.90 floor. Coverage
  // measures execution, not assertion.
  //
  // One test per command that exists today. Plan 2 adds ten more commands and the
  // `musicFolderId` parameter -- the library-scoped-shuffle feature's only mechanism, and a
  // *request* parameter -- and each of those needs the same treatment as it lands.

  @Test
  fun `ping requests the ping path with full Subsonic authentication`() = runTest {
    enqueue(fixture(PING_SUCCESS_FIXTURE))

    client.ping()

    assertAuthenticatedRequestTo("/rest/ping")
  }

  @Test
  fun `getMusicFolders requests the getMusicFolders path with full Subsonic authentication`() = runTest {
    enqueue(fixture(MUSIC_FOLDERS_SUCCESS_FIXTURE))

    client.getMusicFolders()

    assertAuthenticatedRequestTo("/rest/getMusicFolders")
  }

  @Test
  fun `getOpenSubsonicExtensions requests its own path with full Subsonic authentication`() = runTest {
    enqueue(fixture(EXTENSIONS_SUCCESS_FIXTURE))

    client.getOpenSubsonicExtensions()

    assertAuthenticatedRequestTo("/rest/getOpenSubsonicExtensions")
  }

  // The salt is the one auth parameter that must differ per request. `SubsonicAuth`'s own doc says
  // so ("Production wiring must generate a fresh salt per request with SecureRandom -- never cache
  // or reuse one") and `SubsonicClient.generateSalt` implements it, but until this test nothing
  // checked it on the wire: a cached salt turns token auth into a replayable constant and every
  // other assertion in this section would still pass.
  @Test
  fun `every request carries a fresh salt`() = runTest {
    enqueue(fixture(PING_SUCCESS_FIXTURE))
    enqueue(fixture(PING_SUCCESS_FIXTURE))

    client.ping()
    client.ping()

    val firstSalt = server.takeRequest().url.queryParameter("s")
    val secondSalt = server.takeRequest().url.queryParameter("s")
    assertThat(firstSalt).isNotBlank()
    assertThat(secondSalt).isNotBlank()
    assertThat(firstSalt).isNotEqualTo(secondSalt)
  }

  /**
   * Asserts that the single request this test made went to [expectedPath] carrying every parameter
   * the Subsonic token-authentication scheme and the OpenSubsonic spec require, and nothing that
   * would leak the password.
   *
   * `v` and `c` are asserted as **literals**, deliberately, rather than through
   * `SubsonicAuth.PROTOCOL_VERSION`/`CLIENT_NAME`: they are constraints imposed from outside this
   * codebase, and reading them from the constant under test would let a change to that constant
   * pass unnoticed. `c=MuPlay` in particular has a real consequence -- Navidrome strips the whole
   * OpenSubsonic field block from every response for client ids matching its `LegacyClients` /
   * `MinimalClients` defaults, so this value is load-bearing, not decorative.
   *
   * `t` is checked against a digest this test computes itself from the salt actually sent, so the
   * assertion is on the bytes on the wire rather than on `authParams()` agreeing with itself.
   */
  private fun assertAuthenticatedRequestTo(expectedPath: String) {
    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("GET")

    val url = request.url
    assertThat(url.encodedPath).isEqualTo(expectedPath)

    val salt = url.queryParameter("s")
    assertThat(salt).describedAs("salt (s)").isNotNull().matches("[0-9a-f]{16}")

    assertThat(url.queryParameter("u")).isEqualTo("alice")
    assertThat(url.queryParameter("t")).isEqualTo(md5Hex("sesame" + salt))
    assertThat(url.queryParameter("v")).isEqualTo("1.16.1")
    assertThat(url.queryParameter("c")).isEqualTo("MuPlay")
    assertThat(url.queryParameter("f")).isEqualTo("json")

    // Plaintext auth is a different Subsonic scheme and this client must never fall back to it.
    assertThat(url.queryParameter("p")).describedAs("plaintext password parameter").isNull()
    assertThat(url.query).describedAs("query string").doesNotContain("sesame")
  }

  /**
   * `hex(md5(utf8(input)))`, computed here rather than by calling [SubsonicAuth.token], so that
   * this file is an independent oracle for what the client puts on the wire instead of asserting
   * the implementation against itself.
   */
  private fun md5Hex(input: String): String =
    MessageDigest.getInstance("MD5")
      .digest(input.toByteArray(StandardCharsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte) }

  // Every other test in this class builds its client from MockWebServer's own server.url("/"),
  // which HttpUrl always normalizes with a trailing slash — so buildApi's own
  // `if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"` branch had only ever taken its "already
  // has one" path. A real user-entered server URL (this project's own SetupViewModel takes one
  // directly, with no normalization of its own) very often will not have a trailing slash, so this
  // is a real path, not a contrived one.
  @Test
  fun `ping succeeds when baseUrl has no trailing slash`() = runTest {
    enqueue(fixture(PING_SUCCESS_FIXTURE))
    val noTrailingSlashUrl = server.url("/").toString().removeSuffix("/")
    val clientWithoutTrailingSlash =
      SubsonicClient(SubsonicCredentials(noTrailingSlashUrl, "alice", "sesame"))

    val info = clientWithoutTrailingSlash.ping()

    assertThat(info.type).isEqualTo("navidrome")
  }

  private companion object {
    const val PING_SUCCESS_FIXTURE = "ping-success.json"
    const val PING_FAILED_FIXTURE = "ping-failed-wrong-credentials.json"
    const val PING_LEGACY_FIXTURE = "ping-success-legacy-subsonic.json"
    const val MUSIC_FOLDERS_SUCCESS_FIXTURE = "get-music-folders-success.json"
    const val MUSIC_FOLDER_MISSING_NAME_FIXTURE = "get-music-folders-missing-name.json"
    const val EXTENSIONS_SUCCESS_FIXTURE = "get-open-subsonic-extensions-success.json"
    const val EXTENSIONS_FAILED_FIXTURE = "get-open-subsonic-extensions-failed.json"
    const val EXTENSIONS_EMPTY_VERSIONS_FIXTURE = "get-open-subsonic-extensions-empty-versions.json"
  }
}
