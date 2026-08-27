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
 * **Both services have a member.** [Bindery]'s arrived at Task 8, once its authentication
 * mechanism had been established against a real instance rather than guessed — and deferring it
 * was safe precisely because this is sealed: adding a member made every `when` over this type a
 * compile error until it was handled, so nothing could silently forget the new service. Two of
 * those `when`s were in `IntegrationCredentialStore`, and the compiler is what found them.
 *
 * The two members are the same shape, and that is a **finding rather than a coincidence**: two
 * self-hosted services designed independently both authenticate with one instance-wide API key
 * sent as a header. It is still worth one member each rather than a shared `ApiKey(secret)`,
 * because [service] is then a `get()` on the type instead of a field a caller can get wrong, and
 * because nothing obliges a third service to authenticate this way.
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
     *
     * **[baseUrl] is deliberately *not* redacted, and that is a knowing trade.** A redaction that
     * hid everything would make every log line about an integration useless — the point is to hide
     * one field, not the object. But [IntegrationBaseUrl.parse] strips only the query, the fragment
     * and userinfo: it keeps the **path verbatim**, because Servarr applications support a
     * `urlBase` and are commonly proxied at `https://home.example.com/lidarr`. A user who has put a
     * secret in that path (`https://home.example.com/api/TOKEN123/v1`) will see it in anything that
     * logs this object. So: the API key cannot appear here, and the path can. Treat a base URL as
     * possibly secret-bearing wherever it is written down.
     */
    override fun toString(): String = "Lidarr(baseUrl=$baseUrl, apiKey=<redacted>)"
  }

  /**
   * Bindery authenticates with a single API key sent as an `X-Api-Key` **header**.
   *
   * Bindery also accepts `?apikey=` — but **only on GET, HEAD and OPTIONS; it is rejected outright
   * on mutations**, so a query-string client cannot even submit a book. Measured against
   * `ghcr.io/vavallee/bindery:v1.32.1`: `GET /api/v1/search/book?term=dune&apikey=…` answers 200
   * and `POST /api/v1/author/book?apikey=…` answers **401**. The header is therefore not merely
   * the safer choice here, it is the only one that works end to end.
   *
   * **This key is instance-wide and is always treated as admin.** Bindery's `middleware.go` scopes
   * it to nothing, and the `users` table has **no `api_key` column** — verified here against a real
   * database after all 75 of Bindery's migrations had applied, so the README's "per-account API
   * key" claim is false on the shipped schema and not merely in the source. Acceptable for a
   * single self-hosted owner — the Navidrome password is in exactly the same position — but
   * **nothing may be built on top of it that assumes user scoping**, and in particular no
   * multi-user sharing.
   *
   * Measured on the instance every fixture in `:integrations:bindery` came from, the generated key
   * is **64 lowercase hex characters** (32 random bytes) held in Bindery's own `settings` table
   * under `auth.api_key`, and surfaced to its owner in Settings → Security. Twice the length of
   * Lidarr's, and stored here the same way regardless: this type never sees the difference.
   */
  data class Bindery(
    override val baseUrl: IntegrationBaseUrl,
    val apiKey: String,
  ) : IntegrationCredentials {

    /** A `get()` for the same reason [Lidarr.service] is one. */
    override val service: IntegrationService get() = IntegrationService.BINDERY

    /**
     * Redacts the key, for the same reason [Lidarr.toString] does and with more at stake: this one
     * is admin-equivalent by construction rather than by convention, so there is no scoped or
     * read-only form of it to fall back on.
     *
     * **[baseUrl] is deliberately *not* redacted**, and the same knowing trade applies —
     * `IntegrationBaseUrl.parse` strips query, fragment and userinfo but keeps the path verbatim,
     * so a user who has put a secret in the path will see it in anything that logs this object.
     */
    override fun toString(): String = "Bindery(baseUrl=$baseUrl, apiKey=<redacted>)"
  }
}
