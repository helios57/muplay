package app.muplay.testing

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [OpenApiFixtureValidator] is the external oracle every later task's fixture tests lean on: a
 * fixture that drifts from the real OpenSubsonic protocol must fail here, or every assertion built
 * on top of it is circular (code and test written by the same hand, proving nothing). The suite
 * below therefore spends most of its weight on rejection, not acceptance — an oracle that accepts
 * everything is worse than no oracle, because it manufactures confidence across the whole plan.
 */
class OpenApiFixtureValidatorTest {

  // The vendored spec's `paths` map keys every operation as e.g. "/rest/ping" — the literal
  // Subsonic REST path, no ".view" suffix, "/rest" included (confirmed by inspecting the spec:
  // all 87 keys follow this exact form). assertValid's endpointPath is that literal spec key.
  private val pingPath = "/rest/ping"

  private val validPing =
    """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}"""

  // Missing the required "status" field. The spec's "subsonic-response" is `oneOf`
  // [SubsonicSuccessResponse, SubsonicFailureResponse], and both branches require "status" — so
  // dropping it leaves zero oneOf alternatives satisfied.
  private val missingRequiredField =
    """{"subsonic-response":{"version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}"""

  // An otherwise-valid ping with one extra field nested inside "subsonic-response". This is the
  // regression case for withResolveCombinators(true): the response schema composes
  // SubsonicBaseResponse (version/type/serverVersion/openSubsonic) with an inline {status}
  // extension via allOf. Merging those branches before validating is exactly the change that
  // could, done wrong, also merge away each branch's closed "no additional properties" boundary.
  // It must not: a field neither branch declares still has to be rejected.
  private val extraUndefinedNestedField =
    """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":true,"notARealField":"nope"}}"""

  // "openSubsonic" is a string here; the spec declares it a boolean.
  private val wrongTypedField =
    """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.63.2","openSubsonic":"yes"}}"""

  private val unknownPath = "/rest/notAnEndpoint"

  @Test
  fun `accepts a valid ping`() {
    assertThatCode { OpenApiFixtureValidator.assertValid(pingPath, validPing) }.doesNotThrowAnyException()
  }

  @Test
  fun `rejects a missing required field`() {
    assertThatThrownBy { OpenApiFixtureValidator.assertValid(pingPath, missingRequiredField) }
      .isInstanceOf(AssertionError::class.java)
  }

  @Test
  fun `rejects an extra undefined nested field`() {
    assertThatThrownBy { OpenApiFixtureValidator.assertValid(pingPath, extraUndefinedNestedField) }
      .isInstanceOf(AssertionError::class.java)
  }

  @Test
  fun `rejects a wrong-typed field`() {
    assertThatThrownBy { OpenApiFixtureValidator.assertValid(pingPath, wrongTypedField) }
      .isInstanceOf(AssertionError::class.java)
  }

  @Test
  fun `rejects an unknown endpoint path naming it`() {
    assertThatThrownBy { OpenApiFixtureValidator.assertValid(unknownPath, validPing) }
      .isInstanceOf(AssertionError::class.java)
      .hasMessageContaining(unknownPath)
  }
}
