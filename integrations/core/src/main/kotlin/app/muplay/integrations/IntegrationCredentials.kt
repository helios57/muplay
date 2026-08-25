package app.muplay.integrations

/**
 * What MuPlay needs in order to talk to one configured integration.
 *
 * A **sealed interface with one member per service**, not a single data class with a
 * lowest-common-denominator `secret: String`. The two services do not authenticate the same way,
 * and a shape that pretends they do would force one of them to store an empty string in a field it
 * has no use for — the kind of "almost right" model that produces a runtime check where an
 * exhaustive `when` belongs.
 *
 * **Only [Lidarr] exists as of this task.** Task 7 adds the Bindery member once its
 * authentication mechanism is established against a real instance rather than guessed. That is
 * safe to defer precisely because this is sealed: adding a member makes every `when` over this
 * type a compile error until it is handled, so nothing can silently forget the new service.
 *
 * [baseUrl] is an [IntegrationBaseUrl] rather than a `String`, which is what makes it impossible
 * to construct a credential around a URL that has not been through the cleartext policy and the
 * secret-stripping in [IntegrationBaseUrl.parse]. That is the property that keeps a secret out of
 * every URL this plan builds: there is no other constructor to reach for.
 */
sealed interface IntegrationCredentials {

  val service: IntegrationService
  val baseUrl: IntegrationBaseUrl

  /**
   * Lidarr authenticates every API request with a single API key, sent as an `X-Api-Key` **header**
   * — never as a query parameter, even though Lidarr accepts one there. See
   * `:integrations:lidarr`'s `LidarrAuthInterceptor` for that decision and the assertion that
   * pins it.
   */
  data class Lidarr(
    override val baseUrl: IntegrationBaseUrl,
    val apiKey: String,
  ) : IntegrationCredentials {

    /**
     * A `get()`, never a constructor property: there is then no way to build a Lidarr credential
     * that claims to be Bindery's, and no field for a caller to get wrong.
     */
    override val service: IntegrationService get() = IntegrationService.LIDARR

    /**
     * Redacts the key. The same control `SubsonicCredentials` carries, for the same reason: this
     * object ends up in a log line or a crash report through any `Throwable` message that
     * interpolates it, and nobody writes that interpolation deliberately.
     *
     * A Lidarr API key is instance-wide and carries admin authority over the user's download
     * client; there is no scoped or read-only form of it to fall back on. `IntegrationCredentialsTest`
     * is what keeps this override honest, and a coverage floor over this class is what keeps that
     * test from being deleted quietly.
     */
    override fun toString(): String = "Lidarr(baseUrl=$baseUrl, apiKey=<redacted>)"
  }
}
