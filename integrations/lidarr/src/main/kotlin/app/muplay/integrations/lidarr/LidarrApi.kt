package app.muplay.integrations.lidarr

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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

  /**
   * Asks Lidarr to add an album. The body is a raw `JsonObject` built by [LidarrAddPayload].
   *
   * A `JsonObject` rather than a typed request DTO, and that is the whole design of this task: the
   * body is the lookup element **decorated**, so every field this client does not model has to
   * survive a round trip it has no type for. See [LidarrAddPayload].
   *
   * `Response<JsonObject>` on the way back for the same reason it is `Response<...>` everywhere
   * else here, plus one specific to this call. Measured against `3.1.0.4875-ls40`, a successful add
   * answers **`201 Created`** (`RestController.Created` -> `CreatedAtAction`) with the persisted
   * resource, and a **duplicate answers `400`** carrying the ordinary FluentValidation array -- not
   * a 409, and not anything else structurally distinguishable at the status. Both the status and
   * the raw error body are needed to tell those apart, and Retrofit's `HttpException` gives
   * neither.
   */
  @POST("api/v1/album")
  suspend fun addAlbum(@Body body: JsonObject): Response<JsonObject>

  /**
   * The albums in the user's own library whose `foreignAlbumId` is [foreignAlbumId].
   *
   * `AlbumController.GetAlbums` reads this from the **user's database**, not from the metadata
   * proxy -- so unlike [albumLookup] it is fast, local and cannot fail while the server is healthy.
   *
   * **The parameter is not optional in practice, only in the API.** Measured: the same path with no
   * `foreignAlbumId` returns *every* album in the library, 200. A client that dropped the parameter
   * would therefore get a perfectly valid answer about the wrong records, which is why
   * [LidarrClient.findAddedAlbumId] matches the identifier back out of the response rather than
   * trusting the server to have filtered.
   */
  @GET("api/v1/album")
  suspend fun albumsByForeignId(
    @Query("foreignAlbumId") foreignAlbumId: String,
  ): Response<List<JsonObject>>
}
