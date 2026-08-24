package app.muplay.network

import app.muplay.model.AlbumListType
import app.muplay.model.SubsonicCredentials
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Real HTTP over a real socket, through the real Retrofit + kotlinx.serialization stack, against
 * bodies captured from a real Navidrome — and, for every command, **an assertion on the request
 * this client actually sent**.
 *
 * That last part is why this class exists rather than a handful of response-mapping tests. Plan
 * 1's final review proved by mutation that `SubsonicAuth.authParams()` returning an empty map —
 * no credentials on the wire at all — left every test green at 100% branch coverage, because
 * nothing anywhere inspected a request. Coverage measures execution, not assertion. Six new
 * commands land here, and `musicFolderId` is not a nice-to-have parameter: it is the only
 * mechanism library-scoped shuffle has, and it is a *request* parameter. Omit it, mistype it, or
 * let it arrive empty and every response still parses, every mapping test still passes, and the
 * user's music shuffle quietly starts playing audiobook chapters.
 *
 * Protocol constants (`v`, `c`) are asserted as **literals**, never through
 * `SubsonicAuth.PROTOCOL_VERSION`/`CLIENT_NAME`: they are imposed from outside this codebase, and
 * reading them from the constant under test would let a change to that constant pass unnoticed.
 * `t` is recomputed here from the salt actually sent, so the assertion is on the bytes on the
 * wire rather than on `authParams()` agreeing with itself. This duplicates
 * `SubsonicClientTest`'s helper on purpose — sharing it would let one edit weaken both.
 */
class BrowseEndpointsTest {

  private lateinit var server: MockWebServer
  private lateinit var client: SubsonicClient

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    server.start()
    client = SubsonicClient(SubsonicCredentials(server.url("/").toString(), "alice", "sesame"))
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  // --- getAlbumList2 -------------------------------------------------------------------------

  @Test
  fun `getAlbumList2 sends the scope, the type, the page and full authentication`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    client.getAlbumList2(musicFolderId = 1, type = AlbumListType.ALPHABETICAL_BY_NAME, size = 500, offset = 0)

    val url = assertAuthenticatedRequestTo("/rest/getAlbumList2")
    // The scope, on the wire, as a plain decimal integer. Navidrome silently ignores a
    // musicFolderId it cannot parse and widens the response to every library, so "" or "abc"
    // here would be a scope leak that no response assertion could ever catch.
    assertThat(url.queryParameter("musicFolderId")).isEqualTo("1")
    assertThat(url.queryParameter("type")).isEqualTo("alphabeticalByName")
    assertThat(url.queryParameter("size")).isEqualTo("500")
    assertThat(url.queryParameter("offset")).isEqualTo("0")
  }

  @Test
  fun `getAlbumList2 stamps every album with the library it was scoped to`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    val albums = client.getAlbumList2(2, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0)

    // No Subsonic response carries a library id -- AlbumID3 has no such property and Navidrome
    // sends none. The only truthful source is the request's own scope, so this asserts the
    // stamp came from the argument (2) and not from anything in the body (which was captured
    // from library 1).
    assertThat(albums).isNotEmpty
    assertThat(albums).allMatch { it.libraryId == 2 }
  }

  @Test
  fun `getAlbumList2 maps the captured album fields`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    val album = client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).single()

    assertThat(album.name).isEqualTo("Test Album")
    assertThat(album.artistName).isEqualTo("Test Artist")
    assertThat(album.songCount).isEqualTo(3)
    assertThat(album.durationSeconds).isEqualTo(15)
    assertThat(album.id).isNotBlank
    assertThat(album.artistId).isNotBlank
    assertThat(album.coverArtId).isNotBlank
  }

  @Test
  fun `an albumList2 container with no album key maps to no albums, not to a failure`() = runTest {
    // Captured live from a past-the-end offset: `"albumList2": {}`. The reconcile paging loop in
    // the sync engine terminates on exactly this, so mapping it to an error would make a full
    // library sync impossible rather than merely wrong.
    enqueue(fixture(ALBUM_LIST_EMPTY_FIXTURE))

    assertThat(client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, 500, 99)).isEmpty()
  }

  @Test
  fun `a successful getAlbumList2 with no albumList2 payload at all maps to no albums`() = runTest {
    // The other half of the flattened envelope's asymmetric mapping rule, and the half the
    // `"albumList2": {}` capture cannot reach: there the container is present and only `album` is
    // absent, so `body.albumList2?.album` never takes its null branch. Here the whole payload
    // field is missing. A list-shaped payload maps that to "no results" -- unlike `getAlbum` and
    // `getScanStatus` below, whose payload *is* the entire answer and which throw instead.
    enqueue(OK_WITH_NO_PAYLOAD)

    assertThat(client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0)).isEmpty()
  }

  @Test
  fun `getAlbumList2 clamps its page size to the protocol maximum`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, size = 5_000, offset = 0)

    // Subsonic caps this at 500 and silently truncates, so a caller that asked for 5000 and
    // believed it would page straight past 4500 albums. Clamping in the client makes the number
    // on the wire and the number the caller reasons about the same one.
    assertThat(nextRequest().url.queryParameter("size")).isEqualTo("500")
  }

  // --- getAlbum ------------------------------------------------------------------------------

  @Test
  fun `getAlbum sends only the album id and must not send a scope`() = runTest {
    enqueue(fixture(ALBUM_WITH_SONGS_FIXTURE))

    client.getAlbum(albumId = "abc123", musicFolderId = 1)

    val url = assertAuthenticatedRequestTo("/rest/getAlbum")
    assertThat(url.queryParameter("id")).isEqualTo("abc123")
    // The spec gives getAlbum exactly one parameter. `musicFolderId` here is a *stamping*
    // argument -- the library the caller already scoped by -- and sending it would be inventing
    // a parameter the endpoint does not define.
    assertThat(url.queryParameter("musicFolderId")).isNull()
  }

  @Test
  fun `getAlbum maps the album and its songs and stamps both with the library`() = runTest {
    enqueue(fixture(ALBUM_WITH_SONGS_FIXTURE))

    val result = client.getAlbum("abc123", musicFolderId = 7)

    assertThat(result.album.libraryId).isEqualTo(7)
    assertThat(result.album.name).isEqualTo("Test Album")
    assertThat(result.songs).hasSize(3)
    assertThat(result.songs).allMatch { it.libraryId == 7 }
    assertThat(result.songs.map { it.title })
      .containsExactlyInAnyOrder("Track 1", "Track 2", "Track 3")
    assertThat(result.songs.map { it.trackNumber }).containsExactlyInAnyOrder(1, 2, 3)
    assertThat(result.songs).allMatch { it.suffix == "mp3" }
  }

  @Test
  fun `a successful getAlbum with no album payload is malformed, not empty`() = runTest {
    // `SubsonicResponseBody` is one flattened envelope, so every payload field is nullable and a
    // missing one decodes silently. For a list-shaped payload that means "no results"; for
    // getAlbum it means the server said "ok" and told us nothing, and mapping that to an empty
    // album is how a full reconcile deletes a real album's songs.
    enqueue(OK_WITH_NO_PAYLOAD)

    assertThat(thrownBy { client.getAlbum("abc123", 1) })
      .isInstanceOf(SubsonicMalformedResponseException::class.java)
      .hasMessageContaining("album")
  }

  // --- search3 -------------------------------------------------------------------------------

  @Test
  fun `search3 sends the query, the scope and the three counts`() = runTest {
    enqueue(fixture(SEARCH3_FIXTURE))

    client.search3(query = "tra ck", musicFolderId = 1, artistCount = 5, albumCount = 10, songCount = 20)

    val url = assertAuthenticatedRequestTo("/rest/search3")
    // Read back through HttpUrl's own decoding, so a space that must be percent-encoded on the
    // wire is asserted as the value the server will actually see.
    assertThat(url.queryParameter("query")).isEqualTo("tra ck")
    assertThat(url.queryParameter("musicFolderId")).isEqualTo("1")
    assertThat(url.queryParameter("artistCount")).isEqualTo("5")
    assertThat(url.queryParameter("albumCount")).isEqualTo("10")
    assertThat(url.queryParameter("songCount")).isEqualTo("20")
    // A raw space in a query string is not legal and OkHttp encodes it; asserting the encoded
    // form as well pins that this went out as one parameter rather than two.
    assertThat(request().url.encodedQuery).contains("query=tra%20ck")
  }

  @Test
  fun `search3 maps artists, albums and songs and stamps all three`() = runTest {
    enqueue(fixture(SEARCH3_FIXTURE))

    val results = client.search3("Test", musicFolderId = 3, artistCount = 5, albumCount = 5, songCount = 5)

    assertThat(results.artists.map { it.name }).contains("Test Artist")
    assertThat(results.albums.map { it.name }).contains("Test Album")
    assertThat(results.songs.map { it.title })
      .containsExactlyInAnyOrder("Track 1", "Track 2", "Track 3")
    assertThat(results.artists).allMatch { it.libraryId == 3 }
    assertThat(results.albums).allMatch { it.libraryId == 3 }
    assertThat(results.songs).allMatch { it.libraryId == 3 }
  }

  @Test
  fun `a successful search3 with no searchResult3 payload matches nothing`() = runTest {
    // Same rule, same reason as the `getAlbumList2` case above: a search that matched nothing is
    // a real answer, so an absent container is an empty result rather than a failure. Asserted
    // through `isEmpty` so the one piece of author-written logic on `SearchResults` is exercised
    // by the code path that can actually produce all-empty results.
    enqueue(OK_WITH_NO_PAYLOAD)

    val results = client.search3("nothing matches this", 1, 5, 5, 5)

    assertThat(results.isEmpty).isTrue
    assertThat(results.artists).isEmpty()
    assertThat(results.albums).isEmpty()
    assertThat(results.songs).isEmpty()
  }

  // --- getRandomSongs ------------------------------------------------------------------------

  @Test
  fun `getRandomSongs sends the scope and the size`() = runTest {
    enqueue(fixture(RANDOM_SONGS_FIXTURE))

    client.getRandomSongs(musicFolderId = 1, size = 50)

    val url = assertAuthenticatedRequestTo("/rest/getRandomSongs")
    assertThat(url.queryParameter("musicFolderId")).isEqualTo("1")
    assertThat(url.queryParameter("size")).isEqualTo("50")
  }

  @Test
  fun `getRandomSongs clamps size to the 500 Navidrome silently enforces`() = runTest {
    enqueue(fixture(RANDOM_SONGS_FIXTURE))

    client.getRandomSongs(musicFolderId = 1, size = 1_000)

    // Navidrome caps `size` at 500 and says nothing about it. A caller that asks for 1000 and
    // assumes it got 1000 is simply wrong; clamping here means the request and the caller's
    // model of it agree.
    assertThat(nextRequest().url.queryParameter("size")).isEqualTo("500")
  }

  @Test
  fun `getRandomSongs clamps a non-positive size up to one`() = runTest {
    enqueue(fixture(RANDOM_SONGS_FIXTURE))

    client.getRandomSongs(musicFolderId = 1, size = 0)

    // `size=0` is not a documented value and Navidrome's behaviour for it is unknown; asking for
    // one song is the smallest well-defined request, and it keeps a caller's arithmetic error
    // from turning into an undefined server-side one.
    assertThat(nextRequest().url.queryParameter("size")).isEqualTo("1")
  }

  @Test
  fun `a successful getRandomSongs with no randomSongs payload shuffles nothing`() = runTest {
    // Third and last of the list-shaped payloads. An empty shuffle is a legitimate answer -- a
    // library with no playable tracks in it -- so this must not throw; the two commands whose
    // payload is the whole answer do, and the two tests below pin that difference.
    enqueue(OK_WITH_NO_PAYLOAD)

    assertThat(client.getRandomSongs(musicFolderId = 1, size = 50)).isEmpty()
  }

  // --- getScanStatus -------------------------------------------------------------------------

  @Test
  fun `getScanStatus is a read and sends no scan-triggering parameter`() = runTest {
    enqueue(fixture(SCAN_STATUS_FIXTURE))

    client.getScanStatus()

    val url = assertAuthenticatedRequestTo("/rest/getScanStatus")
    // Tempo's getScanStatus calls startScan, re-triggering a full server scan on every poll
    // (spec section 4). This client polls this endpoint; it must never be the thing that makes
    // the server rescan. Asserting the path is `getScanStatus` and that no `fullScan` parameter
    // rides along is what keeps a future "convenience" from reintroducing it.
    assertThat(url.encodedPath).doesNotContain("startScan")
    assertThat(url.queryParameter("fullScan")).isNull()
  }

  @Test
  fun `getScanStatus maps navidrome's lastScan watermark`() = runTest {
    enqueue(fixture(SCAN_STATUS_FIXTURE))

    val status = client.getScanStatus()

    assertThat(status.isScanning).isFalse
    assertThat(status.scannedCount).isEqualTo(4)
    // An opaque token, never parsed as a date: all this client needs is "did it change".
    assertThat(status.lastScan).isNotNull
    assertThat(status.lastScan).isNotBlank
  }

  @Test
  fun `a successful getScanStatus with no scanStatus payload is malformed`() = runTest {
    enqueue(OK_WITH_NO_PAYLOAD)

    assertThat(thrownBy { client.getScanStatus() })
      .isInstanceOf(SubsonicMalformedResponseException::class.java)
      .hasMessageContaining("scanStatus")
  }

  // --- cover art -----------------------------------------------------------------------------

  @Test
  fun `the cover art url carries full authentication and the art id`() {
    val url = client.coverArtUrl("al-abc_0", sizePx = 256).toHttpUrl()

    assertThat(url.encodedPath).isEqualTo("/rest/getCoverArt")
    assertThat(url.queryParameter("id")).isEqualTo("al-abc_0")
    assertThat(url.queryParameter("size")).isEqualTo("256")
    val salt = url.queryParameter("s")
    assertThat(salt).isNotNull.matches("[0-9a-f]{16}")
    assertThat(url.queryParameter("u")).isEqualTo("alice")
    assertThat(url.queryParameter("t")).isEqualTo(md5Hex("sesame" + salt))
    assertThat(url.queryParameter("v")).isEqualTo("1.16.1")
    assertThat(url.queryParameter("c")).isEqualTo("MuPlay")
    assertThat(url.queryParameter("p")).describedAs("plaintext password parameter").isNull()
    assertThat(url.query).describedAs("query string").doesNotContain("sesame")
  }

  @Test
  fun `two cover art urls for the same art differ because the salt is fresh`() {
    // This is not a curiosity: it is the reason `:feature:library` must give Coil an explicit
    // cache key. Coil keys its memory and disk caches on the request URL by default, and a URL
    // that changes on every call can never hit either. Tempo shipped the same defect on Media3's
    // side, where the auth token was part of the cache key.
    assertThat(client.coverArtUrl("al-abc_0", null))
      .isNotEqualTo(client.coverArtUrl("al-abc_0", null))
  }

  @Test
  fun `the cover art url omits size when none is asked for`() {
    assertThat(client.coverArtUrl("al-abc_0", null).toHttpUrl().queryParameter("size")).isNull()
  }

  // --- the production factory ----------------------------------------------------------------

  @Test
  fun `the default factory builds a source that really talks to the server it was given`() = runTest {
    // `DefaultSubsonicSourceFactory` is the seam every repository from Task 4 on injects, and it
    // is one line of wiring -- which is exactly the shape of thing that is never asserted and
    // therefore never noticed when it points somewhere else. Proven by use, not by identity:
    // the source it returns is asked for a real command, and the request that reaches this
    // server is asserted to carry this server's credentials.
    enqueue(fixture(SCAN_STATUS_FIXTURE))

    val source: SubsonicSource =
      DefaultSubsonicSourceFactory.create(
        SubsonicCredentials(server.url("/").toString(), "alice", "sesame"),
      )
    val status = source.getScanStatus()

    assertThat(status.scannedCount).isEqualTo(4)
    assertAuthenticatedRequestTo("/rest/getScanStatus")
  }

  // --- shared helpers ------------------------------------------------------------------------

  private fun enqueue(body: String, code: Int = 200) {
    server.enqueue(
      MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build(),
    )
  }

  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
      .use { it.readBytes().decodeToString() }

  /** The single request this test made, asserted to carry the whole token-auth parameter set. */
  private fun assertAuthenticatedRequestTo(expectedPath: String): okhttp3.HttpUrl {
    val request = nextRequest()
    assertThat(request.method).isEqualTo("GET")
    val url = request.url
    assertThat(url.encodedPath).isEqualTo(expectedPath)

    val salt = url.queryParameter("s")
    assertThat(salt).describedAs("salt (s)").isNotNull.matches("[0-9a-f]{16}")
    assertThat(url.queryParameter("u")).isEqualTo("alice")
    assertThat(url.queryParameter("t")).isEqualTo(md5Hex("sesame" + salt))
    assertThat(url.queryParameter("v")).isEqualTo("1.16.1")
    assertThat(url.queryParameter("c")).isEqualTo("MuPlay")
    assertThat(url.queryParameter("f")).isEqualTo("json")
    assertThat(url.queryParameter("p")).describedAs("plaintext password parameter").isNull()
    assertThat(url.query).describedAs("query string").doesNotContain("sesame")
    recorded = request
    return url
  }

  private var recorded: RecordedRequest? = null

  /** The request [assertAuthenticatedRequestTo] just examined, for assertions on its raw query. */
  private fun request(): RecordedRequest = checkNotNull(recorded) { "no request examined yet" }

  /**
   * The next request the client actually sent, or a failed assertion if it sent none.
   * Deliberately not the no-argument `takeRequest` overload: that blocks forever on an empty
   * queue, so the exact regression these assertions exist to catch — a code path that stops
   * issuing a request at all — would hang the build until CI's own timeout killed it.
   */
  private fun nextRequest(): RecordedRequest {
    val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(request)
      .describedAs("a request within %d s -- the client sent none", REQUEST_TIMEOUT_SECONDS)
      .isNotNull()
    recorded = request
    return request!!
  }

  /**
   * The [Throwable] [call] threw, or a failed assertion if it returned normally.
   *
   * AssertJ's own `assertThatThrownBy` takes a `ThrowableAssert.ThrowingCallable` — a plain Java
   * functional interface, which cannot invoke a `suspend` function — so the two malformed-response
   * assertions below cannot use it directly. Running the call here, inside the test's own
   * coroutine, and handing the captured throwable to `assertThat(Throwable)` keeps every
   * downstream assertion (`isInstanceOf`, `hasMessageContaining`) exactly what it would have been.
   *
   * `requireNotNull` rather than a silent null: a command that *stops* throwing on a payload-less
   * success envelope — mapping it to an empty album instead — is precisely the regression these
   * two tests exist to catch, and it must fail loudly rather than pass an `isInstanceOf` on null.
   */
  private suspend fun thrownBy(call: suspend () -> Unit): Throwable =
    requireNotNull(runCatching { call() }.exceptionOrNull()) {
      "expected the call to fail, but it returned normally"
    }

  /** `hex(md5(utf8(input)))`, computed here rather than by calling `SubsonicAuth.token`. */
  private fun md5Hex(input: String): String =
    MessageDigest.getInstance("MD5")
      .digest(input.toByteArray(StandardCharsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte) }

  private companion object {
    const val REQUEST_TIMEOUT_SECONDS = 5L

    const val ALBUM_LIST_MUSIC_FIXTURE = "get-album-list2-music.json"
    const val ALBUM_LIST_EMPTY_FIXTURE = "get-album-list2-empty.json"
    const val ALBUM_WITH_SONGS_FIXTURE = "get-album-with-songs.json"
    const val SEARCH3_FIXTURE = "search3-music.json"
    const val RANDOM_SONGS_FIXTURE = "get-random-songs-music.json"
    const val SCAN_STATUS_FIXTURE = "get-scan-status.json"

    /**
     * A spec-valid success envelope with no command payload at all. Synthetic, and deliberately
     * so: no real Navidrome produces it. It exists to exercise the one hazard the flattened
     * `SubsonicResponseBody` introduces — a payload field that decodes to null.
     */
    const val OK_WITH_NO_PAYLOAD =
      """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",""" +
        """"serverVersion":"0.63.2 (be10f89c)","openSubsonic":true}}"""
  }
}
