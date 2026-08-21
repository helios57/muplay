package app.muplay.network;

import app.muplay.testing.OpenApiFixtureValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Every committed fixture must match the published OpenSubsonic spec. */
public class FixtureContractTest {

  private final OpenApiFixtureValidator validator = new OpenApiFixtureValidator();

  @Test
  public void pingFixtureMatchesSpec() throws IOException {
    validator.assertValid("/rest/ping", fixture("ping_navidrome.json"));
  }

  @Test
  public void getMusicFoldersFixtureMatchesSpec() throws IOException {
    validator.assertValid("/rest/getMusicFolders", fixture("getMusicFolders_navidrome.json"));
  }

  @Test
  public void getOpenSubsonicExtensionsFixtureMatchesSpec() throws IOException {
    validator.assertValid(
        "/rest/getOpenSubsonicExtensions", fixture("getOpenSubsonicExtensions_navidrome.json"));
  }

  static String fixture(String name) throws IOException {
    try (var in = FixtureContractTest.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IOException("Missing fixture: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
