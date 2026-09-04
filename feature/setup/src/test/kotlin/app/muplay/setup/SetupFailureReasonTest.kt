package app.muplay.setup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `toMessage()` is a plain `when` with no Compose or Android dependency — nothing about it
 * requires an emulator, so it is tested directly here rather than left for Task 8's emulator
 * journey (which covers `SetupScreen`'s own Compose-dependent rendering branches instead).
 *
 * Covers all four [SetupFailureReason] members, plus both sides of [SetupFailureReason.Rejected]'s
 * own `detail` null/non-null branch, so every branch `toMessage()` owns is genuinely exercised.
 */
class SetupFailureReasonTest {

  @Test
  fun `InvalidUrl asks for a valid server URL`() {
    assertThat(SetupFailureReason.InvalidUrl.toMessage())
      .isEqualTo("Enter a valid server URL, e.g. https://music.example.com.")
  }

  @Test
  fun `Rejected with a detail message includes it verbatim`() {
    val reason = SetupFailureReason.Rejected(code = 40, detail = "Wrong username or password")

    assertThat(reason.toMessage()).isEqualTo("Could not sign in: Wrong username or password")
  }

  @Test
  fun `Rejected without a detail message falls back to the numeric code`() {
    val reason = SetupFailureReason.Rejected(code = 404, detail = null)

    assertThat(reason.toMessage()).isEqualTo("Could not sign in (server error 404).")
  }

  @Test
  fun `Unreachable asks the user to check the URL and connection`() {
    assertThat(SetupFailureReason.Unreachable.toMessage())
      .isEqualTo("Could not reach the server. Check the URL and your connection.")
  }

  @Test
  fun `CleartextForbidden names https and the host, and never says check your connection`() {
    val message = SetupFailureReason.CleartextForbidden("music.example.com").toMessage()

    assertThat(message).contains("music.example.com")
    assertThat(message).contains("https")
    // The defect this reason exists to fix: the user's URL and connection are both fine, and
    // telling them to check either sends them to debug the wrong thing.
    assertThat(message).doesNotContain("your connection")
  }
}
