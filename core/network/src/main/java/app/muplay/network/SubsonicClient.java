package app.muplay.network;

import app.muplay.model.LibraryRole;
import app.muplay.model.MusicLibrary;
import app.muplay.model.SubsonicCredentials;
import app.muplay.network.dto.MusicFolderList;
import app.muplay.network.dto.SubsonicResponse;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nonnull;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/** Facade over the Subsonic REST API. */
public final class SubsonicClient {

  private final SubsonicApi api;
  private final SubsonicAuth auth = new SubsonicAuth();
  private final SubsonicCredentials credentials;

  private SubsonicClient(SubsonicApi api, SubsonicCredentials credentials) {
    this.api = api;
    this.credentials = credentials;
  }

  /**
   * Builds a client with a private, default-configured {@link OkHttpClient} (see {@link
   * #defaultHttpClient()}). Prefer {@link #create(SubsonicCredentials, OkHttpClient)} whenever an
   * {@link OkHttpClient} already exists elsewhere in the app: Plan 3 needs Media3's {@code
   * OkHttpDataSource} on the same client and connection pool as this one, and Plan 6's proxy
   * needs it too — sharing one client and pool avoids duplicate connection pools/thread pools to
   * the same server.
   */
  @Nonnull
  public static SubsonicClient create(@Nonnull SubsonicCredentials credentials) {
    return create(credentials, defaultHttpClient());
  }

  /**
   * Builds a client on top of a caller-supplied {@link OkHttpClient}, e.g. one already shared
   * with Media3's {@code OkHttpDataSource} or a local proxy. This client adds nothing to it (no
   * interceptors, no timeout overrides) — it is used exactly as given.
   */
  @Nonnull
  public static SubsonicClient create(
      @Nonnull SubsonicCredentials credentials, @Nonnull OkHttpClient httpClient) {
    Retrofit retrofit =
        new Retrofit.Builder()
            .baseUrl(credentials.baseUrl() + "/")
            .client(httpClient)
            .addConverterFactory(JacksonConverterFactory.create(mapper()))
            .build();
    return new SubsonicClient(retrofit.create(SubsonicApi.class), credentials);
  }

  @Nonnull
  private static OkHttpClient defaultHttpClient() {
    // Spike S1 (docs/superpowers/spikes/2026-08-21-s1-local-network-permission.md) found that a
    // *blocked* local-network connection manifests as a silent TCP connect timeout
    // (SocketTimeoutException, packets dropped with no error at all), not a fast, loud failure —
    // so relying on whatever OkHttp's own built-in default happens to be is a real risk, not
    // merely a style preference: a caller has no way to tell "still connecting" from "will never
    // connect" until some timeout fires. Explicit here, rather than implicit, so this behavior is
    // visible in this codebase and does not silently shift if OkHttp's own default ever changes.
    return new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(15))
        .build();
  }

  private static ObjectMapper mapper() {
    // Servers add fields over time; unknown ones must never break a client.
    return new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @Nonnull
  public ListenableFuture<ServerInfo> ping() {
    return enqueue(
        api.ping(auth.authParams(credentials, SubsonicAuth.randomSaltSupplier())),
        body ->
            new ServerInfo(
                Objects.requireNonNullElse(body.type(), ""),
                Objects.requireNonNullElse(body.serverVersion(), ""),
                body.openSubsonic()));
  }

  @Nonnull
  public ListenableFuture<List<MusicLibrary>> getMusicFolders() {
    return enqueue(
        api.getMusicFolders(auth.authParams(credentials, SubsonicAuth.randomSaltSupplier())),
        body -> {
          List<MusicLibrary> out = new ArrayList<>();
          MusicFolderList folders = body.musicFolders();
          if (folders != null && folders.musicFolder() != null) {
            for (MusicFolderList.MusicFolder f : folders.musicFolder()) {
              out.add(
                  new MusicLibrary(
                      f.id(),
                      Objects.requireNonNullElse(f.name(), "Library " + f.id()),
                      LibraryRole.UNASSIGNED));
            }
          }
          return List.copyOf(out);
        });
  }

  @Nonnull
  public ListenableFuture<Map<String, List<Integer>>> getOpenSubsonicExtensions() {
    return enqueue(
        api.getOpenSubsonicExtensions(
            auth.authParams(credentials, SubsonicAuth.randomSaltSupplier())),
        body -> {
          Map<String, List<Integer>> out = new LinkedHashMap<>();
          List<SubsonicResponse.OpenSubsonicExtension> exts = body.openSubsonicExtensions();
          if (exts != null) {
            for (SubsonicResponse.OpenSubsonicExtension e : exts) {
              if (e.name() != null) {
                List<Integer> versions =
                    List.copyOf(Objects.requireNonNullElse(e.versions(), List.of()));
                // A repeated extension name is a malformed-but-recoverable response, not
                // something worth failing negotiation over. Merging (a union of the version
                // lists, preserving first-seen order) matches this client's general policy of
                // tolerating server quirks rather than either silently keeping only the last
                // entry (data loss — see the Task 5 review that flagged this) or throwing and
                // taking down the whole negotiation for one duplicated field.
                out.merge(
                    e.name(),
                    versions,
                    (existing, incoming) -> {
                      Set<Integer> merged = new LinkedHashSet<>(existing);
                      merged.addAll(incoming);
                      return List.copyOf(merged);
                    });
              }
            }
          }
          return Map.copyOf(out);
        });
  }

  private <T> ListenableFuture<T> enqueue(
      Call<SubsonicResponse> call, Function<SubsonicResponse.Body, T> mapper) {
    SettableFuture<T> future = SettableFuture.create();
    call.enqueue(
        new Callback<>() {
          @Override
          public void onResponse(
              Call<SubsonicResponse> c, retrofit2.Response<SubsonicResponse> response) {
            // These four failure modes are kept distinct rather than collapsed into one "HTTP
            // <code>" message: a real HTTP-level error, an empty/unparseable body, a
            // parsed-but-envelope-less body, and a Subsonic-level error are different problems
            // with different messages, and only the first is a genuine HTTP failure — the middle
            // two would misleadingly report "HTTP 200" as if that were the defect.
            // SubsonicHttpException additionally exists (rather than a bare IOException) so
            // callers such as CapabilityNegotiator can catch "the server answered, just not
            // successfully" without also catching a transport failure they don't understand well
            // enough to degrade on.
            if (!response.isSuccessful()) {
              future.setException(new SubsonicHttpException(response.code()));
              return;
            }
            SubsonicResponse envelope = response.body();
            if (envelope == null) {
              future.setException(
                  new MalformedSubsonicResponseException(
                      "Subsonic response body was empty or could not be parsed (HTTP "
                          + response.code()
                          + ")"));
              return;
            }
            if (envelope.body() == null) {
              future.setException(
                  new MalformedSubsonicResponseException(
                      "Subsonic response was missing its \"subsonic-response\" envelope (HTTP "
                          + response.code()
                          + ")"));
              return;
            }
            SubsonicResponse.Body body = envelope.body();
            SubsonicResponse.SubsonicError error = body.error();
            // body.status() is otherwise parsed and carried for decoration only, read by nothing
            // — checking it here hardens against a non-compliant server or a mangling proxy that
            // sends status:"failed" without an accompanying error object, at no cost on the
            // common path where a compliant server always pairs the two.
            if (error != null || "failed".equals(body.status())) {
              int code = error != null ? error.code() : SubsonicErrorException.CODE_GENERIC;
              String message =
                  error != null
                      ? Objects.requireNonNullElse(error.message(), "")
                      : "Subsonic response status was \"failed\" with no error object";
              future.setException(new SubsonicErrorException(code, message));
              return;
            }
            try {
              future.set(mapper.apply(body));
            } catch (RuntimeException e) {
              future.setException(e);
            }
          }

          @Override
          public void onFailure(Call<SubsonicResponse> c, Throwable t) {
            future.setException(t);
          }
        });
    return future;
  }

  /** Server identity from {@code ping}. */
  public record ServerInfo(
      @Nonnull String type, @Nonnull String serverVersion, boolean openSubsonic) {}
}
