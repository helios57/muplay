package app.muplay.integrations.lidarr

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
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

  /**
   * One page of the download queue.
   *
   * **Both parameters are sent on every call and neither has a safe default.** `PagingResource`
   * defaults `pageSize` to **10** (measured: a bare `GET /api/v1/queue` answers `"pageSize": 10`),
   * so a client that accepted the default stops seeing its own request the moment the user has
   * eleven things downloading -- and reports `Requested` forever with nothing wrong anywhere.
   * `includeUnknownArtistItems` defaults to **false**, which hides exactly the records whose
   * artist Lidarr has not resolved yet -- and an album added seconds ago is the case where it has
   * not.
   *
   * Both are `@Query`, so both appear on the URL; neither is a secret and the one value that is
   * never allowed there is asserted over this endpoint by `LidarrQueueTest`'s key-placement test.
   */
  @GET("api/v1/queue")
  suspend fun queue(
    @Query("pageSize") pageSize: Int,
    @Query("includeUnknownArtistItems") includeUnknownArtistItems: Boolean,
  ): Response<QueuePageBody>

  /**
   * One album from the user's own database, by its Lidarr id.
   *
   * **The single-id getter really does populate `statistics`** -- the plan listed this as *not
   * established* ("the single-id getter uses the same mapper but was not observed") and it is now
   * observed, on the live `3.1.0.4875-ls40`: `GET /api/v1/album/7` answers
   * `"statistics":{"trackFileCount":0,"trackCount":10,"totalTrackCount":10,"sizeOnDisk":0,
   * "percentOfTracks":0}`, byte-identical to the same album's entry in the list form.
   *
   * **But `statistics` is absent entirely for an album whose tracks Lidarr has not fetched yet**,
   * which is measured and is not the same fact. Seconds after a successful add, the new album had
   * `"releases": []`, zero rows from `GET /api/v1/track?albumId=`, and **no `statistics` key at
   * all** -- in the single getter *and* in the list, so it is a property of the album rather than
   * of the endpoint. That is the ordinary state of a request the moment it is made, and it is why
   * [LidarrClient.albumProgress] answers `null` rather than `LidarrAlbumProgress(0, 0)`: "Lidarr
   * has not counted yet" and "Lidarr counted zero of zero" are different facts, and only the
   * second could ever be mistaken for "complete".
   *
   * A 404 is a normal answer here, not an error -- measured on both an id that never existed
   * (`/api/v1/album/99999`) and one whose album was deleted while a request row still named it.
   */
  @GET("api/v1/album/{id}")
  suspend fun album(@Path("id") id: Int): Response<AlbumWithStatisticsBody>

}
