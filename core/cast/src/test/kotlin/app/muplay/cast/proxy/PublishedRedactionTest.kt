package app.muplay.cast.proxy

import app.muplay.cast.didl.CastItem
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.route.CastRoute
import app.muplay.cast.route.ProxiedArtwork
import app.muplay.cast.net.CredentialQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What these types say when something prints them.
 *
 * Not a formatting test. A `data class` gets a compiler-generated `toString` that prints every
 * property, and two of these hold a Navidrome URL carrying `u`, `t` and `s` -- where `t` is
 * md5(password + salt) with the salt alongside it, so a non-expiring password equivalent for the
 * whole Subsonic API. The third holds the capability token that fetches a track's bytes.
 *
 * Printed output goes where the object never meant to: a crash dump, a debugger's variables pane, a
 * failing assertion's message. `ProxyRequest` is the sharpest case because it exists in order to be
 * *recorded*, which is exactly where a secret outlives the request that produced it.
 *
 * The assertions are written against [CredentialQuery], which is pinned to `SubsonicAuth`'s own
 * parameter names, rather than against the literal strings "u", "t" and "s". A test that spelled
 * them itself would keep passing if the auth scheme gained a fourth parameter.
 */
class PublishedRedactionTest {

  private val credentialUrl =
    "https://music.example.com/rest/stream?id=tr-1&u=listener&t=0123456789abcdef0123456789abcdef&s=abcd1234"

  @Test
  fun `a published track does not print the url that carries the password`() {
    val printed = PublishedMedia(
      token = SECRET_TOKEN,
      path = "/m/$SECRET_TOKEN.mp3",
      upstreamUrl = credentialUrl,
      served = ServedMedia("audio/mpeg", "mp3"),
    ).toString()

    assertThat(CredentialQuery.carries(printed))
      .describedAs("printed form still carries a Subsonic auth parameter: %s", printed)
      .isFalse()
    assertThat(printed).doesNotContain(SECRET_TOKEN)
    // Positive control: the redaction must not be achieved by printing nothing useful at all, or
    // this assertion would pass over an empty string.
    assertThat(printed).contains("PublishedMedia", "audio/mpeg")
  }

  @Test
  fun `a published cover does not print the url that carries the password`() {
    val printed = PublishedArtwork(
      token = SECRET_TOKEN,
      path = "/art/$SECRET_TOKEN",
      upstreamUrl = credentialUrl.replace("/stream", "/getCoverArt"),
    ).toString()

    assertThat(CredentialQuery.carries(printed)).isFalse()
    assertThat(printed).doesNotContain(SECRET_TOKEN)
    assertThat(printed).contains("PublishedArtwork")
  }

  @Test
  fun `a recorded request does not print the capability that fetched the bytes`() {
    val printed = ProxyRequest(
      method = "GET", token = SECRET_TOKEN, rangeHeader = "bytes=0-1023", status = 206,
    ).toString()

    assertThat(printed).doesNotContain(SECRET_TOKEN)
    // The parts a reader of a recorded request actually needs are still there.
    assertThat(printed).contains("GET", "bytes=0-1023", "206")
  }

  @Test
  fun `a request with no token says so rather than redacting a value it does not have`() {
    // The null branch exists so a reader can tell 'no token was presented' -- a rejected request --
    // apart from 'a token was presented and hidden'. Collapsing both to <redacted> would make the
    // record unable to answer the one question it is kept for.
    val printed = ProxyRequest(method = "GET", token = null, rangeHeader = null, status = 404).toString()

    assertThat(printed).contains("token=null")
    assertThat(printed).doesNotContain("<redacted>")
  }


  /**
   * **The route types, which carry the same strings one layer up.**
   *
   * `:core:cast` redacted what the *registry* prints and left the *route* holding the identical
   * URL. `CastRoute.Proxied.url` is `/media/<token>.<ext>` -- the token is the path, which is the
   * reason [PublishedMedia] redacts its own `path` -- and `CastRoute.RendererDirect.url` is
   * Navidrome's own stream URL with `u`, `t` and `s` on it.
   *
   * These live here rather than being caught by `ConventionTest`'s name rule because the property
   * is called `url`, and a rule that flagged every `val url` would flag nineteen innocent classes
   * and get switched off. A behaviour test can ask the question a name cannot answer: print it,
   * and see whether a credential comes out.
   */
  @Test
  fun `a proxied route does not print the capability in its url`() {
    val published = PublishedMedia(
      token = SECRET_TOKEN,
      path = "/media/$SECRET_TOKEN.mp3",
      upstreamUrl = credentialUrl,
      served = ServedMedia("audio/mpeg", "mp3"),
    )
    val printed = CastRoute.Proxied(
      url = "http://192.168.1.20:8080/media/$SECRET_TOKEN.mp3",
      media = published,
      artwork = null,
      deviceName = "Study Amp",
      proofRequired = true,
    ).toString()

    assertThat(printed).doesNotContain(SECRET_TOKEN)
    assertThat(CredentialQuery.carries(printed)).isFalse()
    // The fields that make a printed route worth having survive.
    assertThat(printed).contains("Proxied", "Study Amp", "proofRequired=true")
  }

  @Test
  fun `a renderer-direct route does not print the navidrome credentials it carries`() {
    val printed = CastRoute.RendererDirect(url = credentialUrl).toString()

    assertThat(CredentialQuery.carries(printed))
      .describedAs("printed form still carries a Subsonic auth parameter: %s", printed)
      .isFalse()
    assertThat(printed).contains("RendererDirect")
  }

  @Test
  fun `a proxied cover does not print the capability in its url`() {
    val printed = ProxiedArtwork(
      url = "http://192.168.1.20:8080/art/$SECRET_TOKEN",
      media = PublishedArtwork(
        token = SECRET_TOKEN,
        path = "/art/$SECRET_TOKEN",
        upstreamUrl = credentialUrl.replace("/stream", "/getCoverArt"),
      ),
    ).toString()

    assertThat(printed).doesNotContain(SECRET_TOKEN)
    assertThat(CredentialQuery.carries(printed)).isFalse()
  }

  /**
   * The DIDL item is where both secrets meet: its `resourceUrl` is whichever the route chose, and
   * its `artworkUri` is the cover's. `artworkUri` keeps its `null` rather than being redacted
   * unconditionally, because "was this renderer sent a cover at all" is a real question when a
   * speaker shows no art, and an absent URL is not a secret.
   */
  @Test
  fun `a cast item prints neither of the two urls it carries`() {
    val printed = CastItem(
      mediaId = "tr-1",
      title = "Track 1",
      artist = "Test Artist",
      albumTitle = "Test Album",
      artworkUri = credentialUrl.replace("/stream", "/getCoverArt"),
      durationMs = 5_000L,
      upnpClass = "object.item.audioItem.musicTrack",
      resourceUrl = "http://192.168.1.20:8080/media/$SECRET_TOKEN.mp3",
      served = ServedMedia("audio/mpeg", "mp3"),
    ).toString()

    assertThat(CredentialQuery.carries(printed)).isFalse()
    assertThat(printed).doesNotContain(SECRET_TOKEN)
    assertThat(printed).contains("CastItem", "Track 1", "Test Artist")
  }

  @Test
  fun `a cast item with no cover says so rather than redacting one it does not have`() {
    val printed = CastItem(
      mediaId = "tr-1",
      title = "Track 1",
      artist = null,
      albumTitle = null,
      artworkUri = null,
      durationMs = 5_000L,
      upnpClass = "object.item.audioItem.musicTrack",
      resourceUrl = "http://192.168.1.20:8080/media/$SECRET_TOKEN.mp3",
      served = ServedMedia("audio/mpeg", "mp3"),
    ).toString()

    assertThat(printed).contains("artworkUri=null")
  }

  private companion object {
    const val SECRET_TOKEN = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
  }
}
