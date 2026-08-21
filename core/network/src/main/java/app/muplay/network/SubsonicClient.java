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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

  @Nonnull
  public static SubsonicClient create(@Nonnull SubsonicCredentials credentials) {
    OkHttpClient http = new OkHttpClient.Builder().build();
    Retrofit retrofit =
        new Retrofit.Builder()
            .baseUrl(credentials.baseUrl() + "/")
            .client(http)
            .addConverterFactory(JacksonConverterFactory.create(mapper()))
            .build();
    return new SubsonicClient(retrofit.create(SubsonicApi.class), credentials);
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

  private <T> ListenableFuture<T> enqueue(
      Call<SubsonicResponse> call, Function<SubsonicResponse.Body, T> mapper) {
    SettableFuture<T> future = SettableFuture.create();
    call.enqueue(
        new Callback<>() {
          @Override
          public void onResponse(
              Call<SubsonicResponse> c, retrofit2.Response<SubsonicResponse> response) {
            SubsonicResponse envelope = response.body();
            if (!response.isSuccessful() || envelope == null || envelope.body() == null) {
              future.setException(new IOException("HTTP " + response.code()));
              return;
            }
            SubsonicResponse.Body body = envelope.body();
            SubsonicResponse.SubsonicError error = body.error();
            if (error != null) {
              future.setException(
                  new SubsonicErrorException(
                      error.code(), Objects.requireNonNullElse(error.message(), "")));
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
