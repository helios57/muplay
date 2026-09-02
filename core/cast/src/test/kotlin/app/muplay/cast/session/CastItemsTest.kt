package app.muplay.cast.session

import app.muplay.cast.didl.DidlLite
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.net.CredentialQuery
import app.muplay.model.StreamFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **The field mapping, with two disjoint observations of every value.**
 *
 * A mapping of nine fields is where this project has shipped the same defect repeatedly: one field
 * hardcoded, or copied from its neighbour, passing a suite that only ever looked at one example.
 * So there are two examples here and they differ in **every** field -- including the two that are
 * not copies, the `upnp:class` decision and the duration normalisation, which get a case each of
 * their own.
 */
class CastItemsTest {

  @Test
  fun `every field of the cast item comes from the source`() {
    val item = CastItems.of(
      MUSIC,
      resourceUrl = "http://10.0.0.2:8080/media/abc.mp3",
      artworkUrl = "http://10.0.0.2:8080/art/def",
    )

    assertThat(item.mediaId).isEqualTo("track-1")
    assertThat(item.title).isEqualTo("Track One")
    assertThat(item.artist).isEqualTo("An Artist")
    assertThat(item.albumTitle).isEqualTo("An Album")
    assertThat(item.durationMs).isEqualTo(300_000L)
    assertThat(item.served).isEqualTo(MP3)
    assertThat(item.upnpClass).isEqualTo(DidlLite.CLASS_MUSIC_TRACK)
    // The resource URL is the ROUTE's, never the source's: the whole point of the proxy is that
    // the renderer fetches from the phone rather than from Navidrome.
    assertThat(item.resourceUrl).isEqualTo("http://10.0.0.2:8080/media/abc.mp3")
    assertThat(item.resourceUrl).isNotEqualTo(MUSIC.upstreamUrl)
    // ...and so is the artwork URL, for exactly the same reason and with more at stake: the
    // source's own is Navidrome's `getCoverArt`, which carries the user's `u`, `t` and `s`.
    assertThat(item.artworkUri).isEqualTo("http://10.0.0.2:8080/art/def")
    assertThat(item.artworkUri).isNotEqualTo(MUSIC.artworkUri)
  }

  @Test
  fun `the source's own artwork url is never what the renderer is told to fetch`() {
    // The defect this argument exists to remove. `CastItems.of` used to copy `source.artworkUri`
    // into the item, and `DidlLite` renders that into `<upnp:albumArtURI>` -- so a Navidrome cover
    // URL, complete with a non-expiring password equivalent, was handed to every speaker on the
    // default route. A caller that passes nothing now sends no cover, rather than sending that one.
    val withoutArtwork = CastItems.of(MUSIC, resourceUrl = "http://10.0.0.2:8080/media/abc.mp3")

    assertThat(withoutArtwork.artworkUri).isNull()
  }

  @Test
  fun `a credential-bearing artwork url is dropped rather than sent`() {
    // The backstop, not the mechanism -- the mechanism is that the router hands over a proxy URL.
    // It fails closed: a missing picture is a smaller harm than a leaked password, and "drop it"
    // is the only direction a guard over a decoration may fail in.
    val leaked = CastItems.of(
      MUSIC,
      resourceUrl = "http://10.0.0.2:8080/media/abc.mp3",
      artworkUrl = MUSIC.artworkUri,
    )

    assertThat(leaked.artworkUri).isNull()
    // ...and it is the credential that is objected to, not the host: the same origin without the
    // auth parameters is passed through, so the guard is not a blanket refusal it would be
    // tempting to switch off.
    val harmless = CastItems.of(
      MUSIC,
      resourceUrl = "http://10.0.0.2:8080/media/abc.mp3",
      artworkUrl = "http://art.example/rest/getCoverArt?id=al-1&size=512",
    )
    assertThat(harmless.artworkUri).isEqualTo("http://art.example/rest/getCoverArt?id=al-1&size=512")
  }

  @Test
  fun `a second source differs in every field`() {
    val item = CastItems.of(BOOK, resourceUrl = "http://10.0.0.2:8080/media/def.m4b")

    assertThat(item.mediaId).isEqualTo("chapter-14")
    assertThat(item.title).isEqualTo("Chapter 14")
    assertThat(item.artist).isEqualTo("A Reader")
    assertThat(item.albumTitle).isEqualTo("A Book")
    assertThat(item.artworkUri).isNull()
    assertThat(item.durationMs).isEqualTo(3_723_000L)
    assertThat(item.served).isEqualTo(M4B)
    assertThat(item.upnpClass).isEqualTo(DidlLite.CLASS_AUDIO_BOOK)
    assertThat(item.resourceUrl).isEqualTo("http://10.0.0.2:8080/media/def.m4b")
  }

  @Test
  fun `a book gets the audioBook upnp class and music gets musicTrack`() {
    // The branch in both directions, over one source varied in one field, so nothing else can be
    // what moved the answer. A renderer's display and its own library grouping read this.
    val source = MUSIC.copy(isAudiobook = false)

    assertThat(CastItems.of(source, "http://h/x.mp3").upnpClass)
      .isEqualTo(DidlLite.CLASS_MUSIC_TRACK)
    assertThat(CastItems.of(source.copy(isAudiobook = true), "http://h/x.mp3").upnpClass)
      .isEqualTo(DidlLite.CLASS_AUDIO_BOOK)
  }

  @Test
  fun `a duration this app does not know becomes zero rather than a negative sentinel`() {
    // Media3 reports an unread duration as `C.TIME_UNSET`, which is `Long.MIN_VALUE + 1`. Rendered
    // into `res@duration` as a negative clock time it makes several renderers refuse the item.
    assertThat(CastItems.of(MUSIC.copy(durationMs = TIME_UNSET), "http://h/x.mp3").durationMs)
      .isZero()
    // ...and a real duration is NOT clamped, which is what stops `coerceAtLeast` being `0`.
    assertThat(CastItems.of(MUSIC.copy(durationMs = 1L), "http://h/x.mp3").durationMs)
      .isEqualTo(1L)
  }

  @Test
  fun `a source does not print its credential-bearing upstream url`() {
    // A `data class` prints every field, and a `CastSource` is held in a list inside a live
    // session -- the shape that reaches a log line, a crash report or an assertion message by
    // accident. The Subsonic `t` and `s` parameters are a password equivalent.
    val printed = MUSIC.copy(upstreamUrl = "https://nav.example/rest/stream?id=1&u=someone&t=$SECRET&s=abc")
      .toString()

    assertThat(printed).doesNotContain(SECRET)
    assertThat(printed).doesNotContain("nav.example")
    assertThat(printed).contains(CastSource.REDACTED_UPSTREAM)
    // ...and it still says which track it is, which is the reason to print it at all.
    assertThat(printed).contains("track-1")
  }

  @Test
  fun `toString prints no credential-bearing url, whichever field carries one`() {
    // `toString` used to redact `upstreamUrl` and print `artworkUri` in full -- and `artworkUri` is
    // built by the same `authParams()` call, so the redaction named one field and leaked the other.
    // Asserted through `CredentialQuery` over the whole printed string rather than by naming the
    // two fields, so a third credential-bearing field added later fails here too.
    val printed = MUSIC.toString()

    assertThat(CredentialQuery.parametersIn(printed)).isEmpty()
    assertThat(printed).doesNotContain(SECRET)
    assertThat(printed).contains("track-1")

    // A cover URL that carries no credential is still printed, because a redaction that hid
    // everything would tell a reader nothing and would be switched off the first time someone
    // needed to debug a cast.
    val harmless = MUSIC.copy(artworkUri = "http://art.example/1.jpg").toString()
    assertThat(harmless).contains("http://art.example/1.jpg")
  }

  private companion object {
    /** `androidx.media3.common.C.TIME_UNSET`, written out because this module has no Media3. */
    const val TIME_UNSET: Long = Long.MIN_VALUE + 1

    /** Not a real token. Never a real one, even as a fabrication. */
    const val SECRET = "0123456789abcdef0123456789abcdef"

    val MP3: ServedMedia = ServedMedia.of("mp3", StreamFormat.Raw)
    val M4B: ServedMedia = ServedMedia.of("m4b", StreamFormat.Raw)

    val MUSIC = CastSource(
      mediaId = "track-1",
      title = "Track One",
      artist = "An Artist",
      albumTitle = "An Album",
      // The shape `SubsonicClient.coverArtUrl` really produces: the *same* `authParams()` the
      // stream URL gets. Every fixture in this module used to carry `null` or a bare host here,
      // which is precisely why nothing in the suite could see the cover URL being handed to a
      // speaker verbatim.
      artworkUri = "https://nav.example/rest/getCoverArt?id=al-1&size=512&u=someone&t=$SECRET&s=abc",
      durationMs = 300_000L,
      isAudiobook = false,
      upstreamUrl = "https://nav.example/rest/stream?id=1&format=raw",
      served = MP3,
    )

    val BOOK = CastSource(
      mediaId = "chapter-14",
      title = "Chapter 14",
      artist = "A Reader",
      albumTitle = "A Book",
      artworkUri = null,
      durationMs = 3_723_000L,
      isAudiobook = true,
      upstreamUrl = "https://nav.example/rest/stream?id=2&format=raw",
      served = M4B,
    )
  }
}
