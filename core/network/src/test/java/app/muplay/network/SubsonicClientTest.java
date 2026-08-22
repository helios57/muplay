package app.muplay.network;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import app.muplay.model.LibraryRole;
import app.muplay.model.MusicLibrary;
import app.muplay.model.SubsonicCredentials;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
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
  public void create_withSharedOkHttpClientUsesExactlyThatClient() throws Exception {
    // Plan 3 needs Media3's OkHttpDataSource on the same client and connection pool as this one,
    // and Plan 6's proxy needs it too — this overload exists so both can share one OkHttpClient
    // rather than each opening its own pool to the same server. Proven here by a request actually
    // succeeding end-to-end through a client built from a caller-supplied OkHttpClient, not the
    // package-private default one.
    OkHttpClient shared = new OkHttpClient.Builder().build();
    HttpUrl base = server.url("/");
    SubsonicClient sharedClient =
        SubsonicClient.create(
            SubsonicCredentials.create(
                base.toString().substring(0, base.toString().length() - 1), "alice", "sesame"),
            shared);
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(FixtureContractTest.fixture("ping_navidrome.json"))
            .build());

    SubsonicClient.ServerInfo info = sharedClient.ping().get();

    assertThat(info.type()).isEqualTo("navidrome");
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
  public void emptyResponseBodyBecomesAMalformedSubsonicResponseException() {
    // HTTP 204: a genuinely empty, successful response. Retrofit skips body conversion entirely
    // for 204/205, so response.body() is null even though the HTTP call itself succeeded — this
    // must not be reported as "HTTP 200", which would misattribute the problem.
    server.enqueue(new MockResponse.Builder().code(204).build());

    ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.ping().get());
    Throwable cause = Objects.requireNonNull(thrown.getCause());
    // Asserted by type, not by matching message text: callers (Plan 2's error UI included) must
    // be able to tell "server replied with garbage" apart from other IOExceptions without
    // parsing English out of getMessage().
    assertThat(cause).isInstanceOf(MalformedSubsonicResponseException.class);
    assertThat(cause).isNotInstanceOf(SubsonicResponseException.class);
  }

  @Test
  public void missingEnvelopeBecomesAMalformedSubsonicResponseException() {
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
    assertThat(cause).isInstanceOf(MalformedSubsonicResponseException.class);
    assertThat(cause).isNotInstanceOf(SubsonicResponseException.class);
  }

  @Test
  public void statusFailedWithNoErrorObjectBecomesAGenericSubsonicErrorException() {
    // A non-compliant server or a mangling proxy could plausibly send status:"failed" without an
    // accompanying error object — SubsonicClient must still detect the failure via status(),
    // not silently hand the mapper a "successful" body it can't make sense of.
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                "{\"subsonic-response\":{\"status\":\"failed\",\"version\":\"1.16.1\","
                    + "\"type\":\"navidrome\",\"serverVersion\":\"0.63.2\","
                    + "\"openSubsonic\":true}}")
            .build());

    ExecutionException thrown = assertThrows(ExecutionException.class, () -> client.ping().get());
    SubsonicErrorException cause =
        (SubsonicErrorException) Objects.requireNonNull(thrown.getCause());
    assertThat(cause.code()).isEqualTo(SubsonicErrorException.CODE_GENERIC);
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
