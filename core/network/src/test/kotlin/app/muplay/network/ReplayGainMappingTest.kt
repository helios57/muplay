package app.muplay.network

import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import app.muplay.testing.OpenApiFixtureValidator
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The `replayGain` object, from the wire to [Song].
 *
 * Every field gets its own assertion at **two** values wherever two are available, because Plan 2
 * Task 3's four review rounds established the rule this whole plan is written against: a mapped
 * field replaced by a constant leaves a suite green, and a `replayGain` block mapped to
 * `ReplayGain(-6f, null, null)` regardless of input would pass any single-value check.
 *
 * ### The capture this class does not have, and what stands in for it
 *
 * Plan 3 Task 11's brief asks for `get-album-replay-gain.json`, captured off a **tagged** seeded
 * file. No seeded file carries a ReplayGain tag and `ci/seed-fixtures.sh` is held on another
 * branch, so that capture could not be taken -- see task-11-report.md. What is measured instead,
 * stated here rather than implied:
 *
 *  * `get-album-with-songs.json` is a real capture off `deluan/navidrome:0.63.2`, and every song
 *    in it carries `"replayGain": {}`. So *the field is on the wire from the real server*, and the
 *    empty-object shape an untagged library produces is a recorded fact rather than a guess --
 *    which is exactly the input the "no decision" cases below have to get right.
 *  * The **populated** shape is hand-built here and validated against the vendored OpenAPI oracle,
 *    the same external check every capture in this repository is held to. That proves the body
 *    this client is asked to parse is spec-conformant; it does not prove Navidrome emits it. That
 *    last step is the one gap, and it is named in the task report rather than papered over.
 *
 * `enqueue`/`fixture` are per-class here rather than shared, following the convention every other
 * test class in this source set keeps (`BrowseEndpointsTest`, `SubsonicClientTest`,
 * `CapabilityNegotiatorTest` each hold their own copies): sharing them would let one edit weaken
 * all of them at once.
 */
class ReplayGainMappingTest {

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
  fun `a real capture off the real server carries the replayGain field on every song`() = runTest {
    // The assumption the whole feature rests on, anchored on bytes nobody here wrote. Asserted on
    // the fixture TEXT as well as through the parser, because "our parser produced null" is
    // equally consistent with a server that never sent the field at all.
    val body = fixture(ALBUM_FIXTURE)
    assertThat(body).contains("\"replayGain\"")

    val songs = songsFromAlbum(body)

    assertThat(songs).isNotEmpty
    // Untagged files, so no decision -- not a `ReplayGain(null, null, null)`, which is the same
    // fact spelled as an object and would make every caller ask two questions instead of one.
    assertThat(songs.map { it.replayGain }).allSatisfy { assertThat(it).isNull() }
  }

  @Test
  fun `a populated replay gain object is what the oracle says a getAlbum response looks like`() {
    // The external oracle on the populated shape. If this body did not validate, the right answer
    // would be a named, committed deviation in `NavidromeSpecDeviationTest` -- never a loosened
    // validator and never a weaker assertion below.
    OpenApiFixtureValidator.assertValid("/rest/getAlbum", album(POPULATED_GAIN))
  }

  @Test
  fun `each field comes from its own key and not from a neighbour`() = runTest {
    // Four distinct values in one inline body: a mapper that read `albumGain` into `trackGainDb`,
    // or `albumPeak` into the track peak, passes the oracle test above and fails here.
    val gain = checkNotNull(songsFromAlbum(album(POPULATED_GAIN)).single().replayGain)

    assertThat(gain.trackGainDb).isEqualTo(-6.0f)
    assertThat(gain.albumGainDb).isEqualTo(-3.0f)
    assertThat(gain.peakAmplitude).isEqualTo(0.5f)
  }

  @Test
  fun `a second body, every value disjoint from the first, maps field by field`() = runTest {
    // The second observation. Every number here differs from every number in [POPULATED_GAIN], so
    // a mapper hardcoded to any value that satisfies the test above fails this one.
    val gain = checkNotNull(
      songsFromAlbum(
        album("""{"trackGain":2.5,"albumGain":-11.25,"trackPeak":0.875,"albumPeak":0.125}"""),
      ).single().replayGain,
    )

    assertThat(gain.trackGainDb).isEqualTo(2.5f)
    assertThat(gain.albumGainDb).isEqualTo(-11.25f)
    assertThat(gain.peakAmplitude).isEqualTo(0.875f)
  }

  @Test
  fun `the album peak is the fallback for a file with no track peak`() = runTest {
    val gain = checkNotNull(
      songsFromAlbum(album("""{"trackGain":-6.0,"albumPeak":0.8}""")).single().replayGain,
    )

    assertThat(gain.peakAmplitude).isEqualTo(0.8f)
    // ...and the fallback is a fallback, not a swap: with both present the track peak wins, which
    // the disjoint body above already asserts at 0.875 over 0.125.
    assertThat(gain.trackGainDb).isEqualTo(-6.0f)
  }

  @Test
  fun `a file carrying only an album gain still carries a decision`() = runTest {
    // Album gain beats nothing at all. A mapper that returned `null` unless `trackGain` was
    // present would silently drop every album-tagged library there is.
    val gain = checkNotNull(
      songsFromAlbum(album("""{"albumGain":-7.5,"albumPeak":0.6}""")).single().replayGain,
    )

    assertThat(gain.trackGainDb).isNull()
    assertThat(gain.albumGainDb).isEqualTo(-7.5f)
    assertThat(gain.peakAmplitude).isEqualTo(0.6f)
  }

  @Test
  fun `an untagged file carries no replay gain at all, rather than zeroes`() = runTest {
    // `null` means "the file does not say"; `0.0` means "the file says no adjustment is needed".
    // Collapsing the two would apply a decision nobody made, to every untagged library there is.
    assertThat(songsFromAlbum(ALBUM_WITH_NO_REPLAY_GAIN_KEY).single().replayGain).isNull()
  }

  @Test
  fun `an empty replay gain object is also no decision`() = runTest {
    // The shape the real server actually sends for an untagged file -- see the capture test above.
    assertThat(songsFromAlbum(album("{}")).single().replayGain).isNull()
  }

  @Test
  fun `a zero gain is a decision and is not collapsed into no decision`() = runTest {
    // The boundary the two tests above sit either side of, and the one a `!= 0f` guard would get
    // wrong: a file tagged "0.00 dB" has been measured and needs nothing done to it, which is not
    // the same as a file nobody measured.
    val gain = checkNotNull(songsFromAlbum(album("""{"trackGain":0.0}""")).single().replayGain)

    assertThat(gain.trackGainDb).isEqualTo(0.0f)
  }

  @Test
  fun `the server side normaliser fields are parsed and then deliberately dropped`() = runTest {
    // `baseGain` and `fallbackGain` are modelled so the oracle keeps validating the whole object.
    // They configure a normaliser this client does not use, and a body carrying *only* them is a
    // file that still said nothing about its own loudness.
    assertThat(
      songsFromAlbum(album("""{"baseGain":-4.0,"fallbackGain":-8.0}""")).single().replayGain,
    ).isNull()

    // ...and where a real decision is present, neither of them may leak into it.
    val gain = checkNotNull(
      songsFromAlbum(
        album("""{"trackGain":-6.0,"baseGain":-4.0,"fallbackGain":-8.0}"""),
      ).single().replayGain,
    )
    assertThat(gain.trackGainDb).isEqualTo(-6.0f)
    assertThat(gain.albumGainDb).isNull()
    assertThat(gain.peakAmplitude).isNull()
  }

  @Test
  fun `every endpoint that returns songs maps the replay gain the same way`() = runTest {
    // One mapper, three callers. A second mapping added for one endpoint -- the shape this
    // project has already paid for once with the library stamp -- shows up here and nowhere else.
    enqueue(RANDOM_SONGS)
    val shuffled = client.getRandomSongs(musicFolderId = 1, size = 10).single()
    enqueue(SEARCH3)
    val searched = client.search3("q", musicFolderId = 1, 10, 10, 10).songs.single()

    assertThat(checkNotNull(shuffled.replayGain).trackGainDb).isEqualTo(-6.0f)
    assertThat(checkNotNull(shuffled.replayGain).peakAmplitude).isEqualTo(0.5f)
    assertThat(checkNotNull(searched.replayGain).trackGainDb).isEqualTo(-6.0f)
    assertThat(checkNotNull(searched.replayGain).peakAmplitude).isEqualTo(0.5f)
  }

  // --- helpers ---------------------------------------------------------------------------------

  private suspend fun songsFromAlbum(body: String): List<Song> {
    enqueue(body)
    return client.getAlbum("al-1", musicFolderId = 1).songs
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

  private companion object {
    const val ALBUM_FIXTURE = "get-album-with-songs.json"

    /** Four values, all distinct, so no two keys can be confused for one another. */
    const val POPULATED_GAIN =
      """{"trackGain":-6.0,"albumGain":-3.0,"trackPeak":0.5,"albumPeak":0.9}"""

    /**
     * A `getAlbum` body whose one song carries [gain] as its `replayGain`.
     *
     * `userRating` is deliberately absent: Navidrome sends `0` for an unrated album, the spec's
     * `AlbumID3.userRating` is `[1-5]`, and every real album-bearing capture in this repository is
     * rejected by the oracle for exactly that (`NavidromeSpecDeviationTest`). A hand-built body
     * that reproduced the deviation would make the oracle assertion above untestable.
     */
    fun album(gain: String): String =
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",
      "serverVersion":"0.63.2","openSubsonic":true,
      "album":{"id":"al-1","name":"A","songCount":1,"duration":5,"created":"2026-08-24T00:00:00Z",
      "song":[{"id":"s-1","title":"T","isDir":false,"replayGain":$gain}]}}}
      """.trimIndent()

    /** The other arm: the key is absent entirely, not merely empty. */
    val ALBUM_WITH_NO_REPLAY_GAIN_KEY: String =
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",
      "serverVersion":"0.63.2","openSubsonic":true,
      "album":{"id":"al-1","name":"A","songCount":1,"duration":5,"created":"2026-08-24T00:00:00Z",
      "song":[{"id":"s-1","title":"T","isDir":false}]}}}
      """.trimIndent()

    val RANDOM_SONGS: String =
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",
      "serverVersion":"0.63.2","openSubsonic":true,
      "randomSongs":{"song":[{"id":"s-1","title":"T","isDir":false,"replayGain":$POPULATED_GAIN}]}}}
      """.trimIndent()

    val SEARCH3: String =
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",
      "serverVersion":"0.63.2","openSubsonic":true,
      "searchResult3":{"song":[{"id":"s-1","title":"T","isDir":false,"replayGain":$POPULATED_GAIN}]}}}
      """.trimIndent()
  }
}
