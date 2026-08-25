package app.muplay.cast.discovery

import app.muplay.cast.fake.FakeDescriptions
import app.muplay.cast.fake.genericDescription
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The shipping description fetcher, over a real socket.
 *
 * It exists so that *"a 404 is not a description"* is a decision in production code rather than in
 * a test's own lambda. `RendererDirectoryTest` then uses this same function, so its
 * "device whose description cannot be fetched" case is a real 404 answered by a real server.
 */
class DescriptionFetcherTest {

  private var serving: FakeDescriptions? = null

  @AfterEach
  fun tearDown() {
    serving?.close()
  }

  @Test
  fun `a description comes back as the text the device sent, non-ascii included`() {
    val server = start("/d.xml" to genericDescription("uuid:a", "Küche"))

    val body = DescriptionFetcher.overHttp()(URI(server.url("/d.xml")))!!

    // The whole document, not "not null": a fetcher that returned the response *head* would
    // satisfy `isNotNull`, and the parser's message would then be about XML rather than about a
    // fetch.
    assertThat(body).isEqualTo(genericDescription("uuid:a", "Küche"))
    assertThat(body).contains("<friendlyName>Küche</friendlyName>")
  }

  @Test
  fun `a 404 is not a description`() {
    val server = start("/d.xml" to genericDescription("uuid:a", "Study"))

    assertThat(DescriptionFetcher.overHttp()(URI(server.url("/nothing.xml")))).isNull()
    // The other observation of the same predicate: a 200 on the same server is not null, so this
    // is the status code being read rather than every response being discarded.
    assertThat(DescriptionFetcher.overHttp()(URI(server.url("/d.xml")))).isNotNull
  }

  @Test
  fun `an address off the local network yields null rather than throwing`() {
    // `LocalNetworkOnly` refuses this inside `CastHttpClient`, before a socket is opened. What is
    // asserted here is that the refusal reaches the caller as "no description" -- an exception
    // escaping here would abort a whole discovery pass because one device announced a bad URL.
    assertThat(DescriptionFetcher.overHttp()(URI("http://93.184.216.34/desc.xml"))).isNull()
  }

  @Test
  fun `an https url yields null rather than throwing`() {
    // A renderer has no TLS and this client has no trust store to give it one. A device is still
    // entitled to announce one, and it must not take the discovery pass down with it.
    assertThat(DescriptionFetcher.overHttp()(URI("https://192.168.1.50:1400/desc.xml"))).isNull()
  }

  @Test
  fun `a url with no host yields null rather than resolving to loopback`() {
    assertThat(DescriptionFetcher.overHttp()(URI("http:///desc.xml"))).isNull()
  }

  private fun start(vararg documents: Pair<String, String>) =
    FakeDescriptions(documents.toMap()).also { serving = it; it.start() }
}
