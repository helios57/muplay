package app.muplay.network

import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The request contract for `/rest/stream`.
 *
 * This is a URL builder, not a request, so there is no server here and none is needed: the whole
 * subject is what the string contains. It is nonetheless a *request-contract* test in this
 * project's sense — Plan 1 proved by mutation that `authParams()` returning an empty map left
 * every response assertion in the codebase green, and this URL is handed to ExoPlayer with no
 * interceptor of ours in the path, so a missing parameter here has no second chance to be added.
 *
 * Every argument is observed at **two different values**. A field observed once is satisfied by a
 * constant, and this project has shipped that defect four times.
 */
class StreamUrlTest {

  private val credentials =
    SubsonicCredentials(baseUrl = "https://music.example.com", username = "luc", password = "hunter2")

  private fun url(
    songId: String = "track-1",
    format: StreamFormat = StreamFormat.Raw,
    baseUrl: String = credentials.baseUrl,
  ): HttpUrl =
    SubsonicClient(credentials.copy(baseUrl = baseUrl)).streamUrl(songId, format).toHttpUrl()

  @Test
  fun `the path is rest slash stream with no dot-view suffix`() {
    assertThat(url().encodedPath).isEqualTo("/rest/stream")
  }

  @Test
  fun `the song id is on the url and is the one the caller asked for`() {
    // Two disjoint observations. `id` hardcoded to either value passes one of these and fails the
    // other; hardcoded to anything else fails both.
    assertThat(url(songId = "track-1").queryParameter("id")).isEqualTo("track-1")
    assertThat(url(songId = "chapter-14").queryParameter("id")).isEqualTo("chapter-14")
  }

  @Test
  fun `a raw request sends format raw and no bitrate cap`() {
    val raw = url(format = StreamFormat.Raw)

    assertThat(raw.queryParameter("format")).isEqualTo("raw")
    // Not merely "absent by accident": `format=raw` disables transcoding, so a bitrate alongside
    // it is a parameter the server ignores and a reader misinterprets.
    assertThat(raw.queryParameter("maxBitRate")).isNull()
  }

  @Test
  fun `an mp3 request sends format mp3 and the bitrate cap it was given`() {
    assertThat(url(format = StreamFormat.Mp3(96)).queryParameter("format")).isEqualTo("mp3")
    // Two observations again: `maxBitRate` hardcoded to "192" passes nothing here.
    assertThat(url(format = StreamFormat.Mp3(96)).queryParameter("maxBitRate")).isEqualTo("96")
    assertThat(url(format = StreamFormat.Mp3(320)).queryParameter("maxBitRate")).isEqualTo("320")
  }

  /**
   * `estimateContentLength=true` makes a transcoded response carry a `Content-Length` header whose
   * value is a **guess**. ExoPlayer would trust it and compute seek offsets against it, landing in
   * the wrong place with nothing reported anywhere — the silent-wrong-answer class. This client
   * never sends it, and this assertion is what stops it being added later as an improvement.
   */
  @Test
  fun `estimateContentLength is never sent`() {
    assertThat(url(format = StreamFormat.Raw).queryParameter("estimateContentLength")).isNull()
    assertThat(url(format = StreamFormat.Mp3(192)).queryParameter("estimateContentLength")).isNull()
  }

  /**
   * A stream request that asks for no offset carries no `timeOffset`.
   *
   * Task 12 adds transcoded seek and makes `timeOffset` a real parameter; this assertion is about
   * the *other* half of that — the ordinary call must not start smuggling an offset onto every URL,
   * because a `timeOffset` on a `format=raw` request is a parameter the server ignores and a reader
   * misinterprets. Pinned here rather than left unmentioned so that Task 12 has a test to extend
   * rather than a silence to fill.
   */
  @Test
  fun `a request that asks for no offset sends no timeOffset`() {
    assertThat(url().queryParameter("timeOffset")).isNull()
  }

  @Test
  fun `the token on this url is a real md5 of the password and the salt beside it`() {
    val built = url()
    val salt = checkNotNull(built.queryParameter("s"))

    // Recomputed, not compared to a literal: a `t` hardcoded to any fixed string fails this, and
    // so does one computed from the wrong inputs.
    assertThat(built.queryParameter("t")).isEqualTo(SubsonicAuth.token("hunter2", salt))
    assertThat(built.queryParameter("u")).isEqualTo("luc")
  }

  @Test
  fun `the plaintext password never appears on the url`() {
    // The whole point of token auth. Asserted on the raw string, not on a parsed parameter, so an
    // accidental `p=` or a password smuggled into any other parameter fails too.
    assertThat(url().toString()).doesNotContain("hunter2")
  }

  /**
   * Two calls for the same song produce different strings, because `authParams()` generates a
   * fresh salt every time.
   *
   * This is a **problem statement**, committed as an assertion. Anything that keys a cache on this
   * URL can never hit that cache — which is precisely the defect Tempo ships, and precisely why
   * Task 3 keys the media cache on the track id via `setCustomCacheKey`. If salt freshness ever
   * went away, this test failing is the signal to go and simplify the cache key, not to delete
   * the test.
   */
  @Test
  fun `two urls for the same song carry different salts and are therefore different strings`() {
    val first = url(songId = "track-1")
    val second = url(songId = "track-1")

    assertThat(first.queryParameter("s")).isNotEqualTo(second.queryParameter("s"))
    assertThat(first.queryParameter("t")).isNotEqualTo(second.queryParameter("t"))
    assertThat(first.toString()).isNotEqualTo(second.toString())
    // ...and the one thing that must not vary with the salt.
    assertThat(first.queryParameter("id")).isEqualTo(second.queryParameter("id"))
  }

  /**
   * `c` and `v` are fixed, not arguments — so rule 2 ("observed at one value is not tested") does
   * not apply to them, and asserting them is still worth doing: Navidrome's `LegacyClients`
   * default is `DSub` and `MinimalClients` is `SubMusic`, and a client whose `c` matches either
   * has the OpenSubsonic field block stripped from every response.
   */
  @Test
  fun `the client id and protocol version are the ones navidrome must not strip`() {
    assertThat(url().queryParameter("c")).isEqualTo("MuPlay")
    assertThat(url().queryParameter("v")).isEqualTo("1.16.1")
  }

  @Test
  fun `f json rides along and that is deliberate`() {
    // /rest/stream returns audio, so `f` is meaningless on success. On a *failure* Navidrome
    // answers with an error document, and `f=json` makes that document JSON rather than XML —
    // which is what the rest of this client already knows how to read. Sharing one `authParams()`
    // across every command is what keeps auth in one place; this assertion records that the
    // consequence was noticed rather than overlooked.
    assertThat(url().queryParameter("f")).isEqualTo("json")
  }

  @Test
  fun `a base url without a trailing slash produces the same path as one with`() {
    assertThat(url(baseUrl = "https://music.example.com").encodedPath).isEqualTo("/rest/stream")
    assertThat(url(baseUrl = "https://music.example.com/").encodedPath).isEqualTo("/rest/stream")
  }

  @Test
  fun `a base url with a sub-path keeps it`() {
    // Reverse proxies that mount Navidrome under a path are ordinary. Losing the prefix here
    // produces a 404 from the proxy that looks like a missing track.
    assertThat(url(baseUrl = "https://example.com/navidrome").encodedPath)
      .isEqualTo("/navidrome/rest/stream")
    assertThat(url(baseUrl = "https://example.com/navidrome/").encodedPath)
      .isEqualTo("/navidrome/rest/stream")
  }

  @Test
  fun `the host and scheme come from the credentials`() {
    // Two observations of a value a constant could satisfy — N-2 in `ci/mutation-probes.sh` was
    // exactly this defect on the cover-art URL.
    assertThat(url(baseUrl = "https://music.example.com").host).isEqualTo("music.example.com")
    assertThat(url(baseUrl = "http://localhost:4533").host).isEqualTo("localhost")
    assertThat(url(baseUrl = "http://localhost:4533").port).isEqualTo(4533)
    assertThat(url(baseUrl = "http://localhost:4533").scheme).isEqualTo("http")
  }

  /**
   * The scheme, observed on its own and at two values — because a URL is a *compound* value and
   * the assertions above only ever pin one component of it at a time.
   *
   * This is a security assertion, not a tidiness one. `/rest/stream` is the one URL in this
   * codebase that carries an authentication token out of this client's control: it is handed to
   * Media3, fetched by Media3's own HTTP stack with no interceptor of ours in the path, and
   * nothing downstream re-checks it. Inserting `.scheme("http")` into the builder is therefore a
   * silent HTTPS-to-cleartext downgrade of every authenticated stream request — and it was
   * measured to leave the whole JVM tier green (217 tests, 0 failures) before this test existed,
   * because the only scheme any assertion had ever observed was `http`. The live tier could not
   * see it either: `ci-navidrome-1` is plain `http://localhost:4533`.
   *
   * A release build would catch the downgrade loudly (Android blocks cleartext by default, and
   * `verifyReleaseManifest` keeps `usesCleartextTraffic` out of the release manifest), but the
   * debug/e2e variant sets it `true` for the emulator journey and would carry the token in the
   * clear. The mutation is pinned as `stream/scheme-downgrade` in `ci/mutation-probes.sh`.
   *
   * The port rides along here rather than beside the host above for the same reason: the origin
   * is three components, `stream/host-and-scheme` replaces all three at once, and it was caught
   * by the host alone.
   */
  @Test
  fun `the scheme comes from the credentials and is never downgraded to cleartext`() {
    assertThat(url(baseUrl = "https://music.example.com").scheme).isEqualTo("https")
    assertThat(url(baseUrl = "http://localhost:4533").scheme).isEqualTo("http")
    // The second observation of the port, the origin's third component: 4533 above, the scheme's
    // own default here — which a forced `http` would report as 80.
    assertThat(url(baseUrl = "https://music.example.com").port).isEqualTo(443)
  }
}
