package app.muplay.integrations.lidarr

import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Finding an album, and reading the three things an add has to be aimed at.
 *
 * **The subject is the request as much as the response.** Every endpoint this task adds is a new
 * chance to put an instance-wide admin credential somewhere it does not belong, so
 * [no new request carries the key anywhere but the header] drives all four of them and inspects
 * every byte that went out — the same discipline `LidarrAuthTest` applies to the handshake,
 * extended rather than restated.
 *
 * The server is started and stopped by hand rather than with `@StartStop`, because that extension
 * ships in `mockwebserver3-junit5`, which this module deliberately does not declare: the JUnit 5
 * flavour carries a second `META-INF/LICENSE.md` that fails `mergeDebugAndroidTestJavaResource`,
 * and this module has an androidTest source set. Same pattern as `LidarrAuthTest`.
 */
class LidarrLookupTest {

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
    return LidarrClient(
      IntegrationCredentials.Lidarr((url as BaseUrlResult.Valid).url, apiKey),
    )
  }

  private fun json(body: String, code: Int = 200) =
    server.enqueue(
      MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build(),
    )

  /**
   * The next request the client actually sent, or a failed assertion if it sent none.
   *
   * Deliberately not the no-argument `takeRequest`: that one blocks forever on an empty queue, so
   * a client that stopped issuing a request at all -- the exact regression these wire assertions
   * exist to catch -- would hang the build rather than fail this test.
   */
  private fun nextRequest(): RecordedRequest {
    val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(request)
      .describedAs("a request within %d s -- the client sent none", REQUEST_TIMEOUT_SECONDS)
      .isNotNull()
    return request!!
  }

  // ---- the lookup request ------------------------------------------------------------------

  /** Two terms, so a `term` parameter that was dropped or hardcoded fails. */
  @Test
  fun `the lookup sends whichever term it is given, url-encoded, to album slash lookup`() = runTest {
    json("[]")
    client().lookupAlbums("kind of blue")
    val first = nextRequest().url
    assertThat(first.encodedPath).isEqualTo("/api/v1/album/lookup")
    assertThat(first.queryParameter("term")).isEqualTo("kind of blue")
    // The space really was encoded rather than sent raw, which would be a malformed request line.
    assertThat(first.encodedQuery).isEqualTo("term=kind%20of%20blue")

    json("[]")
    client().lookupAlbums("bitches brew")
    assertThat(nextRequest().url.queryParameter("term")).isEqualTo("bitches brew")
  }

  @Test
  fun `every candidate field is read from its own element`() = runTest {
    // A two-element body, so every field is observed at two values in one assertion each. A
    // mapper that hardcoded any field would produce a list with a repeated value and fail.
    json(
      """
      [
        {"foreignAlbumId":"mbid-a","title":"Kind of Blue","disambiguation":"1997 remaster",
         "albumType":"Album","releaseDate":"1959-08-17T00:00:00Z","remoteCover":"https://img/a.jpg",
         "id":0,"artist":{"foreignArtistId":"art-a","artistName":"Miles Davis","id":0}},
        {"foreignAlbumId":"mbid-b","title":"Blue Train","albumType":"EP",
         "remoteCover":"https://img/b.jpg","id":12,
         "artist":{"foreignArtistId":"art-b","artistName":"John Coltrane","id":5}}
      ]
      """.trimIndent(),
    )

    val candidates = client().lookupAlbums("blue")

    // Exact mapped lists, in order. `containsExactly` proves order as well as content, and order
    // here is Lidarr's relevance ranking -- reordering it silently would put the wrong album first.
    assertThat(candidates.map { it.foreignAlbumId }).containsExactly("mbid-a", "mbid-b")
    assertThat(candidates.map { it.title }).containsExactly("Kind of Blue", "Blue Train")
    assertThat(candidates.map { it.disambiguation }).containsExactly("1997 remaster", null)
    assertThat(candidates.map { it.albumType }).containsExactly("Album", "EP")
    assertThat(candidates.map { it.releaseDate }).containsExactly("1959-08-17T00:00:00Z", null)
    assertThat(candidates.map { it.remoteCoverUrl })
      .containsExactly("https://img/a.jpg", "https://img/b.jpg")
    assertThat(candidates.map { it.artistName }).containsExactly("Miles Davis", "John Coltrane")
    assertThat(candidates.map { it.foreignArtistId }).containsExactly("art-a", "art-b")
    // `id == 0` means "not in this Lidarr's database yet". Both values observed.
    assertThat(candidates.map { it.alreadyAdded }).containsExactly(false, true)
  }

  /**
   * `AlbumLookupController` sets `resource.RemoteCover`; it is only `ArtistLookupController` that
   * sets `RemotePoster`. A client reading `remotePoster` off an album gets null on every row and
   * shows no artwork, silently.
   */
  @Test
  fun `the cover comes from remoteCover and not from remotePoster`() = runTest {
    json(
      """
      [{"foreignAlbumId":"m","title":"t","remoteCover":"https://right.jpg",
        "remotePoster":"https://wrong.jpg","artist":{"foreignArtistId":"a","artistName":"n"}}]
      """.trimIndent(),
    )

    assertThat(client().lookupAlbums("t").single().remoteCoverUrl).isEqualTo("https://right.jpg")
  }

  @Test
  fun `the raw element is kept verbatim for the add`() = runTest {
    json(
      """[{"foreignAlbumId":"m","title":"t","someFieldThisClientDoesNotModel":"keep me",
           "artist":{"foreignArtistId":"a","artistName":"n"}}]""",
    )

    val raw = client().lookupAlbums("t").single().raw

    // Task 6 posts this object back with five fields set, exactly as Lidarr's own UI does. A
    // field this client does not model must survive the round trip -- that is the whole reason
    // `raw` exists, and dropping it is how an add starts failing validation on a field nobody
    // knew was required.
    assertThat(raw["someFieldThisClientDoesNotModel"]?.toString()).isEqualTo("\"keep me\"")
    assertThat(raw["artist"]).isNotNull()
  }

  @Test
  fun `an element with no usable identity is skipped rather than crashing the list`() = runTest {
    // `foreignAlbumId` or the nested artist's `foreignArtistId` missing makes the row unusable
    // for an add. Dropping it keeps the other results usable; failing the parse loses all of them.
    json(
      """
      [{"title":"no id"},
       {"foreignAlbumId":"m","title":"ok","artist":{"foreignArtistId":"a","artistName":"n"}},
       {"foreignAlbumId":"n","title":"no artist id","artist":{"artistName":"n"}},
       {"foreignAlbumId":"o","title":"no artist at all"},
       "not an object at all"]
      """.trimIndent(),
    )

    assertThat(client().lookupAlbums("x").map { it.foreignAlbumId }).containsExactly("m")
  }

  @Test
  fun `an empty result is an empty list, not a failure`() = runTest {
    json("[]")
    assertThat(client().lookupAlbums("nothing at all")).isEmpty()
  }

  /**
   * The two text fields a row can be shown by, absent.
   *
   * Lidarr's serializer omits null-valued fields entirely, so this is a shape it produces rather
   * than a hypothetical. A row with no title is still usable for an add -- `foreignAlbumId` and
   * the artist's `foreignArtistId` are what the validators want -- so it is kept, blank, rather
   * than dropped.
   */
  @Test
  fun `a candidate with no title and no artist name keeps its identity`() = runTest {
    json("""[{"foreignAlbumId":"m","artist":{"foreignArtistId":"a"}}]""")

    val candidate = client().lookupAlbums("t").single()

    assertThat(candidate.foreignAlbumId).isEqualTo("m")
    assertThat(candidate.foreignArtistId).isEqualTo("a")
    assertThat(candidate.title).isEmpty()
    assertThat(candidate.artistName).isEmpty()
  }

  /**
   * **A field that is not a JSON string is read as absent, not coerced.**
   *
   * Not defensive padding. `STJson` configures
   * `JsonStringEnumConverter(JsonNamingPolicy.CamelCase, true)` -- the trailing `true` is
   * `allowIntegerValues` -- so `albumType` may legally arrive as the **number** `0` rather than
   * as `"Album"`, and a Lidarr upgrade can move any of these. Rendering `0` where a user expects
   * `Album` is worse than rendering nothing, and a client that called `toString()` on the
   * primitive would do exactly that.
   */
  @Test
  fun `a field that is not a json string reads as absent rather than as its text`() = runTest {
    json(
      """
      [{"foreignAlbumId":"m","title":"t","albumType":0,"disambiguation":["a"],
        "remoteCover":{"url":"x"},"releaseDate":1959,
        "artist":{"foreignArtistId":"a","artistName":"n"}}]
      """.trimIndent(),
    )

    val candidate = client().lookupAlbums("t").single()

    assertThat(candidate.albumType).isNull()
    assertThat(candidate.disambiguation).isNull()
    assertThat(candidate.remoteCoverUrl).isNull()
    assertThat(candidate.releaseDate).isNull()
    // ...and the element is still a usable candidate, with the raw object intact for the add.
    assertThat(candidate.title).isEqualTo("t")
    assertThat(candidate.raw["albumType"]?.toString()).isEqualTo("0")
  }

  /**
   * `alreadyAdded` is a question about Lidarr's own database id, and every non-numeric shape of
   * that field answers "no" rather than throwing.
   *
   * The `"12"` row is the one that matters: a client that insisted on `JsonPrimitive.int` would
   * lose a whole result list over one quoted id.
   */
  @Test
  fun `alreadyAdded survives every shape the id field can take`() = runTest {
    json(
      """
      [{"foreignAlbumId":"a","id":"12","artist":{"foreignArtistId":"x"}},
       {"foreignAlbumId":"b","id":"not a number","artist":{"foreignArtistId":"x"}},
       {"foreignAlbumId":"c","id":{"nested":1},"artist":{"foreignArtistId":"x"}},
       {"foreignAlbumId":"d","id":0,"artist":{"foreignArtistId":"x"}},
       {"foreignAlbumId":"e","id":7,"artist":{"foreignArtistId":"x"}}]
      """.trimIndent(),
    )

    val candidates = client().lookupAlbums("t")

    assertThat(candidates.map { it.foreignAlbumId }).containsExactly("a", "b", "c", "d", "e")
    assertThat(candidates.map { it.alreadyAdded })
      .containsExactly(true, false, false, false, true)
  }

  // ---- the real body, from the pinned container ---------------------------------------------

  /**
   * The same mapper against a **real** `album/lookup` response, captured verbatim from
   * `lscr.io/linuxserver/lidarr:3.1.0.4875-ls40` proxying to `api.lidarr.audio`.
   *
   * Every claim this task rests on is asserted here against bytes nobody in this repository wrote,
   * because a hand-written body proves only that the mapper agrees with its author. Three of the
   * assertions below contradict what the plan expected, and each is the shape a real Lidarr sends:
   * `remotePoster` is not merely wrong on an album, it is **absent**; `id` is **omitted** rather
   * than sent as `0`; and an unknown release date is `0001-01-01T00:00:00Z` rather than absent.
   */
  @Test
  fun `the real lookup body from a pinned lidarr maps as this client claims`() = runTest {
    val body = readFixture("lidarr/album-lookup.json")
    json(body)

    val candidates = client().lookupAlbums("kind of blue")

    // Seven elements, all usable: every one carries a foreignAlbumId and a nested artist with a
    // foreignArtistId, so nothing was dropped and `hasSize` is what says so.
    assertThat(candidates).hasSize(7)
    assertThat(candidates.map { it.title }).containsExactly(
      "Kind of Blue", "KIND OF BLUE", "Kind of Blue", "Kind of Blue", "Kind of Blue",
      "Kind of Blue Beat", "Kind of Blues",
    )
    assertThat(candidates.first().foreignAlbumId).isEqualTo("8e8a594f-2175-38c7-a871-abb68ec363e7")
    assertThat(candidates.first().artistName).isEqualTo("Miles Davis")
    assertThat(candidates.first().foreignArtistId).isEqualTo("561d854a-6a28-4aa7-8c99-323e6ce46c2a")
    assertThat(candidates.first().albumType).isEqualTo("Album")

    // Trap 1, against the real bytes: the cover is there and it is `remoteCover`. Four of the
    // seven elements carry one; the other three omit the key entirely, which is the serializer
    // dropping a null and the reason `remoteCoverUrl` is nullable.
    assertThat(candidates.first().remoteCoverUrl)
      .isEqualTo(
        "https://images.lidarr.audio/cache/https://coverartarchive.org/release/" +
          "e7ba3cb7-a074-45ee-870f-3baeb6d3e8bf/12708426541-1200.jpg",
      )
    assertThat(candidates.count { it.remoteCoverUrl != null }).isEqualTo(4)
    // ...and `remotePoster` is not a field that is merely wrong to read here. It does not exist on
    // a single one of the seven elements, so a client that read it would show no artwork at all.
    assertThat(candidates.map { it.raw.keys }).allSatisfy {
      assertThat(it).doesNotContain("remotePoster")
    }
    // ...while `remoteCover` really is the key present on the four that have artwork, so the
    // negative above is a negative about a field that exists rather than about a typo.
    assertThat(candidates.count { it.raw.keys.contains("remoteCover") }).isEqualTo(4)

    // A real release date is a full ISO-8601 instant, never a bare date...
    assertThat(candidates.first().releaseDate).isEqualTo("1959-08-17T00:00:00Z")
    // ...and the one album whose date Lidarr does not know sent `0001-01-01T00:00:00Z` rather than
    // omitting the field. Exactly one of the seven, and it is the one this client reads as unknown.
    assertThat(candidates.count { it.releaseDate == null }).isEqualTo(1)
    assertThat(candidates.single { it.releaseDate == null }.title).isEqualTo("Kind of Blue Beat")
    assertThat(candidates.map { it.raw["releaseDate"]?.toString() })
      .contains("\"0001-01-01T00:00:00Z\"")

    // `id` is OMITTED on a lookup element for an album this Lidarr has not added, not sent as 0 --
    // measured, and the reason `alreadyAdded` reads the key defensively.
    assertThat(candidates.map { it.raw.keys }).allSatisfy { assertThat(it).doesNotContain("id") }
    assertThat(candidates.map { it.alreadyAdded }).allMatch { !it }

    // `disambiguation` arrives as "" on every element rather than being omitted; it collapses to
    // null so that no surface has two ways to render nothing.
    assertThat(candidates.map { it.disambiguation }).allMatch { it == null }
    assertThat(candidates.map { it.raw["disambiguation"]?.toString() }).allMatch { it == "\"\"" }

    // The whole element survived. The first candidate's `releases` array has 133 entries and this
    // client models none of them; Task 6 posts them all back.
    assertThat(candidates.first().raw["releases"] as? JsonArray).hasSize(133)
    assertThat(candidates.first().raw.keys).hasSize(20)
  }

  /**
   * `/album/lookup` is not served from the user's own database — `SkyHookProxy` proxies it to
   * `api.lidarr.audio`. Measured by blackholing that host inside the pinned container: the failure
   * is a **503** carrying `{"message":…,"description":<a .NET stack trace>}`, which is neither the
   * starting-up body nor anything this client may retry as one.
   *
   * The plan's *"could not establish"* table lists this status as unknown. It is 503, and this is
   * what stops that 503 being read as "your Lidarr is booting, wait a moment" when the truth is
   * "the metadata service is down and waiting will not help".
   */
  @Test
  fun `a lookup that fails upstream is a plain http failure and not a starting-up one`() = runTest {
    json(readFixture("lidarr/lookup-unavailable.json"), code = 503)

    val raised = runCatching { client().lookupAlbums("bitches brew") }.exceptionOrNull()

    assertThat(raised).isInstanceOf(LidarrHttpException::class.java)
    assertThat((raised as LidarrHttpException).status).isEqualTo(503)
    // The body carries a full stack trace and the user's own search term. Neither reaches the
    // exception, which is what a crash reporter would upload.
    assertThat(raised.toString()).doesNotContain("SkyHookException")
    assertThat(raised.toString()).doesNotContain("bitches brew")
  }

  // ---- root folders and profiles ------------------------------------------------------------

  @Test
  fun `root folders carry the defaults an add needs`() = runTest {
    json(
      """
      [{"id":1,"name":"Music","path":"/music","accessible":true,"freeSpace":123,
        "defaultQualityProfileId":2,"defaultMetadataProfileId":3,
        "defaultMonitorOption":"all","defaultNewItemMonitorOption":"none"},
       {"id":4,"name":"Archive","path":"/archive","accessible":false,
        "defaultQualityProfileId":5,"defaultMetadataProfileId":6,
        "defaultMonitorOption":"future","defaultNewItemMonitorOption":"all"}]
      """.trimIndent(),
    )

    val folders = client().rootFolders()

    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/rootfolder")
    // Every field at two values.
    assertThat(folders.map { it.id }).containsExactly(1, 4)
    assertThat(folders.map { it.name }).containsExactly("Music", "Archive")
    assertThat(folders.map { it.path }).containsExactly("/music", "/archive")
    assertThat(folders.map { it.accessible }).containsExactly(true, false)
    assertThat(folders.map { it.freeSpaceBytes }).containsExactly(123L, null)
    assertThat(folders.map { it.defaultQualityProfileId }).containsExactly(2, 5)
    assertThat(folders.map { it.defaultMetadataProfileId }).containsExactly(3, 6)
    assertThat(folders.map { it.defaultMonitorOption }).containsExactly("all", "future")
    assertThat(folders.map { it.defaultNewItemMonitorOption }).containsExactly("none", "all")
  }

  /**
   * A nameless folder is shown by its path, and a folder Lidarr omitted every optional field for
   * still maps to something a picker can render rather than to a row of blanks. Lidarr's
   * serializer drops null-valued fields entirely, so this is a shape it really produces.
   */
  @Test
  fun `a root folder with no name is identified by its path`() = runTest {
    json("""[{"id":1,"path":"/music","accessible":true},{"id":2,"name":"  ","path":"/other"}]""")

    val folders = client().rootFolders()

    assertThat(folders.map { it.name }).containsExactly("/music", "/other")
    assertThat(folders.map { it.defaultMonitorOption }).containsExactly("", "")
    assertThat(folders.map { it.freeSpaceBytes }).containsExactly(null, null)
    assertThat(folders.map { it.accessible }).containsExactly(true, false)
  }

  /**
   * A folder with no path either. It cannot be added to -- [LidarrAddTargets.resolve] refuses a
   * blank path -- but it must not crash the picker on the way there, and it must not borrow
   * another folder's name.
   */
  @Test
  fun `a root folder with neither name nor path is blank rather than absent`() = runTest {
    json("""[{"id":9}]""")

    val folder = client().rootFolders().single()

    assertThat(folder.id).isEqualTo(9)
    assertThat(folder.name).isEmpty()
    assertThat(folder.path).isEmpty()
    assertThat(LidarrAddTargets.resolve(folder, emptyList(), emptyList())).isNull()
  }

  /** The real body, from the pinned container's own `/music` root folder. */
  @Test
  fun `the real root folder body from a pinned lidarr maps as this client claims`() = runTest {
    json(readFixture("lidarr/rootfolder.json"))

    val folder = client().rootFolders().single()

    assertThat(folder.id).isEqualTo(1)
    assertThat(folder.name).isEqualTo("Music")
    assertThat(folder.path).isEqualTo("/music")
    assertThat(folder.accessible).isTrue()
    assertThat(folder.freeSpaceBytes).isEqualTo(70933176320L)
    assertThat(folder.defaultQualityProfileId).isEqualTo(1)
    assertThat(folder.defaultMetadataProfileId).isEqualTo(1)
    assertThat(folder.defaultMonitorOption).isEqualTo("all")
    assertThat(folder.defaultNewItemMonitorOption).isEqualTo("all")

    // ...and it resolves, which is the whole point of reading those four defaults.
    val targets = LidarrAddTargets.resolve(
      folder,
      listOf(LidarrProfile(1, "Any")),
      listOf(LidarrProfile(1, "Standard")),
    )
    assertThat(targets).isEqualTo(LidarrAddTargets("/music", 1, 1, "all", "all"))
  }

  @Test
  fun `quality and metadata profiles are two different endpoints and both are read`() = runTest {
    json("""[{"id":1,"name":"Any"},{"id":2,"name":"Lossless"}]""")
    val quality = client().qualityProfiles()
    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/qualityprofile")
    assertThat(quality.map { it.id }).containsExactly(1, 2)
    assertThat(quality.map { it.name }).containsExactly("Any", "Lossless")

    json("""[{"id":7,"name":"Standard"}]""")
    val metadata = client().metadataProfiles()
    // Two observations of the *path*: a client that called one endpoint for both would fail here.
    assertThat(nextRequest().url.encodedPath).isEqualTo("/api/v1/metadataprofile")
    assertThat(metadata.map { it.id }).containsExactly(7)
    assertThat(metadata.map { it.name }).containsExactly("Standard")
  }

  /**
   * Both real bodies, from the pinned container. They are 34 KB and 7 KB of quality and album-type
   * trees around the two fields this client reads, which is the case for `ignoreUnknownKeys` --
   * and the case for asserting it against the real thing rather than against two-field stubs.
   */
  @Test
  fun `the real profile bodies from a pinned lidarr map as this client claims`() = runTest {
    json(readFixture("lidarr/qualityprofile.json"))
    val quality = client().qualityProfiles()
    assertThat(quality.map { it.id }).containsExactly(1, 2, 3)
    assertThat(quality.map { it.name }).containsExactly("Any", "Lossless", "Standard")

    json(readFixture("lidarr/metadataprofile.json"))
    val metadata = client().metadataProfiles()
    assertThat(metadata.map { it.id }).containsExactly(1, 2)
    assertThat(metadata.map { it.name }).containsExactly("Standard", "None")
  }

  @Test
  fun `a profile with no name at all is an empty name, not a parse failure`() = runTest {
    json("""[{"id":3},{"name":"nameless id"}]""")
    val quality = client().qualityProfiles()
    assertThat(quality.map { it.id }).containsExactly(3, 0)
    assertThat(quality.map { it.name }).containsExactly("", "nameless id")

    // The second endpoint separately: the two share a DTO but not a mapping call site, and a
    // `name.orEmpty()` dropped from one of them is invisible in the other.
    json("""[{"id":4}]""")
    val metadata = client().metadataProfiles()
    assertThat(metadata.map { it.id }).containsExactly(4)
    assertThat(metadata.map { it.name }).containsExactly("")
  }

  // ---- the credential, on every one of the four new requests ---------------------------------

  /**
   * **The constraint this module is named for, restated for every request this task adds.**
   *
   * A Lidarr API key is instance-wide and carries admin authority, so each new endpoint is a fresh
   * chance to put it somewhere it survives: a query string reaches every reverse-proxy access log,
   * every `IOException` message OkHttp builds (which names the full URL), every fixture captured
   * with `curl -i`, and every `RecordedRequest` a report pastes.
   *
   * All four new calls are driven here and **every** recorded request is inspected, rather than the
   * first: an endpoint that authenticated differently from the other three is exactly what a
   * first-request assertion cannot see. `hasSize(4)` comes before every `allSatisfy`, because
   * `allSatisfy` over an empty list is vacuously true -- the defect class this repository has
   * shipped before.
   */
  @Test
  fun `no new request carries the key anywhere but the header`() = runTest {
    json("[]")
    json("[]")
    json("[]")
    json("[]")

    val client = client(API_KEY)
    client.lookupAlbums("a term with spaces")
    client.rootFolders()
    client.qualityProfiles()
    client.metadataProfiles()

    val requests = listOf(nextRequest(), nextRequest(), nextRequest(), nextRequest())

    // Positive first. Four requests really were sent, to the four paths this task adds, in order.
    assertThat(requests).hasSize(4)
    assertThat(requests.map { it.url.encodedPath }).containsExactly(
      "/api/v1/album/lookup",
      "/api/v1/rootfolder",
      "/api/v1/qualityprofile",
      "/api/v1/metadataprofile",
    )
    // ...and every one of them authenticated, in the one place the key belongs. Without this, a
    // client that simply never sent the key passes every negative below.
    assertThat(requests).allSatisfy { assertThat(it.headers["X-Api-Key"]).isEqualTo(API_KEY) }
    assertThat(requests).allSatisfy {
      assertThat(it.headers["Accept"]).contains("application/json")
    }

    // The negatives, over the whole request: URL, query string, and every header value other than
    // the one header that is supposed to carry it.
    assertThat(requests).allSatisfy { request ->
      assertThat(request.url.toString()).doesNotContain(API_KEY)
      assertThat(request.url.toString().lowercase()).doesNotContain("apikey")
      assertThat(request.url.encodedQuery.orEmpty()).doesNotContain(API_KEY)
      assertThat(request.url.encodedFragment).isNull()
      assertThat(request.url.username).isEmpty()
      assertThat(request.url.password).isEmpty()
      assertThat(request.headers.names().filter { it != "X-Api-Key" })
        .allSatisfy { name -> assertThat(request.headers[name]).doesNotContain(API_KEY) }
    }
    // The lookup's query string carries the search term and nothing else -- no key smuggled in
    // beside it.
    assertThat(requests.first().url.encodedQuery).isEqualTo("term=a%20term%20with%20spaces")
    assertThat(requests.drop(1)).allSatisfy { assertThat(it.url.encodedQuery).isNull() }
  }

  /**
   * The other half, and the one a URL assertion cannot make: **no failure any of the four new
   * calls raises names the key**, and none of them carries Lidarr's response body either.
   *
   * An exception message is the likeliest way a secret leaves a process -- interpolated into a bug
   * report, uploaded by a crash reporter, printed by whatever `catch` a later task writes.
   */
  @Test
  fun `no failure the new calls raise names the api key or the response body`() = runTest {
    val client = client(API_KEY)
    // A 401 for each of the four. Lidarr answers a bad key with a bare 401 on every endpoint.
    repeat(4) { server.enqueue(MockResponse.Builder().code(401).body(API_KEY).build()) }

    val raised = listOf(
      runCatching { client.lookupAlbums("t") }.exceptionOrNull(),
      runCatching { client.rootFolders() }.exceptionOrNull(),
      runCatching { client.qualityProfiles() }.exceptionOrNull(),
      runCatching { client.metadataProfiles() }.exceptionOrNull(),
    )

    // Positive first: four calls really did raise. A call that quietly returned an empty list
    // would leave every `doesNotContain` below vacuously true.
    assertThat(raised).hasSize(4)
    assertThat(raised).allSatisfy {
      assertThat(it).isInstanceOf(LidarrUnauthorizedException::class.java)
    }
    // The response body was the key itself, so this is the strongest form of the assertion: even
    // a client that echoed what the server sent would fail it.
    assertThat(raised).allSatisfy { assertThat(it.toString()).doesNotContain(API_KEY) }
    assertThat(raised).allSatisfy {
      assertThat(it!!.stackTraceToString()).doesNotContain(API_KEY)
    }
  }

  private companion object {
    /**
     * A 32-char lowercase hex string, the shape `ConfigFileProvider.cs` generates -- and not any
     * real instance's key. The container these fixtures came from generated its own, which is in
     * none of them.
     */
    const val API_KEY = "0123456789abcdef0123456789abcdef"

    const val REQUEST_TIMEOUT_SECONDS = 10L
  }
}
