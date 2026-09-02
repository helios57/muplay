package app.muplay.cast.session

import app.muplay.cast.control.UpnpRenderer
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.fake.FakeRenderer
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.net.CredentialQuery
import app.muplay.cast.net.LocalAddress
import app.muplay.cast.proxy.ByteRange
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.ProxyRegistry
import app.muplay.cast.proxy.ProxyUpstream
import app.muplay.cast.proxy.UpstreamBody
import app.muplay.cast.route.CastRouter
import app.muplay.cast.soap.SoapClient
import app.muplay.model.StreamFormat
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * **Nothing this app sends to a speaker may carry the user's Subsonic credentials.**
 *
 * A Subsonic `t` is `md5(password + salt)` and the `s` beside it is that salt, so the pair is a
 * **non-expiring password equivalent** granting the whole API as the user. UPnP AV has no
 * authentication of any kind: any unauthenticated device on the LAN can issue `GetMediaInfo` and
 * read `CurrentURIMetaData` straight back out of the renderer, renderers routinely log the URLs
 * they are given, and [app.muplay.cast.http.CastHttpClient] speaks plain HTTP on the LAN, so a
 * passive observer gets it too.
 *
 * ### Why this class exists rather than one more case in `CastSessionTest`
 *
 * The leak that prompted it was **invisible to every existing test, by construction**. Every
 * `CastSource` fixture in this module sets `artworkUri = null` and an upstream URL carrying no
 * authentication parameters -- `CastSessionTest`'s own fixture says so out loud -- so no test in
 * the suite could observe a credential reaching a renderer even when one did. The queue below is
 * the first fixture in this module that carries the shape at all, and it is deliberately its own
 * class so that "the fixture is credential-shaped" cannot quietly be edited away by somebody
 * tidying an unrelated case.
 *
 * ### What it asserts, and why it is a rule rather than one assertion
 *
 * It scans **every byte of every SOAP request the renderer received** -- not the DIDL document
 * this module renders, not a field on a `CastItem`, but the bytes that arrived on the socket.
 * That is what makes it a guard against the *next* credential-bearing URL rather than against this
 * one: a field added to [app.muplay.cast.didl.DidlLite], a new argument on a new action, or a
 * failure message that quotes a URL are all bytes on this socket and all fail here.
 *
 * The one place a credential may legitimately appear is
 * [app.muplay.cast.route.CastRoute.RendererDirect]'s `CurrentURI`, which is the Navidrome URL
 * itself and is off by default behind an explicit user opt-in. `a renderer-direct route is the one
 * and only place...` pins that exemption to exactly one argument of one action, so widening it
 * fails too.
 *
 * The token values below are not a real credential and could not be one: `t` is a fixed run of hex
 * that is not the md5 of anything this project knows. Only the *shape* is under test.
 */
class CastCredentialLeakTest {

  private val closeables = mutableListOf<Closeable>()
  private val registry = ProxyRegistry()
  private val proxy = MediaProxyServer(ConstantUpstream, registry, InetAddress.getLoopbackAddress())
    .also { closeables += it; it.start() }
  private val http = CastHttpClient()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private lateinit var session: CastSession
  private lateinit var fake: FakeRenderer

  @AfterEach
  fun tearDown() {
    scope.cancel()
    closeables.forEach { runCatching { it.close() } }
  }

  @Test
  fun `no soap request a renderer receives carries the subsonic auth parameters`() {
    val session = session()

    runBlocking { session.setQueue(CREDENTIALED_QUEUE); session.play() }

    val offenders = fake.soapRequests
      .flatMap { CredentialQuery.parametersIn(it.bodyText) }
    assertThat(offenders)
      .describedAs(
        "SOAP bodies the renderer received, which carried Subsonic auth parameters: %s",
        fake.soapRequests.filter { CredentialQuery.parametersIn(it.bodyText).isNotEmpty() }
          .map { it.action },
      )
      .isEmpty()
  }

  @Test
  fun `the didl document the renderer decoded carries no auth parameters and no artwork url of navidrome's`() {
    val session = session()

    runBlocking { session.setQueue(CREDENTIALED_QUEUE) }

    val didl = fake.soapRequests.last { it.action == "SetAVTransportURI" }.arguments!![2].second
    assertThat(didl).startsWith("<DIDL-Lite")
    assertThat(CredentialQuery.parametersIn(didl)).isEmpty()
    assertThat(didl).doesNotContain(ARTWORK_HOST)
    assertThat(didl).doesNotContain(SALT)
    assertThat(didl).doesNotContain(TOKEN)
  }

  @Test
  fun `the artwork the renderer is given is this phone's proxy, and it serves the bytes`() {
    // The other half of the fix: the credential is removed by *replacing* the URL, not by dropping
    // the element, so a speaker still shows cover art. Asserting only the absence would be
    // satisfied by a fix that silently stopped sending artwork at all.
    val session = session()

    runBlocking { session.setQueue(CREDENTIALED_QUEUE) }

    val didl = fake.soapRequests.last { it.action == "SetAVTransportURI" }.arguments!![2].second
    val artwork = didl.substringAfter("<upnp:albumArtURI>").substringBefore("</upnp:albumArtURI>")
    assertThat(artwork).startsWith("http://127.0.0.1:${proxy.port}${ProxyRegistry.ART_PATH_PREFIX}")

    val response = http.exchange(URI(artwork), "GET")
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body).isEqualTo(ARTWORK_BYTES)
    assertThat(response.head.headers["Content-Type"]).isEqualTo("image/webp")
    assertThat(response.head.headers["Content-Length"]).isEqualTo("${ARTWORK_BYTES.size}")
  }

  @Test
  fun `a renderer-direct route is the one and only place a credential may appear`() {
    // Renderer-direct is off by default and is an explicit, documented user opt-in: the speaker is
    // handed the Navidrome URL because the user said it may be. Even then it is one argument of one
    // action -- the metadata document beside it, and the artwork inside that document, still carry
    // nothing.
    val session = session(router = router(allowRendererDirect = true, proofTimeoutMs = SHORT_PROOF_MS))
    // The renderer is told to play and does not fetch, so the proof times out and the item is
    // re-issued against the upstream URL. That is the only route on which a credential may travel.
    fake.fetchesMedia = false

    runBlocking { session.setQueue(CREDENTIALED_QUEUE); session.play() }
    awaitCondition("the route fell back to renderer-direct") {
      fake.soapRequests.count { it.action == "SetAVTransportURI" } >= 2
    }

    val reissued = fake.soapRequests.last { it.action == "SetAVTransportURI" }
    val arguments = reissued.arguments!!
    assertThat(arguments.map { it.first })
      .containsExactly("InstanceID", "CurrentURI", "CurrentURIMetaData")

    // The exemption, and its exact extent. `CurrentURI` is the stream URL the user opted in to
    // sending, and the DIDL's `<res>` **must** repeat it -- a document whose `<res>` named a
    // different resource from the `CurrentURI` beside it is not something a renderer would play.
    // So the credential legitimately appears in exactly those two places on this route, and the
    // assertions below say which two rather than waving the whole document through.
    val didl = arguments[2].second
    assertThat(CredentialQuery.parametersIn(arguments[0].second)).isEmpty()
    assertThat(CredentialQuery.parametersIn(arguments[1].second)).containsExactly("u", "t", "s")

    val resource = didl.substringBefore("</res>").substringAfterLast('>')
    // Escaped once inside the document, decoded once out of the SOAP argument: the two are the
    // same URL, which is the invariant that makes `<res>` an unavoidable second copy rather than a
    // second leak.
    assertThat(resource.replace("&amp;", "&")).isEqualTo(arguments[1].second)

    // ...and the cover is simply not sent. Not as Navidrome's URL, which would be the leak arriving
    // by the back door, and not as a proxy URL either, which this renderer has just proved it
    // cannot reach. `DidlLite` omits an absent optional field rather than rendering it empty, so
    // the element is gone rather than blank.
    assertThat(didl).doesNotContain("albumArtURI")
    assertThat(didl).doesNotContain(ARTWORK_HOST)
    // Every credential parameter in the whole document is accounted for by that one `<res>`: strike
    // the resource URL out and nothing credential-shaped is left anywhere else in it. This is the
    // assertion that would fail if a later task put a credential-bearing value in a *new* element.
    assertThat(CredentialQuery.parametersIn(didl.replace(resource, ""))).isEmpty()
  }

  // ---- harness -----------------------------------------------------------------------------------

  private fun device(): CastDevice = CastDevice.from(
    DeviceDescription.parse(http.exchange(fake.descriptionUrl, "GET").bodyText(), fake.descriptionUrl),
    fake.descriptionUrl,
  )!!

  private fun router(
    allowRendererDirect: Boolean = false,
    proofTimeoutMs: Long = PROOF_TIMEOUT_MS,
  ) = CastRouter(
    proxy,
    registry,
    { allowRendererDirect },
    LocalAddress::towards,
    { _, _ -> false },
    proofTimeoutMs,
  )

  private fun session(router: CastRouter? = null): CastSession {
    // A default renderer, which fetches: the ordinary cases below are therefore proved routes and
    // not merely minted ones. `fetchesMedia = false` is what the renderer-direct case sets to make
    // the proof time out.
    fake = FakeRenderer().also { closeables += it; it.start() }
    val device = device()
    session = CastSession(
      device = device,
      renderer = UpnpRenderer(device, SoapClient(http), http),
      router = router ?: router(),
      scope = scope,
      pollIntervalMs = POLL_MS,
    )
    return session
  }

  private fun awaitCondition(what: String, predicate: () -> Boolean) {
    val deadline = System.nanoTime() + AWAIT_MS * 1_000_000L
    while (System.nanoTime() < deadline) {
      if (predicate()) return
      Thread.sleep(2L)
    }
    throw AssertionError("timed out waiting for $what; playback=${session.playback}")
  }

  /** Serves one fixed body for the audio and one for the artwork, by path shape. */
  private object ConstantUpstream : ProxyUpstream {
    override fun totalLength(url: String): Long = CONTENT.size.toLong()

    override fun open(url: String, range: ByteRange): InputStream =
      CONTENT.copyOfRange(range.firstByte.toInt(), range.lastByte.toInt() + 1).inputStream()

    override fun readFully(url: String, maxBytes: Int): UpstreamBody =
      UpstreamBody(ARTWORK_BYTES, "image/webp")
  }

  private companion object {
    const val POLL_MS = 60L
    const val AWAIT_MS = 15_000L
    const val PROOF_TIMEOUT_MS = 6_000L
    const val SHORT_PROOF_MS = 400L

    val CONTENT = ByteArray(4_096) { it.toByte() }
    val ARTWORK_BYTES = ByteArray(512) { (it * 7).toByte() }
    val MP3: ServedMedia = ServedMedia.of("mp3", StreamFormat.Raw)

    const val ARTWORK_HOST = "nav.example"
    const val SALT = "0123456789ab"
    const val TOKEN = "abcdefabcdefabcdefabcdefabcdefab"
    const val AUTH = "u=listener&t=$TOKEN&s=$SALT&v=1.16.1&c=MuPlay"

    /**
     * The queue the whole class turns on: **both** URLs carry the auth parameter shape, because
     * both really do in production -- `SubsonicClient.streamUrl` and `SubsonicClient.coverArtUrl`
     * append `authParams()` alike, and it was the second one that reached a renderer for a year.
     *
     * The stream URL's path ends in an extension so the renderer-direct case can be observed
     * succeeding rather than failing at `714 Illegal MIME-type` for an unrelated reason.
     */
    val CREDENTIALED_QUEUE: List<CastSource> = listOf(
      CastSource(
        mediaId = "track-1",
        title = "Track 1",
        artist = "Artist 1",
        albumTitle = "An Album",
        artworkUri = "https://$ARTWORK_HOST/rest/getCoverArt?id=al-1&size=512&$AUTH",
        durationMs = 300_000L,
        isAudiobook = false,
        upstreamUrl = "http://127.0.0.1:1/media/track-1.mp3?$AUTH",
        served = MP3,
      ),
    )
  }
}
