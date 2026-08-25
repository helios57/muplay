package app.muplay.network

import app.muplay.model.AlbumListType
import app.muplay.model.LibraryRole
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
import org.assertj.core.api.Assertions.assertThat
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
  fun `a non-numeric musicFolderId puts an audiobook chapter into the music shuffle`() = runTest {
    // The user-visible failure this whole application exists to prevent, reproduced end to end:
    // ask the server to shuffle the *music* library with an unparseable scope and it hands back
    // the audiobook as well, indistinguishable from a song (Navidrome types every media file
    // `"type": "music"`). The `Test Book.m4b` is a 15-second stand-in for chapter 14 of a novel.
    val scoped = songTitles(randomSongs(MUSIC_LIBRARY_ID.toString()))
    val leaked = songTitles(randomSongs("abc"))

    assertThat(scoped).containsExactlyInAnyOrder("Track 1", "Track 2", "Track 3")
    assertThat(leaked).containsExactlyInAnyOrder("Track 1", "Track 2", "Track 3", "Test Book")
    assertThat(leaked).describedAs("the audiobook, inside a music shuffle").contains("Test Book")
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

  @Test
  fun `shuffling the music library never returns the audiobook`() = runTest {
    // The whole feature in one assertion, against the real server. `ci/configure-libraries.sh`
    // seeds library 1 "Music" with three tracks and library 2 "Audiobooks" with "Test Book";
    // fifty draws over a four-item corpus makes an unscoped result overwhelmingly likely to
    // show up, and a scoped one certain not to.
    val client = client("testpass")

    repeat(50) {
      val titles = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).map { it.title }
      assertThat(titles).isNotEmpty
      assertThat(titles).doesNotContain(AUDIOBOOK_TITLE)
      assertThat(titles).allMatch { it in MUSIC_TITLES }
    }
  }

  @Test
  fun `shuffling the audiobook library returns the audiobook`() = runTest {
    // The control. Without it, the test above would pass just as well against a client that
    // returned nothing at all, or against a server with an empty audiobook library -- which is
    // exactly the shape of the eleventh silent gate this project already shipped once.
    val titles = client("testpass").getRandomSongs(musicFolderId = AUDIOBOOK_LIBRARY_ID, size = 500)
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
    assertThat(client.search3("Test", AUDIOBOOK_LIBRARY_ID, 10, 10, 10).songs.map { it.title })
      .containsExactly(AUDIOBOOK_TITLE)
  }

  @Test
  fun `getAlbumList2 is scoped and pages`() = runTest {
    val client = client("testpass")

    assertThat(client.getAlbumList2(MUSIC_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).map { it.name })
      .containsExactly("Test Album")
    assertThat(client.getAlbumList2(AUDIOBOOK_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).map { it.name })
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
    val body = rawRest("getRandomSongs", mapOf("size" to "500", "musicFolderId" to AUDIOBOOK_LIBRARY_ID.toString()))

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

  private fun randomSongs(musicFolderId: String): SubsonicResponseBody =
    rawCommand("getRandomSongs", mapOf("size" to "500", "musicFolderId" to musicFolderId))

  private fun albumNames(body: SubsonicResponseBody): List<String> =
    body.albumList2?.album.orEmpty().map { it.name }

  private fun songTitles(body: SubsonicResponseBody): List<String> =
    body.randomSongs?.song.orEmpty().map { it.title }

  /**
   * A `getRandomSongs` request built by hand, so a `musicFolderId` that [SubsonicClient]'s own
   * `Int` parameter makes unrepresentable can still be sent to the real server. Used only to pin
   * the server's silent-widening behaviour.
   */
  private fun rawRandomSongTitles(musicFolderId: String): List<String> {
    val body = rawRest("getRandomSongs", mapOf("size" to "500", "musicFolderId" to musicFolderId))
    return Regex(""""title":"([^"]*)"""").findAll(body).map { it.groupValues[1] }.toList()
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
    /** Same library as [AUDIOBOOKS_LIBRARY_ID]; singular name matches this task's own brief. */
    const val AUDIOBOOK_LIBRARY_ID = 2
    const val AUDIOBOOK_TITLE = "Test Book"
    val MUSIC_TITLES = listOf("Track 1", "Track 2", "Track 3")
    /** Three mp3s plus one m4b — ci/seed-fixtures.sh, and the count configure-libraries.sh waits for. */
    const val SEEDED_TRACK_COUNT = 4

    val json = Json { ignoreUnknownKeys = true }
  }
}
