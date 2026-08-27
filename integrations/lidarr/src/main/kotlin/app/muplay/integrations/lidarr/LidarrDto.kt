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
 * There is **no `attemptedValue`** and no `isWarning`. Only the first two are read here; the rest
 * are left to `ignoreUnknownKeys` because nothing in this app has a use for them and a field this
 * client parses is a field it then owns.
 */
@Serializable
internal data class ValidationFailureBody(
  val propertyName: String? = null,
  val errorMessage: String? = null,
)

/** The body a 503 carries while Lidarr boots (`StartingUpMiddleware.cs`). */
@Serializable
internal data class StartingUpBody(val errorMessage: String? = null)
