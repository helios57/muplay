package app.muplay.integrations

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The base URL of a configured integration: always absolute, always `http`/`https`, always
 * terminated with `/`, and **never carrying a query, a fragment or userinfo**.
 *
 * The constructor is private and [parse] is the only way to get one. That is the whole design:
 * `IntegrationCredentials` holds this type rather than a `String`, so no client in this plan can
 * be constructed around a URL that has not been through the cleartext policy and the
 * secret-stripping below. Checking at each call site would work until the third call site.
 *
 * Not a `value class`: a `value class` over `String` erases to `String` at every JVM boundary,
 * which would let a plain `String` be passed where one of these is expected through any reflective
 * or generic path, and would make the private constructor much weaker than it looks.
 */
class IntegrationBaseUrl private constructor(private val parsed: HttpUrl) {

  /**
   * The normalised URL, in the form Retrofit's `baseUrl` and every log line want it.
   *
   * Held as a `String` rather than recomputed, because it is read on every request build and it is
   * this type's `equals`, `hashCode` and `toString`. The `HttpUrl` behind it is kept private: it is
   * what [isSameOrigin] compares against, and handing it out would let a caller reach
   * `newBuilder()` and construct a base URL that never went through [parse].
   */
  val value: String = parsed.toString()

  /**
   * Whether [url] is on the **same origin** as this base URL: identical scheme, host and port.
   *
   * That tuple is RFC 6454's origin, and it is the unit an API key may be sent to. The reason this
   * lives here rather than in each client is that it must be compared against the URL *as OkHttp
   * canonicalised it* -- host lowercased and IDN-punycoded, port defaulted from the scheme -- and
   * `parse` above already put this value through exactly that. A client re-deriving the comparison
   * from `IntegrationCredentials.baseUrl.value` with string surgery would disagree with the parser
   * that actually connects, on precisely the inputs an attacker chooses: `HOST` vs `host`,
   * `https://host` vs `https://host:443`, a trailing dot, an IDN homograph.
   *
   * **Why this exists at all.** Both integration clients authenticate with `X-Api-Key`, and OkHttp
   * does not strip that header across a redirect. Measured against the OkHttp this project
   * resolves (5.5.0): `RetryAndFollowUpInterceptor.buildRedirectRequest` removes exactly one header
   * when it cannot reuse the connection, `Authorization`. So a server answering
   * `302 Location: https://evil.example/` was handed the user's key -- and Bindery's is
   * instance-wide and always treated as admin. `LidarrAuthInterceptor` and `BinderyAuthInterceptor`
   * are network interceptors that call this on every hop; see either for the whole argument.
   */
  fun isSameOrigin(url: HttpUrl): Boolean =
    url.scheme == parsed.scheme && url.host == parsed.host && url.port == parsed.port

  override fun toString(): String = value

  override fun equals(other: Any?): Boolean =
    this === other || (other is IntegrationBaseUrl && value == other.value)

  override fun hashCode(): Int = value.hashCode()

  companion object {

    /**
     * Matches a URL that carries *any* RFC 3986 scheme, `http` or otherwise — a letter followed by
     * letters, digits, `+`, `-` or `.`, then `://`.
     *
     * A sniff, not a parser. Its only job is to separate the two strings [toHttpUrlOrNull] cannot
     * tell apart for us: `"192.168.1.20:8686"`, which a user typed meaning a host and a port and
     * which deserves *"start the address with https://"*, and `"ftp://host"` or `"https://"`,
     * which are a scheme MuPlay cannot use and a URL that does not parse. Everything about the URL
     * that actually matters — its host, port, path, and its scheme's canonical spelling — is still
     * decided by OkHttp, so a `HTTPS://` prefix here is accepted by the `[a-zA-Z]` class and
     * lowercased by `HttpUrl` rather than by this expression.
     *
     * The leading `^` is what makes `containsMatchIn` below a prefix test: Java regex anchors `^`
     * to position 0 unless `MULTILINE` is set, and it is not.
     */
    private val ANY_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    /**
     * Parses [raw] under [policy].
     *
     * The query, the fragment and any userinfo are **discarded rather than rejected**. Lidarr
     * accepts its API key as a query parameter, so a URL copied out of a browser address bar can
     * arrive with the key in it; rejecting that would be a dead end for a user who did nothing
     * wrong, while keeping it would put a secret into DataStore, into every OkHttp log line and
     * into the message of any `IOException` a crash reporter uploads.
     *
     * The path is kept, because Servarr applications support a `urlBase` and are commonly proxied
     * at `https://home.example.com/lidarr`. The trailing slash is added for Retrofit, which
     * resolves a relative path by replacing the last segment of a base URL that lacks one.
     */
    fun parse(raw: String, policy: CleartextPolicy): BaseUrlResult {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) return BaseUrlResult.Blank
      // Before `toHttpUrlOrNull`, not inferred from its `null`: `HttpUrl` returns `null` for
      // `"192.168.1.20:8686"` and for `"ftp://host"` alike, and those two deserve different
      // sentences. A string with no scheme at all stops here; one with a scheme `HttpUrl` will not
      // accept falls through to `Malformed` below, which is what [BaseUrlResult.Malformed] means.
      if (!ANY_SCHEME.containsMatchIn(trimmed)) return BaseUrlResult.MissingScheme
      val parsed: HttpUrl = trimmed.toHttpUrlOrNull() ?: return BaseUrlResult.Malformed
      if (parsed.scheme == "http" && !permitsCleartext(policy)) {
        return BaseUrlResult.CleartextForbidden(parsed.host)
      }
      return BaseUrlResult.Valid(IntegrationBaseUrl(normalise(parsed)))
    }

    /**
     * Whether [policy] permits an `http://` integration.
     *
     * An exhaustive `when` rather than `policy == CleartextPolicy.Forbidden`, and the difference
     * is not style: an equality test against one member silently treats *every* member added later
     * as permitting cleartext, which is the unsafe direction for a security decision. A `when` over
     * a sealed interface stops compiling instead.
     */
    private fun permitsCleartext(policy: CleartextPolicy): Boolean = when (policy) {
      CleartextPolicy.Allowed -> true
      CleartextPolicy.Forbidden -> false
    }

    /**
     * Strips every credential-bearing component, then guarantees the trailing slash.
     *
     * Returns the `HttpUrl` rather than its string, so [IntegrationBaseUrl] can keep the parsed
     * form for [IntegrationBaseUrl.isSameOrigin] instead of re-parsing a string it just built. The
     * trailing slash is added as an empty **path segment** rather than by concatenating a
     * character, for the same reason the rest of this file uses OkHttp's parser: the URL model
     * decides what a URL looks like. The two agree -- `HttpUrl` canonicalises every `http`/`https`
     * path to start with `/`, so the test below is for a trailing empty segment.
     */
    private fun normalise(parsed: HttpUrl): HttpUrl {
      val stripped = parsed.newBuilder()
        .username("")
        .password("")
        .query(null)
        .fragment(null)
        .build()
      return if (stripped.encodedPath.endsWith("/")) {
        stripped
      } else {
        stripped.newBuilder().addPathSegment("").build()
      }
    }
  }
}

/**
 * The outcome of parsing a user-entered integration URL.
 *
 * A sealed interface with one success member and four distinct failures, rather than a nullable
 * return: every failure has a different thing the user should do about it, and `null` cannot say
 * which. [message] is here rather than in the UI so both configuration screens produce identical
 * copy without either of them owning it.
 */
sealed interface BaseUrlResult {

  data class Valid(val url: IntegrationBaseUrl) : BaseUrlResult

  /** Nothing was entered. Not an error to shout about; the save button is simply not enabled. */
  data object Blank : BaseUrlResult

  /** Something that looks like a host was entered with no `https://` in front of it. */
  data object MissingScheme : BaseUrlResult

  /** A scheme was present but the rest does not parse, or the scheme is not `http`/`https`. */
  data object Malformed : BaseUrlResult

  /**
   * An `http://` URL in a build where cleartext is [CleartextPolicy.Forbidden]. [host] is carried
   * so the message can name it — a message that says "unencrypted connections are not allowed"
   * without saying to *what* is not actionable.
   */
  data class CleartextForbidden(val host: String) : BaseUrlResult
}

/**
 * The sentence to show under the URL field, or `null` when there is nothing wrong.
 *
 * The [service] name is interpolated into every message so the copy reads correctly on both
 * configuration screens. The [BaseUrlResult.CleartextForbidden] message names concrete tools
 * because "use HTTPS" is not advice a self-hoster can act on at 11pm — see the plan's
 * *Cleartext HTTP* section for why this build cannot simply permit cleartext instead.
 */
fun BaseUrlResult.message(service: IntegrationService): String? = when (this) {
  is BaseUrlResult.Valid -> null
  BaseUrlResult.Blank -> "Enter the address of your ${service.displayName} server."
  BaseUrlResult.MissingScheme ->
    "Start the ${service.displayName} address with https:// — for example " +
      "https://${service.displayName.lowercase()}.example.com."
  BaseUrlResult.Malformed -> "That is not an address MuPlay can reach ${service.displayName} at."
  is BaseUrlResult.CleartextForbidden ->
    "MuPlay will not send your ${service.displayName} API key over an unencrypted connection, so " +
      "http://$host cannot be used. Put ${service.displayName} behind HTTPS — a reverse proxy " +
      "such as Caddy or nginx, or a private network such as Tailscale — and enter that address " +
      "instead."
}
