package app.muplay.integrations.lidarr

import app.muplay.integrations.IntegrationCredentials

/**
 * Everything this app asks of a Lidarr server, as one interface.
 *
 * A **port**, exactly like `SubsonicSource` in `:core:network`, and for the same single reason: a
 * test needs to make a *specific call* fail at a *specific point* — Task 9's status poller must not
 * advance a request's state when the third of four calls fails — and no real Lidarr can be asked
 * to do that on demand. A hand-written fake implementing this interface can, with no mock
 * framework anywhere near the build.
 */
interface LidarrSource {

  /**
   * Whether *something that answers Lidarr's unauthenticated ping* is listening.
   *
   * Never throws: its whole value is being a question that always has an answer, so the
   * configuration screen can distinguish "nothing is there" from "something is there and rejected
   * our key" without a try/catch of its own.
   *
   * **This does not prove the server is Lidarr.** Sonarr, Radarr and Prowlarr serve a
   * byte-identical `{"status":"OK"}` at the same path, and `/ping` is the only `[AllowAnonymous]`
   * endpoint, so it cannot check a credential either. [status] is what proves both.
   */
  suspend fun ping(): Boolean

  /** Identity, version and `urlBase`, authenticated. Throws a [LidarrException] on failure. */
  suspend fun status(): LidarrServer

  /**
   * Albums matching [term], from Lidarr's metadata lookup.
   *
   * **Not served from the user's own database.** It proxies to `api.lidarr.audio`, so it is slow,
   * can fail while the user's own server is healthy, and is rate-limited upstream. Callers debounce
   * and do not retry automatically.
   *
   * [term] is sent as the user typed it. This client does not send the `lidarr:`/`lidarrid:`/
   * `mbid:` prefixes `SkyHookProxy.IsMbidQuery` understands, and that is a scope decision rather
   * than an omission: measured on the pinned container, `term=lidarr:not-a-guid` is answered
   * **200 with `[]`** — indistinguishable from an honest "nothing matched" — so supporting the
   * prefixes without also solving that ambiguity would make one of them a lie.
   */
  suspend fun lookupAlbums(term: String): List<LidarrAlbumCandidate>

  /** The folders this Lidarr can file an album into, with the defaults an add needs. */
  suspend fun rootFolders(): List<LidarrRootFolder>

  suspend fun qualityProfiles(): List<LidarrProfile>

  suspend fun metadataProfiles(): List<LidarrProfile>

  /**
   * Asks Lidarr to add [candidate], filed according to [targets].
   *
   * [searchNow] becomes `addOptions.searchForNewAlbum`. Note that the artist's own
   * `searchForMissingAlbums` is always sent as `false`, because a `true` there makes the server
   * silently cancel the album search — see [LidarrAddPayload].
   *
   * Returns an outcome rather than throwing on refusal, because two of the three outcomes are
   * things a user can act on. A 401 still throws: losing authentication is not something the
   * request screen can offer a next step for.
   */
  suspend fun submitAlbum(
    candidate: LidarrAlbumCandidate,
    targets: LidarrAddTargets,
    searchNow: Boolean,
  ): LidarrAddOutcome

  /**
   * The database id of an already-added album, by its MusicBrainz id, or `null` if it is not there.
   *
   * `null` covers three different situations on purpose — the album is not in the library, the
   * server answered with rows that are **not** the album that was asked for, or the matching row
   * carried no usable `id`. All three mean the same thing to a caller: there is no id to poll, and
   * inventing one would send every later status check at somebody else's album.
   */
  suspend fun findAddedAlbumId(foreignAlbumId: String): Int?
}

/**
 * What happened when Lidarr was asked to add an album.
 *
 * A sealed result rather than "success or exception", because [AlreadyAdded] is a **normal**
 * outcome — a user asking twice, or asking for something a housemate already added — and Lidarr
 * reports it as a 400 indistinguishable in status from a real configuration error. Forcing the
 * caller to handle all three is the point.
 */
sealed interface LidarrAddOutcome {

  /** Lidarr created the album. [albumId] is what every later status poll correlates on. */
  data class Added(val albumId: Int) : LidarrAddOutcome

  /**
   * Lidarr already has it.
   *
   * A duplicate add is a **400** — measured, by posting the same body to a live `3.1.0.4875-ls40`
   * twice — and it is separated from any other validation failure by two things, either of which is
   * enough: `errorCode: "AlbumExistsValidator"` and the message
   * `"This album has already been added."` See [LidarrValidationException.isAlreadyAdded], which
   * reads both. If a Lidarr release changed *both* this becomes [Rejected] and the user sees the
   * raw validation text — degraded, never wrong.
   */
  data object AlreadyAdded : LidarrAddOutcome

  /** Lidarr refused. [failures] carry dotted PascalCase property names such as `Artist.QualityProfileId`. */
  data class Rejected(val failures: List<LidarrValidationFailure>) : LidarrAddOutcome
}

/**
 * One album Lidarr's metadata lookup found.
 *
 * [raw] is the element **exactly as it came off the wire**, and Task 6 posts it back with five
 * fields set — the same thing Lidarr's own UI does (`frontend/src/Utilities/Album/getNewAlbum.js`).
 * Rebuilding a payload from the typed fields below would drop every field this client does not
 * model, and `openapi.json` declares none of them required because it is Swashbuckle-generated and
 * does not encode Lidarr's FluentValidation rules. The only complete statement of what Lidarr
 * wants is what Lidarr sends.
 *
 * [alreadyAdded] is `id != 0`. Measured on `3.1.0.4875-ls40`: a lookup element for an album that is
 * **not** in this Lidarr's database omits `id` entirely rather than sending `0` — all seven
 * elements of `fixtures/lidarr/album-lookup.json` do, and so does every nested `artist`. Absent and
 * `0` therefore both have to mean "not added", which is why this reads the key defensively instead
 * of declaring an `Int` with a default.
 */
data class LidarrAlbumCandidate(
  val foreignAlbumId: String,
  val title: String,
  /**
   * Lidarr's own parenthetical, or `null`.
   *
   * Blank collapses to `null`: measured, this arrives as `""` on every element of a real lookup
   * rather than being omitted, and a surface that has to treat `""` and absent as two different
   * kinds of nothing renders `Title ()` for one of them.
   */
  val disambiguation: String?,
  val albumType: String?,
  /**
   * The raw string Lidarr sent, or `null`. Not parsed: this app has no datetime dependency and
   * shows it as-is.
   *
   * Measured, a real value is a full ISO-8601 instant (`1959-08-17T00:00:00Z`), never a bare date.
   * **An unknown release date arrives as `0001-01-01T00:00:00Z`** — .NET's `DateTime.MinValue`,
   * not an omitted field — which [LidarrClient] collapses to `null` so that no surface has to
   * decide whether to print the year 1.
   */
  val releaseDate: String?,
  /** From `remoteCover`. **Not** `remotePoster`, which only artist lookups carry. */
  val remoteCoverUrl: String?,
  val artistName: String,
  val foreignArtistId: String,
  val alreadyAdded: Boolean,
  val raw: kotlinx.serialization.json.JsonObject,
)

/**
 * A folder Lidarr can file an album into, and the defaults it carries.
 *
 * `RootFolderResource` carries `defaultQualityProfileId`, `defaultMetadataProfileId`,
 * `defaultMonitorOption` and `defaultNewItemMonitorOption`, so a user who picks one of these has
 * chosen every remaining required add field — see [LidarrAddTargets.Companion.resolve]. [accessible]
 * is on the same resource and an inaccessible folder is offered to nobody.
 */
data class LidarrRootFolder(
  val id: Int,
  val name: String,
  val path: String,
  val accessible: Boolean,
  val freeSpaceBytes: Long?,
  val defaultQualityProfileId: Int,
  val defaultMetadataProfileId: Int,
  val defaultMonitorOption: String,
  val defaultNewItemMonitorOption: String,
)

/** A quality or a metadata profile, reduced to the two fields an add and a picker need. */
data class LidarrProfile(val id: Int, val name: String)

/**
 * What `GET /api/v1/system/status` tells a client that matters to it.
 *
 * Five of the thirty fields a real Lidarr sends (see `fixtures/lidarr/system-status.json`, taken
 * off `3.1.0.4875`). [appName] is the identity check — the field that separates a Lidarr from the
 * Sonarr whose URL the user pasted by mistake. [urlBase] matters because a proxied install answers
 * unprefixed API paths with a 307 (measured: `Location: /lidarr/api/v1/system/status`, relative);
 * OkHttp follows it, but knowing the real base lets the app store it and stop paying for a
 * redirect on every call.
 *
 * Every field is a non-null `String`, `""` where Lidarr omitted it. A configuration screen that had
 * to distinguish "absent" from "empty" here would be distinguishing something Lidarr's serializer
 * has already collapsed: it omits null-valued fields entirely, and `urlBase` on an unproxied
 * install is `""` rather than absent.
 */
data class LidarrServer(
  val appName: String,
  val instanceName: String,
  val version: String,
  val urlBase: String,
  val authentication: String,
) {
  /**
   * Whether this really is a Lidarr and not a sibling Servarr application.
   *
   * Case-insensitive because the value is a build constant this client does not control, and a
   * comparison that broke on a capitalisation change would fail closed in the *wrong* direction —
   * telling a user with a working Lidarr that they have not got one.
   */
  val isLidarr: Boolean get() = appName.equals("Lidarr", ignoreCase = true)
}

/**
 * How a [LidarrSource] is made from credentials.
 *
 * A `fun interface` so that Task 9's tests can hand [LidarrSourceProvider] a factory returning a
 * hand-written fake, without either of them knowing that the real one builds an OkHttp stack.
 */
fun interface LidarrSourceFactory {
  fun create(credentials: IntegrationCredentials.Lidarr): LidarrSource
}

/** The production factory: a real [LidarrClient] with its real Retrofit stack. */
object DefaultLidarrSourceFactory : LidarrSourceFactory {
  override fun create(credentials: IntegrationCredentials.Lidarr): LidarrSource =
    LidarrClient(credentials)
}
