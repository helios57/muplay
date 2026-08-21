package app.muplay.testing;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;

public class OpenApiFixtureValidatorTest {

  // The vendored spec's `paths` map keys its operations as e.g. "/rest/ping" — the literal
  // Subsonic REST path, with no ".view" suffix and no separate "/rest" base-path stripped out via
  // `servers` (the spec's one server entry is just a host template). assertValid's endpointPath
  // argument is therefore the literal spec key, not a shorthand like "/ping".
  private static final String PING_PATH = "/rest/ping";

  private static final String VALID_PING =
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1",
       "type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}
      """;

  /** Missing the required "status" field. */
  private static final String INVALID_PING =
      """
      {"subsonic-response":{"version":"1.16.1",
       "type":"navidrome","serverVersion":"0.63.2","openSubsonic":true}}
      """;

  /**
   * An otherwise-valid ping response with one extra field nested inside {@code
   * subsonic-response}. This is the regression case for {@code withResolveCombinators(true)}: the
   * spec composes the response schema via allOf (SubsonicBaseResponse + an inline {status}
   * extension), and merging those branches before validating is exactly the change that could,
   * if done wrong, also merge away each branch's "no additional properties beyond what's
   * declared" restriction. It must not: a field neither branch declares still has to be rejected.
   */
  private static final String PING_WITH_EXTRA_NESTED_FIELD =
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1",
       "type":"navidrome","serverVersion":"0.63.2","openSubsonic":true,
       "notARealField":"nope"}}
      """;

  /** {@code openSubsonic} is a string here; the spec declares it a boolean. */
  private static final String PING_WITH_WRONG_TYPED_FIELD =
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1",
       "type":"navidrome","serverVersion":"0.63.2","openSubsonic":"yes"}}
      """;

  @Test
  public void acceptsAValidResponse() {
    new OpenApiFixtureValidator().assertValid(PING_PATH, VALID_PING);
  }

  @Test
  public void rejectsAResponseMissingARequiredField() {
    OpenApiFixtureValidator validator = new OpenApiFixtureValidator();
    assertThrows(
        AssertionError.class, () -> validator.assertValid(PING_PATH, INVALID_PING));
  }

  @Test
  public void rejectsAResponseWithAnExtraUndefinedNestedField() {
    OpenApiFixtureValidator validator = new OpenApiFixtureValidator();
    assertThrows(
        AssertionError.class,
        () -> validator.assertValid(PING_PATH, PING_WITH_EXTRA_NESTED_FIELD));
  }

  @Test
  public void rejectsAResponseWithAWrongTypedField() {
    OpenApiFixtureValidator validator = new OpenApiFixtureValidator();
    assertThrows(
        AssertionError.class,
        () -> validator.assertValid(PING_PATH, PING_WITH_WRONG_TYPED_FIELD));
  }

  @Test
  public void rejectsAnUnknownEndpointPath() {
    OpenApiFixtureValidator validator = new OpenApiFixtureValidator();
    String unknownPath = "/rest/notARealEndpoint";
    AssertionError error =
        assertThrows(
            AssertionError.class, () -> validator.assertValid(unknownPath, VALID_PING));
    String message = error.getMessage();
    assertNotNull(message);
    assertTrue(
        "Expected the failure message to name the unknown path, got: " + message,
        message.contains(unknownPath));
  }

  /**
   * {@code assertValid}'s {@code endpointPath} parameter is {@code @Nonnull}, so passing a null
   * literal directly from this test would be a compile-time NullAway error — correctly so, since
   * every caller inside this build's NullAway-covered code is already stopped there. The
   * defensive null check in {@code requireNonBlank} exists for callers outside that boundary
   * (e.g. a dynamically-constructed path from data this build doesn't statically check), so it is
   * exercised here through reflection instead: {@code Method.invoke} takes {@code Object}
   * arguments, which erases the {@code @Nonnull} contract NullAway would otherwise enforce,
   * without requiring a suppression.
   */
  @Test
  public void rejectsANullEndpointPath() throws NoSuchMethodException {
    OpenApiFixtureValidator validator = new OpenApiFixtureValidator();
    Method assertValid =
        OpenApiFixtureValidator.class.getMethod("assertValid", String.class, String.class);
    InvocationTargetException thrown =
        assertThrows(
            InvocationTargetException.class,
            () -> assertValid.invoke(validator, null, VALID_PING));
    Throwable cause = thrown.getCause();
    assertNotNull(cause);
    assertTrue(
        "Expected the underlying failure to be an AssertionError, got: " + cause,
        cause instanceof AssertionError);
    String message = cause.getMessage();
    assertNotNull(message);
    assertTrue(
        "Expected a message explaining the null path, got: " + message, message.contains("null"));
  }

  @Test
  public void rejectsABlankEndpointPath() {
    OpenApiFixtureValidator validator = new OpenApiFixtureValidator();
    AssertionError error =
        assertThrows(AssertionError.class, () -> validator.assertValid("", VALID_PING));
    String message = error.getMessage();
    assertNotNull(message);
    assertTrue(
        "Expected a message explaining the blank path, got: " + message,
        message.contains("blank"));
  }
}
