package app.muplay.cast.session

import app.muplay.cast.control.RendererFollowsAnotherException
import app.muplay.cast.control.TransportState
import app.muplay.cast.control.UpnpRenderer
import app.muplay.cast.didl.CastItem
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.route.CastRoute
import app.muplay.cast.route.CastRouter
import app.muplay.cast.soap.SoapTransportException
import app.muplay.cast.soap.UpnpErrorException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * **A UPnP renderer, driven as a playback session -- including the part where it stops answering.**
 *
 * Plan 6's design is that a cast output *is* a `Player`, so nothing Plan 3 built above the player
 * changes: `MuPlayer` is a `ForwardingPlayer`, `ProgressWriter` takes a `Player`, `MediaSession`
 * holds one. This class is the half of that which has no Media3 in it -- the queue, the transport
 * commands, the poll, and every decision the poll makes -- and it lives in `:core:cast` because
 * `:core:cast` is a pure-JVM module and therefore the only place those decisions can be gated
 * against a **real renderer over a real socket** without an emulator.
 *
 * Task 9 wraps it in a `SimpleBasePlayer`: [playback] is the snapshot `getState()` reports,
 * [onPlaybackChanged] is `invalidateState()`, and the `handle*` methods dispatch to the suspending
 * commands below. Nothing in this class knows that.
 *
 * ### Why polling, and not GENA eventing
 *
 * `SUBSCRIBE`/`NOTIFY` is the "proper" way to learn a renderer's state, and it is not used here for
 * one decisive reason: **the `AVTransport:1` `LastChange` event does not carry playback position.**
 * `RelTime` and `AbsTime` are excluded from the evented state variable set. So an eventing
 * implementation would still have to poll `GetPositionInfo` for the seek bar, and would
 * *additionally* owe an HTTP callback endpoint, subscription renewal timers at ~85% of a granted
 * timeout, and a parser for XML-escaped XML nested inside an XML document. It buys a faster
 * `PLAYING`/`STOPPED` transition and nothing else.
 *
 * ### The renderer that disappears mid-stream
 *
 * A speaker loses power, or the listener walks out of range. Spec section 6 is explicit that
 * *"playback stopping when the phone leaves the network is intended behaviour"* -- but *stopping*
 * and *appearing to play forever* are different things and only one of them is intended. Without
 * the branch below, the poll's exception is swallowed, [CastPlayback.positionAdvancing] stays true,
 * and the seek bar runs to the end of a track nobody can hear.
 *
 * Detection is [LOST_AFTER_FAILURES] **consecutive** [SoapTransportException]s -- the speaker not
 * answering -- and specifically **not** [UpnpErrorException], which is the speaker answering and
 * saying no. Task 5 keeps those two apart precisely so this branch can; collapsing them would let a
 * single `714` tear down a session. Three and not one, because one missed poll on a busy Wi-Fi
 * network is ordinary and three seconds of silence is not.
 *
 * The response is [CastSessionState.Lost], carrying the **last position the renderer reported**,
 * which is what Task 9 resumes locally from.
 *
 * ### Speed is not here, and that is the answer
 *
 * `AVTransport::Play` takes `Speed`, and `TransportPlaySpeed`'s allowed value list is `{"1"}` on
 * every renderer this plan targets -- Sonos answers `717` to anything else, which
 * [UpnpRenderer.PLAY_SPEED] records. A book's per-item playback speed therefore **cannot** be
 * delivered to a speaker, so this class offers no way to ask for one. Task 9 reports
 * `PlaybackParameters(1.0f)` and withholds `COMMAND_SET_SPEED_AND_PITCH`, which is what makes the
 * limit visible to the UI rather than a setting that silently does nothing.
 *
 * @param nowMs this session's clock, in milliseconds. A seam because [CastPlayback.positionAtMs]
 *   extrapolates against it, and because a test must be able to ask what the position *would* read
 *   at a given instant without waiting for that instant.
 * @param pollIntervalMs how often the renderer is asked where it is. Defaults to
 *   [POLL_INTERVAL_MS], which is the shipping value; tests shorten it so a suite that exercises
 *   several poll cycles per case finishes in seconds rather than minutes.
 * @param onPlaybackChanged called after every change to [playback], on whichever coroutine made the
 *   change. Task 9 passes `::invalidateState`. Media3 derives `onIsPlayingChanged`,
 *   `onMediaItemTransition`, `onPositionDiscontinuity` and `onPlaybackStateChanged` from the diff
 *   between the snapshots it then reads, so a change that does not call this is a callback that
 *   never fires -- and a book cast to a speaker whose position is never written down.
 * @param onSessionStateChanged called when, and only when, [sessionState] differs from the last one.
 */
class CastSession(
  private val device: CastDevice,
  private val renderer: UpnpRenderer,
  private val router: CastRouter,
  private val scope: CoroutineScope,
  private val nowMs: () -> Long = System::currentTimeMillis,
  private val pollIntervalMs: Long = POLL_INTERVAL_MS,
  private val onPlaybackChanged: () -> Unit = {},
  private val onSessionStateChanged: (CastSessionState) -> Unit = {},
) {

  val deviceName: String get() = device.friendlyName

  /** The current snapshot. Replaced wholesale; never mutated. */
  @Volatile
  var playback: CastPlayback = CastPlayback.IDLE
    private set

  @Volatile
  var sessionState: CastSessionState = CastSessionState.Idle
    private set

  /**
   * One lock over every state transition, and it is not optional.
   *
   * The poll runs on [scope] while commands arrive from the player's own thread. Without it a
   * `seekTo` and the poll that follows it interleave, and the seek's optimistic position is
   * overwritten by a `GetPositionInfo` that was already in flight before the `Seek` was sent --
   * the seek bar snapping back, which is a bug that only appears under real timing.
   */
  private val mutex = Mutex()

  private var queue: List<CastSource> = emptyList()
  private var index = 0
  private var playWhenReady = false
  private var transport = TransportState.NO_MEDIA
  private var ended = false
  private var positionMs = 0L
  private var positionMeasuredAtMs = 0L
  private var durationMs = 0L
  private var volumePercent = DEFAULT_VOLUME_PERCENT
  private var canSeek = false
  private var failure: CastFailure? = null
  private var transportFailures = 0
  private var pollJob: Job? = null
  private var released = false

  /**
   * An item the renderer has been given but has not yet been asked to play.
   *
   * `CastRouter.confirm` proves a route by waiting for the renderer to **fetch**, and a renderer
   * fetches after `Play` and not after `SetAVTransportURI`. So a queue that is set while paused --
   * which is exactly what `SimpleBasePlayer` does, `handleSetMediaItems` before
   * `handleSetPlayWhenReady` -- has nothing to prove yet, and proving it there would sit out the
   * full proof timeout and then declare a perfectly good speaker unroutable. The proof is deferred
   * to the first `Play` instead, which is the first moment the answer exists.
   */
  private var unprovedRoute: LoadedItem? = null

  /** What [proveRoute] needs to re-issue an item on the renderer-direct fallback. */
  private data class LoadedItem(val route: CastRoute, val item: CastItem, val upstreamUrl: String)

  // ---- commands ------------------------------------------------------------------------------

  /**
   * Whether a transport command should do nothing at all.
   *
   * A session that has already failed refuses everything except [setQueue] and [release], and that
   * is not tidiness -- it is what stops **the consequences of a failure replacing its diagnosis**.
   * Measured while writing `a grouped sonos is reported rather than silently accepted`: the
   * `SetAVTransportURI` correctly failed with *"this speaker is grouped with another and is
   * following ..."*, the `play()` that followed then failed with `701 Transition not available`
   * (of course it did -- nothing had been loaded), and the second message overwrote the first. What
   * a user would have been shown is the symptom of the symptom.
   *
   * It is also what a Media3 `Player` does: after a `playerError` the player is in `STATE_IDLE` and
   * `prepare()` is the only way out. [setQueue] is this session's `prepare()`.
   */
  private fun refusing(): Boolean = released || failure != null


  /**
   * Replaces the queue and loads [startIndex] at [startPositionMs].
   *
   * The route is decided **per item, when that item is loaded**, and not for the whole queue here.
   * `CastRouter.candidate` publishes a capability token on this phone's LAN-facing proxy for every
   * item it routes; minting one for a fifty-track album at the moment the user pressed play would
   * leave forty-nine tokens fetchable by anything on the network for the length of the album. One
   * live token at a time is the same reachability proof and a much smaller surface.
   */
  suspend fun setQueue(sources: List<CastSource>, startIndex: Int = 0, startPositionMs: Long = 0L) {
    mutex.withLock {
      if (released) return
      queue = sources.toList()
      index = startIndex.coerceIn(0, maxOf(0, queue.size - 1))
      failure = null
      ended = false
      transportFailures = 0
      durationMs = 0L
      positionMs = startPositionMs.coerceAtLeast(0L)
      positionMeasuredAtMs = nowMs()
      transport = TransportState.TRANSITIONING
      publish()
      setSessionState(CastSessionState.Connecting(deviceName))
      guard { load(seekToMs = positionMs) }
    }
  }

  suspend fun play() = setPlayWhenReady(true)

  suspend fun pause() = setPlayWhenReady(false)

  /**
   * Plays or pauses.
   *
   * **Asymmetric on purpose.** Pausing freezes the reported clock *immediately*, by moving the
   * transport to `PAUSED` before the renderer has confirmed it; playing does not move it to
   * `PLAYING`, and leaves the clock still until a poll hears the renderer say so. Freezing early
   * can only under-report a position by up to one poll; advancing early invents playback that may
   * never have started, which is the failure this whole class is written against.
   */
  suspend fun setPlayWhenReady(value: Boolean) {
    mutex.withLock {
      if (refusing()) return
      playWhenReady = value
      if (!value) transport = TransportState.PAUSED
      publish()
      guard {
        if (value) {
          renderer.play()
          // The first Play is the first moment a route can be proved. See [unprovedRoute].
          unprovedRoute?.let { proveRoute(it) }
        } else {
          renderer.pause()
        }
      }
    }
  }

  /**
   * Seeks within the current item, or to another item.
   *
   * A move to a different track re-issues `SetAVTransportURI` rather than sending `Seek`: a
   * renderer's transport knows about one URI at a time, and the new track needs its own route and
   * its own DIDL anyway.
   */
  suspend fun seekTo(mediaItemIndex: Int, positionMs: Long) {
    mutex.withLock {
      if (refusing()) return
      val target = positionMs.coerceAtLeast(0L)
      val destination = mediaItemIndex.coerceIn(0, maxOf(0, queue.size - 1))
      val movedTrack = destination != index
      index = destination
      this.positionMs = target
      positionMeasuredAtMs = nowMs()
      ended = false
      publish()
      guard {
        if (movedTrack) {
          durationMs = 0L
          load(seekToMs = target)
        } else {
          // The `false` return -- no time seek mode, or a target the device calls illegal -- needs
          // no handling here: the next poll reports where the renderer really is and the optimistic
          // position above is corrected. Throwing would take a session down over a dragged bar.
          renderer.seek(target)
        }
      }
    }
  }

  suspend fun stop() {
    mutex.withLock {
      if (refusing()) return
      playWhenReady = false
      transport = TransportState.STOPPED
      publish()
      guard { renderer.stop() }
    }
  }

  suspend fun setVolumePercent(percent: Int) {
    mutex.withLock {
      if (refusing()) return
      volumePercent = percent.coerceIn(UpnpRenderer.MIN_VOLUME, UpnpRenderer.MAX_VOLUME)
      publish()
      guard { renderer.setVolume(volumePercent) }
    }
  }

  /**
   * Ends the session: stop polling, stop the speaker, revoke every published token.
   *
   * The renderer is stopped on a best-effort basis and its failure is **not** allowed to skip
   * [CastRouter.revokeAll]. The commonest reason a session is released is that the speaker has gone
   * away, and a proxy still serving after the session that published it has ended is a capability
   * lying on the LAN with nobody watching it.
   */
  suspend fun release() {
    mutex.withLock {
      if (released) return
      released = true
      unprovedRoute = null
      pollJob?.cancel()
      pollJob = null
      playWhenReady = false
      transport = TransportState.NO_MEDIA
      queue = emptyList()
      publish()
      try {
        renderer.stop()
      } catch (unreachable: IOException) {
        // Deliberately swallowed, and only here. There is nothing left to report it to.
      }
      router.revokeAll()
      setSessionState(CastSessionState.Idle)
    }
  }

  // ---- loading -------------------------------------------------------------------------------

  private suspend fun load(seekToMs: Long) {
    val source = queue.getOrNull(index) ?: return

    // Read once per renderer (UpnpRenderer caches the SCPD), and read rather than guessed: a device
    // that cannot seek by time must show no seek bar at all, instead of one that answers 710 to
    // every drag.
    canSeek = renderer.capabilities().preferredSeekMode != null
    // `null` when the device has no RenderingControl service. The slider is then absent, not inert.
    renderer.volume()?.let { volumePercent = it }

    // The artwork URL goes to the **router**, not to the item: what the renderer is told to fetch
    // for a cover is a capability token on this phone, exactly as the track is. Handing
    // `source.artworkUri` to `CastItems.of` is the credential leak this argument closes -- see
    // `CastRoute.Proxied.artwork`.
    val candidate = router.candidate(device, source.upstreamUrl, source.served, source.artworkUri)
    val url = when (candidate) {
      is CastRoute.Proxied -> candidate.url
      is CastRoute.RendererDirect -> candidate.url
      is CastRoute.Unroutable -> {
        fail(CastFailureKind.UNROUTABLE, candidate.detail)
        return
      }
    }
    val item = CastItems.of(
      source,
      resourceUrl = url,
      artworkUrl = (candidate as? CastRoute.Proxied)?.artwork?.url,
    )
    send(item)

    val loaded = LoadedItem(candidate, item, source.upstreamUrl)
    if (playWhenReady) {
      if (!proveRoute(loaded)) return
    } else {
      unprovedRoute = loaded
    }

    if (seekToMs > 0L) renderer.seek(seekToMs)
    positionMs = seekToMs
    positionMeasuredAtMs = nowMs()
    // From the item rather than from the device: `GetPositionInfo` reports `TrackDuration` only
    // once the renderer has read the container, and a seek bar with no length until then is a
    // visible regression against local playback, which has the length from the library.
    durationMs = item.durationMs
    publish()
    startPolling()
  }

  /**
   * **Why `MimeAgreement.require` is not called from this path either, having been sent here.**
   *
   * Task 5 left it out of `UpnpRenderer.setUri` because the router mints the URL; Task 7 left it out
   * of the router because the router renders no DIDL, and named *this* path -- the only place where
   * the document, the URL and the served `Content-Type` all exist at once -- as where it belongs. It
   * was written here, and then measured, and it does not belong here either. The measurement is
   * recorded rather than the conclusion, because the conclusion is only as good as it:
   *
   * 1. **Task 7's exact recommendation is a two-of-three self-comparison.** It was
   *    `MimeAgreement.require(DidlLite.render(item), item.served.mimeType)`. `item.served` is the
   *    object the document was rendered *from*, so the `protocolInfo` leg and the `Content-Type` leg
   *    are one expression evaluated twice -- the compare-an-object-with-itself defect
   *    `MimeAgreement`'s own KDoc names.
   * 2. **The obvious repair still cannot fire.** Taking the `Content-Type` leg from
   *    [CastRoute.Proxied.media] instead -- the `PublishedMedia` the registry holds and
   *    `MediaProxyServer` really reads when it answers the renderer's `GET` -- makes that leg an
   *    independent artifact. It does not help, because the **URL** leg collapses onto the same
   *    value: `ProxyRegistry.publish` mints the path through `ServedMedia.fileName`, so the
   *    extension the URL carries *is* `served.fileExtension`. All three legs of a proxied route
   *    descend from one `ServedMedia`, and no input to this class can make them disagree. A `throw`
   *    no input can reach is a branch no test can cover and a guard that reports nothing.
   * 3. **A renderer-direct route must not be checked at all**, and this is the one that would have
   *    done harm rather than nothing. The check fires there -- a Subsonic `/rest/stream?id=…` path
   *    carries no extension, which Task 7 measured -- but firing means *refusing*, and refusing
   *    breaks the exact devices the fallback exists for: a generic DLNA renderer reads
   *    `res@protocolInfo` and plays that URL perfectly well. Worse, `MimeAgreement.disagreements`
   *    embeds the resource URL in its first message, and a renderer-direct URL **is** the Navidrome
   *    stream URL, carrying the user's Subsonic `u`, `t` and `s`. That message would have become a
   *    [CastFailure.message], a snackbar and a log line.
   *
   * **Where the check does discriminate is the test tier, over bytes rather than over expressions**
   * -- the DIDL document the renderer actually received, read back off the socket, against the
   * `Content-Type` the proxy actually sent. Neither of those is an expression in this file, so a
   * change in `ProxyRegistry`'s path minting or in `MediaProxyServer`'s response headers moves one
   * and not the other. `CastSessionTest`'s `the three statements of the served format agree on the
   * wire` is that assertion.
   *
   * **The one thing that would make a runtime check fire is not available here**: Navidrome's own
   * `Content-Type` on the upstream response. That is a fourth, genuinely independent statement of
   * the format, and it is observable inside `ProxyUpstream`/`MediaProxyServer` at the moment the
   * bytes are fetched -- Task 6's seam, not this one. If a later task ever hands this session a
   * pre-built [CastItem] from outside instead of building one from a [CastSource], the check
   * becomes fireable at that boundary and should go in *there*.
   */
  private suspend fun send(item: CastItem) {
    renderer.setUri(item)
    if (playWhenReady) renderer.play()
  }

  /**
   * Waits for the renderer to prove it can reach this phone, and falls back or fails if it does not.
   *
   * Runs **after** `Play`, because that is the only moment at which a renderer that *can* reach the
   * phone will have done so -- the whole of Task 7's design is that silence is the answer and it is
   * the only reliable one. `CastRouter.confirm` blocks on a latch for up to its proof timeout, hence
   * the IO dispatcher.
   *
   * @return `false` when the session was failed and the caller must stop.
   */
  private suspend fun proveRoute(loaded: LoadedItem): Boolean {
    unprovedRoute = null
    when (val confirmed = withContext(Dispatchers.IO) { router.confirm(loaded.route, loaded.upstreamUrl) }) {
      is CastRoute.Unroutable -> {
        fail(CastFailureKind.UNROUTABLE, confirmed.detail)
        return false
      }
      // `confirm` answers this only as a fallback: the renderer never fetched from the proxy and
      // renderer-direct is switched on. The item has to be re-issued against the new URL -- and
      // **without the cover**, whose token `confirm` has just revoked along with the track's. A
      // renderer that cannot reach this phone cannot fetch a picture from it either, so keeping the
      // element would be a broken image; putting Navidrome's own URL there instead would be the
      // credential leak arriving by the back door. See `CastRoute.RendererDirect`.
      is CastRoute.RendererDirect ->
        send(loaded.item.copy(resourceUrl = confirmed.url, artworkUri = null))
      // Proved, or on a subnet the fast path vouched for. Nothing to re-issue.
      is CastRoute.Proxied -> Unit
    }
    return true
  }

  // ---- the poll ------------------------------------------------------------------------------

  private fun startPolling() {
    pollJob?.cancel()
    pollJob = scope.launch {
      while (isActive) {
        delay(pollIntervalMs)
        mutex.withLock { if (!released) poll() }
      }
    }
  }

  private suspend fun poll() {
    try {
      val info = renderer.transportInfo()
      val position = renderer.positionInfo()
      transportFailures = 0

      if (info.hasError) {
        // `CurrentTransportStatus = ERROR_OCCURRED` is how a renderer says it could not play what
        // it was given -- wrong format, or a URL that 404'd -- and it arrives alongside an
        // ordinary `STOPPED`. Reading only the state produces a track that never starts and never
        // fails, with nothing reported anywhere.
        fail(CastFailureKind.RENDERER_REFUSED, "$deviceName could not play this track")
        return
      }

      // `null` means the device answered NOT_IMPLEMENTED, which is not the same as zero. Leaving
      // the last known value -- and, crucially, the instant it was measured -- is what stops a
      // renderer with no `RelTime` from resetting the seek bar to the start once a second.
      position.positionMs?.let {
        positionMs = it
        positionMeasuredAtMs = nowMs()
      }
      position.durationMs?.let { durationMs = it }

      val previous = transport
      transport = info.state

      // "The track finished" and "somebody pressed stop on the speaker" are both STOPPED. Reading
      // them the same way skips a track every time a listener touches the hardware, so the end of a
      // track is STOPPED *and* a position that reached the duration.
      val reachedTheEnd = durationMs > 0L && positionMs >= durationMs - END_OF_TRACK_TOLERANCE_MS
      if (previous == TransportState.PLAYING && transport == TransportState.STOPPED && reachedTheEnd) {
        guard { advance() }
        return
      }

      if (transport == TransportState.PLAYING) setSessionState(CastSessionState.Playing(deviceName))
      publish()
    } catch (refused: UpnpErrorException) {
      // The speaker answered and said no. It is *there*. Resetting the counter is the whole point:
      // a renderer that answers 501 to `GetPositionInfo` on some firmware would otherwise be
      // declared dead three seconds into every session.
      transportFailures = 0
    } catch (unreachable: SoapTransportException) {
      transportFailures += 1
      if (transportFailures >= LOST_AFTER_FAILURES) lose()
    }
  }

  private suspend fun advance() {
    if (index + 1 >= queue.size) {
      ended = true
      playWhenReady = false
      transport = TransportState.STOPPED
      publish()
      return
    }
    index += 1
    positionMs = 0L
    positionMeasuredAtMs = nowMs()
    durationMs = 0L
    publish()
    load(seekToMs = 0L)
  }

  // ---- ending --------------------------------------------------------------------------------

  /** Runs [block], turning every way a cast operation can fail into a reported state. */
  private suspend fun guard(block: suspend () -> Unit) {
    try {
      block()
    } catch (cause: IOException) {
      // One catch, and it is complete: `SoapClient` raises `SoapTransportException` for a renderer
      // that will not answer and `UpnpErrorException` for one that refuses, `UpnpRenderer` raises
      // `RendererFollowsAnotherException`, and
      // every one of them is an `IOException` for exactly this reason.
      reportFailure(cause)
    }
  }

  private fun reportFailure(cause: IOException) {
    when (cause) {
      // Counted the same way a failed poll is counted, so a command failing and a poll failing
      // agree about when a speaker has gone.
      is SoapTransportException -> {
        transportFailures += 1
        if (transportFailures >= LOST_AFTER_FAILURES) lose() else publish()
      }
      // Sonos quirk 4: this speaker is grouped and is following another. `SetAVTransportURI` on it
      // is *accepted* and plays nothing, so the message is worth more than the error code.
      is RendererFollowsAnotherException ->
        fail(CastFailureKind.RENDERER_REFUSED, "$deviceName: ${cause.message}")
      is UpnpErrorException ->
        fail(CastFailureKind.RENDERER_REFUSED, "$deviceName refused: ${cause.message}")
      // Unreachable today and kept anyway, which is a decision rather than an oversight. Every
      // `IOException` this class can meet is one of the four above -- `SoapClient.invoke` wraps
      // even a `NonLocalAddressException` into a `SoapTransportException` before it escapes -- so
      // no test can reach this arm and it is recorded as uncovered at the floor rather than
      // pretended away. Deleting it would not remove the branch, it would move it: an unexpected
      // `IOException` would then escape into the poll's coroutine and stop the session polling with
      // nothing reported anywhere, which is the exact silence this class exists to prevent.
      else -> fail(CastFailureKind.UNEXPECTED, "$deviceName: ${cause.message.orEmpty()}")
    }
  }

  private fun fail(kind: CastFailureKind, message: String) {
    stopPolling()
    failure = CastFailure(kind, message)
    playWhenReady = false
    transport = TransportState.NO_MEDIA
    publish()
    setSessionState(CastSessionState.Failed(deviceName, message))
  }

  /**
   * The renderer stopped answering.
   *
   * Everything here is one half of one idea: **the clock must stop and something must be said**.
   * `playWhenReady = false` and `transport = NO_MEDIA` are what make [CastPlayback.positionAdvancing]
   * false, so the reported position freezes instead of running on over a speaker nobody can hear;
   * the [CastSessionState.Lost] is what makes it visible; and [positionMs] travelling with it is
   * what lets Task 9 resume locally on the same second.
   */
  private fun lose() {
    stopPolling()
    failure = CastFailure(CastFailureKind.RENDERER_UNREACHABLE, "$deviceName stopped responding")
    playWhenReady = false
    transport = TransportState.NO_MEDIA
    publish()
    setSessionState(
      CastSessionState.Lost(deviceName, positionMs, queue.getOrNull(index)?.mediaId),
    )
  }

  private fun stopPolling() {
    pollJob?.cancel()
    pollJob = null
  }

  // ---- state ---------------------------------------------------------------------------------

  private fun publish() {
    playback = CastPlayback(
      playbackState = playbackState(),
      playWhenReady = playWhenReady,
      // The renderer's own word, and nothing else. See CastPlayback.positionAtMs.
      positionAdvancing = transport == TransportState.PLAYING,
      queueSize = queue.size,
      currentIndex = index,
      currentMediaId = queue.getOrNull(index)?.mediaId,
      positionMs = positionMs,
      positionMeasuredAtMs = positionMeasuredAtMs,
      durationMs = durationMs,
      volumePercent = volumePercent,
      canSeek = canSeek,
      failure = failure,
    )
    onPlaybackChanged()
  }

  private fun playbackState(): CastPlaybackState = when {
    failure != null -> CastPlaybackState.IDLE
    queue.isEmpty() -> CastPlaybackState.IDLE
    ended -> CastPlaybackState.ENDED
    else -> when (transport) {
      // STOPPED with a queue is "loaded, not playing", which is what a renderer reports between
      // `SetAVTransportURI` and `Play`. It is READY, not IDLE: the item is there and playable.
      TransportState.PLAYING, TransportState.PAUSED, TransportState.STOPPED -> CastPlaybackState.READY
      TransportState.TRANSITIONING -> CastPlaybackState.BUFFERING
      // UNKNOWN is deliberately here and not with STOPPED: `TransportState` keeps a value it could
      // not parse distinct precisely so this class does not read a firmware quirk as "the track
      // ended".
      TransportState.NO_MEDIA, TransportState.RECORDING, TransportState.UNKNOWN -> CastPlaybackState.IDLE
    }
  }

  private fun setSessionState(state: CastSessionState) {
    if (state == sessionState) return
    sessionState = state
    onSessionStateChanged(state)
  }

  companion object {
    /** 1 Hz. Enough for a seek bar once the position extrapolates between polls. */
    const val POLL_INTERVAL_MS: Long = 1_000L

    /** Three seconds of silence. One missed poll on busy Wi-Fi is ordinary; three is not. */
    const val LOST_AFTER_FAILURES: Int = 3

    /**
     * How close to the declared duration counts as "the track finished".
     *
     * A renderer stops when the decoder runs out, which is up to a frame or two short of the
     * declared length, and `RelTime` is reported to the second. 1.5 s covers both without being
     * long enough for a deliberate stop near the end of a track to read as one.
     */
    const val END_OF_TRACK_TOLERANCE_MS: Long = 1_500L

    /** What is reported before a device with `RenderingControl` has been asked. */
    const val DEFAULT_VOLUME_PERCENT: Int = 30
  }
}
