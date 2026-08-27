package app.muplay.integrations.lidarr

import retrofit2.Response
import retrofit2.http.GET

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
 * the versioned controllers. Tasks 5-7 add the rest.
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
}
