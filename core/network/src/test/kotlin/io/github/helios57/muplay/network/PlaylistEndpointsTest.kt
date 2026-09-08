package io.github.helios57.muplay.network

import io.github.helios57.muplay.model.SubsonicCredentials
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `getPlaylists` and `getPlaylist`, over a real socket through the real Retrofit +
 * kotlinx.serialization stack, against bodies whose shape was **measured** against the CI
 * Navidrome rather than read off the spec — a playlist was created there, both endpoints were
 * captured, and it was deleted again.
 *
 * Two things that measurement decided, and neither is guessable from the Subsonic documentation:
 *
 * - the entries of `getPlaylist` are full `Child` objects, `path` included, so one mapping serves
 *   playlists and folders both;
 * - an empty `playlists` container is sent as `"playlists": {}` with no `playlist` key at all,
 *   the same absent-container idiom `getAlbumList2` uses for a past-the-end page.
 *
 * The request assertions are here for the reason [BrowseEndpointsTest]'s are: a response-mapping
 * test passes just as well against a client that sends no credentials at all.
 */
class PlaylistEndpointsTest {

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

  @Test
  fun `getPlaylists reads every playlist the server offers`() = runTest {
    enqueue(fixture("get-playlists.json"))

    val playlists = client.getPlaylists()

    assertThat(playlists.map { it.id }).containsExactly("pl-1", "pl-2")
    assertThat(playlists.map { it.name }).containsExactly("Late Night", "Long Drive")
    assertThat(playlists[0].songCount).isEqualTo(2)
    assertThat(playlists[0].durationSeconds).isEqualTo(9)
    assertThat(playlists[0].owner).isEqualTo("alice")
    assertThat(playlists[0].coverArtId).isEqualTo("pl-1_6a9c7f09")
    // Absent rather than empty-string: the second fixture carries no `coverArt` key, and a
    // placeholder id would be requested from the server and 404 on every list row.
    assertThat(playlists[1].coverArtId).isNull()
    assertAuthenticatedRequestTo("/rest/getPlaylists")
  }

  @Test
  fun `getPlaylists is not library-scoped, because the protocol has no such parameter`() = runTest {
    // Worth an assertion rather than a comment: every other browse command this client makes is
    // scoped with `musicFolderId`, and sending one here would be silently ignored by Navidrome
    // while giving this app's own code the false impression that playlists belong to a library.
    // They do not -- `getPlaylists` answers with every playlist the user owns, whatever library
    // its entries came from, which is why the screen shows them outside the library chips.
    enqueue(fixture("get-playlists.json"))

    client.getPlaylists()

    val url = assertAuthenticatedRequestTo("/rest/getPlaylists")
    assertThat(url.queryParameter("musicFolderId")).isNull()
  }

  @Test
  fun `an empty playlists container is no playlists, not a failure`() = runTest {
    enqueue(fixture("get-playlists-empty.json"))

    assertThat(client.getPlaylists()).isEmpty()
  }

  @Test
  fun `getPlaylist sends the id and reads the entries in the server's order`() = runTest {
    enqueue(fixture("get-playlist.json"))

    val result = client.getPlaylist("pl-1", musicFolderId = 1)

    assertThat(result.playlist.name).isEqualTo("Late Night")
    // Order is the whole content of a playlist and nothing else preserves it -- `containsExactly`,
    // never `containsExactlyInAnyOrder`.
    assertThat(result.songs.map { it.id }).containsExactly("song-1", "song-2")
    assertThat(result.songs.map { it.title }).containsExactly("Part One", "Second Track")
    assertThat(result.songs[1].suffix).isEqualTo("flac")

    val url = assertAuthenticatedRequestTo("/rest/getPlaylist")
    assertThat(url.queryParameter("id")).isEqualTo("pl-1")
  }

  @Test
  fun `every playlist entry carries the file path the server reports`() = runTest {
    // The path is what makes folder browsing and folder-scoped shuffle a local query rather than
    // a walk of `getMusicDirectory`, so it has to survive every command that returns a song.
    enqueue(fixture("get-playlist.json"))

    val songs = client.getPlaylist("pl-1", musicFolderId = 1).songs

    assertThat(songs.map { it.path }).containsExactly(
      "Fourth Author/Multi Part Book/01 - Part One.mp3",
      "Test Artist/Test Album/04 - Second Track.flac",
    )
  }

  @Test
  fun `getPlaylist stamps its entries with the library it was asked for`() = runTest {
    // The same bytes at two scopes. No Subsonic playlist entry carries a library id -- measured,
    // the real `entry` objects have no such field -- so a stamp that differs between two
    // identical responses provably came from the argument. A hardcoded stamp satisfies one scope
    // exactly as well as the correct code does.
    enqueue(fixture("get-playlist.json"))
    enqueue(fixture("get-playlist.json"))

    val music = client.getPlaylist("pl-1", musicFolderId = 1).songs
    val elsewhere = client.getPlaylist("pl-1", musicFolderId = 7).songs

    assertThat(music.map { it.libraryId }).containsExactly(1, 1)
    assertThat(elsewhere.map { it.libraryId }).containsExactly(7, 7)
  }

  @Test
  fun `a success envelope with no playlist payload is a failure, not an empty playlist`() = runTest {
    // The same decision `getAlbum` makes and for the same reason: "the server said ok and told us
    // nothing" is not something a caller can act on, and reading it as "this playlist is empty"
    // would show the user an empty list where a real one exists.
    enqueue(
      """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""",
    )

    val thrown = requireNotNull(
      runCatching { client.getPlaylist("pl-1", musicFolderId = 1) }.exceptionOrNull(),
    ) { "expected the call to fail, but it returned normally" }

    assertThat(thrown)
      .isInstanceOf(SubsonicMalformedResponseException::class.java)
      .hasMessageContaining("playlist")
  }

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
    val request = checkNotNull(server.takeRequest()) { "no request was made" }
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
    return url
  }

  private fun md5Hex(value: String): String =
    MessageDigest.getInstance("MD5")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
}
