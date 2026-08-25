package app.muplay.cast.discovery

import app.muplay.cast.http.CastHttpClient
import java.net.URI

/**
 * The production description fetcher: one `GET`, over [CastHttpClient], held to the local-network
 * rule the client already enforces.
 *
 * [RendererDirectory] takes a `(URI) -> String?` rather than a client so that a test can serve
 * documents without a device -- but the decision *"a 404 is not a description"* must not live in
 * the test's own lambda, or nothing would hold the shipping fetcher to it. It lives here, and the
 * discovery tests use this function over a real loopback server.
 *
 * Every failure collapses to `null`, deliberately: a device that answers nothing, answers 404,
 * announces an `https` URL this client has no trust store for, or announces an address off the
 * local network are four different reasons for the same user-visible outcome -- that device is not
 * in the picker. [RendererDirectory] treats them per device, so one of them never empties the
 * picker of the rest.
 *
 * The body is bounded by [CastHttpClient] itself
 * ([CastHttpClient.DEFAULT_MAX_BODY_BYTES], 1 MiB), which is the cap that matters: a hostile
 * device on the LAN chooses what this fetches, and a `Content-Length` it declares over that cap is
 * refused before a byte of it is buffered. [DeviceDescription.MAX_DESCRIPTION_BYTES] (512 KiB) is
 * the *parser's* second, tighter opinion, applied to a document already in memory -- deliberately
 * two limits rather than one, because the two are about different things and the socket-side one
 * is the one that stops an allocation.
 */
object DescriptionFetcher {

  private const val HTTP_OK = 200

  fun overHttp(client: CastHttpClient = CastHttpClient()): (URI) -> String? = { url ->
    runCatching { client.exchange(url, "GET") }
      .getOrNull()
      ?.takeIf { it.code == HTTP_OK }
      ?.bodyText()
  }
}
