package app.muplay.integrations.bindery

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
 * ### Why `Accept` is here too
 *
 * Bindery answers JSON regardless — measured, a request with no `Accept` header at all is answered
 * 200 with `application/json`, so this header is not rescuing a request that would otherwise fail.
 * It pins the negotiation to the one media type [BinderyClient]'s converter can read, so that an
 * intermediary or a later `@Headers` annotation cannot move it, and it lives beside the key for
 * the same reason the key does: one place, so a new endpoint cannot forget it.
 */
internal class BinderyAuthInterceptor(private val apiKey: String) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response =
    chain.proceed(
      chain.request().newBuilder()
        .header("X-Api-Key", apiKey)
        .header("Accept", "application/json")
        .build(),
    )
}
