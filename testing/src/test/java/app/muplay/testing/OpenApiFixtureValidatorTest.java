package app.muplay.testing;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
}
