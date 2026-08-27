package app.muplay.integrations.lidarr

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The add, over a real socket.
 *
 * The subject is the **body that went out** and the **outcome that came back**. A test that
 * asserted only that a request was made would be satisfied by a client POSTing `{}` — the exact
 * failure this plan's brief names — and `{}` is not even a hypothetical: measured against a live
 * `3.1.0.4875-ls40`, `POST /api/v1/album` with an empty body answers 400 with
 * `NotEmptyValidator` on `ForeignAlbumId`, so the wrong-body client fails loudly at the server and
 * silently at a `requestCount` assertion.
 *
 * The server is started and stopped by hand rather than with `@StartStop`, because that extension
 * ships in `mockwebserver3-junit5` and this module declares plain `mockwebserver3`: the JUnit 5
 * flavour carries a second `META-INF/LICENSE.md` that fails `mergeDebugAndroidTestJavaResource`,
 * and this module has an androidTest source set. Same pattern as `LidarrAuthTest`.
 */
class LidarrSubmitTest {

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

  /**
   * The recorded request's body as JSON.
   *
   * `mockwebserver3` 5.5.0 exposes the body as a **nullable Okio `ByteString`** on
   * `RecordedRequest` -- not the `Buffer` the plan guessed at -- so [bodyText] is
   * `body?.utf8().orEmpty()`. Two things follow, and both were established by compiling rather than
   * assumed, which is what the plan asked for here.
   *
   * `ByteString` does not consume, so the same body can be read repeatedly:
   * `neither new request carries the key anywhere but the header` reads one three times, which a
   * `Buffer` would have emptied after the first. And it is **nullable** -- a GET's body is `null`,
   * not an empty string -- so a scan that only checked the text would pass vacuously on a POST
   * whose body never arrived. That is why the POST's body is asserted non-null and non-empty before
   * anything is searched inside it.
   */
  private fun bodyText(request: RecordedRequest): String = request.body?.utf8().orEmpty()

  private fun bodyOf(request: RecordedRequest): JsonObject =
    Json.parseToJsonElement(bodyText(request)).jsonObject

  private fun candidate(albumId: String, artistId: String) = LidarrAlbumCandidate(
    foreignAlbumId = albumId, title = "An album", disambiguation = null, albumType = null,
    releaseDate = null, remoteCoverUrl = null, artistName = "An artist",
    foreignArtistId = artistId, alreadyAdded = false,
    raw = Json.parseToJsonElement(
      """{"foreignAlbumId":"$albumId","artist":{"foreignArtistId":"$artistId","artistName":"An artist"}}""",
    ).jsonObject,
  )

  private val targets = LidarrAddTargets("/music", 2, 3, "all", "none")

  private fun enqueue(code: Int, body: String? = null) =
    server.enqueue(
      MockResponse.Builder().code(code)
        .apply { if (body != null) body(body) }
        .setHeader("Content-Type", "application/json")
        .build(),
    )

  @Test
  fun `the add is a POST to api v1 album with a json content type`() = runTest {
    enqueue(201, """{"id":42}""")

    client().submitAlbum(candidate("m", "a"), targets, searchNow = true)

    val request = nextRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.url.encodedPath).isEqualTo("/api/v1/album")
    assertThat(request.headers["Content-Type"]).contains("application/json")
    // The key is still a header on a mutation, and still not on the URL.
    assertThat(request.headers["X-Api-Key"]).isEqualTo(API_KEY)
    assertThat(request.url.toString()).doesNotContain("apikey")
  }

  /**
   * **The assertion the plan's brief singles out.** Not "a request was submitted": the body, read
   * back, parsed, and its identifier asserted — at two values, so a hardcoded id fails.
   */
  @Test
  fun `the body carries the identifier that was asked for, not a constant`() = runTest {
    enqueue(201, """{"id":1}""")
    client().submitAlbum(candidate("mbid-a", "art-a"), targets, searchNow = true)
    val first = bodyOf(nextRequest())
    assertThat(first["foreignAlbumId"]!!.jsonPrimitive.content).isEqualTo("mbid-a")
    assertThat(first["artist"]!!.jsonObject["foreignArtistId"]!!.jsonPrimitive.content)
      .isEqualTo("art-a")

    enqueue(201, """{"id":2}""")
    client().submitAlbum(candidate("mbid-b", "art-b"), targets, searchNow = true)
    val second = bodyOf(nextRequest())
    assertThat(second["foreignAlbumId"]!!.jsonPrimitive.content).isEqualTo("mbid-b")
    assertThat(second["artist"]!!.jsonObject["foreignArtistId"]!!.jsonPrimitive.content)
      .isEqualTo("art-b")
  }

  /**
   * The rest of the body reached the wire too, and the flags did not lose their values in transit.
   *
   * The payload builder's own tests pin every field; this one exists because `submitAlbum` could
   * build a perfect payload and then send a *different* object — serialise a typed DTO instead, or
   * post `candidate.raw` untouched — and the identifier test above would not notice, since `raw`
   * carries the right `foreignAlbumId` on its own.
   */
  @Test
  fun `the body that reaches the wire is the built payload and not the raw element`() = runTest {
    enqueue(201, """{"id":1}""")
    client().submitAlbum(candidate("m", "a"), targets, searchNow = false)

    val body = bodyOf(nextRequest())

    assertThat(body["monitored"]!!.jsonPrimitive.boolean).isTrue()
    assertThat(body["addOptions"]!!.jsonObject["searchForNewAlbum"]!!.jsonPrimitive.boolean)
      .isFalse()
    val artist = body["artist"]!!.jsonObject
    assertThat(artist["qualityProfileId"]!!.jsonPrimitive.int).isEqualTo(2)
    assertThat(artist["metadataProfileId"]!!.jsonPrimitive.int).isEqualTo(3)
    assertThat(artist["rootFolderPath"]!!.jsonPrimitive.content).isEqualTo("/music")
    assertThat(artist["addOptions"]!!.jsonObject["searchForMissingAlbums"]!!.jsonPrimitive.boolean)
      .isFalse()
    // ...and none of that is in `raw`, which is what makes the assertions above discriminating.
    assertThat(candidate("m", "a").raw.keys).doesNotContain("addOptions", "monitored")
  }

  /** The caller's `searchNow` survives the whole stack, at both values, on the wire. */
  @Test
  fun `searchNow reaches the wire at both values`() = runTest {
    enqueue(201, """{"id":1}""")
    client().submitAlbum(candidate("m", "a"), targets, searchNow = true)
    assertThat(
      bodyOf(nextRequest())["addOptions"]!!.jsonObject["searchForNewAlbum"]!!.jsonPrimitive.boolean,
    ).isTrue()

    enqueue(201, """{"id":2}""")
    client().submitAlbum(candidate("m", "a"), targets, searchNow = false)
    assertThat(
      bodyOf(nextRequest())["addOptions"]!!.jsonObject["searchForNewAlbum"]!!.jsonPrimitive.boolean,
    ).isFalse()
  }

  @Test
  fun `a 201 yields the album id from the response body`() = runTest {
    // Two ids, so `Added(albumId)` cannot be a constant. This id is what every status poll in
    // Task 7 correlates on -- getting it from the wrong place, or fixing it, breaks every later
    // status update silently.
    enqueue(201, """{"id":42,"title":"An album"}""")
    assertThat(client().submitAlbum(candidate("m", "a"), targets, true))
      .isEqualTo(LidarrAddOutcome.Added(albumId = 42))

    enqueue(201, """{"id":99,"title":"An album"}""")
    assertThat(client().submitAlbum(candidate("m", "a"), targets, true))
      .isEqualTo(LidarrAddOutcome.Added(albumId = 99))
  }

  /**
   * A success with no `id` is a loud failure carrying **the status that actually came back**.
   *
   * `Added(albumId = 0)` would put a row in the request store that every later status poll looks up
   * under an id no album has — the silent-wrong-answer class. Asserted at two statuses because the
   * status here is exactly the kind of value a constant would satisfy: `201` is what
   * `RestController.Created` returns (measured), and it is also the number an implementer would
   * hardcode. A reverse proxy that rewrote the status, or a Lidarr that returned the `200` its own
   * generated spec once documented, would then be reported as a 201 that never happened.
   */
  @Test
  fun `a created response with no id is a failure naming the status that came back`() = runTest {
    enqueue(201, """{"title":"An album"}""")
    val created = runCatching { client().submitAlbum(candidate("m", "a"), targets, true) }
      .exceptionOrNull()
    assertThat(created).isInstanceOf(LidarrHttpException::class.java)
    assertThat((created as LidarrHttpException).status).isEqualTo(201)

    enqueue(200, """{"title":"An album"}""")
    val ok = runCatching { client().submitAlbum(candidate("m", "a"), targets, true) }
      .exceptionOrNull()
    assertThat(ok).isInstanceOf(LidarrHttpException::class.java)
    assertThat((ok as LidarrHttpException).status).isEqualTo(200)
  }

  /**
   * A duplicate add is a **400**, not a 409 — measured against the live instance by posting the
   * same body twice.
   *
   * The response carries **two** things that identify it, and this client reads both:
   * `errorCode: "AlbumExistsValidator"` and the message
   * `"This album has already been added."` (`AlbumExistsValidator.GetDefaultMessageTemplate`).
   * Either alone is enough, so a Lidarr release that reworded the message *or* renamed the
   * validator still lands on [LidarrAddOutcome.AlreadyAdded], and one that changed both degrades to
   * `Rejected` showing the user the raw text — degraded, never wrong.
   */
  @Test
  fun `an already-added album is its own outcome, not a rejection`() = runTest {
    enqueue(
      400,
      """[{"propertyName":"ForeignAlbumId","errorMessage":"This album has already been added.",
          "attemptedValue":"m","severity":"error","errorCode":"AlbumExistsValidator"}]""",
    )

    assertThat(client().submitAlbum(candidate("m", "a"), targets, true))
      .isEqualTo(LidarrAddOutcome.AlreadyAdded)
  }

  /**
   * Each half of that pair on its own, so neither is dead weight.
   *
   * This is the falsification the floor cannot make: a client that read only the message passes the
   * first case and fails the second, and one that read only the code does the reverse. Both are
   * asserted, so removing either arm reddens something.
   */
  @Test
  fun `either the validator code or the message alone identifies an already-added album`() =
    runTest {
      // The code alone. A Lidarr that reworded the sentence still lands on the right outcome.
      enqueue(
        400,
        """[{"propertyName":"ForeignAlbumId","errorMessage":"You already have this one.",
            "errorCode":"AlbumExistsValidator"}]""",
      )
      assertThat(client().submitAlbum(candidate("m", "a"), targets, true))
        .isEqualTo(LidarrAddOutcome.AlreadyAdded)

      // The message alone. A Lidarr that renamed the validator class, or a proxy that dropped the
      // field, still lands on the right outcome -- this is Task 4's original behaviour, kept.
      enqueue(
        400,
        """[{"propertyName":"ForeignAlbumId","errorMessage":"This album has already been added."}]""",
      )
      assertThat(client().submitAlbum(candidate("m", "a"), targets, true))
        .isEqualTo(LidarrAddOutcome.AlreadyAdded)

      // ...and neither. The degradation the two arms above are allowed to make, made explicitly:
      // a rejection carrying the raw text, not a claim about a duplicate that nothing supports.
      enqueue(
        400,
        """[{"propertyName":"ForeignAlbumId","errorMessage":"Something new went wrong",
            "errorCode":"SomethingElseValidator"}]""",
      )
      val outcome = client().submitAlbum(candidate("m", "a"), targets, true)
      assertThat(outcome).isInstanceOf(LidarrAddOutcome.Rejected::class.java)
      assertThat((outcome as LidarrAddOutcome.Rejected).failures.map { it.errorMessage })
        .containsExactly("Something new went wrong")
    }

  /**
   * Any other validation failure is a rejection carrying every failure.
   *
   * The body is the real one, captured from the live instance by posting an add whose artist named
   * a quality profile that does not exist, a metadata profile of `0`, and a root folder that is not
   * configured — the three things a stale picker actually produces.
   */
  @Test
  fun `any other validation failure is a rejection carrying every failure`() = runTest {
    enqueue(
      400,
      """
      [{"propertyName":"Artist.QualityProfileId","errorMessage":"Quality Profile does not exist",
        "attemptedValue":999,"severity":"error","errorCode":"QualityProfileExistsValidator"},
       {"propertyName":"Artist.RootFolderPath","errorMessage":"Root folder '/nope' does not exist",
        "attemptedValue":"/nope","severity":"error","errorCode":"RootFolderExistsValidator"}]
      """.trimIndent(),
    )

    val outcome = client().submitAlbum(candidate("m", "a"), targets, true)

    assertThat(outcome).isInstanceOf(LidarrAddOutcome.Rejected::class.java)
    // The exact mapped lists, in order: `hasSize(2)` alone would be satisfied by two copies of
    // one failure, and the dotted PascalCase property name is what tells a user *which* setting
    // is wrong.
    val rejected = outcome as LidarrAddOutcome.Rejected
    assertThat(rejected.failures.map { it.propertyName })
      .containsExactly("Artist.QualityProfileId", "Artist.RootFolderPath")
    assertThat(rejected.failures.map { it.errorMessage })
      .containsExactly("Quality Profile does not exist", "Root folder '/nope' does not exist")
    assertThat(rejected.failures.map { it.errorCode })
      .containsExactly("QualityProfileExistsValidator", "RootFolderExistsValidator")
  }

  @Test
  fun `a 401 on the add is still an unauthorized failure and not an outcome`() = runTest {
    // Losing authentication is not a thing the user can act on from the request screen the way a
    // validation failure is, so it keeps propagating as an exception rather than becoming a
    // fourth outcome nobody handles specifically.
    server.enqueue(MockResponse.Builder().code(401).build())

    val thrown = runCatching { client().submitAlbum(candidate("m", "a"), targets, true) }
      .exceptionOrNull()
    assertThat(thrown).isInstanceOf(LidarrUnauthorizedException::class.java)
  }

  @Test
  fun `an already-added album can be found again by its foreign id`() = runTest {
    // `AlreadyAdded` carries no id, so status polling needs a way back to one. `GET /api/v1/album`
    // takes `foreignAlbumId`, and two observations prove the parameter is passed through.
    enqueue(200, """[{"id":7,"foreignAlbumId":"m"}]""")
    assertThat(client().findAddedAlbumId("m")).isEqualTo(7)
    val first = nextRequest()
    assertThat(first.method).isEqualTo("GET")
    assertThat(first.url.encodedPath).isEqualTo("/api/v1/album")
    assertThat(first.url.queryParameter("foreignAlbumId")).isEqualTo("m")

    enqueue(200, """[{"id":8,"foreignAlbumId":"n"}]""")
    assertThat(client().findAddedAlbumId("n")).isEqualTo(8)
    assertThat(nextRequest().url.queryParameter("foreignAlbumId")).isEqualTo("n")
  }

  /**
   * **The identifier assertion, restated for the read side.**
   *
   * A client whose `foreignAlbumId` parameter went missing does not fail: `GET /api/v1/album` with
   * no parameter is a legal request that returns **the whole library** — measured on the live
   * instance, two albums came back for the bare path. Taking the first id from that answer hands
   * every later status poll the id of somebody else's album, and nothing anywhere reports it. So
   * this client returns the id of the element that **says** it is the album that was asked for, and
   * `null` otherwise.
   */
  @Test
  fun `an answer that is not the album that was asked for yields null, not its id`() = runTest {
    enqueue(
      200,
      """[{"id":7,"foreignAlbumId":"somebody-else"},{"id":8,"foreignAlbumId":"another"}]""",
    )

    assertThat(client().findAddedAlbumId("mine")).isNull()

    // ...and the matching element is found even when it is not the first, which is what a real
    // unfiltered library answer looks like.
    enqueue(
      200,
      """[{"id":7,"foreignAlbumId":"somebody-else"},{"id":8,"foreignAlbumId":"mine"}]""",
    )
    assertThat(client().findAddedAlbumId("mine")).isEqualTo(8)
  }

  @Test
  fun `an album that is not there yields null rather than an invented id`() = runTest {
    enqueue(200, "[]")
    assertThat(client().findAddedAlbumId("missing")).isNull()
  }

  /**
   * An element that matches but carries no usable `id` is `null`, not zero.
   *
   * Zero is the id no album has, and it is what a `?: 0` would produce. Every later status poll
   * would then look up album 0 forever and report nothing wrong.
   */
  @Test
  fun `a matching element with no usable id yields null`() = runTest {
    enqueue(200, """[{"foreignAlbumId":"m","title":"no id at all"}]""")
    assertThat(client().findAddedAlbumId("m")).isNull()

    enqueue(200, """[{"id":"not a number","foreignAlbumId":"m"}]""")
    assertThat(client().findAddedAlbumId("m")).isNull()
  }

  /**
   * **The constraint this module is named for, restated for a request that carries a body.**
   *
   * A POST is a new place for an instance-wide admin credential to end up, and it is the *first*
   * request in this module with a body — so this scan checks the body as well as the URL, the query
   * string, userinfo, the fragment and every header that is not `X-Api-Key`. Both new calls are
   * driven, and `hasSize(2)` comes before every `allSatisfy`, because `allSatisfy` over an empty
   * list is vacuously true.
   */
  @Test
  fun `neither new request carries the key anywhere but the header`() = runTest {
    enqueue(201, """{"id":1}""")
    enqueue(200, """[{"id":1,"foreignAlbumId":"m"}]""")

    val client = client(API_KEY)
    client.submitAlbum(candidate("m", "a"), targets, searchNow = true)
    client.findAddedAlbumId("m")

    val requests = listOf(nextRequest(), nextRequest())

    // Positive first. Two requests really were sent, by the two methods, to the two paths.
    assertThat(requests).hasSize(2)
    assertThat(requests.map { it.method }).containsExactly("POST", "GET")
    assertThat(requests.map { it.url.encodedPath })
      .containsExactly("/api/v1/album", "/api/v1/album")
    // ...and both authenticated, in the one place the key belongs. Without this, a client that
    // never sent the key at all satisfies every negative below.
    assertThat(requests).allSatisfy { assertThat(it.headers["X-Api-Key"]).isEqualTo(API_KEY) }
    assertThat(requests).allSatisfy {
      assertThat(it.headers["Accept"]).contains("application/json")
    }
    // ...and the POST really did carry a body, so the body negatives below have something to
    // search. `body` is nullable on a `RecordedRequest` and really is null for the GET, so without
    // this the `doesNotContain` pair would be vacuously true on an add that sent nothing at all.
    assertThat(requests.first().body).isNotNull()
    assertThat(bodyText(requests.first())).contains("foreignAlbumId")
    assertThat(bodyText(requests[1])).isEmpty()

    assertThat(requests).allSatisfy { request ->
      assertThat(request.url.toString()).doesNotContain(API_KEY)
      assertThat(request.url.toString().lowercase()).doesNotContain("apikey")
      assertThat(request.url.encodedQuery.orEmpty()).doesNotContain(API_KEY)
      assertThat(request.url.encodedFragment).isNull()
      assertThat(request.url.username).isEmpty()
      assertThat(request.url.password).isEmpty()
      // The body, which no earlier request in this module had.
      assertThat(bodyText(request)).doesNotContain(API_KEY)
      assertThat(bodyText(request).lowercase()).doesNotContain("apikey")
      assertThat(request.headers.names().filter { it != "X-Api-Key" })
        .allSatisfy { name -> assertThat(request.headers[name]).doesNotContain(API_KEY) }
    }
    // The exact query strings: the POST carries none at all, and the GET carries the one parameter
    // it is supposed to and nothing smuggled in beside it.
    assertThat(requests.first().url.encodedQuery).isNull()
    assertThat(requests[1].url.encodedQuery).isEqualTo("foreignAlbumId=m")
  }

  /**
   * The other half, and the one a URL assertion cannot make: **no failure either new call raises
   * names the key**, and neither carries Lidarr's response body.
   *
   * The 400 arm is the delicate one and its body is built deliberately. A validation failure's
   * message **is** built from Lidarr's own `propertyName`/`errorMessage`, so those two are the one
   * place in this module where server text legitimately reaches an exception — putting the key
   * there would be asserting a guarantee this client does not make and should not. What it does
   * guarantee is that **the fields it does not read cannot leak**, so the key is planted in
   * `attemptedValue` (which really does echo what was sent — measured, `"attemptedValue":
   * "c35e782d-..."` on a live duplicate add) and in an unmodelled field beside it.
   */
  @Test
  fun `no failure the add or the lookup-by-id raises names the api key`() = runTest {
    val client = client(API_KEY)
    server.enqueue(MockResponse.Builder().code(401).body(API_KEY).build())
    server.enqueue(MockResponse.Builder().code(401).body(API_KEY).build())
    server.enqueue(
      MockResponse.Builder().code(400)
        .body(
          """[{"propertyName":"ForeignAlbumId","errorMessage":"nothing secret here",
              "errorCode":"NotEmptyValidator","attemptedValue":"$API_KEY",
              "aFieldThisClientDoesNotRead":"$API_KEY"}]""",
        )
        .build(),
    )
    server.enqueue(MockResponse.Builder().code(500).body(API_KEY).build())

    val raised = listOf(
      runCatching { client.submitAlbum(candidate("m", "a"), targets, true) }.exceptionOrNull(),
      runCatching { client.findAddedAlbumId("m") }.exceptionOrNull(),
      // A 400 on the add is an outcome rather than a throw, so the exception under test is the one
      // `findAddedAlbumId` raises from the same body.
      runCatching { client.findAddedAlbumId("m") }.exceptionOrNull(),
      runCatching { client.submitAlbum(candidate("m", "a"), targets, true) }.exceptionOrNull(),
    )

    // Positive first: four calls really did raise, and they raised the four expected types. A call
    // that quietly returned a default would leave every `doesNotContain` below vacuously true.
    assertThat(raised).hasSize(4)
    assertThat(raised.map { it!!::class.java }).containsExactly(
      LidarrUnauthorizedException::class.java,
      LidarrUnauthorizedException::class.java,
      LidarrValidationException::class.java,
      LidarrHttpException::class.java,
    )
    assertThat(raised).allSatisfy { assertThat(it.toString()).doesNotContain(API_KEY) }
    assertThat(raised).allSatisfy {
      assertThat(it!!.stackTraceToString()).doesNotContain(API_KEY)
    }
  }

  private companion object {
    /**
     * A 32-char lowercase hex string, the shape `ConfigFileProvider.cs` generates -- and not any
     * real instance's key. The live container this task drove generated its own, which is in no
     * file in this repository.
     */
    const val API_KEY = "0123456789abcdef0123456789abcdef"

    const val REQUEST_TIMEOUT_SECONDS = 10L
  }
}
