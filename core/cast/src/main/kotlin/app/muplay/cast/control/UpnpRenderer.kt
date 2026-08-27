package app.muplay.cast.control

import app.muplay.cast.didl.CastItem
import app.muplay.cast.didl.DidlLite
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.soap.SoapArgument
import app.muplay.cast.soap.SoapClient
import app.muplay.cast.soap.UpnpError
import app.muplay.cast.soap.UpnpErrorException
import app.muplay.cast.soap.UpnpTime
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A Sonos speaker that has been grouped with another in the Sonos app, and is following it.
 *
 * `SetAVTransportURI` on a follower is **accepted** and produces no sound, because the follower
 * keeps following. Detecting that and saying so is worth more to a user than a silent success --
 * Task 10 turns this into *"Kitchen is grouped with another speaker; ungroup it in the Sonos app
 * to cast to it"*.
 *
 * An `IOException`, like every other failure out of a `SoapClient` call, so the single
 * `catch (e: IOException)` this module's callers already hold stays complete.
 */
class RendererFollowsAnotherException(val coordinatorUri: String) : IOException(
  "this speaker is grouped with another and is following $coordinatorUri. Ungroup it in the " +
    "Sonos app, or cast to the group's coordinator instead.",
)

/**
 * `AVTransport` and `RenderingControl` on one renderer.
 *
 * Every action sends `InstanceID = "0"`: a single-zone renderer has exactly one transport
 * instance, and Sonos answers `718 Invalid InstanceID` to anything else.
 *
 * `play()` sends `Speed = "1"` and can send nothing else. `TransportPlaySpeed`'s allowed value list
 * is `{"1"}` on every renderer this plan targets, and Sonos answers `717` for anything else --
 * which means **a book's per-item playback speed cannot be delivered to a speaker**. That is a
 * protocol limit, not an omission; Task 8 reports `PlaybackParameters(1.0f)` and Task 10 tells the
 * user, rather than accepting a setting that silently does nothing.
 *
 * Metadata is handed to [SoapArgument] **raw**. [app.muplay.cast.soap.SoapEnvelope.render] escapes
 * every argument value it writes, because escaping is framing and framing belongs to the layer
 * that owns the envelope; escaping it here as well is the `&amp;lt;DIDL-Lite` defect, and
 * `FakeRenderer` answers `714` to it.
 */
class UpnpRenderer(
  private val device: CastDevice,
  private val soap: SoapClient,
  private val http: CastHttpClient,
) {

  private val capabilitiesLock = Mutex()
  private var cachedCapabilities: RendererCapabilities? = null

  /** Fetched once per renderer. A SCPD read per seek would add a round trip to every drag. */
  suspend fun capabilities(): RendererCapabilities = capabilitiesLock.withLock {
    cachedCapabilities ?: loadCapabilities().also { cachedCapabilities = it }
  }

  private suspend fun loadCapabilities(): RendererCapabilities = withContext(Dispatchers.IO) {
    val url = device.avTransportScpdUrl ?: return@withContext RendererCapabilities.DEFAULT
    // A device whose SCPD 404s, times out or answers something unreadable is still castable; it
    // just cannot be asked what it supports. `runCatching` and not a narrower catch on purpose:
    // this is a *best-effort* enrichment, and there is no failure of it that should stop a cast.
    runCatching { RendererCapabilities.fromScpd(http.exchange(url, "GET").bodyText()) }
      .getOrDefault(RendererCapabilities.DEFAULT)
  }

  /**
   * @throws RendererFollowsAnotherException when this speaker is a follower in a Sonos group.
   */
  suspend fun setUri(item: CastItem) {
    // Look BEFORE setting: a grouped Sonos accepts the call and plays nothing, so checking
    // afterwards would leave a session that looks established and is not.
    positionInfo().followedCoordinator?.let { throw RendererFollowsAnotherException(it) }

    avTransport(
      "SetAVTransportURI",
      // In the order the service description declares them. UPnP argument lists are ordered, and
      // Sonos answers 402 to a reordering.
      listOf(
        SoapArgument("InstanceID", INSTANCE_ID),
        SoapArgument("CurrentURI", item.resourceUrl),
        // RAW. See this class's KDoc: `SoapEnvelope.render` owns the escaping.
        SoapArgument("CurrentURIMetaData", DidlLite.render(item)),
      ),
    )
  }

  /**
   * Queues the next track on devices that support it, for a transition with no gap of our making.
   *
   * A no-op when the device did not declare `SetNextAVTransportURI` -- calling it anyway returns
   * `401` and, on some firmware, clears the current queue. `null` clears the queued item, which is
   * what a device expects at the end of a playlist.
   */
  suspend fun setNextUri(item: CastItem?) {
    if (!capabilities().supportsSetNextUri) return
    avTransport(
      RendererCapabilities.ACTION_SET_NEXT_URI,
      listOf(
        SoapArgument("InstanceID", INSTANCE_ID),
        SoapArgument("NextURI", item?.resourceUrl.orEmpty()),
        SoapArgument("NextURIMetaData", item?.let { DidlLite.render(it) }.orEmpty()),
      ),
    )
  }

  suspend fun play() {
    avTransport(
      "Play",
      listOf(SoapArgument("InstanceID", INSTANCE_ID), SoapArgument("Speed", PLAY_SPEED)),
    )
  }

  suspend fun pause() {
    avTransport("Pause", listOf(SoapArgument("InstanceID", INSTANCE_ID)))
  }

  suspend fun stop() {
    avTransport("Stop", listOf(SoapArgument("InstanceID", INSTANCE_ID)))
  }

  /**
   * Seeks, returning whether the device actually did.
   *
   * `false` rather than an exception for the two ordinary refusals -- no time seek mode, or a
   * target the device calls illegal -- because the caller is a `Player` and a `Player` that threw
   * on a seek would take the session down over a dragged progress bar. A transport failure still
   * propagates: that one means the speaker is gone.
   *
   * The mode comes from the device's own SCPD rather than from a retry loop, so a device that
   * cannot seek by time is refused **here**, with no request sent at all, and Task 8 can withhold
   * `COMMAND_SEEK_*` instead of drawing a bar that produces a 710 on every drag. `710` is still
   * caught, because an SCPD can lie and a `false` from the one path that knows is better than an
   * exception the UI has to interpret.
   */
  suspend fun seek(positionMs: Long): Boolean {
    val mode = capabilities().preferredSeekMode ?: return false
    return try {
      avTransport(
        "Seek",
        listOf(
          SoapArgument("InstanceID", INSTANCE_ID),
          SoapArgument("Unit", mode),
          SoapArgument("Target", UpnpTime.formatClock(positionMs)),
        ),
      )
      true
    } catch (refused: UpnpErrorException) {
      // An SCPD can lie, and a target past the end is a legitimate refusal. Both are `false`.
      if (refused.fault.errorCode in SEEK_REFUSALS) false else throw refused
    }
  }

  /**
   * `ERROR_OCCURRED` is how a renderer reports that it could not play what it was given -- the
   * format was wrong, or the URL 404'd -- and it arrives in a *different* out-argument from the
   * state, usually alongside an ordinary `STOPPED`. The mapping is [TransportInfo.fromWire]'s so
   * that every shape of it is reachable without a socket.
   */
  suspend fun transportInfo(): TransportInfo {
    val out = avTransport("GetTransportInfo", listOf(SoapArgument("InstanceID", INSTANCE_ID)))
    return TransportInfo.fromWire(out["CurrentTransportState"], out["CurrentTransportStatus"])
  }

  suspend fun positionInfo(): PositionInfo {
    val out = avTransport("GetPositionInfo", listOf(SoapArgument("InstanceID", INSTANCE_ID)))
    return PositionInfo.fromWire(out["RelTime"], out["TrackDuration"], out["TrackURI"])
  }

  /** `null` when the device has no `RenderingControl` -- the UI then shows no slider at all. */
  suspend fun volume(): Int? {
    val controlUrl = device.renderingControlUrl ?: return null
    return renderingControl(
      controlUrl,
      "GetVolume",
      listOf(SoapArgument("InstanceID", INSTANCE_ID), SoapArgument("Channel", VOLUME_CHANNEL)),
    )["CurrentVolume"]?.toIntOrNull()
  }

  suspend fun setVolume(level: Int) {
    val controlUrl = device.renderingControlUrl ?: return
    renderingControl(
      controlUrl,
      "SetVolume",
      listOf(
        SoapArgument("InstanceID", INSTANCE_ID),
        SoapArgument("Channel", VOLUME_CHANNEL),
        // Clamped here rather than sent and refused: a slider's rounding must not become a 402.
        SoapArgument("DesiredVolume", level.coerceIn(MIN_VOLUME, MAX_VOLUME).toString()),
      ),
    )
  }

  suspend fun setMuted(muted: Boolean) {
    val controlUrl = device.renderingControlUrl ?: return
    renderingControl(
      controlUrl,
      "SetMute",
      listOf(
        SoapArgument("InstanceID", INSTANCE_ID),
        SoapArgument("Channel", VOLUME_CHANNEL),
        SoapArgument("DesiredMute", if (muted) MUTE_ON else MUTE_OFF),
      ),
    )
  }

  private suspend fun avTransport(action: String, arguments: List<SoapArgument>): Map<String, String> =
    soap.invoke(device.avTransportControlUrl, DeviceDescription.SERVICE_AV_TRANSPORT, action, arguments)

  private suspend fun renderingControl(
    controlUrl: URI,
    action: String,
    arguments: List<SoapArgument>,
  ): Map<String, String> =
    soap.invoke(controlUrl, DeviceDescription.SERVICE_RENDERING_CONTROL, action, arguments)

  companion object {
    /** A single-zone renderer has exactly one transport instance. Sonos answers 718 to anything else. */
    const val INSTANCE_ID: String = "0"

    /** `TransportPlaySpeed`'s only allowed value on every renderer this plan targets. */
    const val PLAY_SPEED: String = "1"

    /** `RenderingControl` channels are `Master`, `LF`, `RF`; only `Master` is universal. */
    const val VOLUME_CHANNEL: String = "Master"

    const val MIN_VOLUME: Int = 0
    const val MAX_VOLUME: Int = 100

    /** `DesiredMute` is a UPnP boolean, which is the character `1` or `0` and not `true`/`false`. */
    private const val MUTE_ON = "1"
    private const val MUTE_OFF = "0"

    private val SEEK_REFUSALS =
      setOf(UpnpError.SEEK_MODE_NOT_SUPPORTED, UpnpError.ILLEGAL_SEEK_TARGET)
  }
}
