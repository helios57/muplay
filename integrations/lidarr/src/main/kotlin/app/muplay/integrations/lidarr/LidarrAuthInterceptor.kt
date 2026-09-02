package app.muplay.integrations.lidarr

import app.muplay.integrations.IntegrationBaseUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Puts the API key on every request **as a header**, and declares that this client accepts JSON.
 *
 * One interceptor rather than per-endpoint `@Headers`, because the property this module is named
 * for has to hold for endpoints Tasks 5-7 have not written yet: an annotation can be forgotten on
 * a new `@GET`, and an interceptor cannot be. `LidarrAuthTest`'s
 * `no request this client makes carries the key on its url` asserts over **every** recorded
 * request for the same reason.
 *
 * ### Why the header and only the header
 *
 * Lidarr accepts three forms of the key: the `X-Api-Key` header, an `?apikey=` query parameter,
 * and `Authorization: Bearer` (`ApiKeyAuthenticationHandler.ParseApiKey`). Only the header is
 * used. A query-string key would appear:
 *
 *  - in every `RecordedRequest` this module's own tests inspect, and therefore in any transcript
 *    pasted into a report or a review;
 *  - in any fixture captured from a real instance with `curl -i`;
 *  - in the message of any `IOException` OkHttp throws, which names the full URL, and which a
 *    crash reporter uploads verbatim;
 *  - in the access log of every reverse proxy between the app and Lidarr — which, for a build
 *    where cleartext is `Forbidden`, is exactly the deployment this app pushes users towards;
 *  - and in the URL bar, the shell history, or the clipboard of whoever tries the same request by
 *    hand.
 *
 * **One reason the plan gave for this does not hold on the build measured here, and saying so is
 * the point of writing it down.** The plan cites `LoggingMiddleware.cs` writing
 * `string.Concat(request.Path, request.QueryString)` into `lidarr.trace.txt`, concluding that a
 * query-string key lands in a log file on the user's own server. Measured against
 * linuxserver `3.1.0.4875-ls40` at `LogLevel=trace`, an `?apikey=…&foo=bar` request is logged as
 * `/api/v1/system/status?apikey=(removed)&foo=bar` — the key is scrubbed and every *other* query
 * parameter is not, and `grep` over the whole of `/config/logs` finds the key nowhere. So Lidarr
 * redacts this one parameter on its own side. The five reasons above are all on **this** side of
 * the wire, none of them depends on the server's discretion, and they are the ones this module's
 * tests can actually assert on.
 *
 * ### Why this is a NETWORK interceptor, and why the key is scoped to one origin
 *
 * This class is registered with `addNetworkInterceptor`, not `addInterceptor`, and the difference
 * is a vulnerability rather than a preference. An **application** interceptor runs once, before
 * redirect handling, so the header it stamps is carried by whatever OkHttp does next. Measured
 * against the OkHttp this project resolves (5.5.0):
 * `RetryAndFollowUpInterceptor.buildRedirectRequest` strips exactly one header name when it cannot
 * reuse the connection, `Authorization` -- guarded by `canReuseConnectionFor`, which compares
 * scheme, host and port. `X-Api-Key` is not a name it knows.
 *
 * So before this class was scoped, a Lidarr answering
 *
 *     302 Location: https://evil.example/
 *
 * received the user's API key. The attacker need only be the host the user configured, or anyone
 * able to answer as it -- a hostile or compromised instance, a proxy, a DNS answer on a network the
 * user does not own. `:integrations:bindery` had the identical defect, and its key is instance-wide
 * and always admin.
 *
 * A **network** interceptor runs once per hop, so `chain.request().url` is the URL actually about
 * to go on the wire, redirect target included, and [IntegrationBaseUrl.isSameOrigin] decides each
 * hop on its own. The `Accept` header is set on every hop regardless -- it is not a secret, and a
 * redirect target still has to be told what this client can parse.
 *
 * ### Why redirects are still followed
 *
 * `followRedirects` is left at OkHttp's default `true`, deliberately, and the alternative was
 * considered rather than skipped:
 *
 *  - Lidarr **needs** a redirect. A `urlBase` install answers `/api/v1/...` with a `307` to
 *    `{urlBase}/api/v1/...` (`UrlBaseMiddleware.cs`), measured with a **relative** `Location`.
 *    Turning redirects off means owning relative-`Location` resolution, `307`/`308` method and
 *    body preservation, `303`'s method rewrite and a follow-up bound -- several hundred lines of
 *    someone else's well-tested code, reimplemented in the one place where getting it wrong is a
 *    security bug. `LidarrAuthTest`'s `a urlBase redirect is followed with the key and the path
 *    intact` is what holds that path open.
 *  - With the key withheld off-origin, a cross-origin redirect can no longer exfiltrate the
 *    secret, which is the whole of the vulnerability. What remains is that the client fetches and
 *    deserialises a stranger's JSON into typed DTOs it then shows as candidates -- no credential,
 *    no code execution, and a user who configured that host could be served the same bytes
 *    directly.
 *  - Refusing off-origin hops outright would additionally break a legitimate-if-unusual
 *    deployment (a proxy that redirects between host names) with an `IOException` no typed
 *    [LidarrException] describes, in exchange for that residual only.
 *
 * Note the one case the origin rule refuses that a reader might expect it to allow: a same-host
 * `http` -> `https` **upgrade** is cross-origin (the scheme and the default port both change), so
 * the key is withheld. That is the fail-closed direction, and in a shipping build it cannot arise:
 * `CleartextPolicy.Forbidden` refuses an `http://` base URL at `IntegrationBaseUrl.parse`, so the
 * configured origin is always `https` to begin with.
 *
 * ### Why `Accept` is here too
 *
 * `Startup.cs` sets `ReturnHttpNotAcceptable = true`, so content negotiation can end in a **406**
 * rather than in JSON. Measured on the same build: a request with **no** `Accept` header is
 * answered `200` (OkHttp adds none of its own), and a request with `Accept: application/xml` is
 * answered `406`. So this header is not rescuing a request that would otherwise fail today — it
 * pins the negotiation to the one media type [LidarrClient]'s converter can read, so that an
 * intermediary or a later `@Headers` annotation cannot move it. It lives beside the key for the
 * same reason the key does: one place, so a new endpoint cannot forget it.
 */
internal class LidarrAuthInterceptor(
  private val baseUrl: IntegrationBaseUrl,
  private val apiKey: String,
) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val builder = request.newBuilder()
      // Unconditionally, and before the decision below: this class is the only thing entitled to
      // put the key on a request, so a header that arrived from anywhere else -- a `@Headers`
      // annotation on an endpoint a later task adds, an application interceptor somebody installs
      // above this one -- loses its vote here rather than surviving to an origin this class did
      // not approve. Removing a header that is not present is a no-op.
      .removeHeader("X-Api-Key")
      .header("Accept", "application/json")
    if (baseUrl.isSameOrigin(request.url)) {
      builder.header("X-Api-Key", apiKey)
    }
    return chain.proceed(builder.build())
  }
}
