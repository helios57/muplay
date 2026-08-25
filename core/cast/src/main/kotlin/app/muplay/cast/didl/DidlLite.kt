package app.muplay.cast.didl

import app.muplay.cast.soap.UpnpTime
import app.muplay.cast.soap.XmlText

/**
 * The DIDL-Lite metadata document a renderer is given alongside a URL.
 *
 * Spec section 6: *"DIDL-Lite mandatory"*. Sonos will accept a `SetAVTransportURI` with empty
 * metadata on some firmware and refuse it on others; where it accepts, the speaker's display and
 * the Sonos app show the track as unknown, which is a visible half-failure.
 *
 * Built by string building rather than by a DOM serialiser, for the same reason
 * [app.muplay.cast.soap.SoapEnvelope] is: this plan asserts the document byte for byte, and a
 * serialiser's choices about self-closing tags and attribute order are not this project's to
 * assert. Escaping is [XmlText]'s, applied per field.
 */
object DidlLite {

  const val CLASS_MUSIC_TRACK: String = "object.item.audioItem.musicTrack"
  const val CLASS_AUDIO_BOOK: String = "object.item.audioItem.audioBook"

  fun render(item: CastItem): String = buildString {
    append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
    append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
    append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
    append("<item id=\"").append(XmlText.escape(item.mediaId)).append("\" parentID=\"0\" restricted=\"1\">")
    append("<dc:title>").append(XmlText.escape(item.title)).append("</dc:title>")
    append("<upnp:class>").append(item.upnpClass).append("</upnp:class>")
    // `dc:creator` and `upnp:artist` carry the same value: renderers disagree about which one they
    // read, and sending both costs a few bytes while sending one costs a blank artist line on
    // whichever brand reads the other.
    item.artist?.let {
      append("<dc:creator>").append(XmlText.escape(it)).append("</dc:creator>")
      append("<upnp:artist>").append(XmlText.escape(it)).append("</upnp:artist>")
    }
    item.albumTitle?.let { append("<upnp:album>").append(XmlText.escape(it)).append("</upnp:album>") }
    item.artworkUri?.let {
      append("<upnp:albumArtURI>").append(XmlText.escape(it)).append("</upnp:albumArtURI>")
    }
    // An absent optional field is omitted, never rendered empty: `<upnp:albumArtURI></upnp:albumArtURI>`
    // makes several renderers fetch the empty URL once a second and log an error each time.
    append("<res protocolInfo=\"").append(XmlText.escape(item.served.protocolInfo)).append("\" ")
    append("duration=\"").append(UpnpTime.formatDuration(item.durationMs)).append("\">")
    // The URL is escaped like any other text: a Navidrome stream URL carries `&` between query
    // parameters, and one unescaped ampersand makes the whole document unparseable at the device.
    append(XmlText.escape(item.resourceUrl))
    append("</res>")
    append("</item>")
    append("</DIDL-Lite>")
  }

  /**
   * The document, escaped **once**, ready to be the text content of `CurrentURIMetaData`.
   *
   * A separate function rather than a caller's responsibility, because "escape it before you send
   * it" is a rule that gets applied twice as often as it gets forgotten, and `&amp;lt;DIDL-Lite`
   * is a device that shows the track as unknown with no error anywhere.
   */
  fun renderEscaped(item: CastItem): String = XmlText.escape(render(item))
}
