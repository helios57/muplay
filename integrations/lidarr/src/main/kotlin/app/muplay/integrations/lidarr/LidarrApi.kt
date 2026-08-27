package app.muplay.integrations.lidarr

import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The raw Retrofit surface for Lidarr's v1 API.
 *
 * Every method returns `Response<T>` rather than `T`, because this client has to read the **status
 * code and the raw error body** on failure — a 400 carries a JSON array of validation failures, a
 * 503 carries a distinguishing message, and a 401 carries nothing at all (measured:
 * `Content-Length: 0`). Retrofit's default `HttpException` gives none of those without re-reading
 * the body, and re-reading it is not possible after the exception is constructed.
 *
 * `ping` is **not** under `api/v1`: `PingController` maps `/ping` at the application root, outside
 * the versioned controllers. Tasks 6-7 add the rest.
 *
 * No `@Headers` here, and that is the point of [LidarrAuthInterceptor]: `X-Api-Key` and `Accept`
 * are attached to every request by the OkHttp stack, so an endpoint a later task adds cannot ship
 * without them by forgetting an annotation.
 */
internal interface LidarrApi {

  @GET("ping")
  suspend fun ping(): Response<PingBody>

  @GET("api/v1/system/status")
  suspend fun systemStatus(): Response<SystemStatusBody>

  /**
   * Returns raw `JsonElement`s, not a typed resource.
   *
   * Deliberate: Task 6's add posts the lookup element **back** with five fields set, which is what
   * Lidarr's own UI does (`frontend/src/Utilities/Album/getNewAlbum.js`), and a typed round trip
   * would silently drop every field this client does not model. Measured on the pinned container,
   * one `kind of blue` element carries twenty top-level fields and a `releases` array of 133 —
   * `fixtures/lidarr/album-lookup.json`. The typed view is built beside it by
   * [LidarrClient.lookupAlbums].
   *
   * **Not served from the user's own database.** `SkyHookProxy` proxies to
   * `https://api.lidarr.audio/api/v0.4/…`, so this call is slow, is rate-limited upstream, and can
   * fail while the user's own server is perfectly healthy — measured, that failure is a **503**
   * whose body is `{"message":…,"description":<a .NET stack trace>}`
   * (`fixtures/lidarr/lookup-unavailable.json`), which [LidarrClient.call] maps to a plain
   * [LidarrHttpException] because it is not the starting-up body.
   */
  @GET("api/v1/album/lookup")
  suspend fun albumLookup(@Query("term") term: String): Response<List<JsonElement>>

  @GET("api/v1/rootfolder")
  suspend fun rootFolders(): Response<List<RootFolderBody>>

  @GET("api/v1/qualityprofile")
  suspend fun qualityProfiles(): Response<List<ProfileBody>>

  @GET("api/v1/metadataprofile")
  suspend fun metadataProfiles(): Response<List<ProfileBody>>
}
