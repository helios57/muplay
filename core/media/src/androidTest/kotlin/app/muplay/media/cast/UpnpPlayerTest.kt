package app.muplay.media.cast

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.cast.control.UpnpRenderer
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.fake.FakeRenderer
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.OkHttpProxyUpstream
import app.muplay.cast.proxy.ProxyRegistry
import app.muplay.cast.route.CastRouter
import app.muplay.cast.session.CastSession
import app.muplay.cast.soap.UpnpTime
import app.muplay.cast.soap.SoapClient
import app.muplay.media.MediaItems
import app.muplay.media.RealTrackBytes
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The wrapper: a UPnP renderer seen by Media3 as an ordinary `Player`.**
 *
 * The half of the cast output that could not live in `:core:cast` -- `SimpleBasePlayer` is a Media3
 * type and that module is pure JVM by design -- so this is the only tier where it can be driven at
 * all. Everything under it is real: `:core:cast`'s fake renderer over a real socket, the real
 * range-serving proxy, and real bytes from the seeded container when the renderer fetches.
 *
 * ### What this suite is for, and what it deliberately leaves to Tier 1
 *
 * Every **decision** about a cast session -- when a speaker counts as lost, what a position means
 * between polls, whether a `STOPPED` is the end of a track -- belongs to `CastSession` and is gated
 * against the same fake renderer on the JVM, in seconds, with every arm reachable. What is here is
 * the **translation**, and the assertions are shaped accordingly: what did the renderer receive,
 * and what does Media3 see.
 *
 * The most important one is [theQueuesStartPositionReachesTheRendererAsATimeSeek]. It is the middle
 * link of the chain the handover's headline claim rests on -- the phone's position reaches the
 * incoming `Player`, this turns it into a `Seek`, and the renderer honours it -- and without it the
 * two ends could both be right while the join dropped the number.
 */
@RunWith(AndroidJUnit4::class)
class UpnpPlayerTest {

  private lateinit var registry: ProxyRegistry
  private lateinit var proxy: MediaProxyServer
  private lateinit var router: CastRouter
  private lateinit var http: CastHttpClient
  private lateinit var scope: CoroutineScope
  private lateinit var fake: FakeRenderer
  private lateinit var device: CastDevice
  private lateinit var songs: List<Song>
  private lateinit var items: List<MediaItem>
  private var player: UpnpPlayer? = null

  @Before
  fun setUp() {
    registry = ProxyRegistry()
    // `127.0.0.1` explicitly: on Android `InetAddress.getLoopbackAddress()` is the IPv6 loopback,
    // and the fake renderer -- which advertises itself at `127.0.0.1` -- could not then fetch.
    proxy = MediaProxyServer(OkHttpProxyUpstream(OkHttpClient()), registry, InetAddress.getByName("127.0.0.1"))
    proxy.start()
    router = CastRouter(proxy, registry, allowRendererDirect = { false })
    http = CastHttpClient()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    songs = runBlocking { RealTrackBytes.audiobookFiles() }.take(2)
    check(songs.size == 2) { "expected at least two seeded audiobook files, found ${songs.size}" }
    items = songs.map {
      MediaItems.of(it, RealTrackBytes.rawStreamUrl(it), artworkUri = null, isAudiobook = true, format = StreamFormat.Raw)
    }
    startRenderer(FakeRenderer.Strictness())
  }

  @After
  fun tearDown() {
    runCatching { onMain { player?.release() } }
    if (::scope.isInitialized) scope.cancel()
    if (::fake.isInitialized) runCatching { fake.close() }
    if (::proxy.isInitialized) runCatching { proxy.close() }
  }

  @Test
  fun theQueuesStartPositionReachesTheRendererAsATimeSeek() {
    // **The middle link of the handover's headline claim.** The position the incoming player is
    // given has to leave this wrapper as an `AVTransport::Seek` at that time -- read off the
    // renderer's own recorded bytes, with no wait for a value and nothing playing that could drift
    // into the answer.
    val player = player()

    onMain { player.setMediaItems(items.toMutableList(), 1, START_POSITION_MS) }

    awaitLoadSettled()
    assertThat(lastSeekTargetMs()).isEqualTo(START_POSITION_MS)
    assertThat(lastSetUriMetadata()).contains(songs[1].id)
    assertThat(onMain { player.currentMediaItemIndex }).isEqualTo(1)
    assertThat(onMain { player.mediaItemCount }).isEqualTo(2)
  }

  @Test
  fun aQueueStartedAtZeroSendsNoSeekAtAll() {
    // The other side of the branch above, and it is what makes the assertion there mean something:
    // a wrapper that seeked unconditionally would satisfy that test and would also drag every fresh
    // album back to 00:00:00 through a redundant round trip.
    val player = player()

    onMain { player.setMediaItems(items.toMutableList(), 0, 0L) }

    awaitLoadSettled()
    assertThat(fake.soapRequests.count { it.action == "Seek" }).isZero()
  }

  @Test
  fun playAndPauseReachTheSpeakerAndTheReportedStateFollowsTheSpeakerRatherThanTheRequest() {
    val player = player()
    onMain { player.setMediaItems(items.toMutableList(), 0, 0L); player.prepare() }
    awaitLoadSettled()

    onMain { player.play() }

    awaitCondition("the renderer to report PLAYING") { fake.currentTransportState() == "PLAYING" }
    awaitCondition("Media3 to see it playing") { onMain { player.isPlaying } }
    onMain { player.pause() }
    awaitCondition("the renderer to report PAUSED") { fake.currentTransportState() == "PAUSED_PLAYBACK" }
    assertThat(onMain { player.isPlaying }).isFalse()
  }

  @Test
  fun aDragOfTheSeekBarReachesTheRendererAsATimeSeekAtThatTime() {
    val player = player()
    onMain { player.setMediaItems(items.toMutableList(), 0, 0L); player.prepare() }
    awaitLoadSettled()

    onMain { player.seekTo(DRAG_POSITION_MS) }

    awaitCondition("a Seek to reach the renderer") { fake.soapRequests.any { it.action == "Seek" } }
    assertThat(lastSeekTargetMs()).isEqualTo(DRAG_POSITION_MS)
  }

  @Test
  fun skippingToTheNextTrackReloadsItRatherThanSeekingWithinTheCurrentOne() {
    // Media3 asks for the *default* position with `C.TIME_UNSET`, and a renderer's transport knows
    // about one URI at a time -- so the next track is a fresh `SetAVTransportURI`, not a `Seek`.
    // Reading `C.TIME_UNSET` as a literal position would ask the speaker to seek to
    // `-9223372036854775807`.
    val player = player()
    onMain { player.setMediaItems(items.toMutableList(), 0, 0L); player.prepare() }
    awaitLoadSettled()

    onMain { player.seekToNextMediaItem() }

    awaitCondition("the second track to reach the renderer") { lastSetUriMetadata().contains(songs[1].id) }
    awaitLoadSettled()
    assertThat(fake.soapRequests.count { it.action == "Seek" }).isZero()
    assertThat(onMain { player.currentMediaItemIndex }).isEqualTo(1)
  }

  @Test
  fun theVolumeKeysDriveTheSpeakerRatherThanThePhone() {
    // `PLAYBACK_TYPE_REMOTE` is what routes the hardware keys and the notification's volume row to
    // the renderer. Asserted together with a value that actually arrived, because the device info
    // alone would be satisfied by a wrapper that reported "remote" and swallowed every change.
    val player = player()
    onMain { player.setMediaItems(items.toMutableList(), 0, 0L); player.prepare() }
    awaitLoadSettled()

    onMain { player.setDeviceVolume(SPEAKER_VOLUME, 0) }

    awaitCondition("the renderer to receive the volume") {
      fake.soapRequests.any { it.action == "SetVolume" }
    }
    assertThat(onMain { player.deviceInfo.playbackType }).isEqualTo(DeviceInfo.PLAYBACK_TYPE_REMOTE)
    assertThat(onMain { player.deviceInfo.maxVolume }).isEqualTo(100)
    val sent = fake.soapRequests.last { it.action == "SetVolume" }.arguments!!.last()
    assertThat(sent.second).isEqualTo(SPEAKER_VOLUME.toString())
  }

  @Test
  fun speedIsReportedAsOneAndCannotBeAskedFor() {
    // `AVTransport::Play` takes `Speed="1"`, and no renderer this app targets implements anything
    // else. A book's per-item speed is real and is honoured locally; while cast it cannot be, and
    // the honest answer is to **withhold the command** rather than accept it and quietly play at
    // 1.0x. Both halves asserted, because reporting 1.0 while still advertising the command is the
    // shape that lies to a controller.
    val player = player()
    onMain { player.setMediaItems(items.toMutableList(), 0, 0L); player.prepare() }
    awaitLoadSettled()

    assertThat(onMain { player.playbackParameters.speed }).isEqualTo(1.0f)
    assertThat(onMain { player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) }).isFalse()
    // ...and the command that this renderer *does* support, from its own service description, so
    // that "no commands at all" cannot pass the assertion above.
    assertThat(onMain { player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }).isTrue()
    assertThat(onMain { player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }).isTrue()
  }

  @Test
  fun theOverloadsThatNameNoIndexAndNoPositionStartAtTheBeginning() {
    // `setMediaItems(items, resetPosition)` is what a `MediaController` sends, and Media3 passes
    // `C.INDEX_UNSET` and `C.TIME_UNSET` down for it. Read as literals those are
    // `-9223372036854775807` and `-1`, which reach the renderer as a `Seek` to a negative clock
    // time -- a 710 on a strict device and, on a lenient one, silence.
    val player = player()

    onMain { player.setMediaItems(items.toMutableList(), true) }

    awaitLoadSettled()
    assertThat(fake.soapRequests.count { it.action == "Seek" }).isZero()
    assertThat(onMain { player.currentMediaItemIndex }).isZero()
    assertThat(lastSetUriMetadata()).contains(songs[0].id)
  }

  @Test
  fun aSpeakerThatCannotReachThisPhoneIsReportedAsSuchRatherThanPlayingSilence() {
    // The third failure kind, and the one the whole routing design exists for: the renderer
    // accepted the URI and never fetched it, so it is on a network that cannot reach this phone.
    // Mapped to a different Media3 code from "it refused the format" and from "it is gone", because
    // all three are different things to tell a user.
    fake.fetchesMedia = false
    val player = player()

    onMain { player.setMediaItems(items.toMutableList(), 0, 0L); player.prepare(); player.play() }

    awaitCondition("the route proof to run out and be reported") {
      onMain { player.playerError } != null
    }
    val error = onMain { player.playerError }!!
    assertThat(error.errorCode).isEqualTo(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    assertThat(error.message).contains("different network")
    assertThat(error.message).doesNotContain("/rest/stream")
  }

  @Test
  fun stoppingTheCastPlayerStopsTheSpeakerWithoutEndingTheSession() {
    // `stop()` and `release()` are different things: a stop leaves the session usable, which is
    // what a `MediaController`'s stop button means.
    val player = player()
    onMain { player.setMediaItems(items.toMutableList(), 0, 0L); player.prepare(); player.play() }
    awaitCondition("the renderer to report PLAYING") { fake.currentTransportState() == "PLAYING" }

    onMain { player.stop() }

    awaitCondition("the renderer to be stopped") { fake.currentTransportState() == "STOPPED" }
    assertThat(onMain { player.playerError }).isNull()
  }

  @Test
  fun aPlayerWithNothingQueuedIsIdleRatherThanPretendingToHoldATrack() {
    val player = player()

    assertThat(onMain { player.playbackState }).isEqualTo(Player.STATE_IDLE)
    assertThat(onMain { player.mediaItemCount }).isZero()
    assertThat(onMain { player.currentMediaItem }).isNull()
    assertThat(onMain { player.playerError }).isNull()
  }

  @Test
  fun theTrackLengthComesFromTheLibraryRatherThanFromTheRenderer() {
    // A renderer reports `TrackDuration` only once it has read the container, so a seek bar built
    // from the speaker's answer would have no length for the first second or two of every track --
    // a visible regression against local playback, which has the length from the library.
    val player = player()

    onMain { player.setMediaItems(items.toMutableList(), 0, 0L) }

    assertThat(onMain { player.duration }).isEqualTo(songs[0].durationSeconds * 1_000L)
  }

  @Test
  fun aTrackWhoseLengthThisAppDoesNotKnowReportsNoDurationRatherThanZero() {
    // Zero would make a seek bar full at every position and would make `CastPlayback`'s duration
    // clamp pin the reported position at 0 forever.
    val player = player()
    val unknown = MediaItem.Builder()
      .setMediaId("unknown-length")
      .setUri(RealTrackBytes.rawStreamUrl(songs[0]))
      .setMimeType("audio/mp4")
      .build()

    onMain { player.setMediaItems(mutableListOf(unknown), 0, 0L) }

    assertThat(onMain { player.duration }).isEqualTo(C.TIME_UNSET)
  }

  @Test
  fun aRendererThatRefusesTheFormatBecomesAPlaybackErrorAndNotSilence() {
    // The renderer says 714. Media3 has to see a `playerError` -- that is what a notification, a
    // `MediaController` and the picker all read -- rather than a player sitting in READY over
    // silence.
    fake.close()
    startRenderer(FakeRenderer.Strictness(rejectedMimeTypes = setOf(items[0].localConfiguration!!.mimeType!!)))
    val player = player()

    onMain { player.setMediaItems(items.toMutableList(), 0, 0L); player.prepare() }

    awaitCondition("Media3 to see a player error") { onMain { player.playerError } != null }
    val error = onMain { player.playerError }!!
    assertThat(error.errorCode).isEqualTo(PlaybackException.ERROR_CODE_REMOTE_ERROR)
    assertThat(error.message).contains("714")
    // The message reaches a snackbar. A renderer-direct URL is a Navidrome stream URL carrying the
    // user's `u`, `t` and `s`, so a failure message must never carry one.
    assertThat(error.message).doesNotContain("/rest/stream")
    assertThat(onMain { player.playbackState }).isEqualTo(Player.STATE_IDLE)
  }

  @Test
  fun aRendererThatDisappearsBecomesANetworkErrorRatherThanAFrozenPlayer() {
    // The other failure kind, mapped to a different Media3 code, because "the speaker refused this
    // track" and "the speaker is gone" are different things for a user to be told.
    val player = player()
    onMain { player.setMediaItems(items.toMutableList(), 0, 0L); player.prepare(); player.play() }
    awaitLoadSettled()

    fake.disappear()

    awaitCondition("Media3 to see a player error") { onMain { player.playerError } != null }
    assertThat(onMain { player.playerError }!!.errorCode)
      .isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
  }

  @Test
  fun releasingTheCastPlayerStopsTheSpeakerAndRevokesTheTokenItWasServing() {
    // A proxy still serving after the session that published it has ended is a capability lying on
    // the LAN with nobody watching it.
    val player = player()
    onMain { player.setMediaItems(items.toMutableList(), 0, 0L); player.prepare() }
    awaitLoadSettled()
    // The path the renderer was actually told to fetch, off its own `CurrentURI` argument -- so
    // this is the token the proxy really published and not one this test minted.
    val servedPath = URI(
      fake.soapRequests.last { it.action == "SetAVTransportURI" }.arguments!![1].second,
    ).path
    assertThat(registry.resolve(servedPath)).isNotNull()

    onMain { player.release() }
    this.player = null

    awaitCondition("the renderer to be stopped") { fake.currentTransportState() == "STOPPED" }
    awaitCondition("the published token to be revoked") { registry.resolve(servedPath) == null }
  }

  // ---- harness --------------------------------------------------------------------------------

  private fun player(): UpnpPlayer = onMain {
    UpnpPlayer(Looper.getMainLooper(), scope, System::currentTimeMillis) { onPlaybackChanged ->
      CastSession(
        device = device,
        renderer = UpnpRenderer(device, SoapClient(http), http),
        router = router,
        scope = scope,
        onPlaybackChanged = onPlaybackChanged,
      )
    }.also { player = it }
  }

  private fun startRenderer(strictness: FakeRenderer.Strictness) {
    fake = FakeRenderer(strictness).also { it.start() }
    device = CastDevice.from(
      DeviceDescription.parse(http.exchange(fake.descriptionUrl, "GET").bodyText(), fake.descriptionUrl),
      fake.descriptionUrl,
    )!!
  }

  /**
   * Waits until the renderer has finished being loaded, rather than until the call returned.
   *
   * `SimpleBasePlayer`'s handlers are asynchronous by contract: `setMediaItems` returns as soon as
   * the state is masked, and the `SetAVTransportURI` and `Seek` are issued afterwards. The
   * predicate is a **poll after the newest load** -- `CastSession.load` starts polling as its last
   * act -- so every request that load was going to make has already been made.
   */
  private fun awaitLoadSettled() {
    awaitCondition("the renderer to be polled after the newest load") {
      val actions = fake.soapRequests.mapNotNull { it.action }
      val loaded = actions.lastIndexOf("SetAVTransportURI")
      loaded >= 0 && actions.drop(loaded).contains("GetTransportInfo")
    }
  }

  private fun lastSeekTargetMs(): Long {
    val seek = fake.soapRequests.last { it.action == "Seek" }
    val target = seek.arguments!!.last()
    check(target.first == "Target") { "the last Seek argument is ${target.first}, not Target" }
    return checkNotNull(UpnpTime.parseClock(target.second)) { "unparseable Seek target ${target.second}" }
  }

  private fun lastSetUriMetadata(): String =
    fake.soapRequests.lastOrNull { it.action == "SetAVTransportURI" }?.arguments?.get(2)?.second.orEmpty()

  private fun <T> onMain(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private fun awaitCondition(what: String, timeoutMs: Long = AWAIT_MS, predicate: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate()) return
      Thread.sleep(POLL_MS)
    }
    throw AssertionError(
      "timed out after ${timeoutMs}ms waiting for $what; " +
        "playback=${player?.playback}, transport=${fake.currentTransportState()}, " +
        "soapActions=${fake.soapRequests.mapNotNull { it.action }}",
    )
  }

  private companion object {
    /** A start position with a whole number of seconds, because `RelTime` is second-resolution. */
    const val START_POSITION_MS = 12_000L
    const val DRAG_POSITION_MS = 5_000L
    const val SPEAKER_VOLUME = 55
    const val AWAIT_MS = 30_000L
    const val POLL_MS = 25L
  }
}
