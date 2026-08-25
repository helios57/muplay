package app.muplay.network

import app.muplay.model.AlbumListType
import app.muplay.model.LibraryRole
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.model.SubsonicEnvelope
import app.muplay.network.model.SubsonicResponseBody
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Exercises [SubsonicClient] against a real, pinned `deluan/navidrome:0.63.2` container — not a
 * fixture, not `MockWebServer`. Docker is not an emulator: the container in
 * `ci/navidrome.compose.yml` starts in 5-11s, well inside tier 1's 10-minute budget, so anything
 * whose subject is genuinely Navidrome's own behaviour belongs here, proven against the real
 * server, rather than deferred to Task 8's emulator tier or left resting on a captured-fixture
 * stand-in ([SubsonicClientTest] already covers the shape-level contract those fixtures encode).
 *
 * `@Tag("live")`: excluded from the default `test`/`testDebugUnitTest` task (see
 * `Testing.kt`'s `configureJUnit5`, which calls `excludeTags("live")` project-wide) — this class
 * needs a real container listening on `localhost:4533`, which is not true for a plain
 * `./gradlew test` in a developer's inner loop, nor in this repo's static-analysis or
 * unit+integration CI jobs. Only the dedicated `liveNavidromeTest` Gradle task (root
 * `build.gradle.kts`, registered against `:core:network` only) includes it, run by the
 * `live-navidrome` job in `.github/workflows/pr.yml` after that job starts the container via
 * `docker compose -f ci/navidrome.compose.yml up -d --wait` and runs `ci/configure-libraries.sh`.
 *
 * Credentials, port and seeded content match those two files exactly:
 * `ND_DEVAUTOCREATEADMINPASSWORD=testpass` (there is no `ND_DEFAULTADMINPASSWORD` — see
 * `ci/navidrome.compose.yml`'s own comment), port `4533`, and two libraries — "Music" and
 * "Audiobooks" — that `ci/configure-libraries.sh` wires up via Navidrome's native REST API because
 * library 1 is permanently pinned to its mount path and cannot be renamed-by-repointing or
 * deleted.
 *
 * The second test below ("... is rejected ...") is this class's proof, demanded by this tier's own
 * brief, that a green [ping success][`ping succeeds against the real container`] test is not
 * evidence on its own: run once locally with `client("testpass")` swapped in where
 * `client("not-the-real-password")` is used below (i.e. asserting success against a password this
 * container was never given) and watch it fail red against the real server before trusting the
 * version committed here — see `task-7-report.md` for that transcript.
 */
@Tag("live")
class LiveNavidromeTest {

  private val baseUrl = "http://localhost:4533"

  private fun client(password: String) =
    SubsonicClient(SubsonicCredentials(baseUrl = baseUrl, username = "admin", password = password))

  @Test
  fun `ping succeeds against the real container`() = runTest {
    val info = client("testpass").ping()

    assertThat(info.type).isEqualTo("navidrome")
    assertThat(info.isOpenSubsonic).isTrue()
  }

  @Test
  fun `ping with a wrong password is rejected by the real server`() = runTest {
    val result = runCatching { client("not-the-real-password").ping() }

    assertThat(result.isFailure).isTrue()
    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SubsonicErrorException::class.java)
    // Subsonic error code 40 ("Wrong username or password") is the real server's own answer here
    // -- not asserted against a captured fixture standing in for one, as SubsonicClientTest's
    // otherwise-identical assertion is (see PING_FAILED_FIXTURE there).
    assertThat((error as SubsonicErrorException).code).isEqualTo(40)
  }

  @Test
  fun `getMusicFolders returns both libraries configure-libraries sh wires up`() = runTest {
    val libraries = client("testpass").getMusicFolders()

    assertThat(libraries.map { it.name }).containsExactlyInAnyOrder("Music", "Audiobooks")
    // Not `allMatch`, which passes on an empty list: if `getMusicFolders` ever returned nothing,
    // the line above would fail but this one would not, and on its own it would report success.
    assertThat(libraries.map { it.role })
      .containsExactly(LibraryRole.UNASSIGNED, LibraryRole.UNASSIGNED)
  }

  // --- the scoping trap, measured against the server rather than argued from the type ----------
  //
  // `SubsonicSource` takes `musicFolderId` as a non-null `Int` because Navidrome silently ignores
  // a value it cannot parse and widens the answer to every library. Until these four tests, that
  // sentence was a *comment*: the type stops today's callers producing a bad value, but it says
  // nothing about what the server does when one arrives, and it cannot notice if Navidrome's
  // behaviour changes under us. Everything below therefore goes around `SubsonicClient` entirely
  // and issues raw HTTP -- which is also the only way to send a value the type forbids.

  @Test
  fun `a valid musicFolderId really does scope getAlbumList2 to that library`() = runTest {
    // The control. Without it the three tests after this one would be consistent with a server
    // that ignores `musicFolderId` altogether, and "widens the scope" would be unfalsifiable.
    assertThat(albumNames(scopedAlbumList(MUSIC_LIBRARY_ID.toString()))).containsExactly("Test Album")
    assertThat(albumNames(scopedAlbumList(AUDIOBOOKS_LIBRARY_ID.toString())))
      .containsExactly("Test Book")
  }

  @Test
  fun `a non-numeric or empty musicFolderId is silently ignored and widens the scope`() = runTest {
    // The trap itself. `status: "ok"`, a perfectly parseable body, and *both* libraries in it --
    // there is no runtime signal of any kind that the scope the caller asked for was discarded.
    listOf("abc", "1abc", "").forEach { ignored ->
      val body = scopedAlbumList(ignored)

      assertThat(body.status).describedAs("status for musicFolderId=%s", ignored).isEqualTo("ok")
      assertThat(body.error).describedAs("error for musicFolderId=%s", ignored).isNull()
      assertThat(albumNames(body))
        .describedAs("albums for musicFolderId=%s", ignored)
        .containsExactlyInAnyOrder("Test Album", "Test Book")
    }
  }

  @Test
  fun `an unknown but numeric musicFolderId fails closed with error 70`() = runTest {
    // The other half of the rule, and the reason the parameter can safely be an `Int`: a value
    // that *parses* is validated, so the failure mode an `Int` can still produce is a loud one.
    listOf("99", "0", "-1").forEach { unknown ->
      val body = scopedAlbumList(unknown)

      assertThat(body.status).describedAs("status for musicFolderId=%s", unknown).isEqualTo("failed")
      assertThat(body.error?.code).describedAs("error code for musicFolderId=%s", unknown).isEqualTo(70)
    }
  }

  @Test
  fun `both AlbumListType wire values are types this server implements`() = runTest {
    // `AlbumListType`'s two wire values are a protocol contract with this server, and `NEWEST` is
    // sent by nothing in the build yet -- so nothing else would notice a typo in it until a user
    // hit the browse screen. Navidrome answers an unimplemented `type` with a *failure*, which is
    // what makes this a real check rather than a spelling exercise: the assertion below is that
    // the server accepted the string, not that the string equals itself.
    AlbumListType.entries.forEach { type ->
      val body = albumList(mapOf("type" to type.wireValue, "size" to "500",
                                 "musicFolderId" to MUSIC_LIBRARY_ID.toString()))

      assertThat(body.status).describedAs("status for type=%s", type.wireValue).isEqualTo("ok")
      assertThat(body.error).describedAs("error for type=%s", type.wireValue).isNull()
    }
  }

  @Test
  fun `a misspelt album list type is rejected by the real server`() = runTest {
    // The negative control for the test above, and the measurement behind `AlbumListType`'s own
    // KDoc. Recorded because the KDoc used to claim error code 10 ("required parameter missing"):
    // the real answer is code 0, `"type 'nEwEsT_typo' not implemented"`.
    val body = albumList(mapOf("type" to "nEwEsT_typo", "size" to "500",
                               "musicFolderId" to MUSIC_LIBRARY_ID.toString()))

    assertThat(body.status).isEqualTo("failed")
    assertThat(body.error?.code).isEqualTo(0)
    assertThat(body.error?.message).contains("not implemented")
  }

  // --- library-scoped shuffle: the headline feature, proved against the real server -------------

  /**
   * Fix round 1, N-3 (LOW): the brief's own rationale for `repeat(50)` -- "over a four-item
   * corpus makes an unscoped result overwhelmingly likely to show up" -- does not hold at
   * `size = 500` against a 3-track music library: every draw returns the whole library
   * deterministically, so a scope leak shows up on draw one (confirmed directly:
   * task-7-report.md's mutation #2 failed on its first draw, not its fiftieth) and the other 49
   * add nothing to *that* assertion. Rather than shrinking the request (which would weaken the
   * "never" in this test's own name -- a smaller draw could miss a leak by chance) the fifty
   * draws are re-purposed for the property they are actually suited to prove: fix round 1, N-2
   * (MEDIUM), that the server -- and everything between it and this assertion -- is truly
   * randomising rather than returning a fixed order every time. A `.sortedBy` or any other
   * deterministic reordering inserted anywhere in that path would collapse every draw's title
   * order to the same value; `distinctDrawOrders` catches that directly, which no single draw,
   * however many times repeated, ever could.
   */
  @Test
  fun `shuffling the music library never returns the audiobook`() = runTest {
    // `ci/configure-libraries.sh` seeds library 1 "Music" with three tracks and library 2
    // "Audiobooks" with "Test Book". See this test's own doc for what the fifty draws prove.
    val client = client("testpass")
    val distinctDrawOrders = mutableSetOf<List<String>>()

    repeat(50) {
      val titles = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).map { it.title }
      assertThat(titles).isNotEmpty
      assertThat(titles).doesNotContain(AUDIOBOOK_TITLE)
      assertThat(titles).allMatch { it in MUSIC_TITLES }
      distinctDrawOrders += titles
    }

    // The property fifty draws can actually prove that one cannot: real randomisation, not fifty
    // identical draws laundered through a loop.
    assertThat(distinctDrawOrders.size)
      .describedAs("distinct draw orders across 50 draws of the same 3-track library")
      .isGreaterThan(1)
  }

  @Test
  fun `shuffling the audiobook library returns the audiobook`() = runTest {
    // The control. Without it, the test above would pass just as well against a client that
    // returned nothing at all, or against a server with an empty audiobook library -- which is
    // exactly the shape of the eleventh silent gate this project already shipped once.
    val titles = client("testpass").getRandomSongs(musicFolderId = AUDIOBOOKS_LIBRARY_ID, size = 500)
      .map { it.title }

    assertThat(titles).containsExactly(AUDIOBOOK_TITLE)
  }

  @Test
  fun `an unknown numeric library id fails closed`() = runTest {
    // Navidrome rejects a numeric id it does not know rather than widening the scope: error 70,
    // "Library 99 not found or not accessible". Pinned here because the *next* test depends on
    // this being the contrast case.
    val result = runCatching { client("testpass").getRandomSongs(musicFolderId = 99, size = 10) }

    assertThat(result.isFailure).isTrue
    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SubsonicErrorException::class.java)
    assertThat((error as SubsonicErrorException).code).isEqualTo(70)
  }

  /**
   * The trap that makes `musicFolderId` a non-null `Int` everywhere in this codebase, pinned
   * against the real server.
   *
   * A `musicFolderId` Navidrome cannot parse is **silently ignored** — `status: "ok"`, and the
   * response covers every library. This test deliberately bypasses [SubsonicClient], because
   * [SubsonicClient] is built so that this cannot be expressed: it takes an `Int`. The raw
   * request below is the only way to reach the behaviour, and the assertion is what stops anyone
   * "simplifying" that `Int` to a `String` or an `Int?` on the grounds that the server validates
   * its input. It does not.
   */
  @Test
  fun `a non-numeric library id is ignored and silently widens the scope`() = runTest {
    listOf("", "abc", "1abc").forEach { malformed ->
      val titles = rawRandomSongTitles(malformed)

      assertThat(titles).describedAs("musicFolderId='%s'", malformed).contains(AUDIOBOOK_TITLE)
      assertThat(titles).describedAs("musicFolderId='%s'", malformed).containsAll(MUSIC_TITLES)
    }
  }

  @Test
  fun `search3 is scoped by the same mechanism and the same trap`() = runTest {
    val client = client("testpass")

    assertThat(client.search3("Test", MUSIC_LIBRARY_ID, 10, 10, 10).songs.map { it.title })
      .containsExactlyInAnyOrderElementsOf(MUSIC_TITLES)
    assertThat(client.search3("Test", AUDIOBOOKS_LIBRARY_ID, 10, 10, 10).songs.map { it.title })
      .containsExactly(AUDIOBOOK_TITLE)
  }

  @Test
  fun `getAlbumList2 is scoped and pages`() = runTest {
    val client = client("testpass")

    assertThat(client.getAlbumList2(MUSIC_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).map { it.name })
      .containsExactly("Test Album")
    assertThat(client.getAlbumList2(AUDIOBOOKS_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).map { it.name })
      .containsExactly("Test Book")
    // The paging loop's termination condition, against the real server: past the end is an empty
    // list, not an error.
    assertThat(client.getAlbumList2(MUSIC_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 99))
      .isEmpty()
  }

  @Test
  fun `getAlbum returns the album's tracks and stamps the library the caller scoped by`() = runTest {
    val client = client("testpass")
    val album = client.getAlbumList2(MUSIC_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).single()

    val withSongs = client.getAlbum(album.id, MUSIC_LIBRARY_ID)

    assertThat(withSongs.songs.map { it.title }).containsExactlyInAnyOrderElementsOf(MUSIC_TITLES)
    assertThat(withSongs.songs).allMatch { it.libraryId == MUSIC_LIBRARY_ID }
  }

  @Test
  fun `the real server reports every song as type music including the audiobook`() = runTest {
    // Spec section 4's central claim, asserted rather than quoted: Navidrome hardcodes
    // child.Type = "music" for every media file, so no response can ever tell a client that
    // something is an audiobook. This is why LibraryRole is an out-of-band, user-made decision
    // and why the mirror stamps a library id on every row.
    //
    // Asserted through the raw JSON because `Song` deliberately does not model `type` -- reading
    // a field that is always the same constant would be reading nothing.
    val body = rawRest("getRandomSongs", mapOf("size" to "500", "musicFolderId" to AUDIOBOOKS_LIBRARY_ID.toString()))

    assertThat(body).contains(AUDIOBOOK_TITLE)
    assertThat(body).contains("\"type\":\"music\"")
    assertThat(body).doesNotContain("\"audiobook\"")
  }

  @Test
  fun `getScanStatus reports a lastScan watermark and does not trigger a scan`() = runTest {
    val client = client("testpass")

    val first = client.getScanStatus()
    assertThat(first.isScanning).isFalse
    assertThat(first.lastScan).isNotNull.isNotBlank
    assertThat(first.scannedCount).isEqualTo(SEEDED_TRACK_COUNT)

    // Tempo's getScanStatus calls startScan, re-scanning the whole server on every poll. If this
    // client did the same, the second call would find a scan running (or a moved watermark).
    val second = client.getScanStatus()
    assertThat(second.isScanning).isFalse
    assertThat(second.lastScan).isEqualTo(first.lastScan)
  }

  // --- /rest/stream: the URL Media3 fetches, and the server behaviour it rests on --------------

  /**
   * The precondition the whole streaming design rests on: a raw response is a plain, seekable,
   * length-declared HTTP body.
   *
   * `assertThat(bytes).hasSizeGreaterThan(1000)` is not decoration. Without it this test passes
   * against a server that answers 200 with an empty body — the same vacuity that let a
   * live-Navidrome suite pass with no Navidrome running.
   */
  @Test
  fun `a raw stream is a 200 with an accurate content length and byte ranges`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()

    val (response, bytes) = fetch(client.streamUrl(song.id, StreamFormat.Raw))

    assertThat(response.code).isEqualTo(200)
    assertThat(bytes.size).isGreaterThan(1000)
    assertThat(response.header("Content-Length")?.toLong()).isEqualTo(bytes.size.toLong())
    assertThat(response.header("Accept-Ranges")).isEqualTo("bytes")
    // Chunked would mean no Content-Length, and no Content-Length means no seek.
    assertThat(response.header("Transfer-Encoding")).isNull()
    assertThat(response.header("Content-Type")).startsWith("audio/")
  }

  @Test
  fun `a range request on a raw stream is a byte-exact 206`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()
    val url = client.streamUrl(song.id, StreamFormat.Raw)
    val (_, whole) = fetch(url)
    val offset = whole.size / 2

    val (response, tail) = fetch(url, range = "bytes=$offset-")

    assertThat(response.code).isEqualTo(206)
    assertThat(response.header("Content-Range"))
      .isEqualTo("bytes $offset-${whole.size - 1}/${whole.size}")
    // Byte-exact, not merely "the right length": a server that answered 206 with the *start* of
    // the file would pass a length check and produce audio that jumps back on every seek.
    assertThat(tail).isEqualTo(whole.copyOfRange(offset, whole.size))
  }

  @Test
  fun `a range past the end of a raw stream is 416`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()
    val url = client.streamUrl(song.id, StreamFormat.Raw)
    val (_, whole) = fetch(url)

    val (response, _) = fetch(url, range = "bytes=${whole.size + 1000}-")

    assertThat(response.code).isEqualTo(416)
  }

  /**
   * The other half of the raw preference, and the reason it is a preference at all: **a transcode
   * the server is producing right now cannot be seeked.**
   *
   * This test was written against a fixed `Mp3(32)` and was flaky, which is how the real
   * behaviour got measured. Navidrome keeps a *transcoding cache* (`ND_TRANSCODINGCACHESIZE`,
   * 100MB by default, and this compose file mounts no volume over `/data`, so it lives in the
   * container's writable layer for as long as the container runs). The first request for a given
   * (track, requested bitrate) is streamed live — chunked, `Accept-Ranges: none`, **no**
   * `Content-Length`, so no seek. Every request after it is served out of that cache as an
   * ordinary file: `Accept-Ranges: bytes`, an accurate `Content-Length`, and a `Range` answered
   * with a 206. A fixed bitrate therefore passes on a fresh container and fails on a warm one —
   * which is exactly the CI-green/local-red shape this project keeps finding.
   *
   * So the *search* below is setup, not assertion: it looks for a (track, bitrate) this container
   * has not produced before. Every assertion afterwards is unconditional, and the second half —
   * the cache hit — is asserted too, because it is the thing that made the naive version flaky
   * and a reader who does not know about it will write the naive version again.
   *
   * Bitrates below the fixtures' own encoding only: see
   * [`an mp3 cap at or above the source bitrate is not a transcode at all`], which pins the
   * server behaviour that makes higher caps useless here.
   */
  @Test
  fun `a live transcode returns no content length and refuses ranges`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()
    val (_, raw) = fetch(client.streamUrl(song.id, StreamFormat.Raw))

    val cold = coldTranscode(client, song.id)

    assertThat(cold.response.code).isEqualTo(200)
    assertThat(cold.bytes.size).isGreaterThan(1000)
    assertThat(cold.response.header("Accept-Ranges")).isEqualTo("none")
    assertThat(cold.response.header("Content-Length")).isNull()
    // A real transcode rather than the source passed through: the cap is below the fixture's own
    // bitrate, so the body has to be smaller. Without this the test would pass against a server
    // that answered every `format=mp3` with the original file.
    assertThat(cold.bytes.size).isLessThan(raw.size)

    // ...and the same URL fetched again is a cache hit, which *is* seekable. This is the half that
    // makes a fixed-bitrate version of this test flaky, so it is asserted rather than described.
    val (cached, cachedBytes) = fetch(cold.url)
    assertThat(cached.header("Accept-Ranges")).isEqualTo("bytes")
    assertThat(cached.header("Content-Length")?.toLong()).isEqualTo(cachedBytes.size.toLong())
    assertThat(cachedBytes).isEqualTo(cold.bytes)
  }

  /**
   * `format=mp3` is a *cap*, not an instruction: Navidrome serves the source file untouched when
   * the requested format already matches the file's own suffix and the cap is at or above its
   * bitrate.
   *
   * Measured here rather than assumed, because it bounds what [StreamFormat.Mp3] can promise: on
   * an `mp3` source, `Mp3(192)` is not a transcode at all. It does **not** weaken the "never
   * Opus" rule — an Opus source's suffix is not `mp3`, so `format=mp3` always transcodes it —
   * and that distinction is exactly why this is worth pinning where someone will read it.
   */
  @Test
  fun `an mp3 cap at or above the source bitrate is not a transcode at all`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()

    val (_, raw) = fetch(client.streamUrl(song.id, StreamFormat.Raw))
    val (response, capped) = fetch(client.streamUrl(song.id, StreamFormat.Mp3(320)))

    assertThat(response.code).isEqualTo(200)
    assertThat(raw.size).isGreaterThan(1000)
    // Byte-identical, not merely the same length: this is the source file, not a re-encode that
    // happens to land on the same size.
    assertThat(capped).isEqualTo(raw)
    assertThat(response.header("Accept-Ranges")).isEqualTo("bytes")
  }

  /**
   * The URL authenticates itself, which is the only reason handing it to ExoPlayer works at all.
   *
   * Asserted by *removing* the credentials and checking the audio does not come back, rather than
   * by checking a status code: Navidrome answers a `/rest/stream` auth failure with an error
   * document, and this assertion holds whether that arrives as 200-plus-JSON or as a 4xx. The
   * status code is attached to the failure message so the real behaviour is recorded either way.
   */
  @Test
  fun `stripping the credentials from a stream url stops the audio`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()
    val authenticated = client.streamUrl(song.id, StreamFormat.Raw).toHttpUrl()
    val stripped = authenticated.newBuilder().removeAllQueryParameters("t")
      .removeAllQueryParameters("s").removeAllQueryParameters("u").build()

    val (authorisedResponse, audio) = fetch(authenticated.toString())
    val (response, body) = fetch(stripped.toString())

    assertThat(authorisedResponse.code).isEqualTo(200)
    assertThat(response.header("Content-Type"))
      .describedAs("unauthenticated /rest/stream answered %s", response.code)
      .doesNotContain("audio/")
    assertThat(body).isNotEqualTo(audio)
  }

  /**
   * The audiobook streams raw too, and the assertion is on its actual container bytes rather than
   * on a header: every ISO-BMFF file (`.m4b`, `.m4a`, `.mp4`) begins with a four-byte size
   * followed by the literal `ftyp`. A server returning an error page, silence, or the wrong file
   * fails this; a `Content-Type` check would not.
   */
  @Test
  fun `the audiobook streams raw as an mp4 container`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = AUDIOBOOKS_LIBRARY_ID, size = 500).single()

    val (response, bytes) = fetch(client.streamUrl(song.id, StreamFormat.Raw))

    assertThat(response.code).isEqualTo(200)
    assertThat(bytes.size).isGreaterThan(1000)
    assertThat(String(bytes.copyOfRange(4, 8), Charsets.US_ASCII)).isEqualTo("ftyp")
  }

  // --- raw Subsonic, deliberately not through SubsonicClient -----------------------------------

  private fun scopedAlbumList(musicFolderId: String): SubsonicResponseBody =
    albumList(
      mapOf(
        "type" to AlbumListType.ALPHABETICAL_BY_NAME.wireValue,
        "size" to "500",
        "musicFolderId" to musicFolderId,
      ),
    )

  private fun albumList(params: Map<String, String>): SubsonicResponseBody =
    rawCommand("getAlbumList2", params)

  private fun albumNames(body: SubsonicResponseBody): List<String> =
    body.albumList2?.album.orEmpty().map { it.name }

  /**
   * A `getRandomSongs` request built by hand, so a `musicFolderId` that [SubsonicClient]'s own
   * `Int` parameter makes unrepresentable can still be sent to the real server. Used only to pin
   * the server's silent-widening behaviour.
   */
  private fun rawRandomSongTitles(musicFolderId: String): List<String> {
    val body = rawRest("getRandomSongs", mapOf("size" to "500", "musicFolderId" to musicFolderId))
    return Regex(""""title":"([^"]*)"""").findAll(body).map { it.groupValues[1] }.toList()
  }

  /** A `/rest/stream` transcode this container had not produced before, and its first response. */
  private class ColdTranscode(val url: String, val response: Response, val bytes: ByteArray)

  /**
   * The first `format=mp3` request for [songId] that this container answers with a **live**
   * transcode rather than out of its transcoding cache, together with that response.
   *
   * Setup for [`a live transcode returns no content length and refuses ranges`], not an assertion:
   * the cache is keyed on the bitrate as *requested* (24, 25 and 26 all encode to the same bytes
   * and still occupy three separate cache entries — measured), so a bitrate this container has
   * not seen is a cache miss. Shuffled rather than scanned in order so that repeated runs against
   * one long-lived container spread over the range instead of walking it from the bottom.
   */
  private fun coldTranscode(client: SubsonicClient, songId: String): ColdTranscode {
    (1 until FIXTURE_BITRATE_KBPS).shuffled().forEach { kbps ->
      val url = client.streamUrl(songId, StreamFormat.Mp3(kbps))
      val (response, bytes) = fetch(url)
      if (response.header("Accept-Ranges") == "none") return ColdTranscode(url, response, bytes)
    }
    return fail(
      "no bitrate below $FIXTURE_BITRATE_KBPS kbps produced a live transcode of $songId. Either " +
        "this container has already cached every one of them (recreate it: the transcoding cache " +
        "lives in the container's writable layer), or Navidrome no longer streams a first-time " +
        "transcode unseekably — in which case go and simplify the format policy, because the " +
        "reason it prefers raw has changed.",
    )
  }

  /**
   * One raw HTTP GET of [url], returning the response and its whole body.
   *
   * A plain `OkHttpClient`, not [SubsonicClient]'s Retrofit stack, on purpose: the subject of
   * every stream test above is what an *arbitrary* HTTP client sees when handed a stream URL,
   * because that is exactly what Media3 is.
   */
  private fun fetch(url: String, range: String? = null): Pair<Response, ByteArray> {
    val request = Request.Builder().url(url).apply {
      if (range != null) header("Range", range)
    }.build()
    return OkHttpClient().newCall(request).execute().use { response ->
      // The body must be read before `use` closes the response. Returning the `Response`
      // afterwards is safe because every assertion above reads only its status line and headers.
      response to response.body.bytes()
    }
  }

  /** One raw Subsonic GET, authenticated exactly as the client authenticates, returning the body. */
  private fun rawRest(command: String, params: Map<String, String>): String {
    val salt = "0123456789abcdef"
    val auth = SubsonicAuth.authParams(
      SubsonicCredentials(baseUrl = baseUrl, username = "admin", password = "testpass"),
      salt,
    )
    val url = "$baseUrl/rest/$command".toHttpUrl().newBuilder().apply {
      (auth + params).forEach { (name, value) -> addQueryParameter(name, value) }
    }.build()
    return OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
      .use { checkNotNull(it.body).string() }
  }

  /**
   * One Subsonic GET, built and authenticated **here** rather than through [SubsonicClient].
   *
   * Two reasons, both load-bearing. First, the values these tests must send — `"abc"`, `""` — are
   * exactly the ones `SubsonicSource`'s `Int` parameter makes unrepresentable, so there is no way
   * to ask the question through the client. Second, the subject of every assertion above is *the
   * server*: routing them through our own auth and our own mappers would let a change in this
   * codebase re-colour a measurement of Navidrome's behaviour. The token is computed from
   * [MessageDigest] and `v`/`c`/`f` are literals, for the same reason `BrowseEndpointsTest` does
   * not read them from `SubsonicAuth`.
   *
   * Only the response *DTOs* are shared, and only as a JSON reader — no client logic runs.
   */
  private fun rawCommand(command: String, params: Map<String, String>): SubsonicResponseBody {
    val salt = "0123456789abcdef"
    val url = "$baseUrl/rest/$command".toHttpUrl().newBuilder()
      .addQueryParameter("u", "admin")
      .addQueryParameter("t", md5Hex("testpass" + salt))
      .addQueryParameter("s", salt)
      .addQueryParameter("v", "1.16.1")
      .addQueryParameter("c", "MuPlay")
      .addQueryParameter("f", "json")
      .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
      .build()

    val body = OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
      assertThat(response.code).describedAs("HTTP status for %s", url.encodedPath).isEqualTo(200)
      checkNotNull(response.body) { "empty body from $url" }.string()
    }
    return json.decodeFromString<SubsonicEnvelope>(body).subsonicResponse
  }

  private fun md5Hex(input: String): String =
    MessageDigest.getInstance("MD5")
      .digest(input.toByteArray(StandardCharsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte) }

  private companion object {
    /** The ids `ci/configure-libraries.sh` produces: library 1 is "Music", library 2 "Audiobooks". */
    const val MUSIC_LIBRARY_ID = 1
    const val AUDIOBOOKS_LIBRARY_ID = 2
    const val AUDIOBOOK_TITLE = "Test Book"
    val MUSIC_TITLES = listOf("Track 1", "Track 2", "Track 3")
    /** Three mp3s plus one m4b — ci/seed-fixtures.sh, and the count configure-libraries.sh waits for. */
    const val SEEDED_TRACK_COUNT = 4

    /**
     * The bitrate `ci/seed-fixtures.sh` encodes the music fixtures at. Requesting `format=mp3` at
     * or above it gets the source file back untouched — pinned by
     * [`an mp3 cap at or above the source bitrate is not a transcode at all`] — so only caps below
     * it make the server transcode.
     */
    const val FIXTURE_BITRATE_KBPS = 64

    val json = Json { ignoreUnknownKeys = true }
  }
}
