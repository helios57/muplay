package app.muplay.setup

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TDD against a hand-written fake for the Subsonic client — no mock framework, no real network.
 * [SetupViewModel]'s `ping` constructor parameter is the seam: a plain suspend function, faked
 * per test with a lambda that returns canned data or throws the exact exception type
 * [SetupViewModel.connect] is supposed to distinguish. Turbine asserts the exact [SetupUiState]
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
    val viewModel = SetupViewModel(ping = {
      readyToRespond.await()
      SERVER_INFO
    })

    viewModel.uiState.test {
      assertThat(awaitItem()).isEqualTo(SetupUiState.Idle)

      viewModel.connect(VALID_URL, "alice", "sesame")
      assertThat(awaitItem()).isEqualTo(SetupUiState.Connecting)

      readyToRespond.complete(Unit)
      assertThat(awaitItem()).isEqualTo(SetupUiState.Success(SERVER_INFO))
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
  }
}
