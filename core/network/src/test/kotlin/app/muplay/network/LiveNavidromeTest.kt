package app.muplay.network

import app.muplay.model.AlbumListType
import app.muplay.model.LibraryRole
import app.muplay.model.StreamFormat
import app.muplay.model.ServerCapabilities
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import app.muplay.network.model.SubsonicEnvelope
import app.muplay.network.model.SubsonicResponseBody
import app.muplay.testing.BookFixtures
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
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

  /**
   * The one raw HTTP client every helper in this class uses, with timeouts set **explicitly**
   * rather than left at OkHttp's 10-second defaults.
   *
   * Measured: over 14 back-to-back runs of this suite, one run failed with a
   * `SocketTimeoutException` out of [rawCommand] — not an assertion, a 10-second read timeout on a
   * trivial REST call, while the shared container was busy serving another lane. A red that means
   * "the container was slow" is indistinguishable at the report from a red that means "the server
   * is wrong", and this suite exists to produce the second kind.
   *
   * 30 seconds is well beyond anything this corpus can legitimately take (the largest fixture is a
   * 21-second, 32 kbps m4b, and a full transcode of one measured under a second) while still
   * bounded, so a genuine hang still fails rather than parking the build.
   *
   * One instance rather than one per call: each `OkHttpClient()` brings its own connection pool
   * and dispatcher threads, and this class makes hundreds of requests in a run.
   */
  private val http = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .build()

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
    assertThat(albumNames(scopedAlbumList(MUSIC_LIBRARY_ID.toString())))
      .containsExactlyInAnyOrderElementsOf(MUSIC_ALBUM_NAMES)
    assertThat(albumNames(scopedAlbumList(AUDIOBOOKS_LIBRARY_ID.toString())))
      .containsExactlyInAnyOrderElementsOf(BOOK_ALBUM_NAMES)
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
        .containsExactlyInAnyOrderElementsOf(BOOK_ALBUM_NAMES + MUSIC_ALBUM_NAMES)
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
    // `ci/seed-fixtures.sh` seeds library 1 "Music" with four tracks -- three mp3s and, since
    // Task 12, the Opus fixture -- and library 2 "Audiobooks" with six. See this test's own doc
    // for what the fifty draws prove. `MUSIC_TITLES` is derived from the oracle, so the allow-list
    // below moved with the corpus rather than needing to be remembered.
    val client = client("testpass")
    val distinctDrawOrders = mutableSetOf<List<String>>()

    repeat(50) {
      val titles = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).map { it.title }
      assertThat(titles).isNotEmpty
      // Every audiobook title, not just the one: with a four-book corpus, `doesNotContain` on a
      // single title is satisfied by a leak that returns the other three.
      assertThat(titles).doesNotContainAnyElementsOf(AUDIOBOOK_TITLES)
      assertThat(titles).allMatch { it in MUSIC_TITLES }
      distinctDrawOrders += titles
    }

    // The property fifty draws can actually prove that one cannot: real randomisation, not fifty
    // identical draws laundered through a loop.
    assertThat(distinctDrawOrders.size)
      .describedAs("distinct draw orders across 50 draws of the same music library")
      .isGreaterThan(1)
  }

  @Test
  fun `shuffling the audiobook library returns the audiobook`() = runTest {
    // The control. Without it, the test above would pass just as well against a client that
    // returned nothing at all, or against a server with an empty audiobook library -- which is
    // exactly the shape of the eleventh silent gate this project already shipped once.
    val titles = client("testpass").getRandomSongs(musicFolderId = AUDIOBOOKS_LIBRARY_ID, size = 500)
      .map { it.title }

    assertThat(titles).containsExactlyInAnyOrderElementsOf(AUDIOBOOK_TITLES)
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

      assertThat(titles).describedAs("musicFolderId='%s'", malformed).containsAll(AUDIOBOOK_TITLES)
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
      .containsExactlyElementsOf(MUSIC_ALBUM_NAMES)
    // `containsExactly`, in `ALPHABETICAL_BY_NAME` order, against the oracle's own ordering --
    // not `contains`. This is the assertion that would have to be weakened if a fixture were
    // dropped, and weakening it quietly is exactly what must not happen. The control on the line
    // above is what stops it being equally satisfied by a server that ignores `musicFolderId`.
    assertThat(client.getAlbumList2(AUDIOBOOKS_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).map { it.name })
      .containsExactlyElementsOf(BOOK_ALBUM_NAMES)
    // The paging loop's termination condition, against the real server: past the end is an empty
    // list, not an error.
    assertThat(client.getAlbumList2(MUSIC_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 99))
      .isEmpty()
  }

  @Test
  fun `getAlbum returns the album's tracks and stamps the library the caller scoped by`() = runTest {
    val client = client("testpass")
    // Every music album, not `single()`. Task 12 put the Opus fixture in its own album, so the
    // music library holds two -- and a bare `single()` would throw before any assertion ran, which
    // is the failure mode this project's own note about `.first()` on a grown corpus warns about.
    val albums = client.getAlbumList2(MUSIC_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0)

    val songs = albums.flatMap { client.getAlbum(it.id, MUSIC_LIBRARY_ID).songs }

    // `containsExactlyInAnyOrder`, not a per-album loop: a loop over zero albums asserts nothing,
    // and a broken album lookup is exactly what produces zero iterations.
    assertThat(songs.map { it.title }).containsExactlyInAnyOrderElementsOf(MUSIC_TITLES)
    assertThat(songs).allMatch { it.libraryId == MUSIC_LIBRARY_ID }
  }

  /**
   * A book that is many files, which is the ordinary shape of a ripped audiobook and the only
   * fixture that can prove resume came back on the **right file**.
   *
   * The durations carry the discrimination, not the titles: a server (or a mapper) that returned
   * the three files sorted by name would satisfy the titles assertion untouched, because name
   * order and track order are the same list here. `4 / 6 / 5` is neither sorted nor constant, so
   * only a mapping that actually reads each file's own duration produces it.
   */
  @Test
  fun `a multi-file book comes back as ordered tracks with the durations the fixture has`() = runTest {
    val client = client("testpass")
    val books = client.getAlbumList2(AUDIOBOOKS_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0)
    val multiPart = books.single { it.name == BookFixtures.MULTI_PART_BOOK.albumName }

    val album = client.getAlbum(multiPart.id, AUDIOBOOKS_LIBRARY_ID)
    val inTrackOrder = album.songs.sortedBy { it.trackNumber }

    assertThat(inTrackOrder.map { it.title })
      .containsExactlyElementsOf(BookFixtures.MULTI_PART_BOOK.tracks.map { it.title })
    assertThat(inTrackOrder.map { it.trackNumber })
      .containsExactlyElementsOf(BookFixtures.MULTI_PART_BOOK.tracks.map { it.trackNumber })
    // Whole seconds is the resolution `Song.durationSeconds` has. ffprobe reads 4049 / 6034 / 5042
    // ms out of the files (libmp3lame pads to a whole frame — see ci/probe-chapters.sh), and every
    // one of those truncates and rounds to the same integer, so this comparison is not sitting on
    // a rounding convention neither reader promises.
    assertThat(inTrackOrder.map { it.durationSeconds })
      .containsExactlyElementsOf(BookFixtures.MULTI_PART_BOOK.tracks.map { (it.durationMs / 1000).toInt() })
    // Three *different* numbers actually came back, which the line above would also satisfy if
    // ffprobe and Navidrome were both reporting one constant.
    assertThat(inTrackOrder.map { it.durationSeconds }).doesNotHaveDuplicates()
  }

  /**
   * Navidrome's own duration for every file in the corpus, against ffprobe's.
   *
   * Two independent readers of the same nine files — taglib inside the server, ffprobe in
   * `ci/probe-chapters.sh` — agreeing is evidence about the *files*. Neither reader is this
   * project's code, which is what a golden file recorded from our own mapper would have been.
   */
  @Test
  fun `every seeded file's duration agrees with ffprobe`() = runTest {
    val client = client("testpass")
    val expected = (BookFixtures.ALL_BOOKS.flatMap { it.tracks } + BookFixtures.MUSIC_TRACKS)
      .associate { it.title to (it.durationMs / 1000).toInt() }
    val observed = listOf(MUSIC_LIBRARY_ID, AUDIOBOOKS_LIBRARY_ID)
      .flatMap { client.getRandomSongs(musicFolderId = it, size = 500) }
      .associate { it.title to it.durationSeconds }

    // `containsExactlyInAnyOrderEntriesOf`, not a per-entry loop: a loop over `observed` is
    // vacuously true when `observed` is empty, and an empty library is exactly the failure this
    // has to catch.
    assertThat(observed).containsExactlyInAnyOrderEntriesOf(expected)
    assertThat(observed).hasSize(SEEDED_TRACK_COUNT)
  }

  /**
   * The precondition for chapter extraction, asserted for **every** book file including the
   * non-faststart one.
   *
   * Spike S3 measured Media3's tail-`Range` behaviour against a hand-rolled Python server; whether
   * Navidrome's `format=raw` path offers the same guarantees for a file whose `moov` trails its
   * `mdat` is the open question spec §5 and §12 both carry, and `Tail Book` is the fixture that
   * asks it. This is the server half of the answer — Task 3 owes the Media3 half.
   *
   * The tail bytes are compared against the tail of the whole body, not merely counted: a server
   * that answered 206 with the *start* of the file would satisfy a length check and then hand
   * Media3 an `ftyp` atom where it asked for a `moov`.
   */
  @Test
  fun `every seeded book streams raw with an accurate content length and honours a tail Range`() = runTest {
    val client = client("testpass")
    val books = client.getAlbumList2(AUDIOBOOKS_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0)
    val observed = mutableListOf<String>()

    for (book in books) {
      for (song in client.getAlbum(book.id, AUDIOBOOKS_LIBRARY_ID).songs) {
        val url = client.streamUrl(song.id, StreamFormat.Raw)
        val (full, whole) = fetch(url)

        assertThat(full.code).describedAs("status for %s", song.title).isEqualTo(200)
        assertThat(full.header("Content-Length")?.toLong())
          .describedAs("Content-Length for %s", song.title).isEqualTo(whole.size.toLong())

        // The last 16 bytes — where a non-faststart file's `moov` header lives, and the exact
        // shape of request Media3 issues for one.
        val (tailResponse, tail) = fetch(url, range = "bytes=${whole.size - 16}-${whole.size - 1}")

        assertThat(tailResponse.code).describedAs("tail status for %s", song.title).isEqualTo(206)
        assertThat(tail).describedAs("tail bytes for %s", song.title)
          .isEqualTo(whole.copyOfRange(whole.size - 16, whole.size))
        observed += song.title
      }
    }

    // The exact list, not `allMatch` inside the loop: a loop that never ran asserts nothing, and a
    // broken album lookup is precisely what produces zero iterations.
    assertThat(observed).containsExactlyInAnyOrderElementsOf(AUDIOBOOK_TITLES)
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
    val song = musicTrack(client, MP3_TITLE)

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
    val song = musicTrack(client, MP3_TITLE)
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
    val song = musicTrack(client, MP3_TITLE)
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
   *
   * ### The intermittent red this test carried was the container, and the flush that "fixed" it
   *
   * This test failed roughly one run in three for a while, and both published explanations were
   * wrong. It was not cache *exhaustion*, and it was not the cold half racing the transcoder.
   * Measured here, against the shared container:
   *
   * - The searching GET's own response **is** what the cold half asserts on — [coldTranscode]
   *   returns it — so there is no second request for the transcoder to beat. The hypothesised
   *   race cannot occur in the code as written.
   * - The *cache-hit* half's re-fetch is not racy either: over **54** freshly-cold keys the
   *   immediate re-fetch came back `Accept-Ranges: bytes` **54** times, no exceptions. Navidrome
   *   commits the cache entry before the live response finishes.
   * - What actually failed was [TranscodeOutcome.UNAVAILABLE] being scored as "already cached".
   *   See [coldTranscode]: deleting the contents of `/data/cache/transcoding` on a *running*
   *   container poisons every entry in its in-memory index permanently, and the old search read
   *   those permanent errors as warm entries and eventually ran out of bitrates.
   *
   * And the one-in-three was not a probability at all — it was **which track got picked**. The
   * old body took `getRandomSongs(...).first()`, one of the three music fixtures. Censused on the
   * shared container after the flush: **63 of 63** bitrates unusable on Track 1, 6 of 10 sampled
   * on Track 2, 4 of 10 on Track 3. Drawing the fully-poisoned track is a certain failure and the
   * other two are near-certain passes, so the run-to-run outcome was a coin weighted one in three
   * — which reads exactly like a race and is not one.
   *
   * Hence the search space here is the whole library crossed with the bitrate range, not one
   * track's. A single unusable track can no longer decide a run, and the census in
   * [coldTranscode]'s failure message reports across all of them so the next reader gets the
   * distribution rather than a guess.
   */
  @Test
  fun `a live transcode returns no content length and refuses ranges`() = runTest {
    val client = client("testpass")
    // Every track in the music library, not `first()`. The whole library is one search space:
    // a (track, bitrate) pair is what the transcoding cache is keyed on, so one track whose
    // entries are all unusable no longer decides the run. On the shared container this was
    // measured at exactly that: 63 of 63 bitrates unusable on one of the three fixtures and
    // usable on the other two, which is what made a random `first()` fail about one run in three.
    //
    // Task 12's Opus fixture joins that space and is safe in it, which was measured rather than
    // assumed: the assertion below compares the transcoded body against the **source**, and an
    // Opus source is VBR, so "smaller than raw" is not free the way it is for the CBR mp3s. Raw is
    // 285417 bytes and the largest cap this search can ask for -- 63 kbps over 30.0065 s -- comes
    // back at 240321, a 16% margin. Every lower cap is smaller still.
    val songs = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500)

    val cold = coldTranscode(client, songs.map { it.id })
    // Raw for whichever track the search settled on -- the `isLessThan(raw.size)` assertion below
    // is only evidence if both sides are the same audio.
    val (_, raw) = fetch(client.streamUrl(cold.songId, StreamFormat.Raw))

    assertThat(cold.response.code).isEqualTo(200)
    assertThat(cold.bytes.size).isGreaterThan(1000)
    assertThat(outcomeOf(cold.response)).isEqualTo(TranscodeOutcome.LIVE)
    assertThat(cold.response.header("Content-Length")).isNull()
    // Audio, not an error document. `Accept-Ranges: none` alone does not say this, and the
    // failure mode that made this test flaky answered with `application/json` — so the content
    // type is asserted where a reader will see it rather than left to the search's predicate.
    assertThat(cold.response.header("Content-Type")).contains("audio/")
    // A real transcode rather than the source passed through: the cap is below the fixture's own
    // bitrate, so the body has to be smaller. Without this the test would pass against a server
    // that answered every `format=mp3` with the original file.
    assertThat(cold.bytes.size).isLessThan(raw.size)

    // ...and the same URL fetched again is a cache hit, which *is* seekable. Asserted rather than
    // described because it is the other half of the behaviour the format policy is built on. This
    // re-fetch is safe to make: it was measured over 54 freshly-cold keys and was a cache hit
    // every time, because Navidrome commits the entry before the live response completes.
    val (cachedResponse, cachedBytes) = fetch(cold.url)
    assertThat(outcomeOf(cachedResponse))
      .describedAs("re-fetch of a URL just transcoded live answered %s", cachedResponse.code)
      .isEqualTo(TranscodeOutcome.CACHED)
    assertThat(cachedResponse.header("Content-Length")?.toLong())
      .isEqualTo(cachedBytes.size.toLong())
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
    // Named, and it has to be an **mp3** source: the rule this test pins is "the requested format
    // already matches the file's own suffix", and the corpus now holds a file for which it does
    // not. `format=mp3` on the Opus fixture is a real transcode at every cap, so a `.first()` here
    // would fail on `capped == raw` roughly one run in four.
    val song = musicTrack(client, MP3_TITLE)

    val cappedUrl = client.streamUrl(song.id, StreamFormat.Mp3(320)).toHttpUrl()
    val (_, raw) = fetch(client.streamUrl(song.id, StreamFormat.Raw))
    val (response, capped) = fetch(cappedUrl.toString())

    // What this test pins is the behaviour of `format=mp3`, so the request it made has to have
    // been a `format=mp3` request. Without these two lines `capped == raw` is equally true of a
    // `format=raw` fetch — the test stayed green under the `stream/format-wire-value` mutation
    // and under dropping `maxBitRate` altogether, so it could not be read as evidence about
    // `format=mp3` at all. Asserted on the parsed parameters, never on the URL itself: this
    // string carries a token and must not reach a log or a failure message.
    assertThat(cappedUrl.queryParameter("format")).isEqualTo("mp3")
    assertThat(cappedUrl.queryParameter("maxBitRate")).isEqualTo("320")
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
    val song = musicTrack(client, MP3_TITLE)
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
    // `single { title == AUDIOBOOK_TITLE }`, not `single()`: the Audiobooks library holds six
    // files since the corpus grew, and three of them are mp3 parts that are not mp4 containers at
    // all. A bare `single()` would throw before any assertion ran.
    val song = client.getRandomSongs(musicFolderId = AUDIOBOOKS_LIBRARY_ID, size = 500)
      .single { it.title == AUDIOBOOK_TITLE }

    val (response, bytes) = fetch(client.streamUrl(song.id, StreamFormat.Raw))

    assertThat(response.code).isEqualTo(200)
    assertThat(bytes.size).isGreaterThan(1000)
    assertThat(String(bytes.copyOfRange(4, 8), Charsets.US_ASCII)).isEqualTo("ftyp")
  }

  // --- transcoded seek: the `transcodeOffset` extension (Task 12) ------------------------------

  /**
   * The gate's own precondition, asserted rather than assumed: the pinned container advertises
   * `transcodeOffset`.
   *
   * If a future pin stops advertising it, `TranscodeSeek` answers `NotOffered` for every transcode
   * and the seek bar quietly disappears on every Opus track -- which is correct behaviour and a
   * terrible surprise. This is where that gets noticed, and it is asserted through
   * [SubsonicSource.capabilities], the accessor `TranscodeOffsetSupport` actually calls, rather
   * than through the raw endpoint underneath it.
   */
  @Test
  fun `the pinned container advertises the transcodeOffset extension`() = runTest {
    val capabilities = client("testpass").capabilities()

    assertThat(capabilities.isOpenSubsonic).isTrue
    assertThat(capabilities.supports(ServerCapabilities.TRANSCODE_OFFSET_EXTENSION)).isTrue
    // The control, at a name this server does not advertise: without it the assertion above is
    // equally satisfied by a `supports` that returns true for everything.
    assertThat(capabilities.supports("thisExtensionDoesNotExist")).isFalse
  }

  /**
   * The server behaviour the whole feature rests on: `timeOffset` starts the transcode later.
   *
   * Measured as **bytes**, because a live transcode has no `Content-Length` to read and no duration
   * to ask for -- the body is all there is. The Opus fixture is thirty seconds at a fixed cap, so
   * the transcoded body shrinks in proportion to the offset.
   *
   * **Two offsets, not one.** A server that ignored the parameter entirely returns the same size
   * three times, which one observation cannot see; and a client that sent a *constant* offset
   * returns the same size twice, which two observations at one value cannot see either. Ten and
   * twenty seconds into a thirty-second file are two thirds and one third, and nothing constant
   * produces both.
   *
   * The transcoding cache is not a hazard here and that is worth stating, because it is a hazard
   * everywhere else in this file: the entry is keyed on (track, requested bitrate, **offset**)
   * -- measured -- and the *bytes* are identical whether they arrive live or from the cache. Only
   * the headers differ, and this test reads none of them.
   */
  @Test
  fun `a timeOffset shortens the transcoded body in proportion to the offset`() = runTest {
    val client = client("testpass")
    val song = musicTrack(client, OPUS_TITLE)

    val (wholeResponse, whole) = fetch(client.streamUrl(song.id, TRANSCODE, timeOffsetSeconds = 0))
    val (thirdIn, twoThirds) = fetch(client.streamUrl(song.id, TRANSCODE, timeOffsetSeconds = 10))
    val (twoThirdsIn, oneThird) = fetch(client.streamUrl(song.id, TRANSCODE, timeOffsetSeconds = 20))

    assertThat(wholeResponse.code).isEqualTo(200)
    assertThat(thirdIn.code).isEqualTo(200)
    assertThat(twoThirdsIn.code).isEqualTo(200)
    // Not vacuous against three empty 200s -- the shape a poisoned cache entry answers with.
    assertThat(whole.size).isGreaterThan(1000)
    assertThat(twoThirds.size).isGreaterThan(1000)
    assertThat(oneThird.size).isGreaterThan(1000)

    assertThat(twoThirds.size.toDouble() / whole.size)
      .describedAs("body from 10s in, over the whole body")
      .isBetween(0.55, 0.78)
    assertThat(oneThird.size.toDouble() / whole.size)
      .describedAs("body from 20s in, over the whole body")
      .isBetween(0.22, 0.45)
  }

  /**
   * The other half of the same fact, and the one that makes the offset a *seek* rather than a trim:
   * a request with no offset and a request with `timeOffset=0` are the same audio.
   *
   * Which is why `SubsonicClient` sends `0` rather than mapping it to "no parameter": the
   * re-issue path's own boundary case has to reach the server as a request, or nothing can observe
   * that it works.
   */
  @Test
  fun `timeOffset zero is the whole track, byte for byte`() = runTest {
    val client = client("testpass")
    val song = musicTrack(client, OPUS_TITLE)

    val (_, noOffset) = fetch(client.streamUrl(song.id, TRANSCODE))
    val (_, zeroOffset) = fetch(client.streamUrl(song.id, TRANSCODE, timeOffsetSeconds = 0))

    assertThat(noOffset.size).isGreaterThan(1000)
    // Byte-identical, not merely the same length: `timeOffset=0` must not be a re-encode that
    // happens to land on the same size.
    assertThat(zeroOffset).isEqualTo(noOffset)
  }

  /**
   * The Opus fixture reaches the transcoder at all -- which is the reason it is in the corpus.
   *
   * `StreamFormat.forSuffix` forces `format=mp3` for `opus`, so this is the only file here that a
   * user's own playback path sends through Navidrome's transcoder. If the scanner ever stopped
   * reporting `suffix = "opus"` for it, `forSuffix` would return `Raw`, every assertion above would
   * still pass against a raw Ogg body, and the transcoded-seek feature would have no fixture left.
   */
  @Test
  fun `the opus fixture is indexed as opus, which is what forces the transcode`() = runTest {
    val song = musicTrack(client("testpass"), OPUS_TITLE)

    assertThat(song.suffix).isEqualTo("opus")
    assertThat(StreamFormat.forSuffix(song.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS))
      .isEqualTo(StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS))
    // The control on the same line of reasoning: the mp3 fixtures are not transcoded.
    val mp3 = musicTrack(client("testpass"), MP3_TITLE)
    assertThat(StreamFormat.forSuffix(mp3.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS))
      .isEqualTo(StreamFormat.Raw)
  }

  // --- raw Subsonic, deliberately not through SubsonicClient -----------------------------------

  /**
   * One music track, **by title**.
   *
   * Every stream test in this class used to take `getRandomSongs(...).first()`, which was a fair
   * draw over three interchangeable fixtures. It stopped being fair the moment the corpus gained a
   * file that behaves differently: `format=mp3` on the Opus fixture is a real transcode at every
   * cap, so `an mp3 cap at or above the source bitrate is not a transcode at all` would fail on
   * whichever run happened to draw it. A test that passes for one fixture and fails for another is
   * exactly the flake that gets a gate disabled.
   */
  private suspend fun musicTrack(client: SubsonicClient, title: String): Song =
    client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).single { it.title == title }

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
  private class ColdTranscode(
    val songId: String,
    val url: String,
    val response: Response,
    val bytes: ByteArray,
  )

  /**
   * What this container did with one `format=mp3` request — the three outcomes it actually has,
   * not the two the first version of this helper assumed.
   *
   * [UNAVAILABLE] is the one that cost the time. It is not a transcode at all: a 200 carrying a
   * *JSON error document* and no `Accept-Ranges` header whatsoever. See [coldTranscode] for what
   * produces it.
   */
  private enum class TranscodeOutcome { LIVE, CACHED, UNAVAILABLE }

  /**
   * Which of the three [TranscodeOutcome]s [response] is, read off `Accept-Ranges`.
   *
   * The header has three states and they are all meaningful: `none` is a transcode being produced
   * right now, `bytes` is one served back out of the cache as a file, and *absent* is not audio at
   * all. The original predicate was `header("Accept-Ranges") == "none"`, which collapses the last
   * two into "not live" — the defect this enum exists to make unrepresentable.
   */
  private fun outcomeOf(response: Response): TranscodeOutcome =
    when (response.header("Accept-Ranges")) {
      "none" -> TranscodeOutcome.LIVE
      "bytes" -> TranscodeOutcome.CACHED
      else -> TranscodeOutcome.UNAVAILABLE
    }

  /**
   * The first `format=mp3` request for [songId] that this container answers with a **live**
   * transcode rather than out of its transcoding cache, together with that response.
   *
   * Setup for [`a live transcode returns no content length and refuses ranges`], not an assertion:
   * the cache is keyed on the bitrate as *requested* (24, 25 and 26 all encode to the same bytes
   * and still occupy three separate cache entries — measured), so a bitrate this container has
   * not seen is a cache miss. Shuffled rather than scanned in order so that repeated runs against
   * one long-lived container spread over the range instead of walking it from the bottom.
   *
   * The response returned here **is** the response the caller asserts on. There is no second
   * request to re-fetch the URL the search settled on, and there must not be: a `format=mp3` GET
   * both answers "cold" and starts filling the cache entry, so a re-request races the transcoder
   * and loses it perhaps a second later. The search's own response is the only observation of the
   * live state that is not a race.
   *
   * ### `UNAVAILABLE`, and why a red here used to mean nothing
   *
   * A (track, bitrate) whose cache entry is in Navidrome's **in-memory index** but whose file is
   * **missing from disk** is answered, forever, with
   *
   *     200 {"subsonic-response":{"status":"failed", ...
   *          "message":"Internal Server Error: open /data/cache/transcoding/xx/yy/...:
   *          no such file or directory"}}
   *
   * — `Content-Type: application/json`, a `Content-Length` of about 292, and no `Accept-Ranges`.
   * Measured here: the state is *permanent*, not a transient race. Four consecutive retries of
   * each of seven poisoned bitrates gave seven times four errors and no recoveries.
   *
   * That state is created by deleting the cache files out from under the running server —
   * `docker exec ... rm -rf` over the contents of `/data/cache/transcoding`, which earlier work
   * in this repository ran
   * as a *repair* and which this project's own notes recommended. It is the opposite of a repair:
   * it converts every entry the server has ever made into one that can never be read again. Only
   * restarting the container clears the index, and the container here is shared and must not be
   * restarted.
   *
   * The old predicate scored those responses as "not live, keep looking", so on a poisoned
   * container the search walked all 63 bitrates and failed with a message blaming cache
   * *exhaustion* — which is how "recreate the container" got recorded as the diagnosis for what is
   * really "somebody flushed the cache". Distinguishing the outcome is what makes a red here
   * evidence again: the message below reports the census, so the reader can tell a poisoned
   * container from a genuinely exhausted one from a Navidrome that changed its behaviour.
   */
  private fun coldTranscode(client: SubsonicClient, songIds: List<String>): ColdTranscode {
    val census = TranscodeOutcome.entries.associateWith { 0 }.toMutableMap()
    val space = songIds.flatMap { id -> (1 until FIXTURE_BITRATE_KBPS).map { id to it } }.shuffled()
    space.forEach { (songId, kbps) ->
      val url = client.streamUrl(songId, StreamFormat.Mp3(kbps))
      val (response, bytes) = fetch(url)
      val outcome = outcomeOf(response)
      census[outcome] = census.getValue(outcome) + 1
      if (outcome == TranscodeOutcome.LIVE) return ColdTranscode(songId, url, response, bytes)
    }
    return fail(
      "no (track, bitrate) below $FIXTURE_BITRATE_KBPS kbps produced a live transcode. " +
        "Of ${space.size} pairs tried across ${songIds.size} tracks: " +
        "${census.getValue(TranscodeOutcome.CACHED)} were already cached, " +
        "${census.getValue(TranscodeOutcome.UNAVAILABLE)} returned no audio at all.\n" +
        "  * mostly UNAVAILABLE means the transcoding cache was deleted underneath the running " +
        "server (`rm -rf /data/cache/transcoding/*`). That poisons every entry permanently and " +
        "only recreating the container clears it. Do not flush the cache; it causes this.\n" +
        "  * mostly CACHED means this container really has produced every bitrate below the " +
        "fixture's own encoding, which takes many runs — recreate it.\n" +
        "  * neither, on a fresh container, means Navidrome no longer streams a first-time " +
        "transcode unseekably: go and simplify the format policy, because the reason it prefers " +
        "raw has changed.",
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
    return http.newCall(request).execute().use { response ->
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
    return http.newCall(Request.Builder().url(url).build()).execute()
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

    val body = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
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
    /** The one single-file `.m4b` this class still names individually — the mp4-container test. */
    const val AUDIOBOOK_TITLE = "Test Book"

    /**
     * The music fixture every raw-stream test names, so none of them draws at random from a corpus
     * whose members no longer behave the same way. A CBR 64 kbps mp3, five seconds long.
     */
    const val MP3_TITLE = "Track 1"

    /**
     * The corpus's one Opus file, and therefore the only track a user's own playback path sends
     * through Navidrome's transcoder — `StreamFormat.forSuffix` forces `format=mp3` for `opus` and
     * for nothing else. Thirty seconds, in three ten-second regions: silence, a quiet 440 Hz tone,
     * a loud 1760 Hz tone. `ci/seed-fixtures.sh` says why each of those matters.
     */
    const val OPUS_TITLE = "Offset Track"

    /**
     * The format the app really asks for when it plays [OPUS_TITLE] — `StreamFormat.forSuffix`'s
     * own answer for an `opus` suffix, not a bitrate chosen here. A transcoded-seek test that used
     * some other cap would be measuring a request nothing makes.
     */
    val TRANSCODE = StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)

    // Everything below is read out of `BookFixtures`, i.e. out of the ffprobe-derived table
    // `ci/probe-chapters.sh` writes from the committed audio, rather than transcribed here.
    //
    // That is the point, not convenience. The subject of every assertion in this class is *what
    // Navidrome reports*, and the expectation has to come from somewhere that is not Navidrome:
    // ffprobe and taglib are two independent readers of the same nine files, and them agreeing is
    // evidence. A literal `9` copied into this file would instead be a fourth truth -- after the
    // audio, the table and `ci/configure-libraries.sh` -- with nothing keeping it honest, and this
    // corpus grew precisely because a corpus that cannot move is a corpus nothing can be measured
    // against.

    /** Album names in `ALPHABETICAL_BY_NAME` order — the order `BookFixtures.ALL_BOOKS` is in. */
    val BOOK_ALBUM_NAMES: List<String> = BookFixtures.ALL_BOOKS.map { it.albumName }

    /** Every *song* in the Audiobooks library: three single-file books plus Multi Part Book's three. */
    val AUDIOBOOK_TITLES: List<String> = BookFixtures.ALL_BOOKS.flatMap { it.tracks }.map { it.title }

    val MUSIC_TITLES: List<String> = BookFixtures.MUSIC_TRACKS.map { it.title }

    /**
     * The music library's album names, in `ALPHABETICAL_BY_NAME` order, **derived from the oracle's
     * own paths** (`Music/<artist>/<album>/<file>`) rather than transcribed.
     *
     * Transcribing them is what `getAlbumList2 is scoped and pages` did until the corpus grew a
     * second music album, and a literal there is a fourth truth after the audio, the table and the
     * seed script — the exact shape this class's own note one block down argues against.
     */
    val MUSIC_ALBUM_NAMES: List<String> = BookFixtures.MUSIC_TRACKS
      .map { it.path.split('/')[MUSIC_PATH_ALBUM_SEGMENT] }
      .distinct()
      .sorted()

    /** `Music` / `<artist>` / `<album>` / `<file>` — the album is the third segment. */
    private const val MUSIC_PATH_ALBUM_SEGMENT = 2

    /** Nine files across both libraries — and the count `ci/configure-libraries.sh` waits for. */
    val SEEDED_TRACK_COUNT: Int = BookFixtures.allTrackPaths().size

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
