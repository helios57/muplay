package app.muplay.ui

import app.muplay.model.LibraryRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Where the app opens, over the four states a device can actually be in.
 *
 * This decision had **no test of any kind** before this file. It is one `if` in an `init` block,
 * and it was wrong -- see the third case below -- for as long as the app has had a setup screen.
 *
 * `Dispatchers.setMain`, because `viewModelScope` is `Dispatchers.Main` and `runTest`'s scheduler
 * cannot advance work that is not on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartDestinationViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach fun tearDown() = Dispatchers.resetMain()

  private class FakeProgress(
    private val credentials: Boolean,
    private val roles: List<LibraryRole>,
  ) : SetupProgress {
    override suspend fun hasCredentials(): Boolean = credentials
    override suspend fun libraryRoles(): List<LibraryRole> = roles
  }

  private fun TestScope.destinationFor(
    credentials: Boolean,
    roles: List<LibraryRole>,
  ): StartDestination {
    val viewModel = StartDestinationViewModel(FakeProgress(credentials, roles))
    advanceUntilIdle()
    return viewModel.startDestination.value
  }

  @Test
  fun `nothing stored at all opens setup`() = runTest {
    assertThat(destinationFor(credentials = false, roles = emptyList()))
      .isEqualTo(StartDestination.Setup)
  }

  @Test
  fun `credentials and every library tagged opens the library`() = runTest {
    assertThat(destinationFor(credentials = true, roles = listOf(LibraryRole.MUSIC, LibraryRole.AUDIOBOOKS)))
      .isEqualTo(StartDestination.Library)
  }

  /**
   * **The defect.** `SetupViewModel.connect` stores the credentials before it fetches the
   * libraries -- it must, because `refreshFromServer` reads them back out of the store -- so a
   * first run whose fetch then failed left credentials stored and the library mirror empty.
   *
   * The old predicate was `configured && !hasUnassignedLibraries()`, and
   * `hasUnassignedLibraries()` is `idsWithRole(UNASSIGNED).isNotEmpty()`. Over an empty table that
   * is `false`, so the negation is `true` and this state opened the **library** screen: empty,
   * with no route back to setup anywhere in the app. Reinstalling was the only way out.
   *
   * This is the emptiness trap `SetupViewModel.continueToLibrary`'s own doc names, at a second
   * site that never got the guard.
   */
  @Test
  fun `credentials stored but no library ever synced opens setup rather than an empty library`() = runTest {
    assertThat(destinationFor(credentials = true, roles = emptyList()))
      .isEqualTo(StartDestination.Setup)
  }

  @Test
  fun `a library still untagged opens setup`() = runTest {
    assertThat(destinationFor(credentials = true, roles = listOf(LibraryRole.MUSIC, LibraryRole.UNASSIGNED)))
      .isEqualTo(StartDestination.Setup)
  }

  /**
   * Libraries without credentials cannot happen through any path the app has -- but the predicate
   * reads both facts, so both arms of the conjunction are worth pinning. Without this, deleting
   * `progress.hasCredentials() &&` leaves every other case in this file green.
   */
  @Test
  fun `tagged libraries with the credentials gone opens setup`() = runTest {
    assertThat(destinationFor(credentials = false, roles = listOf(LibraryRole.MUSIC)))
      .isEqualTo(StartDestination.Setup)
  }
}
