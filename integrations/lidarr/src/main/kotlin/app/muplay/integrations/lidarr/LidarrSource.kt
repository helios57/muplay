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
}

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
