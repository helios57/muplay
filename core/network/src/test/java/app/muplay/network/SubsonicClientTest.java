package app.muplay.network;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import app.muplay.model.LibraryRole;
import app.muplay.model.MusicLibrary;
import app.muplay.model.SubsonicCredentials;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SubsonicClientTest {

  private MockWebServer server;
  private SubsonicClient client;

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    HttpUrl base = server.url("/");
    client =
        SubsonicClient.create(
            SubsonicCredentials.create(
                base.toString().substring(0, base.toString().length() - 1),
                "alice",
                "sesame"));
  }

  @After
  public void tearDown() throws Exception {
    server.close();
  }

  @Test
  public void ping_sendsAuthParamsAndClientName() throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(FixtureContractTest.fixture("ping_navidrome.json"))
            .build());

    client.ping().get();

    RecordedRequest request = server.takeRequest();
    HttpUrl url = request.getUrl();
    assertThat(url.encodedPath()).endsWith("/rest/ping");
    assertThat(url.queryParameter("c")).isEqualTo("MuPlay");
    assertThat(url.queryParameter("v")).isEqualTo("1.16.1");
    assertThat(url.queryParameter("f")).isEqualTo("json");
    assertThat(url.queryParameter("u")).isEqualTo("alice");
    assertThat(url.queryParameter("t")).matches("[0-9a-f]{32}");
    assertThat(url.queryParameter("s")).isNotEmpty();
    // The password must never appear on the wire.
    assertThat(url.toString()).doesNotContain("sesame");
    assertThat(url.queryParameter("p")).isNull();
  }

  @Test
  public void getMusicFolders_mapsToLibrariesUnassignedByDefault() throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(FixtureContractTest.fixture("getMusicFolders_navidrome.json"))
            .build());

    List<MusicLibrary> libraries = client.getMusicFolders().get();

    assertThat(libraries).hasSize(2);
    assertThat(libraries.get(0).id()).isEqualTo(1);
    assertThat(libraries.get(0).name()).isEqualTo("Music");
    // Role is a user decision — Navidrome never says what a library contains.
    assertThat(libraries.get(0).role()).isEqualTo(LibraryRole.UNASSIGNED);
    assertThat(libraries.get(1).name()).isEqualTo("Audiobooks");
  }

  @Test
  public void subsonicErrorBecomesATypedException() {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                "{\"subsonic-response\":{\"status\":\"failed\",\"version\":\"1.16.1\","
                    + "\"type\":\"navidrome\",\"serverVersion\":\"0.63.2\","
                    + "\"openSubsonic\":true,"
                    + "\"error\":{\"code\":40,\"message\":\"Wrong username or password\"}}}")
            .build());

    ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.ping().get());
    // Throwable.getCause() is @Nullable; Objects.requireNonNull is the NullAway-recognized
    // narrowing idiom (a bare cast does not narrow nullability).
    SubsonicErrorException cause =
        (SubsonicErrorException) Objects.requireNonNull(thrown.getCause());
    // 40 == wrong username or password. HTTP is still 200; Subsonic signals
    // errors in the body, which is a classic source of client bugs.
    assertThat(cause.code()).isEqualTo(40);
    assertThat(cause.getMessage()).contains("Wrong username or password");
  }
}
