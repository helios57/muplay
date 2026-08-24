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
 * model is silently dropped by `Json(ignoreUnknownKeys = true)` — configured where this is
 * deserialized — rather than failing to parse at all.
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
  val openSubsonicExtensions: List<OpenSubsonicExtensionBody>? = null,
  val albumList2: AlbumList2Body? = null,
  val album: AlbumBody? = null,
  val searchResult3: SearchResult3Body? = null,
  val randomSongs: SongsBody? = null,
  val scanStatus: ScanStatusBody? = null,
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

/**
 * The OpenSubsonic `OpenSubsonicExtension` schema: one entry of `getOpenSubsonicExtensions`'
 * `openSubsonicExtensions` array. Both [name] and [versions] are required per the schema, but
 * [versions] still defaults to an empty list here — the same defensive stance every other field in
 * this file takes against a non-compliant server, not an assumption that the schema allows it to
 * be absent.
 *
 * [versions] can itself legitimately *be* an empty array even from a compliant server (the schema
 * places no `minItems` constraint on it) — [app.muplay.model.ServerCapabilities] is built to treat
 * that the same as the extension not being advertised at all; see its own documentation.
 */
@Serializable
data class OpenSubsonicExtensionBody(
  val name: String,
  val versions: List<Int> = emptyList(),
)

/** The OpenSubsonic `AlbumList2` schema: a wrapper object around the album list. */
@Serializable
data class AlbumList2Body(
  val album: List<AlbumBody> = emptyList(),
)

/**
 * The OpenSubsonic `AlbumID3` schema, narrowed to the fields this client uses. Only `id`, `name`,
 * `songCount`, `duration` and `created` are required by the schema; everything optional is
 * nullable or defaulted here.
 *
 * [song] is present only on a `getAlbum` response (the schema puts it on `AlbumID3` itself), so
 * it defaults to empty for every `getAlbumList2`/`search3` album.
 *
 * `userRating` is deliberately **not** modelled. Navidrome sends `0` for an unrated album while
 * the schema declares `[1-5]`, which is the single reason four of this project's captured
 * fixtures fail the OpenAPI oracle (see `NavidromeSpecDeviationTest`); nothing here uses it, and
 * `Json(ignoreUnknownKeys = true)` drops it.
 */
@Serializable
data class AlbumBody(
  val id: String,
  val name: String,
  val artist: String? = null,
  val artistId: String? = null,
  val coverArt: String? = null,
  val songCount: Int = 0,
  val duration: Int = 0,
  val song: List<ChildBody> = emptyList(),
)

/**
 * The OpenSubsonic `Child` schema, narrowed to the fields this client uses. Only `id`, `isDir`
 * and `title` are required by the schema.
 *
 * [type] is modelled and then deliberately ignored by every mapper: Navidrome hardcodes it to
 * `"music"` for every media file including audiobooks, so reading it would be reading a constant.
 * It is kept so the next reader can see that it was considered rather than missed.
 */
@Serializable
data class ChildBody(
  val id: String,
  val title: String,
  val album: String? = null,
  val albumId: String? = null,
  val artist: String? = null,
  val artistId: String? = null,
  val track: Int? = null,
  val discNumber: Int? = null,
  val duration: Int = 0,
  val suffix: String? = null,
  val coverArt: String? = null,
  val type: String? = null,
  val isDir: Boolean = false,
)

/** The OpenSubsonic `ArtistID3` schema, narrowed. Only `id` and `name` are required. */
@Serializable
data class ArtistBody(
  val id: String,
  val name: String,
  val coverArt: String? = null,
  val albumCount: Int = 0,
)

/** The OpenSubsonic `SearchResult3` schema: three optional arrays. */
@Serializable
data class SearchResult3Body(
  val artist: List<ArtistBody> = emptyList(),
  val album: List<AlbumBody> = emptyList(),
  val song: List<ChildBody> = emptyList(),
)

/** The OpenSubsonic `Songs` schema, the payload of `getRandomSongs`. */
@Serializable
data class SongsBody(
  val song: List<ChildBody> = emptyList(),
)

/**
 * The Subsonic `ScanStatus` element. `scanning` is the only field the schema requires; `count` is
 * optional. [lastScan] is Navidrome's own extension and is absent from the vendored spec
 * entirely, together with `folderCount`, `scanType` and `elapsedTime` — see
 * `NavidromeSpecDeviationTest`. It is the field this project's whole sync design rests on, so it
 * is modelled here even though the oracle does not know about it, and typed `String?` so a server
 * that omits it degrades to "cannot tell" rather than failing to parse.
 */
@Serializable
data class ScanStatusBody(
  val scanning: Boolean,
  val count: Int? = null,
  val lastScan: String? = null,
)
