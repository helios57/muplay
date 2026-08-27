package app.muplay.integrations.lidarr

import app.muplay.integrations.IntegrationCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * A typed Kotlin client over [LidarrApi].
 *
 * The OkHttp stack carries exactly one interceptor, [LidarrAuthInterceptor]. **No logging
 * interceptor is installed and none may be added**: it would print the `X-Api-Key` header on every
 * request, which is the same secret this app seals into the Android Keystore two modules away.
 * `ConventionTest`'s `nothing in integrations writes to a log` refuses the obvious form of that
 * mistake; the absence of `okhttp-logging-interceptor` from this module's dependencies is what
 * stops the less obvious one.
 *
 * The public constructor takes credentials only. The two-argument one is `internal` because
 * [LidarrApi] is: a public signature naming an internal type does not compile, and widening
 * `LidarrApi` to make it fit would put Retrofit's raw `Response<PingBody>` surface into this
 * module's public API for no caller's benefit.
 */
class LidarrClient internal constructor(
  private val api: LidarrApi,
) : LidarrSource {

  /**
   * The production constructor: a real Retrofit stack over [credentials].
   *
   * [credentials] is consumed here and not retained. Nothing this class does afterwards needs the
   * API key again — the interceptor holds it — and a client that kept a copy would be one more
   * object with a secret field for a `toString()` to print.
   */
  constructor(credentials: IntegrationCredentials.Lidarr) : this(buildApi(credentials))

  override suspend fun ping(): Boolean =
    runCatching { api.ping() }
      .map { response -> response.isSuccessful && response.body()?.status.equals("OK", ignoreCase = true) }
      .getOrDefault(false)

  override suspend fun status(): LidarrServer {
    val body = call { api.systemStatus() }
    return LidarrServer(
      appName = body.appName.orEmpty(),
      instanceName = body.instanceName.orEmpty(),
      version = body.version.orEmpty(),
      urlBase = body.urlBase.orEmpty(),
      authentication = body.authentication.orEmpty(),
    )
  }

  override suspend fun lookupAlbums(term: String): List<LidarrAlbumCandidate> =
    call { api.albumLookup(term) }.mapNotNull(::toCandidate)

  override suspend fun rootFolders(): List<LidarrRootFolder> =
    call { api.rootFolders() }.map { body ->
      LidarrRootFolder(
        id = body.id,
        // A folder with no name is shown by its path rather than by a blank row.
        name = body.name?.takeIf { it.isNotBlank() } ?: body.path.orEmpty(),
        path = body.path.orEmpty(),
        accessible = body.accessible,
        freeSpaceBytes = body.freeSpace,
        defaultQualityProfileId = body.defaultQualityProfileId,
        defaultMetadataProfileId = body.defaultMetadataProfileId,
        defaultMonitorOption = body.defaultMonitorOption.orEmpty(),
        defaultNewItemMonitorOption = body.defaultNewItemMonitorOption.orEmpty(),
      )
    }

  override suspend fun qualityProfiles(): List<LidarrProfile> =
    call { api.qualityProfiles() }.map { LidarrProfile(it.id, it.name.orEmpty()) }

  override suspend fun metadataProfiles(): List<LidarrProfile> =
    call { api.metadataProfiles() }.map { LidarrProfile(it.id, it.name.orEmpty()) }

  /**
   * A typed view over one lookup element, or `null` when the element cannot be used for an add.
   *
   * An element with no `foreignAlbumId`, or whose nested artist has no `foreignArtistId`, is
   * unusable: both are required by `AlbumController`'s `PostValidator`. Dropping such a row keeps
   * every other result usable, where failing the whole parse would lose all of them.
   */
  private fun toCandidate(element: JsonElement): LidarrAlbumCandidate? {
    val obj = element as? JsonObject ?: return null
    val artist = obj["artist"] as? JsonObject
    val foreignAlbumId = obj.string("foreignAlbumId") ?: return null
    val foreignArtistId = artist?.string("foreignArtistId") ?: return null
    return LidarrAlbumCandidate(
      foreignAlbumId = foreignAlbumId,
      title = obj.string("title").orEmpty(),
      // Blank collapses to null. Measured on a real lookup, `disambiguation` is `""` on every
      // element rather than omitted, so without this a surface would have two ways to say nothing.
      disambiguation = obj.string("disambiguation")?.takeIf { it.isNotBlank() },
      albumType = obj.string("albumType"),
      releaseDate = releaseDate(obj),
      // `remoteCover`, never `remotePoster`: AlbumLookupController sets the former and only
      // ArtistLookupController sets the latter. Measured, an album-lookup element carries no
      // `remotePoster` key at all, so reading the wrong one yields null on every row silently.
      remoteCoverUrl = obj.string("remoteCover"),
      artistName = artist.string("artistName").orEmpty(),
      foreignArtistId = foreignArtistId,
      alreadyAdded = (obj["id"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { it != 0 } ?: false,
      raw = obj,
    )
  }

  /**
   * `releaseDate` with .NET's `DateTime.MinValue` read as "unknown".
   *
   * Measured on `3.1.0.4875-ls40`: an album whose release date Lidarr does not know sends
   * `"0001-01-01T00:00:00Z"` rather than omitting the field — one of the seven elements in
   * `fixtures/lidarr/album-lookup.json` does exactly that. Matching the date part rather than the
   * whole instant, because the sentinel is the *value* `DateTime.MinValue` and its rendering is
   * the serializer's business, not this client's.
   */
  private fun releaseDate(obj: JsonObject): String? =
    obj.string("releaseDate")?.takeIf { !it.startsWith(DATE_TIME_MIN_VALUE) }

  private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

  /**
   * Runs [request] and returns its body only once the response is proven successful.
   *
   * The status-code cascade is ordered by specificity, and the 503 case reads the **body** before
   * deciding: a 503 from Lidarr booting and a 503 from a reverse proxy with no upstream are
   * different facts, and collapsing them would send a user to check their firewall while their
   * container finished starting.
   *
   * A successful response with no body at all is a [LidarrHttpException] carrying the status
   * rather than a `NullPointerException`: Retrofit produces a null body for a 204, and Tasks 5-7
   * add endpoints that could receive one.
   */
  internal suspend fun <T : Any> call(request: suspend () -> Response<T>): T {
    val response = request()
    if (response.isSuccessful) {
      return response.body() ?: throw LidarrHttpException(response.code())
    }
    val raw = response.errorBody()?.string().orEmpty()
    throw when (response.code()) {
      401 -> LidarrUnauthorizedException()
      400 -> LidarrValidationException(parseValidationFailures(raw))
      503 -> if (isStartingUp(raw)) LidarrStartingUpException() else LidarrHttpException(503)
      else -> LidarrHttpException(response.code())
    }
  }

  private fun isStartingUp(raw: String): Boolean =
    runCatching { json.decodeFromString<StartingUpBody>(raw) }
      .getOrNull()
      ?.errorMessage
      ?.contains("starting up", ignoreCase = true) == true

  /**
   * A 400 body is a bare JSON **array** of FluentValidation failures
   * (`LidarrErrorPipeline.cs`: `STJson.ToJson(validationException.Errors)`), not an object — see
   * `fixtures/lidarr/validation-error-empty-album.json`, captured from a real `POST /api/v1/album`
   * with an empty body.
   *
   * A body that is neither — a proxy's HTML error page carrying a 400 — yields an empty list
   * rather than a parse failure, so the caller still gets a [LidarrValidationException] it can
   * show rather than a `SerializationException` it cannot.
   */
  private fun parseValidationFailures(raw: String): List<LidarrValidationFailure> =
    runCatching { json.decodeFromString<List<ValidationFailureBody>>(raw) }
      .getOrDefault(emptyList())
      .map { LidarrValidationFailure(it.propertyName, it.errorMessage) }

  internal companion object {

    /**
     * The date part of .NET's `DateTime.MinValue` as Lidarr's serializer renders it.
     *
     * `0001-01-01T00:00:00Z` on the build measured here; the prefix is matched so that a
     * serializer change to the time part or the offset does not turn the sentinel back into a
     * release date in the year 1.
     */
    private const val DATE_TIME_MIN_VALUE = "0001-01-01"

    val json: Json = Json {
      // Lidarr adds fields between versions and omits every null-valued one. Neither may break
      // this client. Measured: `system/status` on 3.1.0.4875 carries thirty fields; this client
      // declares five.
      ignoreUnknownKeys = true
      explicitNulls = false
    }

    private fun buildApi(credentials: IntegrationCredentials.Lidarr): LidarrApi {
      val http = OkHttpClient.Builder()
        .addInterceptor(LidarrAuthInterceptor(credentials.apiKey))
        .build()
      return Retrofit.Builder()
        // `IntegrationBaseUrl.value` always ends in `/`, which Retrofit requires: without it,
        // resolving `api/v1/system/status` against `https://host/lidarr` would drop the `lidarr`
        // segment. That guarantee is the type's, not this call site's -- and it is why
        // `IntegrationBaseUrl.parse` keeps the path verbatim instead of stripping it the way it
        // strips the query, the fragment and userinfo.
        .baseUrl(credentials.baseUrl.value)
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(LidarrApi::class.java)
    }
  }
}
