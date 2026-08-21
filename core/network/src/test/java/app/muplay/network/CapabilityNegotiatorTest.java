package app.muplay.network;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import app.muplay.model.ServerCapabilities;
import app.muplay.model.SubsonicCredentials;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.SocketEffect;
import okhttp3.HttpUrl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CapabilityNegotiatorTest {

  private MockWebServer server;
  private CapabilityNegotiator negotiator;

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    HttpUrl base = server.url("/");
    String url = base.toString().substring(0, base.toString().length() - 1);
    negotiator =
        new CapabilityNegotiator(
            SubsonicClient.create(SubsonicCredentials.create(url, "alice", "sesame")));
  }

  @After
  public void tearDown() throws Exception {
    server.close();
  }

  @Test
  public void negotiate_recordsVersionsNotJustPresence() throws Exception {
    enqueue("ping_navidrome.json");
    enqueue("getOpenSubsonicExtensions_navidrome.json");

    ServerCapabilities caps = negotiator.negotiate().get();

    assertThat(caps.supports("songLyrics")).isTrue();
    assertThat(caps.supports("songLyrics", 1)).isTrue();
    // v2 differs materially from v1 — a boolean would lose this.
    assertThat(caps.supports("songLyrics", 2)).isTrue();
    assertThat(caps.supports("transcodeOffset", 1)).isTrue();
    assertThat(caps.supports("transcodeOffset", 2)).isFalse();
  }

  @Test
  public void negotiate_reportsIndexBasedQueueSupport() throws Exception {
    enqueue("ping_navidrome.json");
    enqueue("getOpenSubsonicExtensions_navidrome.json");

    ServerCapabilities caps = negotiator.negotiate().get();

    // savePlayQueue has a duplicate-track bug in Navidrome; we need the
    // index-based variant, so this capability is load-bearing.
    assertThat(caps.supports("indexBasedQueue", 1)).isTrue();
  }

  @Test
  public void negotiate_apiKeyAuthenticationIsNotAdvertisedByNavidrome() throws Exception {
    enqueue("ping_navidrome.json");
    enqueue("getOpenSubsonicExtensions_navidrome.json");

    ServerCapabilities caps = negotiator.negotiate().get();

    // Not implemented as of 0.63.2, despite third-party claims. If this ever
    // starts failing, Navidrome shipped it and we can drop password storage.
    assertThat(caps.supports("apiKeyAuthentication")).isFalse();
  }

  @Test
  public void negotiate_legacyServerWithoutOpenSubsonicYieldsEmptyCapabilities()
      throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\","
                    + "\"type\":\"subsonic\",\"serverVersion\":\"6.0\","
                    + "\"openSubsonic\":false}}")
            .build());

    ServerCapabilities caps = negotiator.negotiate().get();

    // No second request should be made — nothing to ask.
    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(caps.supports("songLyrics")).isFalse();
    assertThat(caps.isOpenSubsonic()).isFalse();
  }

  @Test
  public void negotiate_extensionsFetchFailureDegradesToNoExtensions() throws Exception {
    enqueue("ping_navidrome.json");
    // Server claims OpenSubsonic in ping but the extensions endpoint 404s — an older
    // OpenSubsonic-flagged server that predates this call. That is a real answer ("no"), so
    // negotiation must still complete rather than propagate the failure to every caller.
    server.enqueue(new MockResponse.Builder().code(404).build());

    ServerCapabilities caps = negotiator.negotiate().get();

    assertThat(caps.isOpenSubsonic()).isTrue();
    assertThat(caps.supports("songLyrics")).isFalse();
  }

  @Test
  public void negotiate_extensionsFetchSubsonicErrorDegradesToNoExtensions() throws Exception {
    enqueue("ping_navidrome.json");
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(
                "{\"subsonic-response\":{\"status\":\"failed\",\"version\":\"1.16.1\","
                    + "\"type\":\"navidrome\",\"serverVersion\":\"0.63.2\","
                    + "\"openSubsonic\":true,"
                    + "\"error\":{\"code\":70,\"message\":\"Requested data was not found\"}}}")
            .build());

    ServerCapabilities caps = negotiator.negotiate().get();

    assertThat(caps.isOpenSubsonic()).isTrue();
    assertThat(caps.supports("songLyrics")).isFalse();
  }

  @Test
  public void negotiate_extensionsFetchTransportFailurePropagates() throws Exception {
    enqueue("ping_navidrome.json");
    // The connection dies mid-request — not a real answer from the server (contrast with the
    // 404 and Subsonic-error cases above), so this must propagate out of negotiate() rather than
    // being folded into a degraded ServerCapabilities a caller can't tell apart from "confirmed
    // zero extensions".
    server.enqueue(
        new MockResponse.Builder().onRequestStart(new SocketEffect.CloseSocket()).build());

    ExecutionException thrown =
        assertThrows(ExecutionException.class, () -> negotiator.negotiate().get());

    Throwable cause = Objects.requireNonNull(thrown.getCause());
    assertThat(cause).isInstanceOf(IOException.class);
    assertThat(cause).isNotInstanceOf(SubsonicResponseException.class);
  }

  private void enqueue(String fixture) throws Exception {
    server.enqueue(
        new MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(FixtureContractTest.fixture(fixture))
            .build());
  }
}
