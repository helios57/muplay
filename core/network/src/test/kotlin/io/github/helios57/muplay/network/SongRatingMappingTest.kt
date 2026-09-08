package io.github.helios57.muplay.network

import io.github.helios57.muplay.model.Song
import io.github.helios57.muplay.model.SongRating
import io.github.helios57.muplay.model.SubsonicCredentials
import io.github.helios57.muplay.testing.OpenApiFixtureValidator
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `userRating`, from the wire to [Song.rating].
 *
 * ### The fixture is a real capture, and that is the point of it
 *
 * `get-random-songs-rated.json` was taken off the CI Navidrome with two of the four seeded music
 * tracks deliberately rated — `Track 1` at 5, `Track 2` at 1 — and the ratings cleared again
 * afterwards. So the *shape* of a rated response is measured rather than assumed, and the two
 * facts that matter for this mapping are both recorded in one body:
 *
 * - a rated track carries `"userRating": <n>` **inside `getRandomSongs`**, which is what makes
 *   weighting a shuffle by rating free rather than an N-request feature;
 * - an unrated track carries **no `userRating` key at all**, rather than a `0` or a `null` — which
 *   is why [SongRating.ofUserRating] has to read an absent field and not just a number.
 *
 * Both ratings appear in one fixture on purpose: a mapper that returned a constant
 * [SongRating.Promoted] would pass a one-value check, and one that inverted the two thumbs would
 * pass any check that only counted how many songs came back rated.
 */
class SongRatingMappingTest {

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
  fun `the captured rated response is what the spec says a response is`() = runTest {
    // The same external oracle every capture here is held to. It is what stops this fixture
    // drifting into a shape only this client can read.
    OpenApiFixtureValidator.assertValid("/rest/getRandomSongs", fixture(RATED_FIXTURE))
  }

  @Test
  fun `each thumb comes from its own song rather than from a constant`() = runTest {
    enqueue(fixture(RATED_FIXTURE))

    val byTitle = client.getRandomSongs(musicFolderId = 1, size = 500).associateBy { it.title }

    assertThat(byTitle.keys).contains("Track 1", "Track 2", "Track 3", "Offset Track")
    assertThat(byTitle.getValue("Track 1").rating).isEqualTo(SongRating.Promoted)
    assertThat(byTitle.getValue("Track 2").rating).isEqualTo(SongRating.Demoted)
  }

  @Test
  fun `a song the server sends no rating for is neutral rather than absent or a failure`() = runTest {
    enqueue(fixture(RATED_FIXTURE))

    val byTitle = client.getRandomSongs(musicFolderId = 1, size = 500).associateBy { it.title }

    // Two of them, because "the unrated ones are Neutral" is the case a mapper gets right by
    // accident when it gets everything else wrong -- `Neutral` is the model's default.
    assertThat(byTitle.getValue("Track 3").rating).isEqualTo(SongRating.Neutral)
    assertThat(byTitle.getValue("Offset Track").rating).isEqualTo(SongRating.Neutral)
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

  private companion object {
    const val RATED_FIXTURE = "get-random-songs-rated.json"
  }
}
