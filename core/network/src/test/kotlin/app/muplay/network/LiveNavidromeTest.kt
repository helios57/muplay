package app.muplay.network

import app.muplay.model.LibraryRole
import app.muplay.model.SubsonicCredentials
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Exercises [SubsonicClient] against a real, pinned `deluan/navidrome:0.63.2` container — not a
 * fixture, not `MockWebServer`. Docker is not an emulator: the container in
 * `ci/navidrome.compose.yml` starts in 5-11s, well inside tier 1's 10-minute budget, so anything
 * whose subject is genuinely Navidrome's own behaviour belongs here, proven against the real
 * server, rather than deferred to Task 8's emulator tier or left resting on a captured-fixture
 * stand-in ([SubsonicClientTest] already covers the shape-level contract those fixtures encode).
 *
 * `@Tag("live")`: excluded from the default `test`/`testDebugUnitTest` task (see
 * `Testing.kt`'s `configureJUnit5`, which calls `excludeTags("live")` project-wide) — this class
 * needs a real container listening on `localhost:4533`, which is not true for a plain
 * `./gradlew test` in a developer's inner loop, nor in this repo's static-analysis or
 * unit+integration CI jobs. Only the dedicated `liveNavidromeTest` Gradle task (root
 * `build.gradle.kts`, registered against `:core:network` only) includes it, run by the
 * `live-navidrome` job in `.github/workflows/pr.yml` after that job starts the container via
 * `docker compose -f ci/navidrome.compose.yml up -d --wait` and runs `ci/configure-libraries.sh`.
 *
 * Credentials, port and seeded content match those two files exactly:
 * `ND_DEVAUTOCREATEADMINPASSWORD=testpass` (there is no `ND_DEFAULTADMINPASSWORD` — see
 * `ci/navidrome.compose.yml`'s own comment), port `4533`, and two libraries — "Music" and
 * "Audiobooks" — that `ci/configure-libraries.sh` wires up via Navidrome's native REST API because
 * library 1 is permanently pinned to its mount path and cannot be renamed-by-repointing or
 * deleted.
 *
 * The second test below ("... is rejected ...") is this class's proof, demanded by this tier's own
 * brief, that a green [ping success][`ping succeeds against the real container`] test is not
 * evidence on its own: run once locally with `client("testpass")` swapped in where
 * `client("not-the-real-password")` is used below (i.e. asserting success against a password this
 * container was never given) and watch it fail red against the real server before trusting the
 * version committed here — see `task-7-report.md` for that transcript.
 */
@Tag("live")
class LiveNavidromeTest {

  private val baseUrl = "http://localhost:4533"

  private fun client(password: String) =
    SubsonicClient(SubsonicCredentials(baseUrl = baseUrl, username = "admin", password = password))

  @Test
  fun `ping succeeds against the real container`() = runTest {
    val info = client("testpass").ping()

    assertThat(info.type).isEqualTo("navidrome")
    assertThat(info.isOpenSubsonic).isTrue()
  }

  @Test
  fun `ping with a wrong password is rejected by the real server`() = runTest {
    val result = runCatching { client("not-the-real-password").ping() }

    assertThat(result.isFailure).isTrue()
    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SubsonicErrorException::class.java)
    // Subsonic error code 40 ("Wrong username or password") is the real server's own answer here
    // -- not asserted against a captured fixture standing in for one, as SubsonicClientTest's
    // otherwise-identical assertion is (see PING_FAILED_FIXTURE there).
    assertThat((error as SubsonicErrorException).code).isEqualTo(40)
  }

  @Test
  fun `getMusicFolders returns both libraries configure-libraries sh wires up`() = runTest {
    val libraries = client("testpass").getMusicFolders()

    assertThat(libraries.map { it.name }).containsExactlyInAnyOrder("Music", "Audiobooks")
    assertThat(libraries).allMatch { it.role == LibraryRole.UNASSIGNED }
  }
}
