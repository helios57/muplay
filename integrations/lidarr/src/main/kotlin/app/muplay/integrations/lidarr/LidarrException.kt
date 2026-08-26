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

/** One FluentValidation failure from a 400 body. [propertyName] is PascalCase, dotted when nested. */
data class LidarrValidationFailure(val propertyName: String?, val errorMessage: String?)

/**
 * Lidarr answered 400 with a JSON array of FluentValidation failures.
 *
 * This is also how a **duplicate add** arrives — not a 409. `ArtistExistsValidator` and
 * `AlbumExistsValidator` produce the messages `"This artist has already been added."` and
 * `"This album has already been added."` with no machine-readable code beside them, which is why
 * [isAlreadyAdded] matches on the message and says so out loud rather than pretending to be
 * structural. If a Lidarr upgrade rewords those strings this returns `false` and the user sees the
 * raw validation message — degraded, not wrong.
 *
 * The message is built from Lidarr's own `propertyName`/`errorMessage` pairs and from nothing
 * else. Nothing this client sent is interpolated into it.
 */
class LidarrValidationException(val failures: List<LidarrValidationFailure>) :
  Exception(failures.joinToString("; ") { "${it.propertyName}: ${it.errorMessage}" }),
  LidarrException {

  val isAlreadyAdded: Boolean
    get() = failures.any { it.errorMessage?.contains("has already been added", ignoreCase = true) == true }
}

/** Any other unsuccessful HTTP status. [status] is the HTTP code, never a Lidarr-level one. */
class LidarrHttpException(val status: Int) :
  Exception("Lidarr HTTP error $status"), LidarrException
