package app.muplay.requests

import app.muplay.integrations.IntegrationService
import app.muplay.integrations.bindery.BinderyUnauthorizedException
import app.muplay.integrations.lidarr.LidarrUnauthorizedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * All five outcomes of "test connection", as one exact list.
 *
 * Five separate messages, because four separate things can be wrong and sending a user to regenerate
 * a working API key because nothing was listening is the kind of unhelpfulness that makes people
 * give up on a feature. A connection check observed at one outcome is a connection check that has
 * not been tested.
 */
class ConnectionCheckTest {

  private object Boom : Exception("no route")

  @Test
  fun `each way a connection can go maps to its own outcome`() {
    val outcomes = listOf(
      // reachable = false: nothing is listening.
      ConnectionCheck.of(IntegrationService.LIDARR, reachable = false, identity = null, failure = null),
      // reachable, but the authenticated call was refused.
      ConnectionCheck.of(IntegrationService.LIDARR, true, null, LidarrUnauthorizedException()),
      // reachable and authenticated, but it is a Sonarr.
      ConnectionCheck.of(IntegrationService.LIDARR, true, identity = "Sonarr", failure = null),
      // reachable and authenticated and right.
      ConnectionCheck.of(IntegrationService.LIDARR, true, identity = "Lidarr", failure = null),
      // reachable, and something else went wrong entirely.
      ConnectionCheck.of(IntegrationService.LIDARR, true, null, Boom),
    )

    assertThat(outcomes).containsExactly(
      ConnectionCheck.Unreachable,
      ConnectionCheck.Unauthorized,
      ConnectionCheck.WrongApplication(appName = "Sonarr"),
      ConnectionCheck.Ok(description = "Lidarr"),
      ConnectionCheck.Failed(detail = "no route"),
    )
  }

  @Test
  fun `both services' unauthorized exceptions reach the same outcome`() {
    // The second observation of that branch, and it proves the check is not hard-wired to one
    // service's exception type -- which is exactly what a `is LidarrUnauthorizedException` alone
    // would be, silently, for every Bindery user.
    assertThat(ConnectionCheck.of(IntegrationService.LIDARR, true, null, LidarrUnauthorizedException()))
      .isEqualTo(ConnectionCheck.Unauthorized)
    assertThat(ConnectionCheck.of(IntegrationService.BINDERY, true, null, BinderyUnauthorizedException()))
      .isEqualTo(ConnectionCheck.Unauthorized)
  }

  @Test
  fun `the wrong-application outcome names what was actually found`() {
    // Two observations. "This is not Lidarr" is much less useful than "this is a Radarr".
    assertThat(ConnectionCheck.of(IntegrationService.LIDARR, true, "Radarr", null))
      .isEqualTo(ConnectionCheck.WrongApplication("Radarr"))
    assertThat(ConnectionCheck.of(IntegrationService.LIDARR, true, "Prowlarr", null))
      .isEqualTo(ConnectionCheck.WrongApplication("Prowlarr"))
  }

  @Test
  fun `a service that does not identify itself skips the identity check entirely`() {
    // Bindery's `/api/v1/health` reports `status` and `version` and nothing that names the
    // application, so there is no identity to check. Written as a value rather than as a branch that
    // can never be false -- and both paths through that value are exercised here and above.
    assertThat(ConnectionCheck.expectedAppName(IntegrationService.BINDERY)).isNull()
    assertThat(ConnectionCheck.of(IntegrationService.BINDERY, true, "anything at all", null))
      .isEqualTo(ConnectionCheck.Ok(description = "anything at all"))
    assertThat(ConnectionCheck.of(IntegrationService.BINDERY, true, null, null))
      .isEqualTo(ConnectionCheck.Ok(description = "Bindery"))
  }

  @Test
  fun `the identity match is case-insensitive`() {
    // The value is a build constant this client does not control, and a comparison that broke on a
    // capitalisation change would fail in the wrong direction -- telling a user with a working
    // Lidarr that they have not got one.
    assertThat(ConnectionCheck.of(IntegrationService.LIDARR, true, "lidarr", null))
      .isEqualTo(ConnectionCheck.Ok(description = "lidarr"))
  }

  @Test
  fun `a failure with no message still produces a message`() {
    // `Failed("")` renders as a blank line under the field, which reads as a UI bug. Both a null
    // message and a blank one, because they are different values and a `?:` covers only the first.
    assertThat(ConnectionCheck.of(IntegrationService.BINDERY, true, null, Exception()))
      .isEqualTo(ConnectionCheck.Failed("the connection failed"))
    assertThat(ConnectionCheck.of(IntegrationService.BINDERY, true, null, Exception("   ")))
      .isEqualTo(ConnectionCheck.Failed("the connection failed"))
  }

  @Test
  fun `an unreachable server is unreachable whatever else was observed`() {
    // The first arm, and it must win: a failure recorded against a host that never answered would
    // otherwise be shown as a rejected key, sending the user to regenerate a perfectly good one.
    assertThat(ConnectionCheck.of(IntegrationService.LIDARR, false, "Sonarr", LidarrUnauthorizedException()))
      .isEqualTo(ConnectionCheck.Unreachable)
  }

  // ---- the copy, which is the only part of this a user ever sees --------------------------------

  @Test
  fun `the rejected-key message does not claim the key is wrong`() {
    // Both services return a bare 401 for a missing key and a wrong key alike -- measured on each,
    // and recorded in both exception types' own documentation -- so "wrong" is a guess presented as
    // a fact, and it sends someone to regenerate a key that was never the problem.
    val message = ConnectionCheck.Unauthorized.message(IntegrationService.LIDARR)

    assertThat(message).contains("rejected")
    assertThat(message.lowercase()).doesNotContain("wrong key")
    assertThat(message).contains("Lidarr")
  }

  @Test
  fun `the unreachable message says the key is not the problem`() {
    // The other half of the same mistake, from the other side: a user whose server is off must not
    // go looking at their key.
    assertThat(ConnectionCheck.Unreachable.message(IntegrationService.BINDERY))
      .contains("Bindery")
      .contains("not the problem")
  }

  @Test
  fun `the wrong-application message names both applications`() {
    // "That is not a Lidarr" without saying what it *is* leaves the user with nothing to do.
    assertThat(ConnectionCheck.WrongApplication("Sonarr").message(IntegrationService.LIDARR))
      .contains("Sonarr")
      .contains("Lidarr")
  }

  @Test
  fun `every outcome has a message, and none of them is blank`() {
    // The exhaustive `when` cannot be missing a member, but it can hand back an empty string; a
    // sentence-shaped hole under a button reads as a UI bug rather than as an answer.
    val outcomes = listOf(
      ConnectionCheck.Ok("Lidarr"),
      ConnectionCheck.Unreachable,
      ConnectionCheck.Unauthorized,
      ConnectionCheck.WrongApplication("Radarr"),
      ConnectionCheck.Failed("no route"),
    )

    assertThat(outcomes.map { it.message(IntegrationService.LIDARR) }).allSatisfy {
      assertThat(it).isNotBlank()
    }
    assertThat(ConnectionCheck.Ok("Lidarr").message(IntegrationService.LIDARR)).contains("Lidarr")
    assertThat(ConnectionCheck.Failed("no route").message(IntegrationService.LIDARR)).contains("no route")
  }
}
