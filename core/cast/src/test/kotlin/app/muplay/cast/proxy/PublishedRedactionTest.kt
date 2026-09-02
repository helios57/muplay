package app.muplay.cast.proxy

import app.muplay.cast.didl.ServedMedia
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

  private companion object {
    const val SECRET_TOKEN = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
  }
}
