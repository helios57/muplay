package app.muplay.integrations.lidarr

/**
 * Everything that can go wrong once Lidarr produced a response on purpose.
 *
 * A sealed *interface* whose members each also extend `Exception`, for the same reason
 * `SubsonicException` in `:core:network` is built this way: Kotlin cannot make an interface extend
 * `Throwable`, so this buys an exhaustive `when` at the cost of not being directly catchable. A
 * genuine transport failure — no route, a timeout, a TLS handshake that failed — is deliberately
 * **not** a member and propagates as whatever the transport threw. *"We could not ask"* is not
 * *"Lidarr said no"*, and a type that collapsed them would leave the configuration screen unable
 * to tell a wrong address from a rejected key.
 *
 * **No member carries the API key, the base URL, or a response body.** Every message here is
 * either a constant or built from Lidarr's own validation text, so an exception from this module
 * that reaches a crash reporter carries nothing a user would not have put in a bug report
 * themselves. `LidarrAuthTest`'s `no failure this client raises names the api key` is what keeps
 * that true as members are added.
 */
sealed interface LidarrException

/**
 * Lidarr answered 401.
 *
 * **It is not knowable from this response whether the key is wrong or missing.**
 * `HandleAuthenticateAsync` returns `NoResult()` in both cases and `HandleChallengeAsync` writes a
 * bare 401 — measured against linuxserver `3.1.0.4875-ls40`, both a wrong `X-Api-Key` and no
 * `X-Api-Key` at all produce byte-identical `HTTP/1.1 401 Unauthorized` with `Content-Length: 0`
 * and no body. The message therefore says only that the key was *rejected*; claiming to know
 * which would be a guess presented to the user as a fact, and would send someone to regenerate a
 * key that was never the problem.
 *
 * This is the same discipline spec §4 applies to capability negotiation: degrade to the weaker
 * true statement, never to a stronger false one.
 */
class LidarrUnauthorizedException :
  Exception("Lidarr rejected this API key"), LidarrException

/**
 * Lidarr is booting: a 503 whose body is
 * `{"errorMessage":"Lidarr is starting up, please try again later"}` (`StartingUpMiddleware.cs`).
 *
 * A normal transient state after a container restart, and separate from [LidarrHttpException] so a
 * caller can retry rather than telling the user to check their network. The distinction is made by
 * reading the **body**, not the status: a reverse proxy with no upstream also answers 503, and a
 * client that mapped every 503 to this would send a user to wait for a container that is not
 * starting.
 */
class LidarrStartingUpException :
  Exception("Lidarr is starting up"), LidarrException

/**
 * One FluentValidation failure from a 400 body. [propertyName] is PascalCase, dotted when nested.
 *
 * [errorCode] is the FluentValidation **validator class name** — `NotEmptyValidator`,
 * `AlbumExistsValidator`, `QualityProfileExistsValidator`, `RootFolderExistsValidator`,
 * `GreaterThanValidator` were all observed on a live `3.1.0.4875-ls40` in one afternoon. It is
 * nullable because nothing obliges a proxy or a future release to send it, and every consumer here
 * treats its absence as "I do not know" rather than as a value.
 *
 * **This field exists because of one specific use**, and it is worth naming rather than leaving as
 * a general "might be handy": it is the only machine-readable way to recognise a duplicate add. See
 * [LidarrValidationException.isAlreadyAdded]. Task 4 read the same fixture, wrote down that the key
 * was there, and deliberately did not model it on the grounds that nothing had a use for it — which
 * was true then and stopped being true here.
 *
 * **No default on [errorCode]**, though a nullable field invites one. A defaulted parameter that no
 * caller omits compiles to a second, synthetic constructor nothing can reach, and it measures as a
 * permanently uncovered line: with `= null` this class read LINE **4/5 = 0.80** and failed the
 * module's 0.90 LINE floor; without it, 4/4. That is this repository's own recorded lesson about
 * `CastRoute.Proxied`, met again here.
 */
data class LidarrValidationFailure(
  val propertyName: String?,
  val errorMessage: String?,
  val errorCode: String?,
)

/**
 * Lidarr answered 400 with a JSON array of FluentValidation failures.
 *
 * This is also how a **duplicate add** arrives — not a 409. `ArtistExistsValidator` and
 * `AlbumExistsValidator` produce the messages `"This artist has already been added."` and
 * `"This album has already been added."`
 *
 * The message is built from Lidarr's own `propertyName`/`errorMessage` pairs and from nothing
 * else. Nothing this client sent is interpolated into it — in particular not
 * `attemptedValue`, which Lidarr does send (measured: `"attemptedValue":
 * "c35e782d-be05-380b-ac26-1b9c48878ee5"` on a duplicate add, `999` on a bad profile id) and which
 * this client neither reads nor repeats.
 */
class LidarrValidationException(val failures: List<LidarrValidationFailure>) :
  Exception(failures.joinToString("; ") { "${it.propertyName}: ${it.errorMessage}" }),
  LidarrException {

  /**
   * Whether this refusal is Lidarr saying it already has the thing, rather than that something is
   * misconfigured.
   *
   * **Two independent signals, either of which is sufficient**, because a duplicate add is a 400
   * with the same shape as every other refusal:
   *
   * - `errorCode == "AlbumExistsValidator"` — the FluentValidation validator's own class name.
   *   Machine-readable, and **measured on a live `3.1.0.4875-ls40`**: posting the same add twice
   *   answers `{"propertyName":"ForeignAlbumId","errorMessage":"This album has already been
   *   added.","attemptedValue":"…","severity":"error","errorCode":"AlbumExistsValidator"}`. Every
   *   failure that instance produced carried an `errorCode`.
   * - the message containing `"has already been added"` — `AlbumExistsValidator` and
   *   `ArtistExistsValidator`'s default template, and the only signal Task 4 had.
   *
   * Keeping both is the point. A release that rewords the sentence is caught by the code; one that
   * renames the validator class, or a proxy that strips the field, is caught by the message; one
   * that changed both leaves this `false`, and the user sees the raw validation text — degraded,
   * never wrong. Neither arm is dead weight and `LidarrSubmitTest` asserts each alone.
   */
  val isAlreadyAdded: Boolean
    get() = failures.any {
      it.errorCode == ALBUM_EXISTS_VALIDATOR ||
        it.errorMessage?.contains("has already been added", ignoreCase = true) == true
    }

  private companion object {
    /**
     * `AlbumExistsValidator`'s class name, which FluentValidation reports verbatim as `errorCode`.
     *
     * Matched exactly rather than case-insensitively: this is a .NET type name travelling through a
     * serializer that does not rename it (the camelCase policy applies to property names, not to
     * values), so a case difference would mean a different type, not a different rendering.
     */
    const val ALBUM_EXISTS_VALIDATOR = "AlbumExistsValidator"
  }
}

/** Any other unsuccessful HTTP status. [status] is the HTTP code, never a Lidarr-level one. */
class LidarrHttpException(val status: Int) :
  Exception("Lidarr HTTP error $status"), LidarrException
