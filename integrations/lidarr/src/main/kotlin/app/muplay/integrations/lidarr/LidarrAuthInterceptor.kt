package app.muplay.integrations.lidarr

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
internal class LidarrAuthInterceptor(private val apiKey: String) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response =
    chain.proceed(
      chain.request().newBuilder()
        .header("X-Api-Key", apiKey)
        .header("Accept", "application/json")
        .build(),
    )
}
