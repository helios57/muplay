package app.muplay.integrations.lidarr

import kotlinx.serialization.Serializable

/**
 * The wire shapes this client reads.
 *
 * **Every non-primitive field is nullable and defaulted**, and that is not defensive habit:
 * Lidarr's serializer is configured with `DefaultIgnoreCondition = JsonIgnoreCondition
 * .WhenWritingNull` (`src/NzbDrone.Common/Serializer/System.Text.Json/STJson.cs`), so a
 * null-valued field is **omitted from the response entirely** rather than serialised as `null`. A
 * non-nullable Kotlin field with no default would fail to parse a perfectly ordinary response.
 *
 * Names are camelCase because `PropertyNamingPolicy = JsonNamingPolicy.CamelCase`. Enums arrive as
 * camelCase strings (`JsonStringEnumConverter(JsonNamingPolicy.CamelCase, true)`) and are read as
 * `String` rather than as Kotlin enums: the trailing `true` is `allowIntegerValues`, so the same
 * field can legally arrive as a number, and a Lidarr upgrade may add a member. An unknown member
 * must not fail a whole response.
 */
@Serializable
internal data class PingBody(val status: String? = null)

/**
 * `GET /api/v1/system/status`, reduced to the five fields this app has a use for.
 *
 * Captured verbatim from a real Lidarr `3.1.0.4875` (linuxserver `3.1.0.4875-ls40`) into
 * `src/test/resources/fixtures/lidarr/system-status.json`, which carries **thirty** fields. The
 * other twenty-five are dropped by `ignoreUnknownKeys`, which is the property that lets this DTO
 * stay this small across Lidarr upgrades.
 */
@Serializable
internal data class SystemStatusBody(
  val appName: String? = null,
  val instanceName: String? = null,
  val version: String? = null,
  val urlBase: String? = null,
  val authentication: String? = null,
)

/**
 * One element of the JSON **array** a 400 carries.
 *
 * `LidarrErrorPipeline.cs` writes `STJson.ToJson(validationException.Errors)` — a bare array of
 * FluentValidation failures, not an object. `propertyName` is PascalCase and dotted for nested
 * paths (`Artist.QualityProfileId`).
 *
 * The plan listed the exact key set of one failure as *not established*. It is now, measured
 * against the pinned container with `POST /api/v1/album -d '{}'` and committed as
 * `fixtures/lidarr/validation-error-empty-album.json`: `propertyName`, `errorMessage`,
 * `severity`, `errorCode`, `formattedMessageArguments`, `formattedMessagePlaceholderValues`.
 * There is no `isWarning`.
 *
 * **Task 4 recorded "there is no `attemptedValue`" from that fixture, and that generalised too
 * far.** Measured at Task 6 against the same container: `attemptedValue` is present on every
 * failure whose attempted value was not null — `"c35e782d-…"` on a duplicate add, `999` on an
 * unknown quality profile, `0` on a zero metadata profile, `"/nope"` on a missing root folder. The
 * empty-body fixture lacks it because the value it would have carried was null, and Lidarr omits
 * null-valued fields. It is still not read here, and now for a reason that survives the correction:
 * it echoes what this client sent, which this client already knows.
 *
 * `errorCode` **is** read, and it is the one field here that changed hands between tasks. Task 4
 * left it to `ignoreUnknownKeys` on the grounds that nothing had a use for it; Task 6's
 * [LidarrValidationException.isAlreadyAdded] does, because it is the only machine-readable way to
 * recognise a duplicate add. The other three keys stay unparsed on Task 4's original argument: a
 * field this client parses is a field it then owns.
 */
@Serializable
internal data class ValidationFailureBody(
  val propertyName: String? = null,
  val errorMessage: String? = null,
  val errorCode: String? = null,
)

/** The body a 503 carries while Lidarr boots (`StartingUpMiddleware.cs`). */
@Serializable
internal data class StartingUpBody(val errorMessage: String? = null)

/**
 * A root folder, with the four defaults that let one picker satisfy every required add field.
 *
 * `freeSpace` and `totalSpace` are `long?` in the resource and are genuinely absent for an
 * inaccessible folder, which is why `freeSpace` is nullable here rather than defaulted to zero —
 * "zero bytes free" and "we do not know" are different things to show a user. `totalSpace` is not
 * read at all: nothing in this app has a use for it, and a field this client parses is a field it
 * then owns.
 *
 * Captured from the pinned container into `fixtures/lidarr/rootfolder.json`, which also carries
 * `defaultTags` and `totalSpace`; both are dropped by `ignoreUnknownKeys`.
 */
@Serializable
internal data class RootFolderBody(
  val id: Int = 0,
  val name: String? = null,
  val path: String? = null,
  val accessible: Boolean = false,
  val freeSpace: Long? = null,
  val defaultQualityProfileId: Int = 0,
  val defaultMetadataProfileId: Int = 0,
  val defaultMonitorOption: String? = null,
  val defaultNewItemMonitorOption: String? = null,
)

/**
 * Quality and metadata profiles share the only two fields an add needs.
 *
 * One type for both endpoints because both resources really do carry `id` and `name` at the top
 * level and nothing else this app reads — measured against `fixtures/lidarr/qualityprofile.json`
 * (3 profiles: `Any`, `Lossless`, `Standard`) and `fixtures/lidarr/metadataprofile.json`
 * (2 profiles: `Standard`, `None`), whose other 34 KB and 7 KB are quality/album-type trees.
 */
@Serializable
internal data class ProfileBody(val id: Int = 0, val name: String? = null)

/**
 * One page of Lidarr's queue (`PagingResource<QueueResource>`).
 *
 * **`records` is nullable, and the plan's stated reason for that is wrong.** The plan predicted the
 * array would be *omitted* when empty under `WhenWritingNull`. Measured against the live
 * `3.1.0.4875-ls40` this task ran against, an empty queue answers
 * `{"page":1,"pageSize":100,"sortKey":"timeleft","sortDirection":"descending","totalRecords":0,
 * "records":[]}` — the key is **present, as `[]`**, because an empty list is not a null one and
 * `WhenWritingNull` never fires on it.
 *
 * The nullability is kept anyway, on a reason that survives the correction: a body that is *not*
 * a queue page at all — a reverse proxy's JSON error document, a truncated response — must degrade
 * to "nothing is downloading" rather than to a `SerializationException` a status poll cannot show
 * anyone. `LidarrQueueTest`'s `an absent records array is an empty queue, not a failure` is the
 * assertion, and it is now testing a defensive path rather than the everyday one.
 *
 * `sortKey` and `sortDirection` are real fields on this response and are deliberately not read:
 * a field this client parses is a field it then owns.
 */
@Serializable
internal data class QueuePageBody(
  val page: Int = 0,
  val pageSize: Int = 0,
  val totalRecords: Int = 0,
  val records: List<QueueRecordBody>? = null,
)

/**
 * One queue record.
 *
 * **`sizeleft`, lower-case `l`.** `QueueResource` declares `Sizeleft` as a single word, and
 * `JsonNamingPolicy.CamelCase` lower-cases only the leading capital — so it reaches the wire as
 * `sizeleft` and **not** `sizeLeft`. A client reading `sizeLeft` gets kotlinx's default `0.0` on
 * every record and shows every download at 100% forever, with no parse error anywhere.
 *
 * **Not observed on a live wire, and that is a limitation rather than an omission.** A queue record
 * exists only while a download client is working, and the container this task ran against has no
 * download client and no indexer; `GET /api/v1/queue` answered `"records":[]` on every call. The
 * field set here rests on `QueueResource.cs` and the plan's provenance table, and
 * `fixtures/lidarr/queue-downloading.json` is **constructed from that source, not captured** —
 * which its own header says, because a fixture that claims to be a capture and is not is worse
 * than no fixture. `LiveLidarrTest` is where a real record should be asserted the day this
 * container grows a download client.
 *
 * `id` is read and deliberately not surfaced: the queue is a live merge of the download client's
 * queue and pending releases, so it is not durable across polls and every correlation this app
 * makes is on `albumId`.
 */
@Serializable
internal data class QueueRecordBody(
  val id: Int = 0,
  val albumId: Int? = null,
  val artistId: Int? = null,
  val size: Double = 0.0,
  val sizeleft: Double = 0.0,
  val trackedDownloadState: String? = null,
  val trackedDownloadStatus: String? = null,
  val errorMessage: String? = null,
)

/**
 * `GET /api/v1/album/{id}`, reduced to the one nested object a status poll needs.
 *
 * The other twenty-one top-level fields a real response carries are dropped by `ignoreUnknownKeys`
 * — see `fixtures/lidarr/album-with-statistics.json`, captured verbatim from the live container.
 */
@Serializable
internal data class AlbumWithStatisticsBody(
  val id: Int = 0,
  val statistics: AlbumStatisticsBody? = null,
)

/**
 * `AlbumStatisticsResource`, reduced to the two integers that decide "is it here yet".
 *
 * **`percentOfTracks` is on this resource and is deliberately not read.** It is a `double` on a
 * **0–100** scale, not 0–1, so a client that assumed the other convention shows `0.73%` forever
 * on a fully-downloaded album. Measured on the live container it reads `0` beside
 * `trackFileCount: 0, totalTrackCount: 10` — a value that is consistent with *either* scale and
 * therefore settles nothing, which is exactly why this client compares two integers instead.
 * `trackCount` and `sizeOnDisk` are also present and also unread.
 */
@Serializable
internal data class AlbumStatisticsBody(
  val trackFileCount: Int = 0,
  val totalTrackCount: Int = 0,
)
