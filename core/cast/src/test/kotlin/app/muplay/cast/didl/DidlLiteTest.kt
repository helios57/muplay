package app.muplay.cast.didl

import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.soap.SoapArgument
import app.muplay.cast.soap.SoapEnvelope
import app.muplay.cast.soap.XmlText
import app.muplay.model.StreamFormat
import javax.xml.parsers.DocumentBuilderFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The DIDL-Lite document, and the **round trip** spec section 10's Tier 1 row names.
 *
 * The round trip is the assertion that matters, because it is the only one that catches both
 * halves of the escaping defect at once: escaped zero times the envelope does not parse, escaped
 * twice the device receives literal `&lt;` text. Rendering alone catches neither reliably.
 */
class DidlLiteTest {

  private val item = CastItem(
    mediaId = "track-1",
    title = "Track 1",
    artist = "Test Artist",
    albumTitle = "Test Album",
    artworkUri = "http://10.0.0.2:8080/art/album-1.jpg",
    durationMs = 300_000L,
    upnpClass = DidlLite.CLASS_MUSIC_TRACK,
    resourceUrl = "http://10.0.0.2:8080/media/9f2a.mp3",
    served = ServedMedia.of("mp3", StreamFormat.Raw),
  )

  @Test
  fun `the document is exactly this`() {
    assertThat(DidlLite.render(item)).isEqualTo(
      "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item id=\"track-1\" parentID=\"0\" restricted=\"1\">" +
        "<dc:title>Track 1</dc:title>" +
        "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
        "<dc:creator>Test Artist</dc:creator>" +
        "<upnp:artist>Test Artist</upnp:artist>" +
        "<upnp:album>Test Album</upnp:album>" +
        "<upnp:albumArtURI>http://10.0.0.2:8080/art/album-1.jpg</upnp:albumArtURI>" +
        "<res protocolInfo=\"http-get:*:audio/mpeg:DLNA.ORG_OP=01;" +
        "DLNA.ORG_FLAGS=01700000000000000000000000000000\" duration=\"0:05:00.000\">" +
        "http://10.0.0.2:8080/media/9f2a.mp3" +
        "</res>" +
        "</item>" +
        "</DIDL-Lite>",
    )
  }

  @Test
  fun `every field comes from the item it was given`() {
    // Two observations per field, by rendering a second item that differs in all of them. A
    // byte-exact assertion on one item is satisfied by a hardcoded document; this is not.
    val second = item.copy(
      mediaId = "chapter-14",
      title = "Chapter 14",
      artist = "Another Artist",
      albumTitle = "A Book",
      artworkUri = "http://10.0.0.2:8080/art/book-9.jpg",
      durationMs = 3_723_000L,
      upnpClass = DidlLite.CLASS_AUDIO_BOOK,
      resourceUrl = "http://10.0.0.2:8080/media/aaaa.m4b",
      served = ServedMedia.of("m4b", StreamFormat.Raw),
    )
    val xml = DidlLite.render(second)

    assertThat(xml).contains("<item id=\"chapter-14\"")
    assertThat(xml).contains("<dc:title>Chapter 14</dc:title>")
    assertThat(xml).contains("<dc:creator>Another Artist</dc:creator>")
    assertThat(xml).contains("<upnp:album>A Book</upnp:album>")
    assertThat(xml).contains("<upnp:albumArtURI>http://10.0.0.2:8080/art/book-9.jpg</upnp:albumArtURI>")
    assertThat(xml).contains("<upnp:class>object.item.audioItem.audioBook</upnp:class>")
    assertThat(xml).contains("duration=\"1:02:03.000\"")
    assertThat(xml).contains("http-get:*:audio/mp4:")
    assertThat(xml).contains(">http://10.0.0.2:8080/media/aaaa.m4b</res>")
  }

  @Test
  fun `an absent optional field is omitted rather than rendered empty`() {
    // A renderer showing an empty artist line is worse than one showing none, and `albumArtURI`
    // with an empty value makes several renderers fetch "" and log an error every second.
    val sparse = item.copy(artist = null, albumTitle = null, artworkUri = null)
    val xml = DidlLite.render(sparse)

    assertThat(xml).doesNotContain("dc:creator")
    assertThat(xml).doesNotContain("upnp:artist")
    assertThat(xml).doesNotContain("upnp:album>")
    assertThat(xml).doesNotContain("albumArtURI")
    // ...and the mandatory ones are still there.
    assertThat(xml).contains("<dc:title>Track 1</dc:title>")
    assertThat(xml).contains("<upnp:class>")
    assertThat(xml).contains("<res protocolInfo=")
  }

  @Test
  fun `every text field is escaped, including in the res url`() {
    val nasty = item.copy(
      mediaId = "id&1",
      title = "Rock & Roll <live>",
      artist = "AC/DC \"Live\"",
      albumTitle = "It's Album",
      resourceUrl = "http://10.0.0.2:8080/media/9f2a.mp3?x=1&y=2",
    )
    val xml = DidlLite.render(nasty)

    assertThat(xml).contains("<item id=\"id&amp;1\"")
    assertThat(xml).contains("<dc:title>Rock &amp; Roll &lt;live&gt;</dc:title>")
    assertThat(xml).contains("<dc:creator>AC/DC &quot;Live&quot;</dc:creator>")
    assertThat(xml).contains("<upnp:album>It&apos;s Album</upnp:album>")
    // The URL matters most: a stream URL carries `&` between query parameters, and an unescaped
    // one makes the whole document unparseable at the device.
    assertThat(xml).contains(">http://10.0.0.2:8080/media/9f2a.mp3?x=1&amp;y=2</res>")
  }

  @Test
  fun `the rendered document is well-formed xml`() {
    // Parsed, not eyeballed. This is what catches an unescaped character that no `contains`
    // assertion happens to look at.
    val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
      .newDocumentBuilder()
      .parse(DidlLite.render(item.copy(title = "Rock & Roll <live>")).byteInputStream())

    assertThat(document.documentElement.nodeName).isEqualTo("DIDL-Lite")
  }

  /**
   * **The round trip spec section 10 names.** Render, escape once, embed as a SOAP argument,
   * parse the envelope back, decode, re-parse as XML, and read the fields out. Every step is one
   * a real device performs.
   */
  @Test
  fun `didl survives being embedded in a soap envelope and read back out`() {
    val nasty = item.copy(title = "Rock & Roll <live> \"1971\"", artist = "It's & Co")
    val envelope = SoapEnvelope.render(
      DeviceDescription.SERVICE_AV_TRANSPORT,
      "SetAVTransportURI",
      listOf(
        SoapArgument("InstanceID", "0"),
        SoapArgument("CurrentURI", nasty.resourceUrl),
        SoapArgument("CurrentURIMetaData", DidlLite.renderEscaped(nasty)),
      ),
    )

    // What the *device* sees inside the envelope: escaped exactly once.
    assertThat(envelope).contains("<CurrentURIMetaData>&lt;DIDL-Lite")
    assertThat(envelope).doesNotContain("&amp;lt;DIDL-Lite")
    assertThat(envelope).doesNotContain("<CurrentURIMetaData><DIDL-Lite")

    // And what it means once decoded.
    val embedded = envelope.substringAfter("<CurrentURIMetaData>").substringBefore("</CurrentURIMetaData>")
    val decoded = XmlText.unescape(embedded)
    val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
      .newDocumentBuilder().parse(decoded.byteInputStream())

    assertThat(document.getElementsByTagName("dc:title").item(0).textContent)
      .isEqualTo("Rock & Roll <live> \"1971\"")
    assertThat(document.getElementsByTagName("dc:creator").item(0).textContent).isEqualTo("It's & Co")
    assertThat(document.getElementsByTagName("res").item(0).textContent).isEqualTo(nasty.resourceUrl)
    assertThat(
      document.getElementsByTagName("res").item(0).attributes.getNamedItem("protocolInfo").nodeValue,
    ).isEqualTo(nasty.served.protocolInfo)
  }

  @Test
  fun `renderEscaped is render escaped exactly once`() {
    assertThat(DidlLite.renderEscaped(item)).isEqualTo(XmlText.escape(DidlLite.render(item)))
    assertThat(DidlLite.renderEscaped(item)).startsWith("&lt;DIDL-Lite")
    assertThat(DidlLite.renderEscaped(item)).doesNotContain("&amp;lt;")
  }

  @Test
  fun `the two upnp classes are the ones the protocol defines`() {
    assertThat(DidlLite.CLASS_MUSIC_TRACK).isEqualTo("object.item.audioItem.musicTrack")
    assertThat(DidlLite.CLASS_AUDIO_BOOK).isEqualTo("object.item.audioItem.audioBook")
  }
}
