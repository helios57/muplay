package app.muplay.cast.discovery

import app.muplay.model.RememberedRenderer
import java.net.URI

/**
 * A renderer this app can actually control: it has an `AVTransport` service, so
 * `SetAVTransportURI` and `Play` will reach something.
 *
 * Deliberately *not* every device SSDP answered with. A Sonos household answers with a
 * `ZonePlayer`, a `MediaServer` and a `MediaRenderer` from one IP, and a router's UPnP IGD answers
 * too. Putting any of those in the picker means a user chooses one and playback fails at
 * `SetAVTransportURI` with UPnP error 401 -- long after they made the choice, and with no way to
 * tell them why. [from] returns `null` for them instead.
 */
data class CastDevice(
  val udn: String,
  val friendlyName: String,
  val manufacturer: String?,
  val modelName: String?,
  val descriptionUrl: URI,
  val avTransportControlUrl: URI,
  val avTransportScpdUrl: URI?,
  /** `null` when the device has no `RenderingControl` -- the volume control is then absent, not inert. */
  val renderingControlUrl: URI?,
  val isSonos: Boolean,
) {
  companion object {

    /** Sonos has used this UDN prefix on every product since the ZP100. */
    private const val SONOS_UDN_PREFIX = "uuid:RINCON_"

    fun from(root: UpnpDevice, descriptionUrl: URI): CastDevice? {
      // The renderer may be the root (a generic DLNA device) or nested inside it (Sonos). Search
      // the flattened tree for the first device that carries an AVTransport, whatever its type:
      // a handful of devices advertise AVTransport on a deviceType that is not MediaRenderer:1,
      // and the service is what determines whether casting works.
      //
      // The device and its service are taken in one pass rather than found and then looked up
      // again: a second `?: return null` on a service the search has already proved is there is a
      // branch no test could ever reach.
      val (renderer, avTransport) = root.flatten()
        .firstNotNullOfOrNull { device ->
          device.service(DeviceDescription.SERVICE_AV_TRANSPORT)?.let { device to it }
        }
        ?: return null

      // Identity and name come from the ROOT, not from the renderer. The root's UDN is what SSDP's
      // USN deduplicates on, and its friendlyName is the one a user recognises -- Sonos names its
      // embedded renderer "<name> Media Renderer", which nobody would pick out of a list.
      return CastDevice(
        udn = root.udn,
        friendlyName = root.friendlyName.ifEmpty { renderer.friendlyName },
        manufacturer = root.manufacturer,
        modelName = root.modelName,
        descriptionUrl = descriptionUrl,
        avTransportControlUrl = avTransport.controlUrl,
        avTransportScpdUrl = avTransport.scpdUrl,
        renderingControlUrl = renderer.service(DeviceDescription.SERVICE_RENDERING_CONTROL)?.controlUrl,
        // Two independent signals, because firmware has changed the manufacturer string before
        // ("Sonos, Inc." against "Sonos Inc.") and the RINCON_ prefix has not changed in fifteen
        // years. Task 5 branches on this for three real quirks, so a false negative is not cosmetic.
        isSonos = root.udn.startsWith(SONOS_UDN_PREFIX) ||
          root.manufacturer?.contains("Sonos", ignoreCase = true) == true,
      )
    }
  }
}

/**
 * The three fields worth keeping when this device is no longer on the air.
 *
 * This is the **one** place a `CastDevice` becomes a `RememberedRenderer`, and it lives here
 * rather than in `:core:model` for a module reason: naming `CastDevice` from the interface the
 * store implements would drag `:core:cast` into every consumer of `:core:database`. See
 * `RememberedRenderers`' own KDoc.
 */
fun CastDevice.remembered(): RememberedRenderer =
  RememberedRenderer(udn = udn, friendlyName = friendlyName, descriptionUrl = descriptionUrl.toString())
