package app.muplay.cast.control

import app.muplay.cast.didl.CastItem
import app.muplay.cast.didl.DidlLite
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.fake.FakeRenderer
import app.muplay.cast.fake.RecordedSoap
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.soap.SoapClient
import app.muplay.cast.soap.SoapTransportException
import app.muplay.cast.soap.UpnpError
import app.muplay.cast.soap.UpnpErrorException
import app.muplay.model.StreamFormat
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * `UpnpRenderer` against the **real** `FakeRenderer` from Task 3, reached through the **real**
 * description parse, so the control URLs under test are the ones `DeviceDescription` produced from
 * the relative `controlURL`s a Sonos really sends.
 *
 * Every SOAP assertion below reads the **bytes the device recorded** -- the argument list in the
 * order it arrived, the raw `SOAPACTION` value with its quotes, the request line naming which
 * control URL it went to. `assertThat(fake.soapRequests).hasSize(1)` would pass against an
 * envelope with arguments in the wrong order, an unquoted `SOAPACTION`, empty metadata and a URL
 * with no extension -- all four of which a real Sonos rejects, and all four of which this fake
 * rejects too.
 */
class UpnpRendererTest {

  private var fake: FakeRenderer? = null

  @AfterEach fun tearDown() { fake?.close() }

  private val http = CastHttpClient()

  private fun device(
    strictness: FakeRenderer.Strictness = FakeRenderer.Strictness(),
    identity: FakeRenderer.Identity = FakeRenderer.Identity(),
  ): CastDevice {
    val running = FakeRenderer(strictness, identity).also { fake = it; it.start() }
    return CastDevice.from(
      DeviceDescription.parse(http.exchange(running.descriptionUrl, "GET").bodyText(), running.descriptionUrl),
      running.descriptionUrl,
    )!!
  }

  private fun renderer(
    strictness: FakeRenderer.Strictness = FakeRenderer.Strictness(),
    identity: FakeRenderer.Identity = FakeRenderer.Identity(),
  ): UpnpRenderer = UpnpRenderer(device(strictness, identity), SoapClient(http), http)

  private fun item(id: String = "track-1", suffix: String = "mp3", durationMs: Long = 300_000L) = CastItem(
    mediaId = id,
    title = "Track 1",
    artist = "Test Artist",
    albumTitle = "Test Album",
    artworkUri = null,
    durationMs = durationMs,
    upnpClass = DidlLite.CLASS_MUSIC_TRACK,
    // Port 9 (discard) is never listening, so `FakeRenderer`'s media fetch fails quietly and no
    // test here depends on a byte of audio moving. Task 6 owns the proxy that really serves these.
    resourceUrl = "http://127.0.0.1:9/media/$id.$suffix",
    served = ServedMedia.of(suffix, StreamFormat.Raw),
  )

  private val RecordedSoap.arguments2: List<Pair<String, String>>
    get() = requireNotNull(arguments) { "the fake could not parse this request body: $bodyText" }

  /** The request line of the recording, which names the control URL the action was sent to. */
  private val RecordedSoap.requestLine: String get() = headText.lineSequence().first().trim()

  private fun soapRequests(): List<RecordedSoap> = fake!!.soapRequests

  private fun lastRequest(): RecordedSoap = soapRequests().last()

  /** A port nothing is listening on, so a connection to it is refused rather than hung. */
  private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

  // ---- SetAVTransportURI -----------------------------------------------------------------------

  @Test
  fun `setting a uri sends the three arguments in the declared order, with metadata escaped once`() = runTest {
    val renderer = renderer()

    renderer.setUri(item())

    // Read off the bytes the device recorded, not off what this test passed in.
    val request = lastRequest()
    assertThat(request.action).isEqualTo("SetAVTransportURI")
    assertThat(request.arguments2.map { it.first })
      .containsExactly("InstanceID", "CurrentURI", "CurrentURIMetaData")
    assertThat(request.arguments2[0].second).isEqualTo("0")
    assertThat(request.arguments2[1].second).isEqualTo("http://127.0.0.1:9/media/track-1.mp3")
    // The DECODED value, which is what a device acts on: escaped once it reads `<DIDL-Lite`,
    // escaped twice it reads `&lt;DIDL-Lite`, escaped not at all it is not there as text at all.
    assertThat(request.arguments2[2].second).startsWith("<DIDL-Lite")
    assertThat(request.arguments2[2].second).doesNotContain("&lt;")
    assertThat(request.rawSoapAction)
      .isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
    assertThat(request.requestLine).isEqualTo("POST /MediaRenderer/AVTransport/Control HTTP/1.1")
  }

  @Test
  fun `the uri that reached the device is the one the item carried`() = runTest {
    // Two observations, so `CurrentURI` cannot be a constant.
    val renderer = renderer()

    renderer.setUri(item("track-1", "mp3"))
    renderer.setUri(item("chapter-14", "m4b"))

    assertThat(soapRequests().filter { it.action == "SetAVTransportURI" }.map { it.arguments2[1].second })
      .containsExactly(
        "http://127.0.0.1:9/media/track-1.mp3",
        "http://127.0.0.1:9/media/chapter-14.m4b",
      )
  }

  @Test
  fun `the metadata that reached the device is this item's, and it names the format actually served`() =
    runTest {
      // The second observation of `CurrentURIMetaData`, and the one a `startsWith("<DIDL-Lite")`
      // check cannot make: a constant document would satisfy every other assertion in this class.
      val renderer = renderer()

      renderer.setUri(item("track-1", "mp3"))
      renderer.setUri(item("chapter-14", "m4b"))

      val metadata = soapRequests().filter { it.action == "SetAVTransportURI" }.map { it.arguments2[2].second }
      assertThat(metadata[0]).contains("id=\"track-1\"").contains("http-get:*:audio/mpeg:")
      assertThat(metadata[1]).contains("id=\"chapter-14\"").contains("http-get:*:audio/mp4:")
    }

  // ---- Play, Pause, Stop -----------------------------------------------------------------------

  @Test
  fun `play sends speed 1 and moves the device into PLAYING`() = runTest {
    val renderer = renderer()
    renderer.setUri(item())

    renderer.play()

    assertThat(lastRequest().arguments2).containsExactly("InstanceID" to "0", "Speed" to "1")
    assertThat(lastRequest().rawSoapAction)
      .isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
    assertThat(renderer.transportInfo().state).isEqualTo(TransportState.PLAYING)
  }

  @Test
  fun `pause and stop reach the device and change its state`() = runTest {
    val renderer = renderer()
    renderer.setUri(item())
    renderer.play()

    renderer.pause()
    assertThat(lastRequest().arguments2).containsExactly("InstanceID" to "0")
    assertThat(renderer.transportInfo().state).isEqualTo(TransportState.PAUSED)

    renderer.stop()
    assertThat(renderer.transportInfo().state).isEqualTo(TransportState.STOPPED)
  }

  @Test
  fun `a device reporting ERROR_OCCURRED is distinguished from one that merely stopped`() = runTest {
    // Two observations of one boolean, and the reason it is a separate field: a renderer that
    // could not fetch or decode what it was given answers `ERROR_OCCURRED` in
    // `CurrentTransportStatus` while `CurrentTransportState` still reads an ordinary `STOPPED`.
    // Task 8 turns `hasError` into a player error rather than a track that silently never starts.
    val healthy = renderer()
    assertThat(healthy.transportInfo()).isEqualTo(TransportInfo(TransportState.STOPPED, hasError = false))
    fake!!.close()

    val broken = renderer(identity = FakeRenderer.Identity(transportStatus = "ERROR_OCCURRED"))
    assertThat(broken.transportInfo()).isEqualTo(TransportInfo(TransportState.STOPPED, hasError = true))
  }

  // ---- Seek ------------------------------------------------------------------------------------

  @Test
  fun `seeking sends the preferred mode and a clock target, and the device moves`() = runTest {
    val renderer = renderer()
    renderer.setUri(item())

    assertThat(renderer.seek(83_000L)).isTrue

    val request = lastRequest()
    assertThat(request.arguments2).containsExactly(
      "InstanceID" to "0",
      "Unit" to "REL_TIME",
      "Target" to "0:01:23",
    )
    // ...and the *effect*, not only the request: the device's own position moved.
    assertThat(renderer.positionInfo().positionMs).isEqualTo(83_000L)
  }

  @Test
  fun `a second seek lands somewhere else`() = runTest {
    // The observation that stops `Target` being a constant and stops the position readout being one.
    val renderer = renderer()
    renderer.setUri(item())

    renderer.seek(83_000L)
    renderer.seek(7_000L)

    assertThat(lastRequest().arguments2.last()).isEqualTo("Target" to "0:00:07")
    assertThat(renderer.positionInfo().positionMs).isEqualTo(7_000L)
  }

  @Test
  fun `a device that only accepts ABS_TIME is seeked with ABS_TIME`() = runTest {
    // The SCPD-driven branch, observed changing behaviour. Without this, `preferredSeekMode` could
    // be hardcoded and every test above would still pass.
    val renderer = renderer(
      FakeRenderer.Strictness(supportedSeekModes = listOf(RendererCapabilities.ABS_TIME)),
    )
    renderer.setUri(item())

    assertThat(renderer.seek(10_000L)).isTrue
    assertThat(lastRequest().arguments2).contains("Unit" to "ABS_TIME")
  }

  @Test
  fun `a device that cannot seek by time reports so, and seek returns false without throwing`() = runTest {
    val renderer = renderer(FakeRenderer.Strictness(supportedSeekModes = listOf("TRACK_NR")))
    renderer.setUri(item())

    assertThat(renderer.capabilities().preferredSeekMode).isNull()
    assertThat(renderer.seek(10_000L)).isFalse
    // No Seek request was even attempted -- the UI is told in advance, rather than after a failure.
    assertThat(soapRequests().map { it.action }).doesNotContain("Seek")
  }

  @Test
  fun `a seek past the end returns false rather than throwing`() = runTest {
    val renderer = renderer()
    renderer.setUri(item(durationMs = 10_000L))

    assertThat(renderer.seek(99_000L)).isFalse
    // The refusal really was the device's, on a request that really was sent -- otherwise this
    // would be indistinguishable from the "cannot seek at all" branch above.
    assertThat(lastRequest().action).isEqualTo("Seek")
  }

  @Test
  fun `an scpd that lied about its seek mode is a false, not a crash`() = runTest {
    // The `710` arm of the catch, and the reason it is there at all despite the capability being
    // READ rather than tried: firmware has advertised modes it then refuses. The fake's SCPD
    // declares REL_TIME and the device accepts only ABS_TIME, which is a disagreement no honest
    // device shows and every lying one does.
    val renderer = renderer(
      FakeRenderer.Strictness(
        supportedSeekModes = listOf(RendererCapabilities.ABS_TIME),
        declaredSeekModes = listOf(RendererCapabilities.REL_TIME),
      ),
    )
    renderer.setUri(item())

    assertThat(renderer.seek(5_000L)).isFalse
    // The request really was sent and really was refused with 710 -- otherwise this would be
    // indistinguishable from the "cannot seek at all" branch, which sends nothing.
    assertThat(lastRequest().arguments2).contains("Unit" to RendererCapabilities.REL_TIME)
  }

  @Test
  fun `a refusal that is not about seeking reaches the caller rather than reading as a false`() = runTest {
    // `seek` swallows exactly two codes. A device that refuses for any OTHER reason -- here 701,
    // which is what a real renderer (and this fake) answers to a Seek with nothing loaded -- must
    // reach the caller: a blanket `catch (e: UpnpErrorException) { false }` would pass every other
    // seek test in this class and turn "this device rejected the request outright" into "the seek
    // did not take".
    val renderer = renderer()

    val thrown = runCatching { renderer.seek(1_000L) }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(UpnpErrorException::class.java)
    assertThat((thrown as UpnpErrorException).fault.errorCode)
      .isEqualTo(UpnpError.TRANSITION_NOT_AVAILABLE)
  }

  @Test
  fun `a seek at a speaker that has gone away is not swallowed as a false`() = runTest {
    // `seek` returns `false` for the two ORDINARY refusals and for nothing else. A
    // `catch (e: IOException) { false }` would pass every other seek test in this class and turn a
    // dead speaker into a seek bar that simply does not move, which is the silent failure this
    // whole plan is written against.
    val renderer = renderer()
    renderer.setUri(item())
    assertThat(renderer.seek(1_000L)).isTrue
    fake!!.disappear()

    val thrown = runCatching { renderer.seek(2_000L) }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(SoapTransportException::class.java)
  }

  // ---- GetPositionInfo -------------------------------------------------------------------------

  @Test
  fun `position info comes back with the position, the duration and the track uri`() = runTest {
    val renderer = renderer()
    renderer.setUri(item(durationMs = 300_000L))
    renderer.play()
    fake!!.advance(42_000L)

    val info = renderer.positionInfo()

    // Every field, and the duration from the DIDL the device was given -- which is the round trip
    // through `res@duration` that no unit test of `DidlLite` alone can observe.
    assertThat(info.positionMs).isEqualTo(42_000L)
    assertThat(info.durationMs).isEqualTo(300_000L)
    assertThat(info.trackUri).isEqualTo("http://127.0.0.1:9/media/track-1.mp3")
    assertThat(info.isFollowingAnotherSpeaker).isFalse
  }

  @Test
  fun `a second track reports its own duration and not the first one's`() = runTest {
    // The second observation of `durationMs`, which is the field a constant would satisfy: it is
    // read back out of the DIDL the device parsed, so a `res@duration` that never varied would
    // pass the test above forever.
    val renderer = renderer()

    renderer.setUri(item(durationMs = 300_000L))
    val first = renderer.positionInfo().durationMs
    renderer.setUri(item(id = "track-2", durationMs = 61_000L))
    val second = renderer.positionInfo().durationMs

    assertThat(listOf(first, second)).containsExactly(300_000L, 61_000L)
  }

  // ---- RenderingControl ------------------------------------------------------------------------

  @Test
  fun `volume is read and written, and the value that comes back is the one that went in`() = runTest {
    val renderer = renderer()

    renderer.setVolume(17)
    assertThat(renderer.volume()).isEqualTo(17)
    renderer.setVolume(64)
    assertThat(renderer.volume()).isEqualTo(64)

    val request = soapRequests().last { it.action == "SetVolume" }
    assertThat(request.arguments2)
      .containsExactly("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "64")
    // The service in the SOAPACTION and the control URL are RenderingControl's, not AVTransport's.
    // A copy-paste of the transport's service type is a 401 on a conformant device and is
    // invisible to every assertion about arguments.
    assertThat(request.rawSoapAction)
      .isEqualTo("\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"")
    assertThat(request.requestLine).isEqualTo("POST /MediaRenderer/RenderingControl/Control HTTP/1.1")
  }

  @Test
  fun `a volume outside 0 to 100 is clamped rather than sent and refused`() = runTest {
    val renderer = renderer()

    renderer.setVolume(-5)
    assertThat(renderer.volume()).isZero
    renderer.setVolume(150)
    assertThat(renderer.volume()).isEqualTo(100)

    // On the wire, and both ends of the clamp: the fake answers 402 to anything outside 0..100, so
    // an unclamped value would not merely read back wrong, it would fail -- and the bytes say which.
    assertThat(soapRequests().filter { it.action == "SetVolume" }.map { it.arguments2.last().second })
      .containsExactly("0", "100")
  }

  @Test
  fun `muting sends a upnp boolean, and un-muting sends the other one`() = runTest {
    val renderer = renderer()

    renderer.setMuted(true)
    renderer.setMuted(false)

    assertThat(soapRequests().filter { it.action == "SetMute" }.map { it.arguments2 })
      .containsExactly(
        listOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to "1"),
        listOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to "0"),
      )
  }

  @Test
  fun `a device with no RenderingControl reports no volume instead of throwing`() = runTest {
    val renderer = renderer(identity = FakeRenderer.Identity(hasRenderingControl = false))

    assertThat(renderer.volume()).isNull()
    // ...and setting it is a no-op rather than an exception the UI has to swallow -- proved by the
    // absence of the request, not only by the absence of a throw. The fake answers 401 to every
    // RenderingControl action when it has no such service, so a request that WAS sent would be a
    // `UpnpErrorException` here.
    renderer.setVolume(50)
    renderer.setMuted(true)

    assertThat(soapRequests().map { it.action }).isEmpty()
  }

  // ---- SetNextAVTransportURI -------------------------------------------------------------------

  @Test
  fun `a device that declares SetNextAVTransportURI is given the next track, in order`() = runTest {
    val renderer = renderer(identity = FakeRenderer.Identity(supportsSetNextUri = true))
    renderer.setUri(item("track-1"))

    renderer.setNextUri(item("track-2"))

    val request = lastRequest()
    assertThat(request.action).isEqualTo("SetNextAVTransportURI")
    assertThat(request.arguments2.map { it.first })
      .containsExactly("InstanceID", "NextURI", "NextURIMetaData")
    assertThat(request.arguments2[1].second).isEqualTo("http://127.0.0.1:9/media/track-2.mp3")
    assertThat(request.arguments2[2].second).startsWith("<DIDL-Lite")
    assertThat(fake!!.queuedNextUri()).isEqualTo("http://127.0.0.1:9/media/track-2.mp3")
  }

  @Test
  fun `a device that declares no such action is never asked, rather than asked and refused`() = runTest {
    // The other direction, and the reason the capability is read rather than tried: this fake --
    // like a real renderer without the action -- answers 401. A client that called anyway would
    // throw here, and on some firmware would clear the queue that is already playing.
    val renderer = renderer(identity = FakeRenderer.Identity(supportsSetNextUri = false))
    renderer.setUri(item("track-1"))

    renderer.setNextUri(item("track-2"))

    assertThat(soapRequests().map { it.action }).doesNotContain("SetNextAVTransportURI")
  }

  @Test
  fun `a null next item clears the queue with two empty arguments`() = runTest {
    val renderer = renderer(identity = FakeRenderer.Identity(supportsSetNextUri = true))
    renderer.setUri(item("track-1"))
    renderer.setNextUri(item("track-2"))

    renderer.setNextUri(null)

    assertThat(lastRequest().arguments2)
      .containsExactly("InstanceID" to "0", "NextURI" to "", "NextURIMetaData" to "")
    assertThat(fake!!.queuedNextUri()).isNull()
  }

  // ---- The Sonos group quirk -------------------------------------------------------------------

  /**
   * A follower accepts `SetAVTransportURI` and plays nothing; detecting it is what turns silence
   * into a sentence the user can act on.
   */
  @Test
  fun `a sonos following another speaker is detected and named`() = runTest {
    val renderer = renderer(
      identity = FakeRenderer.Identity(followingCoordinator = "x-rincon:RINCON_OTHER01400"),
    )

    val thrown = runCatching { renderer.setUri(item()) }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(RendererFollowsAnotherException::class.java)
    val followed = thrown as RendererFollowsAnotherException
    assertThat(followed).hasMessageContaining("RINCON_OTHER01400").hasMessageContaining("grouped")
    assertThat(followed.coordinatorUri).isEqualTo("x-rincon:RINCON_OTHER01400")
    // Detected BEFORE the set, not after: a session that looked established and was not is exactly
    // the silent half-failure this check exists to prevent.
    assertThat(soapRequests().map { it.action }).doesNotContain("SetAVTransportURI")
  }

  @Test
  fun `a speaker that is not following is not reported as following`() = runTest {
    // The other direction. Without it, a check that threw unconditionally would pass the test
    // above and make every cast fail.
    val renderer = renderer()

    renderer.setUri(item())

    assertThat(renderer.positionInfo().isFollowingAnotherSpeaker).isFalse
    assertThat(soapRequests().map { it.action }).contains("SetAVTransportURI")
  }

  // ---- Failure shapes --------------------------------------------------------------------------

  @Test
  fun `a refused action surfaces the device's own error code`() = runTest {
    val renderer = renderer()

    // Play with nothing set: the fake answers 701, exactly as a real device does.
    val thrown = runCatching { renderer.play() }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(UpnpErrorException::class.java)
    assertThat((thrown as UpnpErrorException).fault.errorCode)
      .isEqualTo(UpnpError.TRANSITION_NOT_AVAILABLE)
  }

  @Test
  fun `a renderer that has gone away is a transport failure and not a upnp error`() = runTest {
    // The distinction Task 8's fallback branches on: a UPnP error means "the device said no", a
    // transport failure means "the device is not there". Collapsing them would make a 714 tear
    // down the session and a dead speaker look like a rejected format.
    val renderer = renderer()
    renderer.setUri(item())
    fake!!.disappear()

    val thrown = runCatching { renderer.play() }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(SoapTransportException::class.java)
    assertThat(thrown).isNotInstanceOf(UpnpErrorException::class.java)
  }

  // ---- Capabilities ----------------------------------------------------------------------------

  @Test
  fun `capabilities are fetched once and not on every seek`() = runTest {
    // A SCPD fetch per seek would add a round trip to every drag of the seek bar. Counted at the
    // DEVICE, which is the only place the difference is visible: an identity check on the returned
    // object would go green against a client that re-fetched and memoised the second answer.
    val renderer = renderer()
    renderer.setUri(item())

    renderer.seek(1_000L)
    renderer.seek(2_000L)
    renderer.seek(3_000L)
    renderer.capabilities()

    assertThat(fake!!.documentRequests.filter { it.endsWith("AVTransport1.xml") }).hasSize(1)
  }

  @Test
  fun `a device that declares no scpd url at all is castable on the conservative default`() = runTest {
    // The branch a fake cannot reach by serving a bad document: `avTransportScpdUrl` is null when
    // the description carried no `SCPDURL` for the service, which several generic renderers do.
    val declared = device()
    val renderer = UpnpRenderer(declared.copy(avTransportScpdUrl = null), SoapClient(http), http)

    assertThat(renderer.capabilities()).isEqualTo(RendererCapabilities.DEFAULT)
    assertThat(fake!!.documentRequests.filter { it.endsWith("AVTransport1.xml") }).isEmpty()
    // ...and it is still castable, seeking on the default's REL_TIME.
    renderer.setUri(item())
    assertThat(renderer.seek(5_000L)).isTrue
  }

  @Test
  fun `a scpd that cannot be fetched at all is the conservative default, not a failed cast`() = runTest {
    // The other arm of the same fallback: the URL is there and the fetch fails. A device whose
    // SCPD 404s, times out or refuses the connection is still castable; it just cannot be asked
    // what it supports, and a cast that failed because a *description* could not be read would be
    // a speaker the user can see and cannot use.
    val declared = device()
    val renderer = UpnpRenderer(
      declared.copy(avTransportScpdUrl = URI("http://127.0.0.1:${closedPort()}/xml/AVTransport1.xml")),
      SoapClient(http),
      http,
    )

    assertThat(renderer.capabilities()).isEqualTo(RendererCapabilities.DEFAULT)
    renderer.setUri(item())
    assertThat(renderer.seek(5_000L)).isTrue
  }
}
