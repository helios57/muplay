package app.muplay.network

import app.muplay.testing.OpenApiFixtureValidator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Where the real Navidrome and the vendored OpenSubsonic spec disagree, pinned.
 *
 * Every fixture this class names was captured from a running `deluan/navidrome:0.63.2` and is
 * committed **exactly as it came off the wire**. Three of them do not validate against the
 * vendored spec, and rather than editing the capture until the oracle is happy — which would
 * destroy the only external check this project has — each disagreement is asserted here by name.
 *
 * These assertions fail in both directions, which is the point. If Navidrome stops sending
 * `userRating: 0`, or the vendored spec is refreshed to model Navidrome's `scanStatus`
 * extensions, the `assertThatThrownBy` calls below go red and someone reads this file instead of
 * discovering the change six months later through a parsing bug.
 */
class NavidromeSpecDeviationTest {

  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
      .use { it.readBytes().decodeToString() }

  @Test
  fun `getRandomSongs validates against the vendored spec exactly as captured`() {
    // The one browse capture with no deviation at all -- and, not coincidentally, the endpoint
    // the headline feature depends on. Asserted first so this class is not purely a list of
    // disagreements: the oracle does accept a real Navidrome response.
    OpenApiFixtureValidator.assertValid("/rest/getRandomSongs", fixture(RANDOM_SONGS_FIXTURE))
  }

  @Test
  fun `getAlbumList2 with no albums validates as captured`() {
    // A past-the-end offset returns `"albumList2": {}` -- the container present, the `album` key
    // absent entirely. Spec-legal, and the exact shape the reconcile paging loop terminates on.
    OpenApiFixtureValidator.assertValid("/rest/getAlbumList2", fixture(ALBUM_LIST_EMPTY_FIXTURE))
  }

  @Test
  fun `navidrome sends userRating 0 which the spec forbids`() {
    // AlbumID3.userRating is `minimum: 1, maximum: 5` ("The user rating of the album. [1-5]").
    // Navidrome sends 0 for an unrated album, which is not in that range. Every album-bearing
    // response is therefore rejected -- three endpoints, four captures, one cause.
    ALBUM_BEARING_FIXTURES.forEach { (name, path) ->
      assertThatThrownBy { OpenApiFixtureValidator.assertValid(path, fixture(name)) }
        .describedAs(name)
        .isInstanceOf(AssertionError::class.java)
        .hasMessageContaining("minimum value of 1")
    }
  }

  @Test
  fun `stripping only userRating makes every album response validate`() {
    // The other half of the claim above, and the half that makes it actionable: `userRating` is
    // the *only* thing wrong with these captures. Without this, "rejected" would be
    // consistent with any number of unnoticed deviations hiding behind the first one.
    ALBUM_BEARING_FIXTURES.forEach { (name, path) ->
      OpenApiFixtureValidator.assertValid(path, withoutUserRating(fixture(name)))
    }
  }

  @Test
  fun `navidrome extends scanStatus with fields the spec does not model`() {
    // `lastScan` is the whole basis of this plan's sync design (spec section 4: "Navidrome
    // extends it with a monotonic lastScan"), and the vendored spec's ScanStatus schema has only
    // `scanning` and `count`. Asserting all four extension fields by name means a future spec
    // refresh that adds three of them still fails here rather than half-passing.
    assertThatThrownBy {
      OpenApiFixtureValidator.assertValid("/rest/getScanStatus", fixture(SCAN_STATUS_FIXTURE))
    }
      .isInstanceOf(AssertionError::class.java)
      .hasMessageContaining("lastScan")
      .hasMessageContaining("folderCount")
      .hasMessageContaining("scanType")
      .hasMessageContaining("elapsedTime")
  }

  @Test
  fun `the captured scanStatus really does carry a lastScan token`() {
    // Not a shape assertion: the sync engine reads this exact field, so its presence in a real
    // capture is a precondition of the design, not a detail of the oracle's opinion about it.
    assertThat(fixture(SCAN_STATUS_FIXTURE)).contains("\"lastScan\"")
  }

  /**
   * The capture with every `userRating` key removed, wherever it appears. Textual, deliberately:
   * a JSON round-trip through a parser would also normalise key order and whitespace, and the
   * point of the assertion above is that *only* this key differs.
   */
  private fun withoutUserRating(json: String): String =
    json.lineSequence().filterNot { it.trim().startsWith("\"userRating\"") }.joinToString("\n")
      // Removing a middle line leaves the previous line's trailing comma dangling only when the
      // removed line was last in its object; `json.tool` never emits `userRating` last for
      // Navidrome's field order, verified against the captures. If that ever changes this method
      // produces invalid JSON and every assertion using it fails loudly rather than silently.
      .also { check(it.contains("\"id\"")) { "the fixture filter removed more than it should" } }

  private companion object {
    const val ALBUM_LIST_MUSIC_FIXTURE = "get-album-list2-music.json"
    const val ALBUM_LIST_AUDIOBOOKS_FIXTURE = "get-album-list2-audiobooks.json"
    const val ALBUM_LIST_EMPTY_FIXTURE = "get-album-list2-empty.json"
    const val ALBUM_WITH_SONGS_FIXTURE = "get-album-with-songs.json"
    const val SEARCH3_FIXTURE = "search3-music.json"
    const val RANDOM_SONGS_FIXTURE = "get-random-songs-music.json"
    const val SCAN_STATUS_FIXTURE = "get-scan-status.json"

    /**
     * Every captured response that carries an `AlbumID3` anywhere in it, with the spec path it
     * would be validated against. All four committed album-bearing captures are here, not just
     * the three the two assertions above strictly need to make their point: a fixture this
     * project commits and never puts in front of the oracle is a fixture nobody is checking, and
     * `get-album-list2-audiobooks.json` (this project's only capture of library 2, and so the
     * only evidence that the audiobook library is reachable at all) would otherwise be exactly
     * that.
     */
    val ALBUM_BEARING_FIXTURES = listOf(
      ALBUM_LIST_MUSIC_FIXTURE to "/rest/getAlbumList2",
      ALBUM_LIST_AUDIOBOOKS_FIXTURE to "/rest/getAlbumList2",
      ALBUM_WITH_SONGS_FIXTURE to "/rest/getAlbum",
      SEARCH3_FIXTURE to "/rest/search3",
    )
  }
}
