package app.muplay.setup

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ServerInfo
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicErrorException
import app.muplay.network.SubsonicHttpException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TDD against a hand-written fake for the Subsonic client — no mock framework, no real network.
 * [SetupViewModel]'s `ping` and `fetchLibraries` constructor parameters are the seams: plain
 * suspend functions, faked per test with lambdas that return canned data or throw the exact
 * exception type [SetupViewModel.connect] is supposed to distinguish — including a lambda that
 * fails the test outright if it is called at all, which is how the ordering between the two is
 * asserted. Turbine asserts the exact [SetupUiState]
 * sequence a `connect()` call produces, in particular that [SetupUiState.Connecting] is a real,
 * separately observable state and not skipped straight to the terminal one.
 *
 * `Dispatchers.Main` is replaced with [UnconfinedTestDispatcher] so `viewModelScope.launch` runs
 * eagerly: a fake `ping` that suspends on a [CompletableDeferred] genuinely pauses the coroutine
 * at that point (letting a test observe [SetupUiState.Connecting] in between), while a fake that
 * returns or throws immediately runs to completion synchronously, so tests that only care about
 * the final state can read `uiState.value` directly without any manual dispatcher advancing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

  @BeforeEach
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @AfterEach
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `initial state is Idle`() {
    val viewModel = SetupViewModel(ping = failIfCalled())

    assertThat(viewModel.uiState.value).isEqualTo(SetupUiState.Idle)
  }

  @Test
  fun `a successful ping moves from Connecting to Success, not straight to the terminal state`() = runTest {
    val readyToRespond = CompletableDeferred<Unit>()
    val viewModel = SetupViewModel(
      ping = {
        readyToRespond.await()
        SERVER_INFO
      },
      fetchLibraries = { LIBRARIES },
    )

    viewModel.uiState.test {
      assertThat(awaitItem()).isEqualTo(SetupUiState.Idle)

      viewModel.connect(VALID_URL, "alice", "sesame")
      assertThat(awaitItem()).isEqualTo(SetupUiState.Connecting)

      readyToRespond.complete(Unit)
      assertThat(awaitItem()).isEqualTo(SetupUiState.Success(SERVER_INFO, LIBRARIES))
    }
  }

  @Test
  fun `wrong credentials produce a Rejected failure carrying the Subsonic error code`() = runTest {
    val viewModel = SetupViewModel(
      ping = { throw SubsonicErrorException(40, "Wrong username or password") },
    )

    viewModel.connect(VALID_URL, "alice", "wrong")

    assertThat(viewModel.uiState.value).isEqualTo(
      SetupUiState.Failure(SetupFailureReason.Rejected(code = 40, detail = "Wrong username or password")),
    )
  }

  @Test
  fun `an unreachable server produces Unreachable, distinct from a Rejected failure`() = runTest {
    val viewModel = SetupViewModel(ping = { throw IOException("Failed to connect") })

    viewModel.connect(VALID_URL, "alice", "sesame")

    val state = viewModel.uiState.value
    assertThat(state).isEqualTo(SetupUiState.Failure(SetupFailureReason.Unreachable))
    assertThat((state as SetupUiState.Failure).reason)
      .isNotInstanceOf(SetupFailureReason.Rejected::class.java)
  }

  @Test
  fun `an HTTP-level failure is also reported as Rejected, not Unreachable`() = runTest {
    val viewModel = SetupViewModel(ping = { throw SubsonicHttpException(404) })

    viewModel.connect(VALID_URL, "alice", "sesame")

    assertThat(viewModel.uiState.value).isEqualTo(
      SetupUiState.Failure(SetupFailureReason.Rejected(code = 404, detail = "Subsonic HTTP error 404")),
    )
  }

  @Test
  fun `a blank URL is rejected before any network call`() = runTest {
    val viewModel = SetupViewModel(ping = failIfCalled())

    viewModel.connect("   ", "alice", "sesame")

    assertThat(viewModel.uiState.value).isEqualTo(SetupUiState.Failure(SetupFailureReason.InvalidUrl))
  }

  @Test
  fun `a malformed URL is rejected before any network call`() = runTest {
    val viewModel = SetupViewModel(ping = failIfCalled())

    viewModel.connect("not a url", "alice", "sesame")

    assertThat(viewModel.uiState.value).isEqualTo(SetupUiState.Failure(SetupFailureReason.InvalidUrl))
  }

  // Proves the catch clause ordering in connect(): a cancelled coroutine must not be
  // misreported as SetupFailureReason.Unreachable. Cancels the ViewModel's own scope directly
  // (not the test's), so this exercises exactly the coroutine connect() launches, not Turbine's.
  @Test
  fun `cancellation is not reported as a failure state`() = runTest {
    val neverResponds = CompletableDeferred<ServerInfo>()
    val viewModel = SetupViewModel(ping = { neverResponds.await() })

    viewModel.connect(VALID_URL, "alice", "sesame")
    assertThat(viewModel.uiState.value).isEqualTo(SetupUiState.Connecting)

    viewModel.viewModelScope.cancel()

    assertThat(viewModel.uiState.value).isEqualTo(SetupUiState.Connecting)
  }

  // Every other test above injects a fake `ping`, so SetupViewModel's *default* constructor
  // parameter -- a real `SubsonicClient(credentials).ping()` call -- never actually runs; that
  // gap is exactly what surfaced as `SetupViewModel$1` (the compiled default-lambda class) sitting
  // at 0% in Task 7's per-module coverage measurement. This exercises the real default wiring
  // end-to-end, pointed at a real but immediately-refused TCP port (127.0.0.1:1, a reserved port
  // nothing ever listens on) rather than a real Navidrome container: a refused connection fails
  // fast and deterministically -- no live server, no timeout, no flakiness -- while still routing
  // through the genuine `SubsonicClient` + Retrofit + OkHttp stack this seam exists to bypass in
  // every other test.
  @Test
  fun `the default ping wiring performs a real network call that surfaces as Unreachable`() = runTest {
    val viewModel = SetupViewModel()

    viewModel.uiState.test {
      assertThat(awaitItem()).isEqualTo(SetupUiState.Idle)

      viewModel.connect("http://127.0.0.1:1", "alice", "sesame")
      assertThat(awaitItem()).isEqualTo(SetupUiState.Connecting)
      assertThat(awaitItem()).isEqualTo(SetupUiState.Failure(SetupFailureReason.Unreachable))
    }
  }

  // Companion to the test above: this exercises *both* default lambdas' success path -- the other
  // half of each compiled state machine that a refused-connection test alone cannot reach (a
  // suspend lambda's dispatch differs between "resumed with a value" and "resumed with an
  // exception"). Real socket, real Retrofit/OkHttp stack, same MockWebServer stance
  // core/network's SubsonicClientTest documents -- not a fake standing in for the network. Two
  // responses are enqueued because a successful connect makes two calls, `ping` then
  // `getMusicFolders`, in that order.
  @Test
  fun `the default ping and library wiring performs real network calls that succeed`() = runTest {
    val server = MockWebServer()
    server.start()
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(
          """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",""" +
            """"serverVersion":"0.63.2","openSubsonic":true}}""",
        )
        .build(),
    )
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(
          """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",""" +
            """"serverVersion":"0.63.2","openSubsonic":true,"musicFolders":{"musicFolder":""" +
            """[{"id":1,"name":"Music"},{"id":2,"name":"Audiobooks"}]}}}""",
        )
        .build(),
    )

    try {
      val viewModel = SetupViewModel()

      viewModel.uiState.test {
        assertThat(awaitItem()).isEqualTo(SetupUiState.Idle)

        viewModel.connect(server.url("/").toString(), "alice", "sesame")
        assertThat(awaitItem()).isEqualTo(SetupUiState.Connecting)

        val success = awaitItem()
        assertThat(success).isInstanceOf(SetupUiState.Success::class.java)
        assertThat((success as SetupUiState.Success).serverInfo.type).isEqualTo("navidrome")
        assertThat(success.libraries).isEqualTo(LIBRARIES)
      }
    } finally {
      server.close()
    }
  }

  // The assertion Task 8's emulator journey makes on-device ("both seeded libraries are listed by
  // name"), made here at the state-machine level: Success is not reached from `ping` alone, it
  // carries what `getMusicFolders` returned, in order.
  @Test
  fun `a successful connect reports the server's libraries alongside its identity`() = runTest {
    val viewModel = SetupViewModel(ping = { SERVER_INFO }, fetchLibraries = { LIBRARIES })

    viewModel.connect(VALID_URL, "alice", "sesame")

    assertThat(viewModel.uiState.value).isEqualTo(SetupUiState.Success(SERVER_INFO, LIBRARIES))
  }

  // The libraries call is part of connecting, not an optional extra afterwards: a server that
  // answers `ping` but rejects `getMusicFolders` has not produced a usable setup, so it must
  // report the rejection rather than a Success carrying no libraries -- which would be
  // indistinguishable from a server that genuinely has none.
  @Test
  fun `a server that answers ping but rejects getMusicFolders reports the rejection`() = runTest {
    val viewModel = SetupViewModel(
      ping = { SERVER_INFO },
      fetchLibraries = { throw SubsonicErrorException(50, "User is not authorized") },
    )

    viewModel.connect(VALID_URL, "alice", "sesame")

    assertThat(viewModel.uiState.value).isEqualTo(
      SetupUiState.Failure(SetupFailureReason.Rejected(code = 50, detail = "User is not authorized")),
    )
  }

  // Ordering, not just presence: `fetchLibraries` must not run when `ping` already failed. A
  // fake that fails the test if called is the whole assertion here.
  @Test
  fun `libraries are not fetched when ping itself fails`() = runTest {
    val viewModel = SetupViewModel(
      ping = { throw SubsonicErrorException(40, "Wrong username or password") },
      fetchLibraries = { credentials -> error("getMusicFolders must not be called for $credentials") },
    )

    viewModel.connect(VALID_URL, "alice", "wrong")

    assertThat(viewModel.uiState.value).isEqualTo(
      SetupUiState.Failure(SetupFailureReason.Rejected(code = 40, detail = "Wrong username or password")),
    )
  }

  private fun failIfCalled(): suspend (SubsonicCredentials) -> ServerInfo =
    { credentials -> error("ping must not be called for $credentials") }

  private companion object {
    const val VALID_URL = "https://navidrome.example.com"
    val SERVER_INFO = ServerInfo(
      type = "navidrome",
      serverVersion = "0.63.2 (be10f89c)",
      apiVersion = "1.16.1",
      isOpenSubsonic = true,
    )

    // The two libraries ci/configure-libraries.sh seeds into the real container, with the role
    // SubsonicClient.getMusicFolders actually returns: the Subsonic response carries nothing that
    // says what a folder is *for*, so everything is UNASSIGNED here. Inferring MUSIC/AUDIOBOOKS
    // from a name is a later plan's problem, deliberately not this one's.
    val LIBRARIES = listOf(
      MusicLibrary(id = 1, name = "Music", role = LibraryRole.UNASSIGNED),
      MusicLibrary(id = 2, name = "Audiobooks", role = LibraryRole.UNASSIGNED),
    )
  }
}
