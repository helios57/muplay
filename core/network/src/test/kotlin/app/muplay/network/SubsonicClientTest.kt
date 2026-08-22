package app.muplay.network

import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.SubsonicCredentials
import app.muplay.testing.OpenApiFixtureValidator
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
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

  private companion object {
    const val PING_SUCCESS_FIXTURE = "ping-success.json"
    const val PING_FAILED_FIXTURE = "ping-failed-wrong-credentials.json"
    const val MUSIC_FOLDERS_SUCCESS_FIXTURE = "get-music-folders-success.json"
    const val MUSIC_FOLDER_MISSING_NAME_FIXTURE = "get-music-folders-missing-name.json"
  }
}
