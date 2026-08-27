package app.muplay.setup

import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ServerInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [SetupUiState.Tagging.prompt]'s two branches.
 *
 * The empty one is the one that matters, and it is why this test exists at all: a freshly created
 * non-admin Navidrome user is granted no libraries, so this exact state is what a Play reviewer
 * following `docs/REVIEWER-ACCESS.md` reaches if the grant step was skipped -- a successful
 * sign-in with nothing on screen and a disabled Continue button. See that document's *zero-library
 * trap* section for the measurement.
 *
 * Tested here rather than on the emulator because that is the whole point of the property living
 * on the state instead of inside `SetupScreen`: the module's `SetupScreenKt` floor is a LINE floor
 * that only a real composition can move, so a branch written in the composable would be a branch
 * no JVM test could reach.
 */
class SetupUiStateTest {

  private val serverInfo =
    ServerInfo(type = "navidrome", serverVersion = "0.63.2", apiVersion = "1.16.1", isOpenSubsonic = true)

  private fun tagging(libraries: List<MusicLibrary>) =
    SetupUiState.Tagging(serverInfo = serverInfo, libraries = libraries, canContinue = false)

  @Test
  fun `with no libraries the prompt names the server-side remedy`() {
    assertThat(tagging(emptyList()).prompt)
      .isEqualTo(
        "This account can see no libraries. Give it access to at least one on the server, " +
          "then press Connect again.",
      )
  }

  @Test
  fun `with libraries the prompt asks what each one is for`() {
    val libraries = listOf(MusicLibrary(id = 1, name = "Music", role = LibraryRole.UNASSIGNED))

    assertThat(tagging(libraries).prompt).isEqualTo("What is each library for?")
  }
}
