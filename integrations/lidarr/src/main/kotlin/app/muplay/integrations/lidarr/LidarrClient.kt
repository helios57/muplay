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
 * The OkHttp stack carries exactly one interceptor, [LidarrAuthInterceptor], installed as a
 * **network** interceptor so that it sees every redirect hop and can withhold the API key from any
 * hop that leaves the configured origin -- see that class for the whole argument. **No logging
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
   * The add.
   *
   * A 400 is caught and turned into an outcome rather than propagating, because two of the three
   * things Lidarr can say here are things a user can act on. Everything else — a 401, a 503, a
   * transport failure — keeps propagating.
   *
   * **A success with no `id` is a loud failure, deliberately.** Returning `Added(albumId = 0)`
   * would put a row in the request store that every later status poll looks up under an id no
   * album has: the silent-wrong-answer class. The exception carries the status that actually came
   * back rather than the `201` this endpoint is measured to return, so a proxy that rewrote the
   * status is reported as what it did.
   */
  override suspend fun submitAlbum(
    candidate: LidarrAlbumCandidate,
    targets: LidarrAddTargets,
    searchNow: Boolean,
  ): LidarrAddOutcome =
    try {
      val response = proven { api.addAlbum(LidarrAddPayload.build(candidate, targets, searchNow)) }
      // `RestController.Created` re-fetches the persisted resource, so the id here is the real
      // database id -- not the synthetic counter `/api/v1/album/lookup` assigns.
      LidarrAddOutcome.Added(
        albumId = response.body()?.int("id") ?: throw LidarrHttpException(response.code()),
      )
    } catch (e: LidarrValidationException) {
      if (e.isAlreadyAdded) LidarrAddOutcome.AlreadyAdded else LidarrAddOutcome.Rejected(e.failures)
    }

  /**
   * The database id of an album already in the library, by its MusicBrainz id.
   *
   * **The identifier is matched back out of the answer rather than trusted.** `GET /api/v1/album`
   * with no `foreignAlbumId` is a legal request that returns the *whole library* — measured, 200
   * with every album — so a client whose parameter went missing would receive a perfectly valid
   * response about the wrong records and hand the first one's id to every later status poll. That
   * is the same defect this task's payload tests exist to refuse, on the read side, and it costs
   * one comparison to make impossible.
   */
  override suspend fun findAddedAlbumId(foreignAlbumId: String): Int? =
    call { api.albumsByForeignId(foreignAlbumId) }
      .firstOrNull { it.string("foreignAlbumId") == foreignAlbumId }
      ?.int("id")

  /**
   * One page of the queue, mapped.
   *
   * The page size and `includeUnknownArtistItems` are both sent explicitly; see [LidarrApi.queue]
   * for why neither default is safe. A body that is not a queue page yields an empty list rather
   * than a throw, because "nothing is downloading" is the fail-closed reading of "I could not read
   * the queue" and a status poll has nothing to show a user for a `SerializationException`.
   */
  override suspend fun queue(): List<LidarrQueueItem> =
    call { api.queue(pageSize = QUEUE_PAGE_SIZE, includeUnknownArtistItems = true) }
      .records
      .orEmpty()
      .map { record ->
        LidarrQueueItem(
          albumId = record.albumId,
          artistId = record.artistId,
          sizeBytes = record.size,
          // `record.sizeleft`, lower-case `l`. See `QueueRecordBody`.
          sizeLeftBytes = record.sizeleft,
          trackedDownloadState = record.trackedDownloadState.orEmpty(),
          trackedDownloadStatus = record.trackedDownloadStatus.orEmpty(),
          errorMessage = record.errorMessage,
        )
      }

  /**
   * How much of [albumId] is on disk.
   *
   * **A 404 is a normal answer to "how is this going"** -- a user can delete an album in Lidarr
   * while MuPlay still holds a request row naming it, and measured, an id that no longer exists
   * (and one that never did) answers 404. Anything else still propagates: a 401 here means the key
   * stopped working, which is not something a poller may swallow.
   *
   * A missing `statistics` object yields `null` rather than `LidarrAlbumProgress(0, 0)`, and that
   * distinction is load-bearing rather than fastidious: measured on the live container, an album
   * seconds after a successful add carries **no `statistics` key at all**, and only a real
   * `0`-of-`0` could ever be mistaken by a caller for a count.
   */
  override suspend fun albumProgress(albumId: Int): LidarrAlbumProgress? =
    try {
      call { api.album(albumId) }.statistics
        ?.let { LidarrAlbumProgress(it.trackFileCount, it.totalTrackCount) }
    } catch (e: LidarrHttpException) {
      if (e.status == HTTP_NOT_FOUND) null else throw e
    }

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
      alreadyAdded = obj.int("id")?.let { it != 0 } ?: false,
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
   * An integer field, or `null` when it is absent or is not one.
   *
   * `content.toIntOrNull()` rather than `.int`, because `.int` throws on a quoted or non-numeric
   * value and Lidarr's `JsonStringEnumConverter(..., allowIntegerValues = true)` means numbers and
   * strings are interchangeable on the wire in both directions. `null` is the honest answer for
   * every shape that is not an id, and every caller here treats it as "no id" rather than as zero.
   */
  private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

  /**
   * Runs [request] and returns its body only once the response is proven successful.
   *
   * A successful response with no body at all is a [LidarrHttpException] carrying the status
   * rather than a `NullPointerException`: Retrofit produces a null body for a 204, and Tasks 5-7
   * add endpoints that could receive one.
   */
  internal suspend fun <T : Any> call(request: suspend () -> Response<T>): T {
    val response = proven(request)
    return response.body() ?: throw LidarrHttpException(response.code())
  }

  /**
   * Runs [request] and returns the **response** once its status is proven successful.
   *
   * The status-code cascade is ordered by specificity, and the 503 case reads the **body** before
   * deciding: a 503 from Lidarr booting and a 503 from a reverse proxy with no upstream are
   * different facts, and collapsing them would send a user to check their firewall while their
   * container finished starting.
   *
   * Separate from [call] because [submitAlbum] needs the status code as well as the body: a
   * successful add with no `id` has to fail naming the status that really came back, and hardcoding
   * the `201` this endpoint is measured to return would be a constant standing in for a value —
   * the defect class this whole task exists to refuse.
   */
  private suspend fun <T : Any> proven(request: suspend () -> Response<T>): Response<T> {
    val response = request()
    if (response.isSuccessful) return response
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
      .map { LidarrValidationFailure(it.propertyName, it.errorMessage, it.errorCode) }

  internal companion object {

    /**
     * `PagingResource` defaults `pageSize` to **10** -- measured, a bare `GET /api/v1/queue`
     * answers `"pageSize": 10`. A client that accepted that would stop seeing its own request as
     * soon as the user had eleven things downloading, and would report `Requested` forever with
     * nothing wrong anywhere. This client does not follow paging, so this number is the whole
     * window it ever sees.
     */
    private const val QUEUE_PAGE_SIZE = 100

    /** The one status [albumProgress] swallows. Named rather than inline so the probe can move it. */
    private const val HTTP_NOT_FOUND = 404

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
        // `addNetworkInterceptor`, NOT `addInterceptor`, and `followRedirects` is left at its
        // default `true`. Both halves of that are load-bearing and both are argued at
        // [LidarrAuthInterceptor]: an application interceptor stamps the key once, before redirect
        // handling, and OkHttp carries a custom header to a cross-origin redirect target verbatim
        // -- it strips `Authorization` and nothing else. A network interceptor runs per hop, which
        // is what lets the key be scoped to the configured origin while a `urlBase` install's
        // same-origin 307 keeps working.
        .addNetworkInterceptor(LidarrAuthInterceptor(credentials.baseUrl, credentials.apiKey))
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
