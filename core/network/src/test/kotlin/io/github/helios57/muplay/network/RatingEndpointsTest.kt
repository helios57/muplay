package io.github.helios57.muplay.network

import io.github.helios57.muplay.model.SubsonicCredentials
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The three commands the thumbs write: `setRating`, `createPlaylist`, `updatePlaylist`.
 *
 * These are the first **writes** this client makes. Everything before them reads, so a mistake was
 * a wrong screen; a mistake here edits the listener's server. That is why every test below asserts
 * the request rather than only the response — a client that sends `rating=5` to the wrong id, or
 * omits `songIndexToRemove` entirely, returns a perfectly good `ok` envelope and this suite would
 * be green over it.
 *
 * The `ok` and `createPlaylist` bodies are real captures off the CI Navidrome, taken by issuing the
 * commands and deleting what they made.
 */
class RatingEndpointsTest {

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
    if (::server.isInitialized) server.close()
  }

  @Test
  fun `setRating names the song and the number, authenticated`() = runTest {
    enqueue(fixture(OK_FIXTURE))

    client.setRating(songId = "song-7", rating = 5)

    val url = assertAuthenticatedRequestTo("/rest/setRating")
    assertThat(url.queryParameter("id")).isEqualTo("song-7")
    assertThat(url.queryParameter("rating")).isEqualTo("5")
  }

  @Test
  fun `clearing a rating sends a zero rather than omitting the parameter`() = runTest {
    // Subsonic has no "unset" verb, so the only way back to unrated is an explicit 0. Omitting the
    // parameter is not a no-op that leaves the old rating -- it is a malformed command -- and the
    // difference is invisible in the response, which is `ok` either way.
    enqueue(fixture(OK_FIXTURE))

    client.setRating(songId = "song-7", rating = 0)

    assertThat(server.takeRequest().url.queryParameter("rating")).isEqualTo("0")
  }

  @Test
  fun `createPlaylist sends the name and one songId parameter per track`() = runTest {
    enqueue(fixture(CREATE_PLAYLIST_FIXTURE))

    val created = client.createPlaylist(name = "promoted-alice", songIds = listOf("a", "b"))

    val url = assertAuthenticatedRequestTo("/rest/createPlaylist")
    assertThat(url.queryParameter("name")).isEqualTo("promoted-alice")
    // Repeated, not comma-joined. A `songId=a,b` reaches Navidrome as one unknown id and the
    // playlist comes back empty -- with an `ok` status, because the command itself succeeded.
    assertThat(url.queryParameterValues("songId")).containsExactly("a", "b")
    assertThat(created.id).isNotBlank()
  }

  @Test
  fun `updatePlaylist adds by id and removes by index`() = runTest {
    enqueue(fixture(OK_FIXTURE))

    client.updatePlaylist(
      playlistId = "pl-1",
      songIdsToAdd = listOf("new-song"),
      songIndexesToRemove = listOf(3),
    )

    val url = assertAuthenticatedRequestTo("/rest/updatePlaylist")
    assertThat(url.queryParameter("playlistId")).isEqualTo("pl-1")
    assertThat(url.queryParameterValues("songIdToAdd")).containsExactly("new-song")
    // By **index**, which is what Subsonic offers and is the reason removing a song from the
    // promoted playlist costs a `getPlaylist` first: there is no `songIdToRemove`.
    assertThat(url.queryParameterValues("songIndexToRemove")).containsExactly("3")
  }

  @Test
  fun `an update that only adds sends no remove parameter at all`() = runTest {
    // An empty `songIndexToRemove=` is not the same as no parameter: Navidrome parses the empty
    // string as an index and answers with an error, so a client that always sends both parameters
    // cannot add a song.
    enqueue(fixture(OK_FIXTURE))

    client.updatePlaylist(playlistId = "pl-1", songIdsToAdd = listOf("x"), songIndexesToRemove = emptyList())

    val url = server.takeRequest().url
    assertThat(url.queryParameterValues("songIndexToRemove")).isEmpty()
  }

  @Test
  fun `a refused write is an exception rather than a silent success`() = runTest {
    // The whole reason these return Unit rather than a Boolean: a caller that has to remember to
    // check a return value will eventually not, and a rating that silently failed to save is the
    // kind of defect a user reports as "it forgets my thumbs".
    enqueue(
      """{"subsonic-response":{"status":"failed","version":"1.16.1",
         "error":{"code":50,"message":"User is not authorized"}}}""",
    )

    val thrown = runCatching { client.setRating(songId = "song-7", rating = 5) }.exceptionOrNull()

    // `requireNotNull` rather than a null-tolerant assertion: a client that stops throwing on a
    // `failed` envelope is exactly the regression this test exists to catch, and `isInstanceOf`
    // on a null would report it as a type mismatch rather than as "the write silently succeeded".
    assertThat(requireNotNull(thrown) { "expected the refused write to throw, but it returned" })
      .isInstanceOf(SubsonicException::class.java)
  }

  private fun enqueue(body: String) {
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build(),
    )
  }

  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
      .use { it.readBytes().decodeToString() }

  private fun assertAuthenticatedRequestTo(expectedPath: String): okhttp3.HttpUrl {
    val request = server.takeRequest()
    val url = request.url
    assertThat(url.encodedPath).isEqualTo(expectedPath)
    assertThat(url.queryParameter("u")).isEqualTo("alice")
    assertThat(url.queryParameter("t")).isNotNull()
    assertThat(url.queryParameter("s")).isNotNull.matches("[0-9a-f]{16}")
    return url
  }

  private companion object {
    const val OK_FIXTURE = "set-rating-ok.json"
    const val CREATE_PLAYLIST_FIXTURE = "create-playlist.json"
  }
}
