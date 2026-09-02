package app.muplay.media.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.fake.FakeRenderer
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.OkHttpProxyUpstream
import app.muplay.cast.proxy.ProxyRegistry
import app.muplay.cast.route.CastRouter
import app.muplay.cast.session.CastSessionState
import app.muplay.cast.soap.SoapClient
import app.muplay.cast.soap.UpnpTime
import app.muplay.database.MuPlayDatabase
import app.muplay.database.SubsonicSourceProvider
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.media.ArtworkUrls
import app.muplay.media.MediaCache
import app.muplay.media.MediaItems
import app.muplay.media.MuPlayDataSourceFactory
import app.muplay.media.MuPlayer
import app.muplay.media.MuPlayerFactory
import app.muplay.media.NavidromeLoadErrorHandlingPolicy
import app.muplay.media.PlaybackOutputSwitch
import app.muplay.media.ProgressWriter
import app.muplay.media.RealTrackBytes
import app.muplay.media.fixedSubsonicSourceProvider
import app.muplay.media.ResumePolicy
import app.muplay.media.ResumeTarget
import app.muplay.media.di.MediaModule
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import java.io.File
import java.net.InetAddress
import java.time.Clock
import java.util.concurrent.Executor
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Casting mid-song lands on the same second, and a book's position is still written.**
 *
 * Everything here is real: a real `ExoPlayer` built by the one construction site this module
 * permits, a real `MuPlayer` seam over it, `:core:cast`'s **fake renderer** -- an actual UPnP
 * renderer over an actual socket, strict in the ways a Sonos is strict -- a real range-serving
 * proxy fetching real bytes from the seeded Navidrome container, a real in-memory Room, and a real
 * `MediaSession`. Nothing is substituted, because every question below is of the form *"what did
 * the speaker actually receive"* or *"what is actually in the row"*, and neither survives being
 * asked of a stand-in.
 *
 * ### The five-second trap, and why the headline assertion cannot be satisfied by waiting
 *
 * `CLAUDE.md`: three device tests in this project were green **against the very mutation they
 * existed to catch**, because an assertion that waits for a position is satisfied by playback
 * reaching that position on its own. The headline claim here -- *"casting mid-song lands on the
 * same second"* -- is a position claim, so it is arranged so that time cannot supply the answer:
 *
 *  1. **The position handed over is put there by a seek, not by waiting.** The local player plays
 *     about [PLAY_BEFORE_HANDOVER_MS] of real audio (which is what makes it a *mid-song* handover
 *     rather than a queue transfer), and is then seeked to [HANDOFF_MS]. Reaching [HANDOFF_MS] by
 *     elapsed playback would take [HANDOFF_MS] of playing, and every test here asserts how much
 *     playing actually happened.
 *  2. **The assertion is on bytes the renderer received**, not on a player field. `Seek`'s
 *     `Target` argument is read back off the fake's own recorded SOAP request. A handover that
 *     discarded the position sends `Seek` to `00:00:00` or sends none at all, and no amount of
 *     waiting turns one recorded value into another.
 *  3. **The tolerance band excludes zero by a wide margin.** ±[SEEK_TOLERANCE_MS] around a
 *     12-second handover cannot contain 0.
 *
 * Where a wait is unavoidable it waits for an **event** (a SOAP request arriving, a session state
 * being reported), never for a value; the value is then asserted on what arrived.
 */
@RunWith(AndroidJUnit4::class)
class HandoverTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var db: MuPlayDatabase
  private lateinit var dao: MediaProgressDao

  private lateinit var registry: ProxyRegistry
  private lateinit var proxy: MediaProxyServer
  private lateinit var router: CastRouter
  private lateinit var http: CastHttpClient
  private lateinit var castScope: CoroutineScope
  private lateinit var writerScope: CoroutineScope

  private lateinit var fake: FakeRenderer
  private lateinit var device: CastDevice

  private lateinit var switch: PlaybackOutputSwitch
  private lateinit var oneShot: OneShotResumePolicy
  private lateinit var boundResumePolicy: ResumePolicy
  private lateinit var localPlayer: MuPlayer
  private lateinit var writer: ProgressWriter
  private lateinit var manager: CastSessionManager

  private lateinit var songs: List<Song>
  private lateinit var items: List<MediaItem>

  private var session: MediaSession? = null

  /**
   * A real credential store behind a real `SubsonicClient`, so `CastSessionManager` is built the
   * way the app builds it.
   *
   * These fixtures carry no cover art, so nothing resolves through it here -- and wiring it anyway
   * rather than stubbing it is the point: a cast that could not construct its artwork resolver
   * would fail in this suite instead of on a phone. The store's file is deleted in `tearDown`,
   * because DataStore refuses a second instance over one path in a process.
   */
  private lateinit var artworkStoreFile: File

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "handover-${System.nanoTime()}")
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    dao = db.mediaProgressDao()

    // The two longest seeded book files. Books rather than the five-second music fixtures because a
    // handover at twelve seconds has to be *inside* the track, and `check`ed rather than assumed --
    // the corpus is shared and a lane that changes it must break this loudly rather than quietly
    // measure something else.
    songs = runBlocking { RealTrackBytes.audiobookFiles() }.take(2)
    check(songs.size == 2) { "expected at least two seeded audiobook files, found ${songs.size}" }
    songs.forEach { song ->
      check(song.durationSeconds * 1_000L > HANDOFF_MS + SEEK_TOLERANCE_MS) {
        "seeded book '${song.title}' is ${song.durationSeconds}s, too short to hold a handover at " +
          "${HANDOFF_MS}ms"
      }
    }
    items = songs.map {
      MediaItems.of(it, RealTrackBytes.rawStreamUrl(it), artworkId = null, isAudiobook = true, format = StreamFormat.Raw)
    }

    registry = ProxyRegistry()
    // Bound to `127.0.0.1` explicitly, not to `InetAddress.getLoopbackAddress()`: on Android that
    // answers the **IPv6** loopback, and `LocalAddress.towards` resolves the fake renderer's own
    // `127.0.0.1` to an IPv4 address -- so the URL the renderer would be handed names a port nothing
    // is listening on. Same trap as the fake's own bind address, measured the same afternoon.
    proxy = MediaProxyServer(OkHttpProxyUpstream(OkHttpClient()), registry, InetAddress.getByName("127.0.0.1"))
    proxy.start()
    router = CastRouter(proxy, registry, allowRendererDirect = { false })
    http = CastHttpClient()
    castScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    writerScope = CoroutineScope(
      SupervisorJob() +
        Executor { command -> Handler(Looper.getMainLooper()).post(command) }.asCoroutineDispatcher(),
    )

    startRenderer(FakeRenderer.Strictness())
    buildPlaybackStack()
  }

  @After
  fun tearDown() {
    if (::manager.isInitialized) runCatching { onMain { session?.release() } }
    if (::writer.isInitialized) runCatching { onMain { writer.stop() } }
    if (::localPlayer.isInitialized) runCatching { onMain { localPlayer.release() } }
    if (::manager.isInitialized) runCatching { onMain { manager.castPlayer?.release() } }
    if (::castScope.isInitialized) castScope.cancel()
    if (::writerScope.isInitialized) writerScope.cancel()
    if (::fake.isInitialized) runCatching { fake.close() }
    if (::proxy.isInitialized) runCatching { proxy.close() }
    if (::db.isInitialized) db.close()
    if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
    if (::artworkStoreFile.isInitialized) artworkStoreFile.delete()
  }

  private fun artworkSourceProvider(): SubsonicSourceProvider {
    val (provider, file) = fixedSubsonicSourceProvider(
      context,
      SubsonicClient(
        SubsonicCredentials(baseUrl = "http://localhost:4533", username = "admin", password = "testpass"),
      ),
    )
    artworkStoreFile = file
    return provider
  }

  // ---- the headline: the position transfers ---------------------------------------------------

  @Test
  fun castingMidSongLandsOnTheSameSecondOnTheSpeaker() {
    // Not "the queue transferred" -- the *position* transferred, which is the thing `MuPlayer`'s
    // seam would otherwise discard and the reason this whole task exists.
    val played = playLocallyThenSeekTo(startIndex = 1, positionMs = HANDOFF_MS)
    val handoffFrom = onMain { localPlayer.currentPosition }
    assertThat(handoffFrom).isBetween(HANDOFF_MS - 500L, HANDOFF_MS + 500L)

    castNow()

    val target = lastSeekTargetMs()
    assertThat(target).isBetween(handoffFrom - SEEK_TOLERANCE_MS, handoffFrom + SEEK_TOLERANCE_MS)
    // The band cannot contain zero, and the local player never played anywhere near this far: the
    // fixture's length is not what makes this pass.
    assertThat(target).isGreaterThan(SEEK_TOLERANCE_MS)
    assertThat(played).isLessThan(HANDOFF_MS - SEEK_TOLERANCE_MS)
    // ...and the same track, identified the way the handover identifies it -- by media id, off the
    // DIDL document the renderer actually received.
    assertThat(lastSetUriMetadata()).contains(songs[1].id)
  }

  @Test
  fun aSecondHandoverCarriesADifferentPosition() {
    // One observation of "the position transferred" is satisfied by a constant seek target. Two
    // handovers at two positions, asserted to differ by more than the whole tolerance band.
    playLocallyThenSeekTo(startIndex = 0, positionMs = EARLY_HANDOFF_MS)
    castNow()
    awaitLoadSettled()
    val first = lastSeekTargetMs()

    stopCastingNow()
    val seekCountBefore = seekCount()
    onMain { localPlayer.seekTo(HANDOFF_MS) }
    castNow()
    awaitCondition("a second Seek to reach the renderer") { seekCount() > seekCountBefore }
    val second = lastSeekTargetMs()

    assertThat(first).isBetween(EARLY_HANDOFF_MS - SEEK_TOLERANCE_MS, EARLY_HANDOFF_MS + SEEK_TOLERANCE_MS)
    assertThat(second).isGreaterThan(first + 2 * SEEK_TOLERANCE_MS)
  }

  // ---- the row --------------------------------------------------------------------------------

  @Test
  fun theHandoverWritesTheOutgoingPositionToTheProgressRow() {
    // The durable half of the write-then-arm ordering. If the write happened, the ordinary resume
    // path finds the position even if nothing else about the handover worked. (That the write comes
    // *before* the arming is a crash-window property no in-process test can observe; it is stated
    // in `CastSessionManager`'s KDoc and this is the half that is observable.)
    playLocallyThenSeekTo(startIndex = 0, positionMs = HANDOFF_MS)

    castNow()

    val row = runBlocking { dao.find(songs[0].id) }
    assertThat(row).isNotNull
    assertThat(row!!.positionMs).isBetween(HANDOFF_MS - SEEK_TOLERANCE_MS, HANDOFF_MS + SEEK_TOLERANCE_MS)
  }

  @Test
  fun theHandoverPreservesTheColumnsItDoesNotOwn() {
    // **The audiobook-plan interaction.** `speed`, `skipSilence` and `gainDb` belong to that plan. A
    // handover that constructed a fresh entity would reset a listener's per-book speed every time
    // they cast -- silently, and only for the feature this project exists to get right.
    runBlocking {
      dao.upsert(
        MediaProgressEntity(
          mediaId = songs[0].id,
          positionMs = 0L,
          isFinished = false,
          lastPlayedAtEpochMs = 1L,
          speed = 1.4f,
          skipSilence = true,
          gainDb = 3.5f,
        ),
      )
    }
    playLocallyThenSeekTo(startIndex = 0, positionMs = HANDOFF_MS)

    castNow()

    val row = runBlocking { dao.find(songs[0].id) }!!
    assertThat(row.speed).isEqualTo(1.4f)
    assertThat(row.skipSilence).isTrue()
    assertThat(row.gainDb).isEqualTo(3.5f)
    assertThat(row.positionMs).isGreaterThanOrEqualTo(HANDOFF_MS - SEEK_TOLERANCE_MS)
  }

  @Test
  fun theProgressWriterFollowsTheSwitchAndKeepsWritingWhileCast() {
    // One writer, two players. Left on the paused local player, a book cast to a speaker records
    // nothing at all -- and nobody finds out until they lose their place.
    //
    // The discriminator is that the **speaker's** position is ahead of the phone's: the renderer is
    // advanced past where the handover left it, so a row carrying the local player's number is a
    // different number from a row carrying the remote's.
    playLocallyThenSeekTo(startIndex = 0, positionMs = EARLY_HANDOFF_MS)
    castNow()
    assertThat(switch.current()).isNotSameAs(localPlayer)
    awaitLoadSettled()
    fake.advance(REMOTE_ADVANCE_MS)
    awaitCondition("the poll to report the renderer's own position") {
      (manager.castPlayer?.playback?.positionMs ?: 0L) >= EARLY_HANDOFF_MS + REMOTE_ADVANCE_MS - 1_500L
    }

    onMain { writer.flushBlocking() }

    val row = runBlocking { dao.find(songs[0].id) }!!
    assertThat(row.positionMs).isGreaterThan(EARLY_HANDOFF_MS + REMOTE_ADVANCE_MS - 2_000L)
    // The local player is still parked where the handover left it, so this number could not have
    // come from it.
    assertThat(onMain { localPlayer.currentPosition }).isLessThan(EARLY_HANDOFF_MS + 2_000L)
  }

  // ---- the one-shot target --------------------------------------------------------------------

  @Test
  fun theOneShotTargetIsSpentAndDoesNotLeakIntoTheNextQueue() {
    // Casting, then choosing a different album, must start that album at zero -- not twelve seconds
    // in. Read off the renderer: `CastSession` issues `Seek` only for a non-zero start, so a leaked
    // target is a Seek that should not exist.
    playLocallyThenSeekTo(startIndex = 0, positionMs = HANDOFF_MS)
    castNow()
    awaitLoadSettled()
    val seeksAfterHandover = seekCount()
    assertThat(seeksAfterHandover).isEqualTo(1)

    val other = mutableListOf(items[1])
    onMain { switch.current()!!.setMediaItems(other, 0, 0L) }
    awaitCondition("the new queue to reach the renderer") { lastSetUriMetadata().contains(songs[1].id) }
    // The second load has to be **finished** before its absence of a `Seek` means anything.
    awaitLoadSettled()

    assertThat(seekCount()).isEqualTo(seeksAfterHandover)
  }

  @Test
  fun theLocalPlayerConsultsTheSamePolicyInstanceTheSessionManagerArms() {
    // The wiring assertion, stated on the object graph rather than on behaviour, because the
    // behavioural symptom of getting it wrong -- the return leg restarting from zero -- reads as a
    // resume bug rather than as a binding bug and would be chased in the wrong file.
    oneShot.armFor(songs[0].id, ResumeTarget(startIndex = 0, startPositionMs = 42_000L))

    val resolved = boundResumePolicy.resolve(songs.map { it.id }, requestedIndex = 0)

    assertThat(resolved.startPositionMs).isEqualTo(42_000L)
    // Same object, not merely same behaviour: two decorators over one delegate answer this once and
    // then diverge on the arming that matters.
    assertThat(boundResumePolicy).isSameAs(oneShot)
  }

  // ---- the way back ---------------------------------------------------------------------------

  @Test
  fun comingBackFromCastLandsOnTheSameSecondLocally() {
    // The direction a user hits when they leave the house.
    val played = playLocallyThenSeekTo(startIndex = 0, positionMs = EARLY_HANDOFF_MS)
    castNow()
    assertThat(switch.current()).isNotSameAs(localPlayer)
    awaitLoadSettled()
    fake.advance(REMOTE_ADVANCE_MS)
    val expected = EARLY_HANDOFF_MS + REMOTE_ADVANCE_MS
    awaitCondition("the renderer's own position to be polled") {
      (manager.castPlayer?.playback?.positionMs ?: 0L) >= expected - 1_500L
    }

    stopCastingNow()

    assertThat(switch.current()).isSameAs(localPlayer)
    // Read with **no wait at all**: `setMediaItems` is masked synchronously, so this is the number
    // the handover actually handed the local player. The local player itself only ever played
    // `played` milliseconds, which is nowhere near this.
    val landed = onMain { localPlayer.currentPosition }
    assertThat(landed).isBetween(expected - 2_500L, expected + 2_500L)
    assertThat(played).isLessThan(expected - 5_000L)
    // ...and the speaker was told to stop, rather than left playing into an empty room.
    awaitCondition("the renderer to be stopped") { fake.currentTransportState() == "STOPPED" }
  }

  @Test
  fun aSpeakerThatDisappearsHandsPlaybackBackAtTheRightSecond() {
    // Spec section 6: "Playback stopping when the phone leaves the network is intended behaviour."
    // Losing the listener's place is not.
    playLocallyThenSeekTo(startIndex = 0, positionMs = EARLY_HANDOFF_MS)
    castNow()
    assertThat(switch.current()).isNotSameAs(localPlayer)
    awaitLoadSettled()
    fake.advance(REMOTE_ADVANCE_MS)
    val expected = EARLY_HANDOFF_MS + REMOTE_ADVANCE_MS
    awaitCondition("the renderer's own position to be polled") {
      (manager.castPlayer?.playback?.positionMs ?: 0L) >= expected - 1_500L
    }

    fake.disappear()

    awaitCondition("the session to report Lost") { manager.state.value is CastSessionState.Lost }
    awaitCondition("playback to come back to the phone") { switch.current() === localPlayer }
    assertThat(onMain { localPlayer.currentPosition }).isBetween(expected - 2_500L, expected + 2_500L)
    // Paused, not playing: a speaker vanishing must not start audio out of the phone's own
    // loudspeaker in somebody's pocket.
    assertThat(onMain { localPlayer.isPlaying }).isFalse()
    // The diagnosis survives the tidy-up that follows it. Releasing the session reports `Idle`, and
    // an `Idle` that overwrote this would leave the user with a picker saying nothing happened.
    assertThat(manager.state.value).isInstanceOf(CastSessionState.Lost::class.java)
  }

  @Test
  fun theLocalPlayerIsPausedRatherThanReleasedWhileCasting() {
    playLocallyThenSeekTo(startIndex = 0, positionMs = EARLY_HANDOFF_MS)

    castNow()

    assertThat(onMain { localPlayer.isPlaying }).isFalse()
    // Still usable. `playbackState` on a released `ExoPlayer` is `STATE_IDLE`, and the handover-back
    // tests above would then fail obscurely somewhere else rather than here.
    assertThat(onMain { localPlayer.playbackState }).isNotEqualTo(Player.STATE_IDLE)
  }

  // ---- the session, the renderer that says no -------------------------------------------------

  @Test
  fun theMediaSessionAcceptsTheCastPlayerAndReportsItsTrack() {
    // "The notification, the media buttons and every `MediaController` come along for free" is only
    // true if `MediaSession.setPlayer` accepts this player at all -- it requires the incoming
    // player to share the session's application looper, and a `SimpleBasePlayer` built on the wrong
    // one throws. "For free" and "quietly broken" look identical without this.
    playLocallyThenSeekTo(startIndex = 1, positionMs = HANDOFF_MS)
    onMain { session = MediaSession.Builder(context, localPlayer).setId("handover-test").build() }

    castNow()

    val active = switch.current()!!
    onMain { session!!.player = active }
    assertThat(onMain { session!!.player }).isSameAs(active)
    assertThat(onMain { session!!.player.currentMediaItem?.mediaId }).isEqualTo(songs[1].id)
    assertThat(onMain { session!!.player.mediaMetadata.title.toString() }).isEqualTo(songs[1].title)
  }

  @Test
  fun aRendererThatRefusesTheFormatFailsTheSessionInsteadOfGoingQuiet() {
    // The renderer says 714. The session has to end **visibly** and playback has to come back to
    // the phone, rather than leaving a UI that says "Playing on Fake Speaker" over silence.
    restartRendererRejecting(items[0].localConfiguration!!.mimeType!!)
    playLocallyThenSeekTo(startIndex = 0, positionMs = EARLY_HANDOFF_MS)

    castNow()

    awaitCondition("the session to fail") { manager.state.value is CastSessionState.Failed }
    assertThat((manager.state.value as CastSessionState.Failed).reason).contains("714")
    awaitCondition("playback to come back to the phone") { switch.current() === localPlayer }
    // The failure message is user-facing, so it must not carry the credential-bearing upstream URL.
    assertThat((manager.state.value as CastSessionState.Failed).reason).doesNotContain("/rest/stream")
  }

  // ---- harness --------------------------------------------------------------------------------

  /**
   * The playback stack, wired the way `MuPlaybackService` wires it and out of the **production
   * providers**.
   *
   * `MediaModule`'s own three `ResumePolicy` providers are called here rather than the constructors
   * they wrap, so that `theLocalPlayerConsultsTheSamePolicyInstanceTheSessionManagerArms` is a
   * statement about the shipped graph's shape and not about this file's. What is taken on trust is
   * `@Singleton`, which is Dagger's to honour and which `MediaModuleTest` asserts is present.
   */
  private fun buildPlaybackStack() {
    switch = PlaybackOutputSwitch()
    // An **empty** item source, which is what Plan 4 Task 6's policy answers for a queue of music
    // -- and this suite's queue is music. The alternative, a real `AudiobookSnapshot`, would need
    // a Room database this suite has no other use for, and would make every position assertion
    // below depend on a seed rather than on the handover.
    oneShot = MediaModule.provideOneShotResumePolicy(
      MediaModule.provideUndecoratedResumePolicy({ null }, Clock.systemUTC()),
    )
    boundResumePolicy = MediaModule.provideResumePolicy(oneShot)
    onMain {
      localPlayer = MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), MediaCache.create(context, cacheDir)),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        resumePolicy = boundResumePolicy,
      ).create()
      writer = ProgressWriter(localPlayer, dao, Clock.systemUTC(), writerScope)
      manager = CastSessionManager(
        switch = switch,
        oneShot = oneShot,
        router = Provider { router },
        soap = SoapClient(http),
        http = http,
        clock = Clock.systemUTC(),
        // A real `ArtworkUrls` over the real credential store, wired the way the app wires it.
        // These fixtures carry no cover art, so it resolves to nothing here -- which is the point
        // of wiring it rather than stubbing it: this suite proves the handover, and a cast that
        // could not even construct its artwork resolver would fail here rather than on a phone.
        artworkUrls = ArtworkUrls(artworkSourceProvider()),
        scope = castScope,
      )
      manager.useProgressWriter(writer)
      switch.installLocal(localPlayer)
    }
  }

  private fun startRenderer(strictness: FakeRenderer.Strictness) {
    fake = FakeRenderer(strictness).also { it.start() }
    device = CastDevice.from(
      DeviceDescription.parse(http.exchange(fake.descriptionUrl, "GET").bodyText(), fake.descriptionUrl),
      fake.descriptionUrl,
    )!!
  }

  private fun restartRendererRejecting(mimeType: String) {
    fake.close()
    startRenderer(FakeRenderer.Strictness(rejectedMimeTypes = setOf(mimeType)))
  }

  /**
   * Plays real audio, then seeks to [positionMs], and returns how far playback itself actually got.
   *
   * Both halves matter. The **playing** is what makes this a mid-song handover rather than a queue
   * transfer -- a position that has genuinely moved past a second of media is the only thing that
   * distinguishes "playing" from "was asked to play". The **seek** is what puts the position
   * somewhere elapsed time could not have carried it, and the returned reading is what lets each
   * test assert that the fixture's length is not doing the work.
   *
   * `MuPlayer` discards the caller's position on `setMediaItems` -- that is the seam -- but not on
   * `seekTo`, which is a deliberate move by a user and not a queue-load guess.
   */
  private fun playLocallyThenSeekTo(startIndex: Int, positionMs: Long): Long {
    onMain {
      localPlayer.setMediaItems(items.toMutableList(), startIndex, 0L)
      localPlayer.prepare()
      localPlayer.play()
    }
    awaitCondition("real audio to come out of the local player") {
      onMain { localPlayer.isPlaying && localPlayer.currentPosition > PLAY_BEFORE_HANDOVER_MS }
    }
    val played = onMain { localPlayer.currentPosition }
    onMain {
      localPlayer.pause()
      localPlayer.seekTo(positionMs)
    }
    return played
  }

  /**
   * `castTo` on the player's application thread, which is where it must run: it reads and writes
   * `Player`s, and a `Player` may only be touched from the thread it was built on.
   *
   * `runBlocking` on the main looper is safe here for the same reason `ProgressWriter.flushBlocking`
   * is: everything the handover awaits hops to another dispatcher and completes there.
   */
  private fun castNow() = onMain { runBlocking { manager.castTo(device) } }

  private fun stopCastingNow() = onMain { runBlocking { manager.stopCasting() } }

  private fun seekCount(): Int = fake.soapRequests.count { it.action == "Seek" }

  private fun soapActions(): List<String> = fake.soapRequests.mapNotNull { it.action }

  /**
   * Waits until the renderer has finished being loaded, rather than until `castTo` has returned.
   *
   * `castTo` returns as soon as the incoming player has been *told* what to play; the
   * `SetAVTransportURI` and the `Seek` are issued from the cast scope afterwards. Three tests were
   * red before this existed and every one of them was the same race, in two shapes: `fake.advance`
   * landed before the handover's own `Seek` and was overwritten by it, and a `Seek` count taken as
   * a baseline was taken before the `Seek` it was meant to exclude.
   *
   * The predicate is a **poll after the load**: `CastSession.load` starts polling as its last act,
   * so a `GetTransportInfo` recorded after the newest `SetAVTransportURI` proves every request that
   * load was going to make has already been made.
   */
  private fun awaitLoadSettled() {
    awaitCondition("the renderer to be polled after the newest load") {
      val actions = soapActions()
      val loaded = actions.lastIndexOf("SetAVTransportURI")
      loaded >= 0 && actions.drop(loaded).contains("GetTransportInfo")
    }
  }

  /** The `Target` of the last `Seek` the renderer received, in milliseconds, off its own bytes. */
  private fun lastSeekTargetMs(): Long {
    awaitCondition("a Seek to reach the renderer") { seekCount() > 0 }
    val seek = fake.soapRequests.last { it.action == "Seek" }
    val target = seek.arguments!!.last()
    check(target.first == "Target") { "the last Seek argument is ${target.first}, not Target" }
    return checkNotNull(UpnpTime.parseClock(target.second)) { "unparseable Seek target ${target.second}" }
  }

  /** The DIDL document of the last `SetAVTransportURI`, as the renderer decoded it. */
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

  /** A bounded wait that fails loudly with what it did see, rather than a sleep that hopes. */
  private fun awaitCondition(what: String, timeoutMs: Long = AWAIT_MS, predicate: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate()) return
      Thread.sleep(POLL_MS)
    }
    throw AssertionError(
      "timed out after ${timeoutMs}ms waiting for $what; castState=${manager.state.value}, " +
        "playback=${manager.castPlayer?.playback}, " +
        "soapActions=${fake.soapRequests.mapNotNull { it.action }}",
    )
  }

  private companion object {
    /**
     * Where the handover happens. Inside every seeded book file and **on no boundary**, and far
     * enough from zero that a ±[SEEK_TOLERANCE_MS] band around it cannot contain zero.
     */
    const val HANDOFF_MS = 12_000L

    /** A second, different handover position, for the tests that need two. */
    const val EARLY_HANDOFF_MS = 4_000L

    /**
     * How far the local player really plays before being seeked.
     *
     * Small on purpose: it is what makes the handover a *mid-song* one, and keeping it far below
     * [HANDOFF_MS] is what lets every test assert that elapsed playback could not have produced the
     * position that transferred.
     */
    const val PLAY_BEFORE_HANDOVER_MS = 700L

    /** How far the speaker's own clock is moved on, for the tests about coming back. */
    const val REMOTE_ADVANCE_MS = 9_000L

    /**
     * A renderer reports `RelTime` to the **second**, and the poll runs at 1 Hz, so a position that
     * round-trips through one is good to about a second. Two is the honest band; it is also
     * comfortably narrower than either handover position, which is what keeps zero outside it.
     */
    const val SEEK_TOLERANCE_MS = 2_000L

    const val AWAIT_MS = 30_000L
    const val POLL_MS = 25L
  }
}
