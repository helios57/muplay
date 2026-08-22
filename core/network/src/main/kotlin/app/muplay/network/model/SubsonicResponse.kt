package app.muplay.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The outer envelope every Subsonic response is wrapped in, keyed by the literal JSON field
 * `subsonic-response` — a hyphenated name, not a legal Kotlin identifier, hence [SerialName].
 */
@Serializable
data class SubsonicEnvelope(
  @SerialName("subsonic-response") val subsonicResponse: SubsonicResponseBody,
)

/**
 * One DTO shared by every Subsonic command this client calls, rather than a distinct
 * `@Serializable` type per command's response.
 *
 * The OpenSubsonic spec models a command's success payload as `SubsonicBaseResponse`
 * (`version`/`type`/`serverVersion`/`openSubsonic`, all required) composed via `allOf` with either
 * a `status: "ok"` extension plus command-specific fields, or `SubsonicFailureResponse`
 * (`status: "failed"` plus a required `error`) — a `oneOf` between the two. A single flattened DTO
 * with every field nullable models that union without a custom polymorphic deserializer: whichever
 * branch the server actually sent, the fields that branch does not have simply decode as `null`
 * (or, for [musicFolders], as absent). Anything a response can carry that this class does not
 * model (e.g. `getOpenSubsonicExtensions`' `openSubsonicExtensions` field, unused before Task 5) is
 * silently dropped by `Json(ignoreUnknownKeys = true)` — configured where this is deserialized —
 * rather than failing to parse at all.
 */
@Serializable
data class SubsonicResponseBody(
  val status: String? = null,
  val version: String? = null,
  val type: String? = null,
  val serverVersion: String? = null,
  val openSubsonic: Boolean? = null,
  val error: SubsonicErrorBody? = null,
  val musicFolders: MusicFoldersBody? = null,
)

/**
 * The OpenSubsonic `SubsonicError` schema: [code] is required, [message] is optional. `helpUrl`
 * (also optional in the schema) is not modeled — nothing in this client uses it yet.
 */
@Serializable
data class SubsonicErrorBody(
  val code: Int,
  val message: String? = null,
)

/** The OpenSubsonic `MusicFolders` schema: a wrapper object around the folder list. */
@Serializable
data class MusicFoldersBody(
  val musicFolder: List<MusicFolderBody> = emptyList(),
)

/**
 * The OpenSubsonic `MusicFolder` schema. Only [id] is required — [name] is genuinely optional per
 * the spec (`required: ["id"]`), which [app.muplay.network.SubsonicClient.getMusicFolders] accounts
 * for with a fallback rather than assuming every folder is named.
 */
@Serializable
data class MusicFolderBody(
  val id: Int,
  val name: String? = null,
)
