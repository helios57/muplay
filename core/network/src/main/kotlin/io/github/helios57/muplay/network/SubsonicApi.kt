package io.github.helios57.muplay.network

import io.github.helios57.muplay.network.model.SubsonicEnvelope
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * The raw Retrofit surface for the Subsonic REST API.
 *
 * Every command returns the same envelope shape ([SubsonicEnvelope]) whether it succeeded or
 * failed — Subsonic answers `HTTP 200` for a failed command too, encoding the failure in the
 * body's `status`/`error` fields instead — so nothing here inspects anything beyond what Retrofit
 * already does for a genuine transport-level failure (a non-2xx status, a dead connection).
 * [SubsonicClient] is what interprets the body and maps it to a domain type or
 * [SubsonicException]; this interface only describes the wire shape.
 *
 * Paths intentionally omit the historic `.view` suffix (`"rest/ping"`, not `"rest/ping.view"`):
 * OpenSubsonic servers are required to accept both forms — confirmed directly against a running
 * `deluan/navidrome:0.63.2` container, `curl .../rest/ping?...` and `curl .../rest/ping.view?...`
 * return identical `HTTP 200` bodies — and the vendored spec itself keys every path this way (see
 * [io.github.helios57.muplay.testing.OpenApiFixtureValidator]), so using the same form here keeps the path this
 * client requests and the path the spec validates textually identical.
 */
interface SubsonicApi {

  @GET("rest/ping")
  suspend fun ping(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getMusicFolders")
  suspend fun getMusicFolders(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getOpenSubsonicExtensions")
  suspend fun getOpenSubsonicExtensions(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getAlbumList2")
  suspend fun getAlbumList2(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getAlbum")
  suspend fun getAlbum(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getPlaylists")
  suspend fun getPlaylists(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getPlaylist")
  suspend fun getPlaylist(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/search3")
  suspend fun search3(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getRandomSongs")
  suspend fun getRandomSongs(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getScanStatus")
  suspend fun getScanStatus(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/setRating")
  suspend fun setRating(@QueryMap params: Map<String, String>): SubsonicEnvelope

  /**
   * `songId` is **repeated**, not comma-joined, so it cannot travel in the [QueryMap] a
   * `Map<String, String>` provides. Retrofit renders a `List` parameter as one query pair per
   * element, which is the shape Subsonic specifies and Navidrome accepts.
   */
  @GET("rest/createPlaylist")
  suspend fun createPlaylist(
    @QueryMap params: Map<String, String>,
    @Query("songId") songIds: List<String>,
  ): SubsonicEnvelope

  /**
   * Adds by id and removes by **index**, which is the only removal Subsonic offers.
   *
   * An empty list renders no query pair at all, which matters: `songIndexToRemove=` with an empty
   * value is a parse error at the server rather than a no-op.
   */
  @GET("rest/updatePlaylist")
  suspend fun updatePlaylist(
    @QueryMap params: Map<String, String>,
    @Query("songIdToAdd") songIdsToAdd: List<String>,
    @Query("songIndexToRemove") songIndexesToRemove: List<Int>,
  ): SubsonicEnvelope
}
