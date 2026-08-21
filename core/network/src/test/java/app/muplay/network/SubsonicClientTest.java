package app.muplay.network;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import app.muplay.model.LibraryRole;
import app.muplay.model.MusicLibrary;
import app.muplay.model.SubsonicCredentials;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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

  @Test
  public void httpErrorBecomesSubsonicHttpException() {
    server.enqueue(new MockResponse.Builder().code(404).build());

    ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.ping().get());
    SubsonicHttpException cause =
        (SubsonicHttpException) Objects.requireNonNull(thrown.getCause());
    assertThat(cause.httpStatusCode()).isEqualTo(404);
  }

  @Test
  public void emptyResponseBodyBecomesADistinctIOException() {
    // HTTP 204: a genuinely empty, successful response. Retrofit skips body conversion entirely
    // for 204/205, so response.body() is null even though the HTTP call itself succeeded — this
    // must not be reported as "HTTP 200", which would misattribute the problem.
    server.enqueue(new MockResponse.Builder().code(204).build());

    ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.ping().get());
    Throwable cause = Objects.requireNonNull(thrown.getCause());
    assertThat(cause).isInstanceOf(IOException.class);
    assertThat(cause).isNotInstanceOf(SubsonicResponseException.class);
    assertThat(cause.getMessage()).contains("empty or could not be parsed");
  }

  @Test
  public void missingEnvelopeBecomesADistinctIOException() {
    // Valid JSON, but no "subsonic-response" key at all — a different defect than either an
    // HTTP-level error or an empty body, and must not be conflated with either.
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body("{}")
            .build());

    ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.ping().get());
    Throwable cause = Objects.requireNonNull(thrown.getCause());
    assertThat(cause).isInstanceOf(IOException.class);
    assertThat(cause).isNotInstanceOf(SubsonicResponseException.class);
    assertThat(cause.getMessage()).contains("subsonic-response");
  }

  @Test
  public void getOpenSubsonicExtensions_missingFieldYieldsEmptyMap() throws Exception {
    // ping_navidrome.json is openSubsonic=true but predates this call in every sense that
    // matters here: it simply has no openSubsonicExtensions field, exercising the "exts != null"
    // guard in SubsonicClient.getOpenSubsonicExtensions().
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(FixtureContractTest.fixture("ping_navidrome.json"))
            .build());

    Map<String, List<Integer>> extensions = client.getOpenSubsonicExtensions().get();

    assertThat(extensions).isEmpty();
  }

  @Test
  public void getOpenSubsonicExtensions_duplicateNameMergesVersions() throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\","
                    + "\"type\":\"navidrome\",\"serverVersion\":\"0.63.2\","
                    + "\"openSubsonic\":true,\"openSubsonicExtensions\":["
                    + "{\"name\":\"songLyrics\",\"versions\":[1]},"
                    + "{\"name\":\"songLyrics\",\"versions\":[2]}]}}")
            .build());

    Map<String, List<Integer>> extensions = client.getOpenSubsonicExtensions().get();

    // A duplicate extension name is malformed-but-recoverable; merging (rather than the later
    // entry silently clobbering the earlier one, or throwing and failing outright) matches this
    // client's general policy of tolerating server quirks (task 5 fix-round review).
    assertThat(extensions.get("songLyrics")).containsExactly(1, 2);
  }
}
