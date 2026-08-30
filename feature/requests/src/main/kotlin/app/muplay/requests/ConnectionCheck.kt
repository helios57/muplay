package app.muplay.requests

import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.bindery.BinderySource
import app.muplay.integrations.bindery.BinderySourceFactory
import app.muplay.integrations.bindery.BinderyUnauthorizedException
import app.muplay.integrations.lidarr.LidarrSource
import app.muplay.integrations.lidarr.LidarrSourceFactory
import app.muplay.integrations.lidarr.LidarrUnauthorizedException
import kotlinx.coroutines.CancellationException

/**
 * The outcome of "test connection", with one member per thing that can actually be wrong.
 *
 * Five members rather than success-or-failure, because the *advice* differs: an unreachable host
 * and a rejected key send the user to completely different places, and a Sonarr URL pasted into the
 * Lidarr field is the single most likely real mistake -- `/ping` is byte-identical across every
 * Servarr application, so without an identity check it produces a green tick and then a stream of
 * 404s.
 *
 * A connection check observed at one outcome is a connection check that has not been tested, and
 * this one is a five-way branch; `ConnectionCheckTest` asserts all five as one exact mapped list.
 */
sealed interface ConnectionCheck {

  /** [description] is whatever the service called itself, shown back as confirmation. */
  data class Ok(val description: String) : ConnectionCheck

  /** Nothing answered the unauthenticated call. The key is not the problem and must not be blamed. */
  data object Unreachable : ConnectionCheck

  /**
   * Something is there and refused us.
   *
   * The message this renders must **not** say the key is wrong. Both services return a bare 401 for
   * a missing key and a wrong key alike -- measured on each, and both exception types say so in
   * their own documentation -- so "wrong" is a guess. "Rejected" is what is known.
   */
  data object Unauthorized : ConnectionCheck

  /** Reachable, authenticated, and a different application. [appName] is what it called itself. */
  data class WrongApplication(val appName: String) : ConnectionCheck

  /** Anything else. [detail] is whatever the failure said, and is never blank. */
  data class Failed(val detail: String) : ConnectionCheck

  companion object {

    /**
     * Decides the outcome from three observations about one [service].
     *
     * The identity check is skipped for a service that does not identify itself, and **which
     * services those are is derived from the enum rather than passed in** -- see [expectedAppName].
     * That is a nullable value rather than a second code path so that "there is no identity to
     * check" is a tested case rather than an unreachable branch.
     *
     * The plan's Step 3 wrote this as a four-argument function taking `expectedAppName: String?`
     * directly. Taking the service instead buys one thing worth the change: [Ok.description] can
     * fall back to the service's own display name, so a Bindery that connected reads "Bindery"
     * rather than the literal word "connected".
     */
    fun of(
      service: IntegrationService,
      reachable: Boolean,
      identity: String?,
      failure: Throwable?,
    ): ConnectionCheck {
      val expected = expectedAppName(service)
      return when {
        !reachable -> Unreachable
        failure is LidarrUnauthorizedException || failure is BinderyUnauthorizedException ->
          Unauthorized
        // Blank collapses to the fallback: `Failed("")` renders as an empty line under the field,
        // which reads as a UI bug rather than as a failure.
        failure != null -> Failed(failure.message?.takeIf { it.isNotBlank() } ?: "the connection failed")
        expected != null && identity != null && !identity.equals(expected, ignoreCase = true) ->
          WrongApplication(identity)
        else -> Ok(description = identity ?: service.displayName)
      }
    }

    /**
     * The application name [service] should call itself, or `null` when it does not say.
     *
     * **Bindery has no `appName` to check**: its `/api/v1/health` reports `status` and `version` and
     * nothing that names the application, so `WrongApplication` is unreachable for it. Written down
     * as `null` rather than left as a branch that can never be false.
     */
    internal fun expectedAppName(service: IntegrationService): String? = when (service) {
      IntegrationService.LIDARR -> "Lidarr"
      IntegrationService.BINDERY -> null
    }
  }
}

/**
 * The sentence to show under the "Test connection" button.
 *
 * Here rather than in the screen, for the reason `BaseUrlResult.message` is: both halves of the
 * setup form produce identical copy without either of them owning it, and the copy is then
 * assertable from the fast tier -- which matters most for [ConnectionCheck.Unauthorized], whose one
 * job is to *not* claim the key is wrong.
 */
fun ConnectionCheck.message(service: IntegrationService): String = when (this) {
  is ConnectionCheck.Ok -> "Connected to $description."
  ConnectionCheck.Unreachable ->
    "Nothing answered at that address. Check the address and that ${service.displayName} is running " +
      "— your API key is not the problem here."
  ConnectionCheck.Unauthorized ->
    "${service.displayName} rejected this API key. It cannot tell us whether the key is wrong or " +
      "was not sent, so check that you pasted the whole of it."
  is ConnectionCheck.WrongApplication ->
    "That address answers, but it is a $appName rather than a ${service.displayName}. Every " +
      "Servarr application answers the same health check, so this is easy to do by accident."
  is ConnectionCheck.Failed -> "MuPlay could not check that connection: $detail"
}

/**
 * The three observations [ConnectionCheck.of] decides from, taken from a live server.
 *
 * Separate from the decision so the decision is a pure function with no network in it, and so the
 * *gathering* -- which is where the two services genuinely differ -- can be tested over the two
 * `…SourceFactory` seams on the fast tier rather than against a real Lidarr.
 *
 * @property reachable something answered the unauthenticated call.
 * @property identity what the service called itself, or `null` when it does not say.
 * @property failure what the authenticated call threw, or `null`.
 */
data class ConnectionObservation(
  val reachable: Boolean,
  val identity: String?,
  val failure: Throwable?,
)

/**
 * Asks a server that is **not yet configured** what it is.
 *
 * A seam, and the reason it is one is that this call cannot go through `RequestsRepository`: that
 * class is built entirely around credentials that are already in the store, and a connection check
 * runs against a URL and a key the user has just typed and has not saved.
 */
fun interface ConnectionProbe {
  suspend fun observe(credentials: IntegrationCredentials): ConnectionObservation
}

/**
 * The production probe, as a plain function over the two factories.
 *
 * A function rather than a class so that `di.RequestsFeatureModule` can bind it with a one-line SAM
 * conversion -- a `@Provides` returning an object expression would be six lines of wiring that only
 * the Hilt graph ever runs, in a module with no device tier to run them.
 */
internal suspend fun observeConnection(
  credentials: IntegrationCredentials,
  lidarr: LidarrSourceFactory,
  bindery: BinderySourceFactory,
): ConnectionObservation = when (credentials) {
  is IntegrationCredentials.Lidarr -> observeLidarr(lidarr.create(credentials))
  is IntegrationCredentials.Bindery -> observeBindery(bindery.create(credentials))
}

/**
 * Lidarr: an unauthenticated `ping`, then an authenticated `status`.
 *
 * `ping()` never throws by contract -- its whole value is being a question that always has an
 * answer -- so "nothing is listening" is a `false` here rather than a caught exception. `status()`
 * is what proves both the key and that this is a Lidarr rather than the Sonarr next door.
 */
private suspend fun observeLidarr(source: LidarrSource): ConnectionObservation {
  if (!source.ping()) return ConnectionObservation(reachable = false, identity = null, failure = null)
  return try {
    ConnectionObservation(reachable = true, identity = source.status().appName, failure = null)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    ConnectionObservation(reachable = true, identity = null, failure = e)
  }
}

/**
 * Bindery: `health()`, then the cheapest authenticated call there is.
 *
 * **`health()` is not a credential check and nothing may treat it as one** -- measured, it answers
 * `200` with a wrong `X-Api-Key` and with none at all -- so a check that called only it would tell a
 * user with a mistyped key that everything was fine. `books(limit = 1)` is what proves the key.
 *
 * It throws rather than returning a boolean, which is why the shape differs from Lidarr's above;
 * `isBindery` is the weaker of the two identity claims and is treated as reachability rather than as
 * an [ConnectionObservation.identity], because it is not a name.
 */
private suspend fun observeBindery(source: BinderySource): ConnectionObservation {
  val unreachable = ConnectionObservation(reachable = false, identity = null, failure = null)
  val server = try {
    source.health()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    return unreachable
  }
  if (!server.isBindery) return unreachable
  return try {
    source.books(status = null, limit = 1, offset = 0)
    // No identity: Bindery's health body carries no application name, so there is nothing to check
    // and `ConnectionCheck.of` falls back to the service's own display name.
    ConnectionObservation(reachable = true, identity = null, failure = null)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    ConnectionObservation(reachable = true, identity = null, failure = e)
  }
}
