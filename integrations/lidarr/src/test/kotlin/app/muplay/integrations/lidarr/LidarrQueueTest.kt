package app.muplay.integrations.lidarr

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The queue and the album statistics, over a real socket.
 *
 * The server is started and stopped by hand rather than with `@StartStop`: that extension ships in
 * `mockwebserver3-junit5`, which carries a second `META-INF/LICENSE.md` that fails
 * `mergeDebugAndroidTestJavaResource`, and this module has an androidTest source set. The plan's
 * listing for this file used `@StartStop` and could not have compiled here. Same pattern as
 * `LidarrAuthTest` and `LidarrSubmitTest`.
 */
class LidarrQueueTest {

  private lateinit var server: MockWebServer

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  private fun client(apiKey: String = API_KEY): LidarrClient {
    val url = IntegrationBaseUrl.parse(server.url("/").toString(), CleartextPolicy.Allowed)
    return LidarrClient(IntegrationCredentials.Lidarr((url as BaseUrlResult.Valid).url, apiKey))
  }

  /**
   * The next request the client actually sent, or a failed assertion if it sent none.
   *
   * Deliberately not the no-argument `takeRequest`: that one blocks forever on an empty queue, so a
   * client that stopped issuing a request at all -- the exact regression these wire assertions
   * exist to catch -- would hang the build rather than fail this test.
   */
  private fun nextRequest(): RecordedRequest {
    val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(request)
      .describedAs("a request within %d s -- the client sent none", REQUEST_TIMEOUT_SECONDS)
      .isNotNull()
    return request!!
  }

  private fun json(body: String) =
    MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()

  /**
   * **A real, captured empty page**, not a hand-written one: `fixtures/lidarr/queue-empty.json` came
   * off a live `3.1.0.4875-ls40` and is what proves the two facts this client is built on --
   * `"pageSize": 10` is genuinely the default, and `"records": []` is genuinely **present** rather
   * than omitted.
   */
  private fun emptyPage() = json(readFixture("lidarr/queue-empty.json"))

  /**
   * The queue is asked for a page big enough to contain the answer, and for the records that are
   * hidden by default.
   *
   * `pageSize` defaults to **10** (`PagingResource.cs`, and measured: the committed
   * `queue-empty.json` capture answers `"pageSize": 10` to a bare request), so a client that
   * accepted the default would stop seeing its own request the moment the user had eleven things
   * downloading, and would report `Requested` forever with nothing wrong anywhere.
   *
   * `includeUnknownArtistItems=true` matters for the same reason: items whose artist Lidarr cannot
   * resolve are hidden by default, and an album added seconds ago is exactly the case where the
   * artist may not be resolved yet -- measured on a live server, a freshly added album has no
   * releases and no tracks for some seconds after the add returns 201.
   */
  @Test
  fun `the queue is asked for a page big enough to contain the answer`() = runTest {
    server.enqueue(emptyPage())

    client().queue()

    val url = nextRequest().url
    assertThat(url.encodedPath).isEqualTo("/api/v1/queue")
    assertThat(url.queryParameter("pageSize")).isEqualTo("100")
    assertThat(url.queryParameter("includeUnknownArtistItems")).isEqualTo("true")
  }

  @Test
  fun `every queue record field is read from its own record`() = runTest {
    server.enqueue(
      json(
        """
        {"page":1,"pageSize":100,"totalRecords":2,"records":[
          {"id":1,"albumId":11,"artistId":21,"size":100.0,"sizeleft":25.0,
           "trackedDownloadState":"downloading","trackedDownloadStatus":"ok"},
          {"id":2,"albumId":12,"artistId":22,"size":400.0,"sizeleft":0.0,
           "trackedDownloadState":"importFailed","trackedDownloadStatus":"error",
           "errorMessage":"no audio files found"}
        ]}
        """.trimIndent(),
      ),
    )

    val items = client().queue()

    // Exact mapped lists, in order, two distinct values per field. One value per field would be
    // satisfied by a constant; the order is pinned too, because a poller correlating by index
    // rather than by albumId would be broken by a reorder.
    assertThat(items).hasSize(2)
    assertThat(items.map { it.albumId }).containsExactly(11, 12)
    assertThat(items.map { it.artistId }).containsExactly(21, 22)
    assertThat(items.map { it.sizeBytes }).containsExactly(100.0, 400.0)
    assertThat(items.map { it.sizeLeftBytes }).containsExactly(25.0, 0.0)
    assertThat(items.map { it.trackedDownloadState }).containsExactly("downloading", "importFailed")
    assertThat(items.map { it.trackedDownloadStatus }).containsExactly("ok", "error")
    assertThat(items.map { it.errorMessage }).containsExactly(null, "no audio files found")
  }

  /**
   * The whole pipeline on a body shaped like a real queue: three records, read off the wire, mapped
   * to statuses.
   *
   * **`fixtures/lidarr/queue-downloading.json` is constructed from `QueueResource.cs`, not
   * captured, and its own header says so.** A queue record exists only while a download client is
   * working; the container this task ran against has no download client and no indexer, so
   * `GET /api/v1/queue` answered `"records":[]` on every one of the several dozen calls made to it.
   * That is a limitation of the fixture and it is stated rather than glossed: the field *names*
   * here rest on Lidarr's source, not on an observation, and `sizeleft`'s lower-case `l` is the one
   * this client would most like to have seen on a wire.
   *
   * What the fixture does carry that a hand-written literal would not bother with: a record with
   * **no `albumId` and no `artistId` at all**, which is what an unresolved-artist item looks like
   * and is the reason `includeUnknownArtistItems=true` is sent.
   */
  @Test
  fun `a realistic queue page maps to one status per record`() = runTest {
    server.enqueue(json(readFixture("lidarr/queue-downloading.json")))

    val items = client().queue()

    assertThat(items).hasSize(3)
    assertThat(items.map { it.albumId }).containsExactly(6, 7, null)
    assertThat(items.map { it.trackedDownloadState })
      .containsExactly("downloading", "importPending", "importFailed")
    // `trackedDownloadStatus` is carried and is deliberately not decided on: the second record is
    // `warning` and is still progressing, which is the case that makes branching on it wrong.
    assertThat(items.map { it.trackedDownloadStatus }).containsExactly("ok", "warning", "error")
    assertThat(items.map { LidarrStatusMapper.map(it, progress = null) }).containsExactly(
      // 419430400 bytes with 104857600 left is exactly three quarters done.
      app.muplay.integrations.RequestStatus.Downloading(75),
      // Nothing left to fetch, so 100 -- and `warning` did not make it a failure.
      app.muplay.integrations.RequestStatus.Downloading(100),
      // Lidarr's own text, not this client's generic wording.
      app.muplay.integrations.RequestStatus.Failed(
        "No files found are eligible for import in /downloads/complete/music/Some.Album",
      ),
    )
  }

  /**
   * `sizeleft` is lower-case `l`. `QueueResource` declares `Sizeleft` as a single word, and
   * `JsonNamingPolicy.CamelCase` lower-cases only the leading capital -- so it reaches the wire as
   * `sizeleft`, never `sizeLeft`. A client reading `sizeLeft` gets kotlinx's default `0.0` on every
   * record and shows every download at 100% forever, with no parse error anywhere.
   *
   * Both spellings are sent here with different values, so the assertion says *which one was read*
   * rather than merely that something was.
   */
  @Test
  fun `sizeleft is read from the lower-case field lidarr actually sends`() = runTest {
    server.enqueue(
      json(
        """{"records":[{"albumId":1,"size":100.0,"sizeleft":40.0,"sizeLeft":999.0,
             "trackedDownloadState":"downloading","trackedDownloadStatus":"ok"}]}""",
      ),
    )

    val item = client().queue().single()

    assertThat(item.sizeLeftBytes).isEqualTo(40.0)
    // ...and through the mapper, which is where a user would see the consequence.
    assertThat(LidarrStatusMapper.map(item, null))
      .isEqualTo(app.muplay.integrations.RequestStatus.Downloading(60))
  }

  @Test
  fun `an absent records array is an empty queue, not a failure`() = runTest {
    // Not the everyday case -- measured, a real empty queue sends `"records":[]` and the plan's
    // claim that `WhenWritingNull` omits it is wrong. This is the defensive path: a body that is
    // not a queue page must degrade to "nothing is downloading" rather than to a parse failure a
    // status poll cannot show anyone.
    server.enqueue(json("""{"page":1,"pageSize":100,"totalRecords":0}"""))
    assertThat(client().queue()).isEmpty()
    // ...and the everyday one, from the real capture.
    server.enqueue(emptyPage())
    assertThat(client().queue()).isEmpty()
  }

  /**
   * A record with no `trackedDownloadState` at all does not crash and does not lie.
   *
   * Lidarr omits null-valued fields, so this is a shape the wire can really produce, and `""` maps
   * exactly as any unrecognised state does -- progress, never a verdict.
   */
  @Test
  fun `a record missing its state fields reads as empty strings rather than failing`() = runTest {
    server.enqueue(json("""{"records":[{"albumId":1,"size":100.0,"sizeleft":50.0}]}"""))

    val item = client().queue().single()

    assertThat(item.trackedDownloadState).isEqualTo("")
    assertThat(item.trackedDownloadStatus).isEqualTo("")
    assertThat(item.albumId).isEqualTo(1)
    assertThat(LidarrStatusMapper.map(item, null))
      .isEqualTo(app.muplay.integrations.RequestStatus.Downloading(50))
  }

  @Test
  fun `album progress is fetched by id and read from the statistics object`() = runTest {
    server.enqueue(json("""{"id":42,"statistics":{"trackFileCount":7,"totalTrackCount":10}}"""))
    val first = client().albumProgress(42)
    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/album/42")
    assertThat(first).isEqualTo(LidarrAlbumProgress(trackFileCount = 7, totalTrackCount = 10))

    // Two observations of the path *and* of both fields, so neither is a constant.
    server.enqueue(json("""{"id":43,"statistics":{"trackFileCount":3,"totalTrackCount":3}}"""))
    val second = client().albumProgress(43)
    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/album/43")
    assertThat(second).isEqualTo(LidarrAlbumProgress(trackFileCount = 3, totalTrackCount = 3))
  }

  /**
   * The real thing: `fixtures/lidarr/album-with-statistics.json` is `GET /api/v1/album/7` captured
   * verbatim from the live `3.1.0.4875-ls40`, twenty-two top-level fields of which this client
   * reads two.
   *
   * **This fixture is the answer to a question the plan listed as unestablished** -- *"whether
   * `GET /api/v1/album/{id}` populates `statistics`"*. It does: the single-id getter returned a
   * `statistics` object byte-identical to the same album's entry in the list form, at the same
   * moment. The nested object also carries `trackCount`, `sizeOnDisk` and `percentOfTracks`, all
   * three of which are dropped here.
   */
  @Test
  fun `the real album body from a pinned lidarr maps as this client claims`() = runTest {
    server.enqueue(json(readFixture("lidarr/album-with-statistics.json")))

    val progress = client().albumProgress(7)

    assertThat(progress).isEqualTo(LidarrAlbumProgress(trackFileCount = 0, totalTrackCount = 10))
    // Nothing on disk yet out of ten known tracks, so not complete -- and the status a poll would
    // report for it is `Requested`, not `Imported`.
    assertThat(progress!!.isComplete).isFalse()
    assertThat(LidarrStatusMapper.map(null, progress))
      .isEqualTo(app.muplay.integrations.RequestStatus.Requested)
  }

  /**
   * An album with no statistics object yields `null` rather than a zeroed progress.
   *
   * `LidarrAlbumProgress(0, 0)` and "we do not know" are different facts, and this is **not a
   * hypothetical shape**: measured on the live container, an album seconds after a successful
   * `POST /api/v1/album` carries no `statistics` key at all -- in the single getter and in the list
   * form alike, so it is a property of the album rather than of the endpoint.
   */
  @Test
  fun `an album with no statistics object yields null rather than a zeroed progress`() = runTest {
    server.enqueue(json("""{"id":42}"""))
    assertThat(client().albumProgress(42)).isNull()
  }

  /**
   * A user can delete an album in Lidarr while MuPlay still has a request row for it. A 404 here is
   * a normal answer to "how is this going", not an error to surface -- measured against the live
   * container on an id that never existed (`/api/v1/album/99999`) and on one whose album had just
   * been deleted; both 404.
   */
  @Test
  fun `an album that is gone yields null rather than throwing`() = runTest {
    server.enqueue(MockResponse.Builder().code(404).build())
    assertThat(client().albumProgress(42)).isNull()
  }

  /**
   * ...but only a 404. Every other failure still propagates, and a 401 above all: the key having
   * stopped working is not something a status poll may swallow into "no progress information".
   */
  @Test
  fun `a failure that is not a 404 still propagates from album progress`() = runTest {
    server.enqueue(MockResponse.Builder().code(401).build())
    assertThat(runCatching { client().albumProgress(42) }.exceptionOrNull())
      .isInstanceOf(LidarrUnauthorizedException::class.java)

    server.enqueue(MockResponse.Builder().code(500).build())
    assertThat(runCatching { client().albumProgress(42) }.exceptionOrNull())
      .isInstanceOf(LidarrHttpException::class.java)

    // The queue does not swallow anything at all.
    server.enqueue(MockResponse.Builder().code(500).build())
    assertThat(runCatching { client().queue() }.exceptionOrNull())
      .isInstanceOf(LidarrHttpException::class.java)
  }

  /**
   * **The constraint this module is named for, over both endpoints this task adds: the key never
   * appears in a URL.**
   *
   * Lidarr really does accept `?apikey=` -- measured against the live container on this very
   * endpoint, `GET /api/v1/queue?apikey=...` answers **200** while the same request with no key at
   * all answers **401** -- so this is a live wrong path, not a hypothetical one. On this side of
   * the wire a query-string key would appear in every recorded request, in every fixture captured
   * with `curl -i`, in the message of any `IOException` OkHttp raises (which names the full URL),
   * and in the access log of every reverse proxy between here and Lidarr.
   *
   * Task 5's standard, applied to both new requests: the URL string, `apikey` by name, every query
   * value, the exact `encodedQuery`, userinfo, the fragment, and every header that is not
   * `X-Api-Key`. **Positive controls first**, because `allSatisfy` over an empty list is vacuously
   * true and a client that sent nothing at all would otherwise pass this test with flying colours.
   */
  @Test
  fun `neither new request carries the key anywhere but the header`() = runTest {
    server.enqueue(emptyPage())
    server.enqueue(json("""{"id":42,"statistics":{"trackFileCount":1,"totalTrackCount":2}}"""))

    val client = client(KEY_ON_TRIAL)
    client.queue()
    client.albumProgress(42)

    val requests = listOf(nextRequest(), nextRequest())
    val urls = requests.map { it.url.toString() }

    // --- positive controls, asserted first ---
    // Two requests really were made...
    assertThat(requests).hasSize(2)
    // ...to the two endpoints this task adds, and not to one constant URL.
    assertThat(urls.map { it.substringAfter(server.url("/").toString()) }).containsExactly(
      "api/v1/queue?pageSize=100&includeUnknownArtistItems=true",
      "api/v1/album/42",
    )
    // ...and the key really was sent on both, in the one place it belongs. Without this, a client
    // that simply never authenticated satisfies every negative below.
    assertThat(requests).allSatisfy { assertThat(it.headers["X-Api-Key"]).isEqualTo(KEY_ON_TRIAL) }

    // --- the negatives this test is named for ---
    assertThat(urls).allSatisfy { assertThat(it).doesNotContain(KEY_ON_TRIAL) }
    assertThat(urls).allSatisfy { assertThat(it).doesNotContain("apikey") }
    assertThat(requests).allSatisfy { assertThat(it.url.queryParameter("apikey")).isNull() }
    assertThat(requests).allSatisfy { assertThat(it.url.queryParameter("ApiKey")).isNull() }
    // No query *value* is the key, whatever the parameter happens to be called.
    assertThat(requests).allSatisfy { request ->
      (0 until request.url.querySize).forEach { i ->
        assertThat(request.url.queryParameterValue(i)).isNotEqualTo(KEY_ON_TRIAL)
        assertThat(request.url.queryParameterName(i)).isNotEqualTo(KEY_ON_TRIAL)
      }
    }
    // The exact query strings, so a parameter appearing here at all has to be one of these two.
    assertThat(requests.map { it.url.encodedQuery })
      .containsExactly("pageSize=100&includeUnknownArtistItems=true", null)
    // Userinfo -- `https://key@host/` is the other place a secret hides in a URL, and it is not
    // covered by a query assertion.
    assertThat(requests).allSatisfy { assertThat(it.url.username).isEmpty() }
    assertThat(requests).allSatisfy { assertThat(it.url.password).isEmpty() }
    // A fragment is never sent to a server, but it would be in a log line that printed the URL.
    assertThat(requests).allSatisfy { assertThat(it.url.fragment).isNull() }
    // Every *other* header. `X-Api-Key` is the one place the key belongs; a copy of it in a
    // `User-Agent`, an `Authorization` or a `Cookie` is the same leak by another name.
    assertThat(requests).allSatisfy { request ->
      request.headers.names().filterNot { it.equals("X-Api-Key", ignoreCase = true) }
        .forEach { name ->
          assertThat(request.headers.values(name))
            .describedAs("header %s", name)
            .allSatisfy { value -> assertThat(value).doesNotContain(KEY_ON_TRIAL) }
        }
    }
    // ...and the positive control for that loop: there really were other headers to search, so the
    // `forEach` above is not iterating over nothing.
    assertThat(requests).allSatisfy { request ->
      assertThat(request.headers.names().filterNot { it.equals("X-Api-Key", ignoreCase = true) })
        .isNotEmpty()
    }
  }

  private companion object {
    const val API_KEY = "0123456789abcdef0123456789abcdef"

    /**
     * A key shaped like Lidarr's own -- 32 lower-case hex characters, `Guid.NewGuid().ToString()
     * .Replace("-", "")` (`ConfigFileProvider.cs`). Not a real one: no key from any live server
     * appears in this repository.
     */
    const val KEY_ON_TRIAL = "fedcba9876543210fedcba9876543210"

    const val REQUEST_TIMEOUT_SECONDS = 5L
  }
}
