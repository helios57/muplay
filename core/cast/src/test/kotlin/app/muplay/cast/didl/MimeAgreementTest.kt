package app.muplay.cast.didl

import app.muplay.model.StreamFormat
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * **The three-way MIME invariant, made observable.**
 *
 * Spec section 6 records that Sonos infers MIME *from the URL*, a generic DLNA renderer trusts
 * `res/@protocolInfo`, and everything else believes the proxy's `Content-Type`. Three statements
 * of one fact, and the defect this task exists to close is the one where **two of them agree and
 * the third silently differs** -- a shape no assertion comparing `served.mimeType` to
 * `served.protocolInfo` can see, because both come from the same object.
 *
 * So the check here never reads that object. It reads each leg from the artifact the party that
 * believes it actually sees: the `protocolInfo` attribute parsed back out of the rendered
 * document, the extension of the `<res>` URL as a renderer sniffing the path would take it, and
 * the `Content-Type` header string. Every document below that must produce a disagreement is
 * **hand-written**, not rendered by `DidlLite`, so no assertion here is satisfied by two copies of
 * the same expression.
 */
class MimeAgreementTest {

  private fun didl(protocolInfo: String, resourceUrl: String): String =
    "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
      "<item id=\"x\" parentID=\"0\" restricted=\"1\">" +
      "<res protocolInfo=\"$protocolInfo\" duration=\"0:05:00.000\">$resourceUrl</res>" +
      "</item></DIDL-Lite>"

  @Test
  fun `a document whose three legs agree reports nothing`() {
    val served = ServedMedia.of("flac", StreamFormat.Raw)
    val document = didl(served.protocolInfo, "http://10.0.0.2:8080/media/${served.fileName("9f2a")}")

    assertThat(MimeAgreement.disagreements(document, served.mimeType)).isEmpty()
  }

  /**
   * The sweep. Every format this client can serve, all three legs, from the real renderer.
   *
   * The URL is minted with `fileName` and the header with `mimeType`, which is exactly what Task
   * 6's proxy does -- so a `RAW_TYPES` entry whose extension implies a different MIME from the one
   * it declares (`"oga" to ServedMedia("audio/ogg", "mp3")`, the plausible mistake) fails here on
   * that entry alone, naming it.
   */
  @Test
  fun `every format this client serves agrees with itself on all three legs`() {
    val offenders = ServedMedia.rawTypes.entries.filter { (_, served) ->
      val item = CastItem(
        mediaId = "track-1",
        title = "Track 1",
        artist = null,
        albumTitle = null,
        artworkUri = null,
        durationMs = 1_000L,
        upnpClass = DidlLite.CLASS_MUSIC_TRACK,
        resourceUrl = "http://10.0.0.2:8080/media/${served.fileName("9f2a")}",
        served = served,
      )
      MimeAgreement.disagreements(DidlLite.render(item), served.mimeType).isNotEmpty()
    }.map { it.key }

    assertThat(offenders).isEmpty()
    // ...and the sweep ran over something. An empty `rawTypes` would pass the line above
    // vacuously, which is the exact defect class this project keeps finding.
    assertThat(ServedMedia.rawTypes).hasSizeGreaterThanOrEqualTo(12)
  }

  @Test
  fun `a protocolInfo that disagrees with the url extension is reported`() {
    // The transcode defect in its natural form: MP3 bytes on a `.mp3` URL, announced as Ogg.
    val problems = MimeAgreement.disagreements(
      didl("http-get:*:audio/ogg:DLNA.ORG_OP=01", "http://10.0.0.2:8080/media/9f2a.mp3"),
      "audio/mpeg",
    )

    assertThat(problems).hasSize(1)
    assertThat(problems.single()).contains("audio/ogg").contains("audio/mpeg").contains("mp3")
  }

  /**
   * **The leg that a two-way check loses.** `protocolInfo` and the URL agree perfectly; the bytes
   * are something else entirely. Sonos plays this and every other renderer refuses it, which is
   * the hardest failure of the three to diagnose from a bug report.
   */
  @Test
  fun `a content-type that disagrees while the other two agree is reported`() {
    val problems = MimeAgreement.disagreements(
      didl("http-get:*:audio/mpeg:DLNA.ORG_OP=01", "http://10.0.0.2:8080/media/9f2a.mp3"),
      "audio/flac",
    )

    assertThat(problems).hasSize(1)
    assertThat(problems.single()).contains("audio/flac")
  }

  @Test
  fun `a url extension that disagrees while the other two agree is reported`() {
    // The mirror image: the two legs a `Content-Type`-vs-`protocolInfo` assertion would compare
    // are identical, and the one Sonos actually reads is wrong.
    val problems = MimeAgreement.disagreements(
      didl("http-get:*:audio/mpeg:DLNA.ORG_OP=01", "http://10.0.0.2:8080/media/9f2a.flac"),
      "audio/mpeg",
    )

    assertThat(problems).hasSize(1)
    assertThat(problems.single()).contains("flac")
  }

  @Test
  fun `a url with no extension at all is reported, because sonos has nothing to sniff`() {
    // A Navidrome stream URL is exactly this shape: no extension, all meaning in the query. The
    // `&` is written as `&amp;` because that is what a real document carries -- and the reported
    // URL below is the decoded one, which is a second observation that this check reads the
    // document the way the device does rather than scanning the raw string.
    val problems = MimeAgreement.disagreements(
      didl("http-get:*:audio/mpeg:DLNA.ORG_OP=01", "https://nav.example/rest/stream?id=7&amp;format=raw"),
      "audio/mpeg",
    )

    assertThat(problems).hasSize(1)
    assertThat(problems.single()).contains("extension")
    assertThat(problems.single()).contains("id=7&format=raw")
  }

  @Test
  fun `an extension this client never serves is reported rather than assumed to be mp3`() {
    // `.opus` must read as "I cannot tell what that renderer will conclude", never as the
    // fallback -- otherwise the one URL suffix spec section 4 forbids would agree with everything.
    val problems = MimeAgreement.disagreements(
      didl("http-get:*:audio/mpeg:DLNA.ORG_OP=01", "http://10.0.0.2:8080/media/9f2a.opus"),
      "audio/mpeg",
    )

    assertThat(problems).hasSize(1)
    assertThat(problems.single()).contains("opus")
  }

  @Test
  fun `a content-type carrying parameters still agrees`() {
    val problems = MimeAgreement.disagreements(
      didl("http-get:*:audio/mp4:DLNA.ORG_OP=01", "http://10.0.0.2:8080/media/9f2a.m4b"),
      "Audio/MP4; codecs=\"mp4a.40.2\"",
    )

    assertThat(problems).isEmpty()
  }

  @Test
  fun `a missing content-type is reported, and so is a blank one`() {
    val document = didl("http-get:*:audio/mpeg:DLNA.ORG_OP=01", "http://10.0.0.2:8080/media/9f2a.mp3")

    assertThat(MimeAgreement.disagreements(document, null)).hasSize(1)
    assertThat(MimeAgreement.disagreements(document, "   ")).hasSize(1)
    assertThat(MimeAgreement.disagreements(document, null).single()).contains("Content-Type")
  }

  @Test
  fun `a document with no res element, or an unreadable one, is reported`() {
    val noRes = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
      "<item id=\"x\" parentID=\"0\" restricted=\"1\"><dc:title>t</dc:title></item></DIDL-Lite>"

    assertThat(MimeAgreement.disagreements(noRes, "audio/mpeg")).hasSize(1)
    assertThat(MimeAgreement.disagreements("not xml at all", "audio/mpeg")).hasSize(1)
    // A `DOCTYPE` is refused in code, before the parser sees it -- the same rule
    // `SoapEnvelope.bodyOf` and `DeviceDescription.parse` apply, for the same reason: a device can
    // echo a metadata document back (`GetMediaInfo`), and a DOCTYPE is how an XML parser is talked
    // into reading a local file.
    val doctype = "<!DOCTYPE DIDL-Lite SYSTEM \"file:///etc/passwd\">" +
      didl("http-get:*:audio/mpeg:DLNA.ORG_OP=01", "http://10.0.0.2:8080/media/9f2a.mp3")
    assertThat(MimeAgreement.disagreements(doctype, "audio/mpeg")).hasSize(1)
  }

  @Test
  fun `a res element with no protocolInfo is reported`() {
    val document = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
      "<item id=\"x\" parentID=\"0\" restricted=\"1\">" +
      "<res duration=\"0:05:00.000\">http://10.0.0.2:8080/media/9f2a.mp3</res>" +
      "</item></DIDL-Lite>"

    assertThat(MimeAgreement.disagreements(document, "audio/mpeg").single()).contains("protocolInfo")
  }

  @Test
  fun `mimeOfProtocolInfo reads the third field and nothing else`() {
    // Two observations, so it cannot be a constant, and one that is not a MIME position at all.
    assertThat(MimeAgreement.mimeOfProtocolInfo("http-get:*:audio/flac:DLNA.ORG_OP=01")).isEqualTo("audio/flac")
    assertThat(MimeAgreement.mimeOfProtocolInfo("http-get:*:audio/x-ms-wma:*")).isEqualTo("audio/x-ms-wma")
    assertThat(MimeAgreement.mimeOfProtocolInfo("http-get:*:audio/mpeg")).isNull()
    assertThat(MimeAgreement.mimeOfProtocolInfo("http-get:*::DLNA.ORG_OP=01")).isNull()
    assertThat(MimeAgreement.mimeOfProtocolInfo("")).isNull()
  }

  @Test
  fun `extensionOfUrl takes the path's own suffix, not the host's dots or the query's`() {
    assertThat(MimeAgreement.extensionOfUrl("http://10.0.0.2:8080/media/9f2a.mp3")).isEqualTo("mp3")
    assertThat(MimeAgreement.extensionOfUrl("http://10.0.0.2:8080/media/9f2a.FLAC")).isEqualTo("flac")
    // A query and a fragment are not part of the path, and a `.mp3` inside either must not count.
    assertThat(MimeAgreement.extensionOfUrl("http://h/media/9f2a.m4b?next=b.mp3#c.mp3")).isEqualTo("m4b")
    // A dotted host with no path at all must not be read as an extension of "example".
    assertThat(MimeAgreement.extensionOfUrl("http://host.example")).isNull()
    assertThat(MimeAgreement.extensionOfUrl("http://h/rest/stream")).isNull()
    assertThat(MimeAgreement.extensionOfUrl("http://h/media/trailing.")).isNull()
    assertThat(MimeAgreement.extensionOfUrl("not a url")).isNull()
    assertThat(MimeAgreement.extensionOfUrl("mailto:someone@example.com")).isNull()
  }

  @Test
  fun `mimeOfContentType drops parameters, case and absence`() {
    assertThat(MimeAgreement.mimeOfContentType("audio/mpeg")).isEqualTo("audio/mpeg")
    assertThat(MimeAgreement.mimeOfContentType("Audio/MP4; codecs=\"mp4a.40.2\"")).isEqualTo("audio/mp4")
    assertThat(MimeAgreement.mimeOfContentType(null)).isNull()
    assertThat(MimeAgreement.mimeOfContentType("  ")).isNull()
  }

  /**
   * The refusal is an `IOException`, as `MalformedSoapRequestException` is, so a caller's
   * `catch (e: IOException)` around a cast operation sees it. `CastHttpClient` throws
   * `IllegalArgumentException` for bytes of the same kind, and that difference is why this is
   * stated as an assertion rather than as a comment.
   */
  @Test
  fun `require refuses a disagreement as an IOException naming every leg`() {
    val document = didl("http-get:*:audio/ogg:DLNA.ORG_OP=01", "http://10.0.0.2:8080/media/9f2a.opus")

    assertThatThrownBy { MimeAgreement.require(document, "audio/mpeg") }
      .isInstanceOf(MimeDisagreementException::class.java)
      .isInstanceOf(IOException::class.java)
      .hasMessageContaining("opus")
      .hasMessageContaining("audio/ogg")

    // ...and it says nothing at all when they agree, which is the half a `refuse()` helper hides.
    val served = ServedMedia.of("mp3", StreamFormat.Raw)
    MimeAgreement.require(didl(served.protocolInfo, "http://h/m/${served.fileName("a")}"), served.mimeType)
  }
}
