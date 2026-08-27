package app.muplay.cast.session

import app.muplay.cast.control.UpnpRenderer
import app.muplay.cast.didl.MimeAgreement
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.fake.FakeRenderer
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.net.LocalAddress
import app.muplay.cast.proxy.ByteRange
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.ProxyRegistry
import app.muplay.cast.proxy.ProxyUpstream
import app.muplay.cast.route.CastRouter
import app.muplay.cast.soap.SoapClient
import app.muplay.cast.soap.UpnpError
import app.muplay.model.StreamFormat
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * **A cast session against a real renderer, a real proxy and a real router, over real sockets.**
 *
 * Everything below runs against Task 3's `FakeRenderer` -- an in-process **real** UPnP renderer,
 * strict in the ways a Sonos is strict -- and Task 6's real [MediaProxyServer] on loopback. Nothing
 * here substitutes an [UpnpRenderer] or a [CastRouter], because the questions being asked are all of
 * the form *"what did the speaker actually receive"* and *"what does the session report when the
 * speaker stops answering"*, and neither survives being asked of a stand-in.
 *
 * **The half this class exists for is the second one.** A suite that drives play, pause and seek
 * against a healthy renderer proves the easy third: it is satisfied by a session that would report
 * `PLAYING` forever over a speaker that has lost power. `FakeRenderer.disappear` and
 * `FakeRenderer.disappearFor` are what make the hard third observable -- respectively "the speaker
 * is gone" and "the network dropped a few packets" -- and the two of them together are what fix the
 * threshold at three rather than at one.
 *
 * The poll interval is shortened to [POLL_MS] so that cases needing several poll cycles finish in
 * milliseconds. Everything the interval *means* -- that a position extrapolates between polls,
 * that it stops when the renderer stops -- is asserted through [CastPlayback.positionAtMs], which
 * takes the clock as an argument and needs no wall-clock waiting at all.
 */
class CastSessionTest {

  private val closeables = mutableListOf<Closeable>()
  private val registry = ProxyRegistry()
  private val proxy = MediaProxyServer(ConstantUpstream(CONTENT), registry, InetAddress.getLoopbackAddress())
    .also { closeables += it; it.start() }
  private val http = CastHttpClient()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val sessionStates = CopyOnWriteArrayList<CastSessionState>()
  private val snapshots = CopyOnWriteArrayList<CastPlayback>()

  private lateinit var session: CastSession
  private lateinit var fake: FakeRenderer

  @AfterEach
  fun tearDown() {
    scope.cancel()
    closeables.forEach { runCatching { it.close() } }
  }

  // ---- the queue and what reaches the renderer -------------------------------------------------

  @Test
  fun `the queue is what was set and the session reports where it is in it`() {
    val session = session()

    runBlocking { session.setQueue(THREE_TRACKS, startIndex = 1) }

    assertThat(session.playback.queueSize).isEqualTo(3)
    assertThat(session.playback.currentIndex).isEqualTo(1)
    assertThat(session.playback.currentMediaId).isEqualTo("track-2")
  }

  @Test
  fun `a start index past the end of the queue lands on the last item, not off it`() {
    val session = session()

    runBlocking { session.setQueue(THREE_TRACKS, startIndex = 9) }

    assertThat(session.playback.currentIndex).isEqualTo(2)
    assertThat(session.playback.currentMediaId).isEqualTo("track-3")
  }

  @Test
  fun `loading sends the uri and the didl to the renderer, in the order the service declares`() {
    val session = session()

    runBlocking { session.setQueue(THREE_TRACKS) }

    // Read off the renderer's recorded bytes, not off the session.
    val request = fake.soapRequests.last { it.action == "SetAVTransportURI" }
    assertThat(request.arguments!!.map { it.first })
      .containsExactly("InstanceID", "CurrentURI", "CurrentURIMetaData")
    assertThat(request.arguments!![1].second).endsWith(".mp3")
    // Escaped exactly once on the wire: a device that decodes what arrived sees `<DIDL-Lite`, one
    // that was sent a pre-escaped document sees `&lt;DIDL-Lite` and answers 714.
    assertThat(request.arguments!![2].second).startsWith("<DIDL-Lite")
    assertThat(request.arguments!![2].second).contains("track-1")
  }

  @Test
  fun `the renderer really fetched the media, from the proxy rather than from navidrome`() {
    // The discriminating observation. `play()` returning, `playWhenReady == true` and a READY state
    // are all satisfied by a renderer that was told to play and could not.
    val session = session()

    runBlocking { session.setQueue(THREE_TRACKS); session.play() }

    val fetch = fake.awaitMediaRequest(timeoutMs = AWAIT_MS)
    assertThat(fetch).isNotNull
    assertThat(fetch!!.target).endsWith(".mp3")
    awaitCondition("the proxy saw a HEAD and a GET") {
      proxy.requestLog.map { it.method }.containsAll(listOf("HEAD", "GET"))
    }
    // ...and the URL it fetched is this phone's, not the credential-bearing upstream.
    assertThat(fetch.target).startsWith(ProxyRegistry.PATH_PREFIX)
  }

  @Test
  fun `the three statements of the served format agree on the wire`() {
    // `MimeAgreement` re-derives all three legs from the artifacts the three parties really see.
    // Both inputs here are BYTES OBSERVED rather than expressions in the code under test: the DIDL
    // document as the renderer received and decoded it, and the `Content-Type` the proxy actually
    // put on the wire. That is why this discriminates where a runtime check inside `CastSession`
    // could not -- see `CastSession.send`'s KDoc.
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }

    val request = fake.soapRequests.last { it.action == "SetAVTransportURI" }
    val documentTheRendererGot = request.arguments!![2].second
    val urlTheRendererGot = request.arguments!![1].second
    val served = http.exchange(URI(urlTheRendererGot), "HEAD")

    assertThat(served.code).isEqualTo(200)
    assertThat(MimeAgreement.disagreements(documentTheRendererGot, served.head.headers["Content-Type"]))
      .isEmpty()
    // ...and the check had something to read on every leg, so "no disagreements" is not "nothing
    // was compared".
    assertThat(served.head.headers["Content-Type"]).isEqualTo("audio/mpeg")
    assertThat(documentTheRendererGot).contains("protocolInfo=\"http-get:*:audio/mpeg:")
    assertThat(MimeAgreement.extensionOfUrl(urlTheRendererGot)).isEqualTo("mp3")
  }

  @Test
  fun `a queue set while paused is not declared unroutable for never having been fetched`() {
    // `CastRouter.confirm` proves a route by waiting for the renderer to FETCH, and a renderer
    // fetches after `Play`. `SimpleBasePlayer` sets the queue before it sets playWhenReady, so
    // proving at load time would sit out the whole proof timeout and then call a perfectly good
    // speaker unroutable. Two observations: nothing failed, and no time was spent waiting.
    val session = session()

    val elapsed = kotlin.system.measureTimeMillis { runBlocking { session.setQueue(THREE_TRACKS) } }

    assertThat(session.playback.failure).isNull()
    assertThat(elapsed).isLessThan(PROOF_TIMEOUT_MS)
    // ...and the deferred proof really happens: playing it proves the route and it still does not
    // fail, which is what stops "defer" becoming "never check".
    runBlocking { session.play() }
    awaitPlaying()
    assertThat(session.playback.failure).isNull()
    assertThat(proxy.requestLog).isNotEmpty
  }

  // ---- the clock -------------------------------------------------------------------------------

  @Test
  fun `the position advances while the renderer is playing`() {
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()
    val before = session.playback.positionMs

    fake.advance(2_000L)

    awaitCondition("the poll picked up the renderer's new position") {
      session.playback.positionMs >= before + 2_000L
    }
    assertThat(session.playback.positionMs).isGreaterThan(before)
    assertThat(session.playback.positionAdvancing).isTrue()
  }

  @Test
  fun `the position extrapolates between polls`() {
    // Without this the seek bar ticks once a second, and a progress writer records a position up to
    // a whole poll interval stale. Asked of the snapshot at two clock readings, so it is the
    // extrapolation under test and not the passage of real time.
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()

    val snapshot = session.playback
    val measured = snapshot.positionMeasuredAtMs

    assertThat(snapshot.positionAtMs(measured + POLL_MS / 3))
      .isEqualTo(snapshot.positionMs + POLL_MS / 3)
    assertThat(snapshot.positionAtMs(measured + POLL_MS / 3))
      .isGreaterThan(snapshot.positionAtMs(measured))
  }

  @Test
  fun `pausing stops the clock and reaches the renderer`() {
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()

    runBlocking { session.pause() }

    awaitCondition("the renderer was paused") { fake.currentTransportState() == "PAUSED_PLAYBACK" }
    // The clock stopped, which "Pause was sent" does not prove: a snapshot read an hour later still
    // reports the same position.
    val paused = session.playback
    assertThat(paused.positionAdvancing).isFalse()
    assertThat(paused.positionAtMs(paused.positionMeasuredAtMs + 3_600_000L))
      .isEqualTo(paused.positionMs)
  }

  @Test
  fun `seeking moves the renderer and then the session`() {
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()

    runBlocking { session.seekTo(0, 83_000L) }

    assertThat(fake.soapRequests.last { it.action == "Seek" }.arguments)
      .contains("Unit" to "REL_TIME", "Target" to "0:01:23")
    awaitCondition("the renderer reported the sought position") {
      session.playback.positionMs >= 83_000L
    }
  }

  @Test
  fun `a device that cannot seek by time does not report that it can, and one that can does`() {
    // The honest UI. Task 9 withholds `COMMAND_SEEK_*` on this flag, so a device whose SCPD offers
    // no time seek mode shows no seek bar -- rather than one that answers 710 to every drag.
    val restricted = session(FakeRenderer.Strictness(supportedSeekModes = listOf("TRACK_NR")))
    runBlocking { restricted.setQueue(THREE_TRACKS) }
    assertThat(restricted.playback.canSeek).isFalse()

    // ...and an ordinary device does, so the flag is not simply always off. A second renderer on a
    // second socket, differing from the first in exactly the declared seek mode.
    val ordinary = session()
    runBlocking { ordinary.setQueue(THREE_TRACKS) }
    assertThat(ordinary.playback.canSeek).isTrue()
  }

  // ---- the end of a track, and something that only looks like it --------------------------------

  @Test
  fun `the track ends and the next one is sent to the renderer`() {
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()

    fake.advance(TRACK_MS)
    hardwareStop()

    awaitCondition("a second SetAVTransportURI reached the renderer") {
      fake.soapRequests.count { it.action == "SetAVTransportURI" } >= 2
    }
    val uris = fake.soapRequests.filter { it.action == "SetAVTransportURI" }.map { it.arguments!![1].second }
    assertThat(uris[0]).isNotEqualTo(uris[1])
    assertThat(session.playback.currentIndex).isEqualTo(1)
    assertThat(session.playback.currentMediaId).isEqualTo("track-2")
  }

  @Test
  fun `a stop that is not the end of the track does not skip to the next one`() {
    // The other side of the same branch, with the SAME stimulus: a renderer reports STOPPED both
    // for "finished" and for "somebody pressed stop on the speaker", and the only thing that tells
    // them apart is whether the position reached the duration. Reading them the same way skips a
    // track every time a listener touches the hardware.
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()

    fake.advance(1_000L)
    hardwareStop()

    awaitCondition("the session saw the stop") { !session.playback.positionAdvancing }
    Thread.sleep(POLL_MS * 4)
    assertThat(session.playback.currentIndex).isZero()
    assertThat(fake.soapRequests.count { it.action == "SetAVTransportURI" }).isEqualTo(1)
  }

  @Test
  fun `the end of the last track ends the queue rather than looking for another one`() {
    val session = session()
    runBlocking { session.setQueue(listOf(THREE_TRACKS.last())); session.play() }
    awaitPlaying()

    fake.advance(TRACK_MS)
    hardwareStop()

    awaitCondition("the queue ended") { session.playback.playbackState == CastPlaybackState.ENDED }
    assertThat(session.playback.playWhenReady).isFalse()
    assertThat(fake.soapRequests.count { it.action == "SetAVTransportURI" }).isEqualTo(1)
  }

  // ---- failures ---------------------------------------------------------------------------------

  @Test
  fun `a renderer reporting ERROR_OCCURRED becomes a reported failure and not a silent stall`() {
    // `CurrentTransportStatus = ERROR_OCCURRED` is how a renderer says it could not play what it
    // was given, and it arrives in a different out-argument from the state -- usually alongside an
    // ordinary STOPPED. Swallowing it produces a track that never starts and never fails.
    val session = session(identity = FakeRenderer.Identity(transportStatus = ERROR_OCCURRED))
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }

    awaitCondition("the session failed") { session.playback.failure != null }
    assertThat(session.playback.failure!!.kind).isEqualTo(CastFailureKind.RENDERER_REFUSED)
    assertThat(session.playback.playbackState).isEqualTo(CastPlaybackState.IDLE)
    assertThat(sessionStates.filterIsInstance<CastSessionState.Failed>()).isNotEmpty
  }

  @Test
  fun `a grouped sonos is reported rather than silently accepted`() {
    // Sonos quirk 4: a follower accepts `SetAVTransportURI` and plays nothing. The session must say
    // so, because "playing on Kitchen" over silence is the worst possible report.
    val session = session(identity = FakeRenderer.Identity(followingCoordinator = "x-rincon:RINCON_OTHER"))

    runBlocking { session.setQueue(THREE_TRACKS) }

    assertThat(session.playback.failure!!.kind).isEqualTo(CastFailureKind.RENDERER_REFUSED)
    assertThat(session.playback.failure!!.message).contains("Ungroup it")
    assertThat(session.playback.failure!!.message).contains("x-rincon:RINCON_OTHER")
    assertThat(session.playback.playbackState).isEqualTo(CastPlaybackState.IDLE)
  }

  @Test
  fun `a failed session ignores later commands rather than replacing the diagnosis with its symptoms`() {
    // Measured, not imagined: this is why the assertion above is about the FIRST failure. The
    // grouped speaker refuses `SetAVTransportURI`, so nothing is loaded, so the `Play` that follows
    // refuses too -- with `701 Transition not available`, which is the consequence of the real
    // problem and tells a user nothing. Without the refusal it is what they would have been shown.
    val session = session(identity = FakeRenderer.Identity(followingCoordinator = "x-rincon:RINCON_OTHER"))
    runBlocking { session.setQueue(THREE_TRACKS) }
    val diagnosis = session.playback.failure!!

    runBlocking { session.play(); session.seekTo(0, 1_000L); session.stop() }

    assertThat(session.playback.failure).isEqualTo(diagnosis)
    assertThat(diagnosis.message).doesNotContain("701")
    // ...and the refusal really refused: nothing further reached the speaker.
    assertThat(fake.soapRequests.none { it.action == "Play" }).isTrue()
    assertThat(fake.soapRequests.none { it.action == "Seek" }).isTrue()
  }

  @Test
  fun `a device this phone has no route to fails the session with a reason, and publishes nothing`() {
    val session = session(router = router(localAddress = { null }))

    runBlocking { session.setQueue(THREE_TRACKS) }

    assertThat(session.playback.failure!!.kind).isEqualTo(CastFailureKind.UNROUTABLE)
    assertThat(session.playback.failure!!.message).contains("Fake Speaker cannot be reached")
    assertThat(fake.soapRequests.none { it.action == "SetAVTransportURI" }).isTrue()
  }

  // ---- the renderer that disappears mid-stream ---------------------------------------------------

  @Test
  fun `a renderer that disappears mid-stream ends the session with the last known position`() {
    // **The whole point of this class.** Spec section 6 says playback stopping when the speaker
    // goes away is intended behaviour; playback *appearing to continue* is not.
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()
    fake.advance(42_000L)
    awaitCondition("the renderer reported 42 s") { session.playback.positionMs >= 42_000L }

    fake.disappear()

    awaitCondition("the session was declared lost") {
      sessionStates.any { it is CastSessionState.Lost }
    }
    val lost = sessionStates.filterIsInstance<CastSessionState.Lost>().first()
    assertThat(lost.deviceName).isEqualTo("Fake Speaker")
    assertThat(lost.mediaId).isEqualTo("track-1")
    // The position Task 9 resumes from. Zero here silently sends the listener back to the start of
    // the track -- or, for a book, to the start of the book.
    assertThat(lost.positionMs).isGreaterThanOrEqualTo(42_000L)
    assertThat(session.playback.failure!!.kind).isEqualTo(CastFailureKind.RENDERER_UNREACHABLE)
    assertThat(session.playback.playbackState).isEqualTo(CastPlaybackState.IDLE)
  }

  @Test
  fun `a renderer that disappears mid-stream stops the clock instead of playing on forever`() {
    // The other half, and the one a `Lost` state alone does not give you. A session that reported
    // the loss but left the position extrapolating would show a seek bar running to the end of a
    // track nobody can hear, and would hand a progress writer a position that was never played.
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()
    fake.advance(42_000L)
    awaitCondition("the renderer reported 42 s") { session.playback.positionMs >= 42_000L }

    fake.disappear()
    awaitCondition("the session was declared lost") {
      sessionStates.any { it is CastSessionState.Lost }
    }

    val frozen = session.playback
    assertThat(frozen.positionAdvancing).isFalse()
    assertThat(frozen.positionAtMs(frozen.positionMeasuredAtMs + 600_000L))
      .isEqualTo(frozen.positionMs)
    // ...and it froze at the last position the renderer really reported, not at zero and not at the
    // end of the track.
    assertThat(frozen.positionMs).isBetween(42_000L, TRACK_MS - 1)
  }

  @Test
  fun `two consecutive missed polls do not end the session, and a good poll resets the count`() {
    // The other direction, and the reason the threshold is three rather than one: a single dropped
    // poll on a busy network is ordinary, and ending a session on it makes casting unusable on real
    // Wi-Fi. Counted in requests by the fake rather than timed, so the assertion is exact rather
    // than a race against the poll loop's phase.
    //
    // **Two, written as a literal**, and this is the whole discrimination. Writing it as
    // `LOST_AFTER_FAILURES - 1` was measured to make this test blind to the mutation it exists to
    // catch: at a threshold of 1 the expression is 0, the fake hangs up on nothing, and the suite
    // stayed green with `LOST_AFTER_FAILURES = 1` in the tree (probe
    // `session/lost-after-one-failure`, MISSED). A test parameterised by the constant under test
    // moves with it and can never fail. This asserts a fact about the product instead: two missed
    // polls in a row are survivable.
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()

    // Twice, with successful polls in between: 4 failed polls in total, never 3 in a row. A
    // hung-up request is never recorded, so a growing count of recorded polls is proof that the
    // hang-ups were consumed AND that the renderer is being talked to again.
    repeat(2) {
      val before = pollCount()
      fake.disappearFor(2)
      awaitCondition("the polls resumed after two hang-ups") { pollCount() >= before + 2 }
    }

    assertThat(sessionStates.filterIsInstance<CastSessionState.Lost>()).isEmpty()
    assertThat(session.playback.failure).isNull()
    assertThat(session.playback.playbackState).isEqualTo(CastPlaybackState.READY)
  }

  @Test
  fun `a upnp error from a poll is not mistaken for a dead speaker`() {
    // Task 5 keeps `UpnpErrorException` and `SoapTransportException` apart so this branch can. The
    // speaker answered -- it is there -- and folding the two together would let one 501 on some
    // firmware tear down every session three seconds in.
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()

    val before = pollCount()
    // Five, as a literal and not as `LOST_AFTER_FAILURES + 2`, for the reason written out in
    // `two consecutive missed polls...`: an expression over the constant under test moves with it.
    // Five refusals in a row is comfortably more than any threshold a renderer should be declared
    // dead at, which is the fact being asserted.
    fake.faultNextControlRequests(5, UpnpError.ACTION_FAILED)

    // A faulted request IS recorded, so this waits out all five refusals and two good polls after.
    awaitCondition("the renderer refused five polls and then answered again") {
      pollCount() >= before + 7
    }
    assertThat(sessionStates.filterIsInstance<CastSessionState.Lost>()).isEmpty()
    assertThat(session.playback.failure).isNull()
  }

  @Test
  fun `stopping reaches the renderer and stops the clock`() {
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()

    runBlocking { session.stop() }

    awaitCondition("the renderer stopped") { fake.currentTransportState() == "STOPPED" }
    val stopped = session.playback
    assertThat(stopped.positionAdvancing).isFalse()
    assertThat(stopped.playWhenReady).isFalse()
    // Stopped with a queue is still READY -- the item is loaded and playable. IDLE here would tell
    // a UI there is nothing to press play on.
    assertThat(stopped.playbackState).isEqualTo(CastPlaybackState.READY)
  }

  @Test
  fun `an empty queue loads nothing, and asking it to play reports the renderer's refusal`() {
    val session = session()

    runBlocking { session.setQueue(emptyList()) }
    assertThat(session.playback.playbackState).isEqualTo(CastPlaybackState.IDLE)
    assertThat(session.playback.failure).isNull()
    assertThat(fake.soapRequests.none { it.action == "SetAVTransportURI" }).isTrue()

    runBlocking { session.play() }

    // 701 Transition not available, which is exactly what a real renderer answers to `Play` with
    // nothing loaded. Reported rather than swallowed: a play button that does nothing and says
    // nothing is the failure this class is written against.
    assertThat(session.playback.failure!!.kind).isEqualTo(CastFailureKind.RENDERER_REFUSED)
    assertThat(session.playback.failure!!.message).contains("Fake Speaker refused")
  }

  @Test
  fun `a command that cannot reach the renderer counts toward losing it, exactly as a poll does`() {
    // A command failing and a poll failing must agree about when a speaker has gone -- otherwise a
    // session with no poll running (nothing loaded yet) can never be declared lost at all, and sits
    // accepting commands into a void.
    val session = session()
    fake.disappear()

    runBlocking { repeat(CastSession.LOST_AFTER_FAILURES - 1) { session.setVolumePercent(40 + it) } }
    assertThat(sessionStates.filterIsInstance<CastSessionState.Lost>()).isEmpty()
    assertThat(session.playback.failure).isNull()

    runBlocking { session.setVolumePercent(43) }

    val lost = sessionStates.filterIsInstance<CastSessionState.Lost>().single()
    assertThat(session.playback.failure!!.kind).isEqualTo(CastFailureKind.RENDERER_UNREACHABLE)
    // Nothing was queued, so there is no track to resume and this says so rather than inventing one.
    assertThat(lost.mediaId).isNull()
    assertThat(lost.positionMs).isZero()
  }

  @Test
  fun `a renderer that never fetches falls back to fetching for itself, when that is switched on`() {
    // Task 7's fallback, end to end. The renderer is told to play and does not fetch, so the proof
    // times out and the item is re-issued against the upstream URL.
    val session = session(router = router(allowRendererDirect = true, proofTimeoutMs = SHORT_PROOF_MS))
    fake.fetchesMedia = false

    runBlocking { session.setQueue(listOf(EXTENSIONED_UPSTREAM)); session.play() }

    val uris = fake.soapRequests.filter { it.action == "SetAVTransportURI" }.map { it.arguments!![1].second }
    assertThat(uris).hasSize(2)
    assertThat(uris[0]).startsWith("http://127.0.0.1:${proxy.port}${ProxyRegistry.PATH_PREFIX}")
    assertThat(uris[1]).isEqualTo(EXTENSIONED_UPSTREAM.upstreamUrl)
    assertThat(session.playback.failure).isNull()
  }

  @Test
  fun `a renderer-direct fallback to a real subsonic url is refused by a strict renderer, and said so`() {
    // Task 7 measured this and could not fix it: a Subsonic stream URL's PATH carries no file
    // extension, Sonos infers the MIME type from the URL, and a strict renderer answers 714. What
    // this task owns is that the refusal becomes a reported failure rather than silence -- and that
    // the message does not carry the URL, which for this route is the credential-bearing one.
    val session = session(router = router(allowRendererDirect = true, proofTimeoutMs = SHORT_PROOF_MS))
    fake.fetchesMedia = false

    runBlocking { session.setQueue(listOf(THREE_TRACKS.first())); session.play() }

    assertThat(session.playback.failure!!.kind).isEqualTo(CastFailureKind.RENDERER_REFUSED)
    assertThat(session.playback.failure!!.message).contains("714")
    assertThat(session.playback.failure!!.message).doesNotContain("nav.example")
    assertThat(session.playback.playbackState).isEqualTo(CastPlaybackState.IDLE)
  }

  @Test
  fun `a renderer that never fetches and cannot fetch for itself fails with a reason a user can act on`() {
    // The outcome that matters most in this whole subsystem: without it the tap is accepted, `Play`
    // answers 200, the UI says "Playing on Kitchen", and nothing comes out of the speaker, forever.
    val session = session(router = router(allowRendererDirect = false, proofTimeoutMs = SHORT_PROOF_MS))
    fake.fetchesMedia = false

    runBlocking { session.setQueue(THREE_TRACKS); session.play() }

    assertThat(session.playback.failure!!.kind).isEqualTo(CastFailureKind.UNROUTABLE)
    assertThat(session.playback.failure!!.message).contains("Fake Speaker did not fetch anything")
    assertThat(session.playback.playbackState).isEqualTo(CastPlaybackState.IDLE)
  }

  @Test
  fun `seeking to another track re-issues the uri rather than sending a seek into thin air`() {
    // A renderer's transport knows about one URI at a time, so a cross-track seek is a load and not
    // a `Seek` -- and the new track needs its own route and its own DIDL anyway.
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()

    runBlocking { session.seekTo(2, 5_000L) }

    awaitCondition("the third track was sent") {
      fake.soapRequests.count { it.action == "SetAVTransportURI" } == 2
    }
    val uris = fake.soapRequests.filter { it.action == "SetAVTransportURI" }.map { it.arguments!![1].second }
    assertThat(uris[0]).isNotEqualTo(uris[1])
    assertThat(session.playback.currentIndex).isEqualTo(2)
    assertThat(session.playback.currentMediaId).isEqualTo("track-3")
    assertThat(fake.soapRequests.last { it.action == "Seek" }.arguments)
      .contains("Target" to "0:00:05")
  }

  @Test
  fun `a released session accepts nothing further, including a second release`() {
    val session = session()
    runBlocking { session.release() }
    val stops = fake.soapRequests.count { it.action == "Stop" }

    runBlocking {
      session.setQueue(THREE_TRACKS)
      session.play()
      session.stop()
      session.seekTo(0, 1_000L)
      session.setVolumePercent(80)
      session.release()
    }

    assertThat(session.playback.queueSize).isZero()
    assertThat(fake.soapRequests.none { it.action == "SetAVTransportURI" }).isTrue()
    // A second release must not send a second Stop, or revoke tokens a later session published.
    assertThat(fake.soapRequests.count { it.action == "Stop" }).isEqualTo(stops)
    assertThat(session.sessionState).isEqualTo(CastSessionState.Idle)
  }

  @Test
  fun `a renderer that reports no position keeps the last one instead of resetting to the start`() {
    // `RelTime = NOT_IMPLEMENTED` is a real answer from real firmware, and `UpnpTime.parseClock`
    // returns `null` for it rather than `0` precisely so this arm can exist. Read as zero it would
    // snap the seek bar back to the start once a second, and write a resume position of 0 for a
    // book somebody was forty minutes into.
    val session = session(identity = FakeRenderer.Identity(reportsPosition = false))
    runBlocking { session.setQueue(THREE_TRACKS, startPositionMs = 90_000L); session.play() }
    awaitPlaying()

    awaitCondition("several polls have happened") { pollCount() >= 3 }

    assertThat(session.playback.positionMs).isEqualTo(90_000L)
    // ...and the duration is the one the library knew, not the NOT_IMPLEMENTED the device sent.
    assertThat(session.playback.durationMs).isEqualTo(TRACK_MS)
    // ...and the bar still moves, by extrapolating from what was last known.
    val snapshot = session.playback
    assertThat(snapshot.positionAtMs(snapshot.positionMeasuredAtMs + 2_000L)).isEqualTo(92_000L)
  }

  @Test
  fun `a transport state this client cannot parse is not read as the end of a track`() {
    // `TransportState` keeps `UNKNOWN` apart from `STOPPED` for exactly this: folded together, one
    // unrecognised value from one firmware skips a track every second.
    val session = session(identity = FakeRenderer.Identity(transportStateOverride = "SOMETHING_NEW"))
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }

    awaitCondition("several polls have happened") { pollCount() >= 3 }

    assertThat(session.playback.playbackState).isEqualTo(CastPlaybackState.IDLE)
    assertThat(session.playback.positionAdvancing).isFalse()
    assertThat(session.playback.currentIndex).isZero()
    assertThat(fake.soapRequests.count { it.action == "SetAVTransportURI" }).isEqualTo(1)
  }

  @Test
  fun `a track whose length this app does not know never declares itself finished`() {
    // The other side of the end-of-track test's first condition. With no duration there is nothing
    // to compare a position against, so STOPPED can only mean "somebody stopped it" -- and guessing
    // otherwise would skip the rest of the queue on the first track with no length.
    val session = session()
    runBlocking { session.setQueue(listOf(THREE_TRACKS.first().copy(durationMs = 0L))); session.play() }
    awaitPlaying()

    fake.advance(60_000L)
    hardwareStop()
    awaitCondition("the session saw the stop") { !session.playback.positionAdvancing }
    Thread.sleep(POLL_MS * 4)

    assertThat(session.playback.durationMs).isZero()
    assertThat(session.playback.playbackState).isNotEqualTo(CastPlaybackState.ENDED)
    assertThat(session.playback.currentIndex).isZero()
  }

  @Test
  fun `a device with no volume control reports the default rather than pretending to have read one`() {
    val session = session(identity = FakeRenderer.Identity(hasRenderingControl = false))

    runBlocking { session.setQueue(THREE_TRACKS) }

    assertThat(session.playback.volumePercent).isEqualTo(CastSession.DEFAULT_VOLUME_PERCENT)
    assertThat(fake.soapRequests.none { it.action == "GetVolume" }).isTrue()
  }

  @Test
  fun `a track change whose route cannot be proved fails the session rather than playing silence`() {
    // The route is proved per item, so the second track's proof is a fresh question -- and the
    // answer can differ, because the phone may have changed network between two tracks of an album.
    val session = session(router = router(proofTimeoutMs = SHORT_PROOF_MS))
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()

    fake.fetchesMedia = false
    runBlocking { session.seekTo(1, 0L) }

    assertThat(session.playback.failure!!.kind).isEqualTo(CastFailureKind.UNROUTABLE)
    assertThat(session.playback.playbackState).isEqualTo(CastPlaybackState.IDLE)
  }

  @Test
  fun `playing again once the route is proved does not prove it a second time`() {
    // `unprovedRoute` is cleared by the first Play. A pause and a resume must not sit out another
    // proof timeout -- and must not wait at all when the renderer has already fetched.
    val session = session(router = router(proofTimeoutMs = PROOF_TIMEOUT_MS))
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()
    runBlocking { session.pause() }
    fake.fetchesMedia = false

    val elapsed = kotlin.system.measureTimeMillis { runBlocking { session.play() } }

    assertThat(elapsed).isLessThan(SHORT_PROOF_MS)
    assertThat(session.playback.failure).isNull()
  }

  // ---- volume, and ending the session ------------------------------------------------------------

  @Test
  fun `volume is written to the renderer and read back from it`() {
    val session = session()

    runBlocking { session.setVolumePercent(17) }

    assertThat(runBlocking { UpnpRenderer(device(), SoapClient(http), http).volume() }).isEqualTo(17)
    assertThat(session.playback.volumePercent).isEqualTo(17)
    // ...and loading takes the device's own level rather than assuming one, so a speaker somebody
    // turned down by hand does not show a slider at 30%.
    runBlocking { session.setQueue(THREE_TRACKS) }
    assertThat(session.playback.volumePercent).isEqualTo(17)
  }

  @Test
  fun `releasing stops the renderer and revokes every published token`() {
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()
    val published = URI(fake.soapRequests.last { it.action == "SetAVTransportURI" }.arguments!![1].second).path
    assertThat(registry.resolve(published)).isNotNull

    runBlocking { session.release() }

    assertThat(fake.currentTransportState()).isEqualTo("STOPPED")
    // Every token revoked: a proxy still serving after a session ends is a capability left on the
    // LAN with nobody watching it.
    assertThat(registry.resolve(published)).isNull()
    assertThat(session.sessionState).isEqualTo(CastSessionState.Idle)
  }

  @Test
  fun `a session released after the renderer has gone still revokes its tokens`() {
    // The commonest reason a session is released is that the speaker went away, so a `Stop` that
    // throws must not be allowed to skip the revoke.
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()
    val published = URI(fake.soapRequests.last { it.action == "SetAVTransportURI" }.arguments!![1].second).path

    fake.disappear()
    runBlocking { session.release() }

    assertThat(registry.resolve(published)).isNull()
  }

  @Test
  fun `every change to the snapshot is announced, and the announcements carry the changes`() {
    // Task 9 passes `SimpleBasePlayer::invalidateState` here, and Media3 derives every listener
    // callback -- `onIsPlayingChanged`, `onMediaItemTransition`, `onPositionDiscontinuity` -- from
    // the diff between the snapshots it reads afterwards. A change that does not announce itself is
    // a callback that never fires, which for a book cast to a speaker is a position never recorded.
    val session = session()
    runBlocking { session.setQueue(THREE_TRACKS); session.play() }
    awaitPlaying()
    fake.advance(5_000L)
    awaitCondition("the renderer reported 5 s") { session.playback.positionMs >= 5_000L }

    val positions = snapshots.map { it.positionMs }.distinct()
    assertThat(snapshots.size).isGreaterThan(3)
    assertThat(positions).hasSizeGreaterThan(1)
    // ...and whatever the session reports right now was announced, so the callback is not firing on
    // a stale value. Read once: the poll is still running, and comparing two live reads would race.
    assertThat(snapshots).contains(session.playback)
  }

  // ---- harness -----------------------------------------------------------------------------------

  private fun device(): CastDevice = CastDevice.from(
    DeviceDescription.parse(http.exchange(fake.descriptionUrl, "GET").bodyText(), fake.descriptionUrl),
    fake.descriptionUrl,
  )!!

  private fun router(
    allowRendererDirect: Boolean = false,
    localAddress: (InetAddress) -> InetAddress? = LocalAddress::towards,
    proofTimeoutMs: Long = PROOF_TIMEOUT_MS,
  ) = CastRouter(
    proxy,
    registry,
    allowRendererDirect,
    localAddress,
    // The renderer is on loopback and really does fetch, so the proof is taken rather than skipped:
    // asserting on a route the fast path waved through would assert nothing about routing.
    { _, _ -> false },
    proofTimeoutMs,
  )

  private fun session(
    strictness: FakeRenderer.Strictness = FakeRenderer.Strictness(),
    identity: FakeRenderer.Identity = FakeRenderer.Identity(),
    router: CastRouter? = null,
  ): CastSession {
    fake = FakeRenderer(strictness, identity).also { closeables += it; it.start() }
    val device = device()
    session = CastSession(
      device = device,
      renderer = UpnpRenderer(device, SoapClient(http), http),
      router = router ?: router(),
      scope = scope,
      pollIntervalMs = POLL_MS,
      onPlaybackChanged = { snapshots += session.playback },
      onSessionStateChanged = { sessionStates += it },
    )
    return session
  }

  /** A `Stop` from outside this session -- somebody pressing the button on the speaker. */
  private fun hardwareStop() = runBlocking {
    UpnpRenderer(device(), SoapClient(http), http).stop()
  }

  /** Recorded `GetTransportInfo`s. A hung-up request never reaches the recorder; a faulted one does. */
  private fun pollCount(): Int = fake.soapRequests.count { it.action == "GetTransportInfo" }

  private fun awaitPlaying() =
    awaitCondition("the renderer reported PLAYING") { session.playback.positionAdvancing }

  /** A bounded wait that fails loudly with what it did see, rather than a sleep that hopes. */
  private fun awaitCondition(what: String, predicate: () -> Boolean) {
    val deadline = System.nanoTime() + AWAIT_MS * 1_000_000L
    while (System.nanoTime() < deadline) {
      if (predicate()) return
      Thread.sleep(2L)
    }
    throw AssertionError(
      "timed out waiting for $what; playback=${session.playback}, sessionStates=$sessionStates",
    )
  }

  /** Serves whatever range it is asked for, out of one fixed body. */
  private class ConstantUpstream(private val content: ByteArray) : ProxyUpstream {
    override fun totalLength(url: String): Long = content.size.toLong()

    override fun open(url: String, range: ByteRange): InputStream =
      content.copyOfRange(range.firstByte.toInt(), range.lastByte.toInt() + 1).inputStream()
  }

  private companion object {
    const val POLL_MS = 60L
    const val AWAIT_MS = 15_000L
    const val PROOF_TIMEOUT_MS = 6_000L
    const val SHORT_PROOF_MS = 400L
    const val TRACK_MS = 300_000L
    const val ERROR_OCCURRED = "ERROR_OCCURRED"

    val CONTENT = ByteArray(4_096) { it.toByte() }
    val MP3: ServedMedia = ServedMedia.of("mp3", StreamFormat.Raw)

    /**
     * Three tracks, so index arithmetic is observable and a queue of one cannot make an off-by-one
     * look right. The upstream URLs are shaped like Navidrome's and carry **no** authentication
     * parameters, not even fabricated ones: a stream URL's `t` and `s` are password equivalents and
     * this repository does not write them down.
     */
    val THREE_TRACKS: List<CastSource> = (1..3).map { n ->
      CastSource(
        mediaId = "track-$n",
        title = "Track $n",
        artist = "Artist $n",
        albumTitle = "An Album",
        artworkUri = null,
        durationMs = TRACK_MS,
        isAudiobook = false,
        upstreamUrl = "https://nav.example/rest/stream?id=$n&format=raw",
        served = MP3,
      )
    }

    /**
     * An upstream URL whose path really does end in an extension, so the renderer-direct branch can
     * be observed **succeeding**. Nothing fetches it: that test switches the fake's media fetch off,
     * which is what makes the proof time out in the first place.
     */
    val EXTENSIONED_UPSTREAM: CastSource = THREE_TRACKS.first().copy(
      upstreamUrl = "http://127.0.0.1:1/media/track-1.mp3",
    )
  }
}
