package app.muplay.integrations.bindery

import app.muplay.integrations.IntegrationBaseUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Puts the API key on every request **as a header**, and declares that this client accepts JSON.
 *
 * One interceptor rather than per-endpoint `@Headers`, because the property this module is named
 * for has to hold for endpoints later tasks have not written yet: an annotation can be forgotten
 * on a new `@GET`, and an interceptor cannot be. `BinderyAuthTest`'s
 * `no request this client makes carries the key on its url` asserts over **every** recorded
 * request for the same reason.
 *
 * ### Why the header, and why here it is not even a choice
 *
 * Bindery accepts the key two ways: the `X-Api-Key` header, and an `?apikey=` query parameter —
 * **but the query parameter is honoured on GET, HEAD and OPTIONS only and is rejected outright on
 * mutations.** Measured against `v1.32.1`: `GET /api/v1/search/book?term=dune&apikey=…` answers
 * 200, and `POST /api/v1/author/book?apikey=…` answers **401** `{"error":"unauthorized"}`. So a
 * query-string client cannot submit a book at all; the header is not merely the safer choice here,
 * it is the only one that works end to end.
 *
 * The reasons it is also the safer choice are the same five `:integrations:lidarr` records, and
 * every one of them is on **this** side of the wire rather than depending on the server's
 * discretion. A query-string key would appear in every `RecordedRequest` this module's own tests
 * inspect and therefore in any transcript pasted into a report; in any fixture captured with
 * `curl -i`; in the message of any `IOException` OkHttp throws, which names the full URL and which
 * a crash reporter uploads verbatim; in the access log of every reverse proxy between the app and
 * Bindery — which, for a build where cleartext is `Forbidden`, is exactly the deployment this app
 * pushes users towards; and in the URL bar, shell history or clipboard of whoever tries the same
 * request by hand.
 *
 * **And this key is worse to leak than Lidarr's.** It is instance-wide and always treated as
 * admin: Bindery's `middleware.go` scopes it to nothing, and the `users` table has no `api_key`
 * column — verified here against a real database after all **75** migrations had applied, so the
 * README's "per-account API key" claim is false on the shipped schema and not merely in the source.
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
 * So before this class was scoped, a Bindery answering
 *
 *     302 Location: https://evil.example/
 *
 * handed over the key described two paragraphs above: instance-wide, admin-equivalent, and scoped
 * by `middleware.go` to nothing. The attacker need only be the host the user configured, or anyone
 * able to answer as it. `:integrations:lidarr` had the identical defect and the identical fix; the
 * two were written apart and acquired one bug twice, which is the argument for
 * [IntegrationBaseUrl.isSameOrigin] living in `:integrations:core` where both call it.
 *
 * A **network** interceptor runs once per hop, so `chain.request().url` is the URL actually about
 * to go on the wire, redirect target included, and [IntegrationBaseUrl.isSameOrigin] decides each
 * hop on its own. The `Accept` header is set on every hop regardless -- it is not a secret, and a
 * redirect target still has to be told what this client can parse.
 *
 * ### Why redirects are still followed
 *
 * `followRedirects` is left at OkHttp's default `true`, deliberately. A reverse-proxied Bindery is
 * the deployment a release build pushes users towards -- `CleartextPolicy.Forbidden` refuses a
 * plain `http://` base URL -- and a proxy that normalises a path answers a same-origin redirect;
 * `BinderyAuthTest`'s `a same-origin redirect is followed with the key intact` holds that open.
 * Turning redirects off instead means owning relative-`Location` resolution, `307`/`308` method
 * and body preservation and a follow-up bound, in the one place where getting it wrong is a
 * security bug. With the key withheld off-origin the secret can no longer leave the configured
 * origin, which is the whole of the vulnerability; what remains is that the client deserialises a
 * stranger's JSON into typed DTOs, with no credential attached and no code execution.
 *
 * Note the one case the origin rule refuses that a reader might expect it to allow: a same-host
 * `http` -> `https` **upgrade** is cross-origin (the scheme and the default port both change), so
 * the key is withheld. That is the fail-closed direction, and in a shipping build it cannot arise,
 * because the configured origin is always `https` to begin with.
 *
 * ### Why `Accept` is here too
 *
 * Bindery answers JSON regardless — measured, a request with no `Accept` header at all is answered
 * 200 with `application/json`, so this header is not rescuing a request that would otherwise fail.
 * It pins the negotiation to the one media type [BinderyClient]'s converter can read, so that an
 * intermediary or a later `@Headers` annotation cannot move it, and it lives beside the key for
 * the same reason the key does: one place, so a new endpoint cannot forget it.
 */
internal class BinderyAuthInterceptor(
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
