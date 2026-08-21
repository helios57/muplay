package app.muplay.network;

import app.muplay.network.dto.SubsonicResponse;
import java.util.Map;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.QueryMap;

interface SubsonicApi {

  @GET("rest/ping")
  Call<SubsonicResponse> ping(@QueryMap Map<String, String> auth);

  @GET("rest/getMusicFolders")
  Call<SubsonicResponse> getMusicFolders(@QueryMap Map<String, String> auth);

  @GET("rest/getOpenSubsonicExtensions")
  Call<SubsonicResponse> getOpenSubsonicExtensions(@QueryMap Map<String, String> auth);
}
