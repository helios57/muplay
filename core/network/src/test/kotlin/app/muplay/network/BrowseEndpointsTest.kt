package app.muplay.network

import app.muplay.model.AlbumListType
import app.muplay.model.SubsonicCredentials
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Real HTTP over a real socket, through the real Retrofit + kotlinx.serialization stack, against
 * bodies captured from a real Navidrome — and, for every command, **an assertion on the request
 * this client actually sent**.
 *
 * That last part is why this class exists rather than a handful of response-mapping tests. Plan
 * 1's final review proved by mutation that `SubsonicAuth.authParams()` returning an empty map —
 * no credentials on the wire at all — left every test green at 100% branch coverage, because
 * nothing anywhere inspected a request. Coverage measures execution, not assertion. Six new
 * commands land here, and `musicFolderId` is not a nice-to-have parameter: it is the only
 * mechanism library-scoped shuffle has, and it is a *request* parameter. Omit it, mistype it, or
 * let it arrive empty and every response still parses, every mapping test still passes, and the
 * user's music shuffle quietly starts playing audiobook chapters.
 *
 * Protocol constants (`v`, `c`) are asserted as **literals**, never through
 * `SubsonicAuth.PROTOCOL_VERSION`/`CLIENT_NAME`: they are imposed from outside this codebase, and
 * reading them from the constant under test would let a change to that constant pass unnoticed.
 * `t` is recomputed here from the salt actually sent, so the assertion is on the bytes on the
 * wire rather than on `authParams()` agreeing with itself. This duplicates
 * `SubsonicClientTest`'s helper on purpose — sharing it would let one edit weaken both.
 */
class BrowseEndpointsTest {

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

  // --- getAlbumList2 -------------------------------------------------------------------------

  @Test
  fun `getAlbumList2 sends the scope, the type, the page and full authentication`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    // Deliberately not `size = 500, offset = 0`: those are the clamp ceiling and the clamp floor,
    // so a client that hardcoded either would still satisfy this test. The whole class of defect
    // this file exists to catch is a parameter that *executes* but is never *discriminated*, and
    // a value that coincides with a constant discriminates nothing. See the companion test below,
    // which sends an entirely different set, and `getAlbumList2 clamps ...`, which sends the
    // boundaries: between the three, no fixed string satisfies any parameter.
    client.getAlbumList2(musicFolderId = 1, type = AlbumListType.ALPHABETICAL_BY_NAME, size = 250, offset = 40)

    val url = assertAuthenticatedRequestTo("/rest/getAlbumList2")
    // The scope, on the wire, as a plain decimal integer. Navidrome silently ignores a
    // musicFolderId it cannot parse and widens the response to every library, so "" or "abc"
    // here would be a scope leak that no response assertion could ever catch.
    assertThat(url.queryParameter("musicFolderId")).isEqualTo("1")
    assertThat(url.queryParameter("type")).isEqualTo("alphabeticalByName")
    assertThat(url.queryParameter("size")).isEqualTo("250")
    // `offset` is the reconcile paging loop's entire mechanism. Asserted at a real page rather
    // than at 0: with every offset assertion in this file expecting "0", replacing the whole
    // expression with the constant `"0"` -- a client that can only ever fetch page one -- left
    // all 95 tests green at 100% branch coverage. Found by the independent review, not by a gate.
    assertThat(url.queryParameter("offset")).isEqualTo("40")
  }

  @Test
  fun `getAlbumList2 sends whichever ordering, scope and page it is given`() = runTest {
    enqueue(fixture(ALBUM_LIST_AUDIOBOOKS_FIXTURE))

    client.getAlbumList2(musicFolderId = 2, type = AlbumListType.NEWEST, size = 500, offset = 0)

    val url = assertAuthenticatedRequestTo("/rest/getAlbumList2")
    // A second, disjoint set of values for all four parameters. That is what this test is for:
    // every one of them was previously observed at exactly one value, so a hardcoded constant
    // satisfied the suite. `NEWEST` in particular was sent by nothing at all -- corrupting its
    // wire value to "nEwEsT_typo" left all 95 tests green, and because `AlbumListType` is a
    // deliberate zero-branch coverage rider no floor could ever have moved for it either. The
    // real server answers an unimplemented type with `status: "failed"`, error 0 (`LiveNavidromeTest`
    // measures both directions), so the literal below is a protocol contract, not a spelling.
    assertThat(url.queryParameter("type")).isEqualTo("newest")
    assertThat(url.queryParameter("musicFolderId")).isEqualTo("2")
    assertThat(url.queryParameter("size")).isEqualTo("500")
    assertThat(url.queryParameter("offset")).isEqualTo("0")
  }

  @Test
  fun `getAlbumList2 stamps every album with the library it was scoped to`() = runTest {
    // The *same bytes*, twice, at two different scopes. That is the whole assertion: no Subsonic
    // response carries a library id -- `AlbumID3` has no such property and Navidrome sends none --
    // so if one identical body yields libraryId 2 on one call and 5 on the next, the stamp
    // provably came from the argument and from nowhere else.
    //
    // One scope would not prove it. A client that hardcoded `toAlbum(2)` satisfies a single
    // `== 2` assertion exactly as well as the correct one does, which is how the stamps on all
    // four commands were hardcoded with `./gradlew check` still at exit 0.
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    val music = client.getAlbumList2(2, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0)
    val elsewhere = client.getAlbumList2(5, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0)

    // `map { it.libraryId }` rather than `allMatch`: `allMatch` is vacuously true on an empty
    // list, so a mapper that returned nothing at all would pass it.
    assertThat(music.map { it.libraryId }).containsExactly(2)
    assertThat(elsewhere.map { it.libraryId }).containsExactly(5)
    // Same body both times, so any difference between the two can only have come from the scope.
    assertThat(music.map { it.id }).isEqualTo(elsewhere.map { it.id })
  }

  @Test
  fun `getAlbumList2 maps the captured album fields`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    val album = client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).single()

    assertThat(album.name).isEqualTo("Test Album")
    assertThat(album.artistName).isEqualTo("Test Artist")
    assertThat(album.songCount).isEqualTo(3)
    assertThat(album.durationSeconds).isEqualTo(15)
    assertThat(album.id).isNotBlank
    assertThat(album.artistId).isNotBlank
    assertThat(album.coverArtId).isNotBlank
  }

  @Test
  fun `an albumList2 container with no album key maps to no albums, not to a failure`() = runTest {
    // Captured live from a past-the-end offset: `"albumList2": {}`. The reconcile paging loop in
    // the sync engine terminates on exactly this, so mapping it to an error would make a full
    // library sync impossible rather than merely wrong.
    enqueue(fixture(ALBUM_LIST_EMPTY_FIXTURE))

    assertThat(client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, 500, 99)).isEmpty()
  }

  @Test
  fun `a successful getAlbumList2 with no albumList2 payload at all maps to no albums`() = runTest {
    // The other half of the flattened envelope's asymmetric mapping rule, and the half the
    // `"albumList2": {}` capture cannot reach: there the container is present and only `album` is
    // absent, so `body.albumList2?.album` never takes its null branch. Here the whole payload
    // field is missing. A list-shaped payload maps that to "no results" -- unlike `getAlbum` and
    // `getScanStatus` below, whose payload *is* the entire answer and which throw instead.
    enqueue(OK_WITH_NO_PAYLOAD)

    assertThat(client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0)).isEmpty()
  }

  @Test
  fun `getAlbumList2 clamps its page size to the protocol maximum`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, size = 5_000, offset = 0)

    // Subsonic caps this at 500 and silently truncates, so a caller that asked for 5000 and
    // believed it would page straight past 4500 albums. Clamping in the client makes the number
    // on the wire and the number the caller reasons about the same one.
    assertThat(nextRequest().url.queryParameter("size")).isEqualTo("500")
  }

  @Test
  fun `getAlbumList2 clamps a non-positive size up to one and a negative offset up to zero`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, size = 0, offset = -10)

    // The mirror image of the 500 clamp, and the same argument: neither value is documented, so
    // rather than letting the server decide what a negative offset means, the client asks the
    // smallest well-defined question -- one album, from the start of the list.
    val url = nextRequest().url
    assertThat(url.queryParameter("size")).isEqualTo("1")
    assertThat(url.queryParameter("offset")).isEqualTo("0")
  }

  // --- getAlbum ------------------------------------------------------------------------------

  @Test
  fun `getAlbum sends whichever album id it is given and must not send a scope`() = runTest {
    enqueue(fixture(ALBUM_WITH_SONGS_FIXTURE))
    enqueue(fixture(ALBUM_WITH_SONGS_FIXTURE))

    // Two calls with two different ids, for the reason the whole class exists: one call asserting
    // one id proves only that *an* id arrived, which a hardcoded constant satisfies too. The
    // second id is the real one Navidrome minted for the captured album, so this also pins that
    // the client passes a base62 server id through untouched rather than normalising it.
    client.getAlbum(albumId = "abc123", musicFolderId = 1)
    val first = assertAuthenticatedRequestTo("/rest/getAlbum")
    assertThat(first.queryParameter("id")).isEqualTo("abc123")
    // The spec gives getAlbum exactly one parameter. `musicFolderId` here is a *stamping*
    // argument -- the library the caller already scoped by -- and sending it would be inventing
    // a parameter the endpoint does not define.
    assertThat(first.queryParameter("musicFolderId")).isNull()

    client.getAlbum(albumId = CAPTURED_ALBUM_ID, musicFolderId = 2)
    val second = assertAuthenticatedRequestTo("/rest/getAlbum")
    assertThat(second.queryParameter("id")).isEqualTo(CAPTURED_ALBUM_ID)
    assertThat(second.queryParameter("musicFolderId")).isNull()
  }

  @Test
  fun `getAlbum maps the album and its songs and stamps both with the library`() = runTest {
    enqueue(fixture(ALBUM_WITH_SONGS_FIXTURE))

    val result = client.getAlbum("abc123", musicFolderId = 7)

    assertThat(result.album.libraryId).isEqualTo(7)
    assertThat(result.album.name).isEqualTo("Test Album")
    assertThat(result.songs).hasSize(3)
    assertThat(result.songs).allMatch { it.libraryId == 7 }
    assertThat(result.songs.map { it.title })
      .containsExactlyInAnyOrder("Track 1", "Track 2", "Track 3")
    assertThat(result.songs.map { it.trackNumber }).containsExactlyInAnyOrder(1, 2, 3)
    assertThat(result.songs).allMatch { it.suffix == "mp3" }
  }

  @Test
  fun `getAlbum stamps the album and every song from the argument, not from the body`() = runTest {
    enqueue(fixture(ALBUM_WITH_SONGS_FIXTURE))
    enqueue(fixture(ALBUM_WITH_SONGS_FIXTURE))

    val seventh = client.getAlbum(CAPTURED_ALBUM_ID, musicFolderId = 7)
    val eleventh = client.getAlbum(CAPTURED_ALBUM_ID, musicFolderId = 11)

    // `getAlbum` is the command where the stamp is *all* the argument does -- `musicFolderId` is
    // never sent (the test above asserts it is absent from the wire), so if the stamp does not
    // follow it, the argument does nothing whatsoever and nothing in the build would say so.
    assertThat(seventh.album.libraryId).isEqualTo(7)
    assertThat(eleventh.album.libraryId).isEqualTo(11)
    assertThat(seventh.songs.map { it.libraryId }).containsExactly(7, 7, 7)
    assertThat(eleventh.songs.map { it.libraryId }).containsExactly(11, 11, 11)
    assertThat(seventh.songs.map { it.id }).isEqualTo(eleventh.songs.map { it.id })
  }

  @Test
  fun `a successful getAlbum with no album payload is malformed, not empty`() = runTest {
    // `SubsonicResponseBody` is one flattened envelope, so every payload field is nullable and a
    // missing one decodes silently. For a list-shaped payload that means "no results"; for
    // getAlbum it means the server said "ok" and told us nothing, and mapping that to an empty
    // album is how a full reconcile deletes a real album's songs.
    enqueue(OK_WITH_NO_PAYLOAD)

    assertThat(thrownBy { client.getAlbum("abc123", 1) })
      .isInstanceOf(SubsonicMalformedResponseException::class.java)
      .hasMessageContaining("album")
  }

  // --- search3 -------------------------------------------------------------------------------

  @Test
  fun `search3 sends the query, the scope and the three counts`() = runTest {
    enqueue(fixture(SEARCH3_FIXTURE))

    client.search3(query = "tra ck", musicFolderId = 1, artistCount = 5, albumCount = 10, songCount = 20)

    val url = assertAuthenticatedRequestTo("/rest/search3")
    // Read back through HttpUrl's own decoding, so a space that must be percent-encoded on the
    // wire is asserted as the value the server will actually see.
    assertThat(url.queryParameter("query")).isEqualTo("tra ck")
    assertThat(url.queryParameter("musicFolderId")).isEqualTo("1")
    assertThat(url.queryParameter("artistCount")).isEqualTo("5")
    assertThat(url.queryParameter("albumCount")).isEqualTo("10")
    assertThat(url.queryParameter("songCount")).isEqualTo("20")
    // A raw space in a query string is not legal and OkHttp encodes it; asserting the encoded
    // form as well pins that this went out as one parameter rather than two.
    assertThat(request().url.encodedQuery).contains("query=tra%20ck")
  }

  @Test
  fun `search3 maps artists, albums and songs and stamps all three`() = runTest {
    enqueue(fixture(SEARCH3_FIXTURE))

    val results = client.search3("Test", musicFolderId = 3, artistCount = 5, albumCount = 5, songCount = 5)

    assertThat(results.artists.map { it.name }).contains("Test Artist")
    assertThat(results.albums.map { it.name }).contains("Test Album")
    assertThat(results.songs.map { it.title })
      .containsExactlyInAnyOrder("Track 1", "Track 2", "Track 3")
    assertThat(results.artists).allMatch { it.libraryId == 3 }
    assertThat(results.albums).allMatch { it.libraryId == 3 }
    assertThat(results.songs).allMatch { it.libraryId == 3 }

    // The stamp above is only trustworthy if the scope it claims to come from actually went out,
    // so the request is asserted here too -- and a second query and a second scope mean no fixed
    // string satisfies either parameter across this file.
    val url = nextRequest().url
    assertThat(url.queryParameter("query")).isEqualTo("Test")
    assertThat(url.queryParameter("musicFolderId")).isEqualTo("3")
  }

  @Test
  fun `a successful search3 with no searchResult3 payload matches nothing`() = runTest {
    // Same rule, same reason as the `getAlbumList2` case above: a search that matched nothing is
    // a real answer, so an absent container is an empty result rather than a failure. Asserted
    // through `isEmpty` so the one piece of author-written logic on `SearchResults` is exercised
    // by the code path that can actually produce all-empty results.
    enqueue(OK_WITH_NO_PAYLOAD)

    val results = client.search3("nothing matches this", 1, 5, 5, 5)

    assertThat(results.isEmpty).isTrue
    assertThat(results.artists).isEmpty()
    assertThat(results.albums).isEmpty()
    assertThat(results.songs).isEmpty()
  }

  @Test
  fun `search3 clamps negative counts to zero`() = runTest {
    enqueue(fixture(SEARCH3_FIXTURE))

    client.search3("Test", musicFolderId = 1, artistCount = -1, albumCount = -5, songCount = -20)

    // Zero of a kind is a request Subsonic defines ("do not return any artists"); a negative
    // count is not, and sending one would leave the number of results entirely to the server.
    val url = nextRequest().url
    assertThat(url.queryParameter("artistCount")).isEqualTo("0")
    assertThat(url.queryParameter("albumCount")).isEqualTo("0")
    assertThat(url.queryParameter("songCount")).isEqualTo("0")
  }

  @Test
  fun `search3 stamps every artist, album and song from the argument, not from the body`() = runTest {
    enqueue(fixture(SEARCH3_FIXTURE))
    enqueue(fixture(SEARCH3_FIXTURE))

    val third = client.search3("Test", musicFolderId = 3, artistCount = 5, albumCount = 5, songCount = 5)
    val sixth = client.search3("Test", musicFolderId = 6, artistCount = 5, albumCount = 5, songCount = 5)

    // All three result kinds, because `search3` is the only command that produces an `Artist` at
    // all -- its stamp has no other test anywhere in the build.
    assertThat(third.artists.map { it.libraryId }).containsExactly(3)
    assertThat(third.albums.map { it.libraryId }).containsExactly(3)
    assertThat(third.songs.map { it.libraryId }).containsExactly(3, 3, 3)
    assertThat(sixth.artists.map { it.libraryId }).containsExactly(6)
    assertThat(sixth.albums.map { it.libraryId }).containsExactly(6)
    assertThat(sixth.songs.map { it.libraryId }).containsExactly(6, 6, 6)
    // The same-bytes control its three sibling stamp tests carry: identical ids from both calls,
    // so the only thing that differed between them was the argument.
    assertThat(third.artists.map { it.id }).isEqualTo(sixth.artists.map { it.id })
    assertThat(third.albums.map { it.id }).isEqualTo(sixth.albums.map { it.id })
    assertThat(third.songs.map { it.id }).isEqualTo(sixth.songs.map { it.id })
  }

  // --- getRandomSongs ------------------------------------------------------------------------

  @Test
  fun `getRandomSongs sends whichever scope and size it is given`() = runTest {
    enqueue(fixture(RANDOM_SONGS_FIXTURE))
    enqueue(fixture(RANDOM_SONGS_FIXTURE))

    // Two calls, two disjoint values for both parameters, in one test whose name claims exactly
    // that. Previously the only second observation of this command's scope lived inside a test
    // named for size clamping, so the probe protecting the shuffle's scope pointed at a test that
    // did not say it was protecting it.
    client.getRandomSongs(musicFolderId = 1, size = 50)
    val first = assertAuthenticatedRequestTo("/rest/getRandomSongs")
    assertThat(first.queryParameter("musicFolderId")).isEqualTo("1")
    assertThat(first.queryParameter("size")).isEqualTo("50")

    client.getRandomSongs(musicFolderId = 2, size = 25)
    val second = assertAuthenticatedRequestTo("/rest/getRandomSongs")
    assertThat(second.queryParameter("musicFolderId")).isEqualTo("2")
    assertThat(second.queryParameter("size")).isEqualTo("25")
  }

  @Test
  fun `getRandomSongs clamps size to the 500 Navidrome silently enforces`() = runTest {
    enqueue(fixture(RANDOM_SONGS_FIXTURE))

    client.getRandomSongs(musicFolderId = 1, size = 1_000)

    // Navidrome caps `size` at 500 and says nothing about it. A caller that asks for 1000 and
    // assumes it got 1000 is simply wrong; clamping here means the request and the caller's
    // model of it agree.
    assertThat(nextRequest().url.queryParameter("size")).isEqualTo("500")
  }

  @Test
  fun `getRandomSongs clamps a non-positive size up to one`() = runTest {
    enqueue(fixture(RANDOM_SONGS_FIXTURE))

    client.getRandomSongs(musicFolderId = 1, size = 0)

    // `size=0` is not a documented value and Navidrome's behaviour for it is unknown; asking for
    // one song is the smallest well-defined request, and it keeps a caller's arithmetic error
    // from turning into an undefined server-side one.
    assertThat(nextRequest().url.queryParameter("size")).isEqualTo("1")
  }

  @Test
  fun `a successful getRandomSongs with no randomSongs payload shuffles nothing`() = runTest {
    // Third and last of the list-shaped payloads. An empty shuffle is a legitimate answer -- a
    // library with no playable tracks in it -- so this must not throw; the two commands whose
    // payload is the whole answer do, and the two tests below pin that difference.
    enqueue(OK_WITH_NO_PAYLOAD)

    assertThat(client.getRandomSongs(musicFolderId = 1, size = 50)).isEmpty()
  }

  @Test
  fun `getRandomSongs stamps every song with the library it was scoped to`() = runTest {
    enqueue(fixture(RANDOM_SONGS_FIXTURE))
    enqueue(fixture(RANDOM_SONGS_FIXTURE))

    val music = client.getRandomSongs(musicFolderId = 1, size = 50)
    val audiobooks = client.getRandomSongs(musicFolderId = 4, size = 50)

    // Until this test, `getRandomSongs` asserted the stamp at **no value at all** -- and it is the
    // one command library-scoped shuffle actually calls. `Song.libraryId` is the only thing that
    // can keep an audiobook chapter out of a music shuffle once the response is in memory
    // (Navidrome types every media file `"type": "music"`, so nothing in the body can), which
    // makes an unstamped or mis-stamped song exactly the failure `LiveNavidromeTest` reproduces
    // against the real server -- just arriving through the client instead of through the wire.
    assertThat(music.map { it.libraryId }).containsExactly(1, 1, 1)
    assertThat(audiobooks.map { it.libraryId }).containsExactly(4, 4, 4)
    assertThat(music.map { it.id }).isEqualTo(audiobooks.map { it.id })
  }

  // --- getScanStatus -------------------------------------------------------------------------

  @Test
  fun `getScanStatus is a read and sends no scan-triggering parameter`() = runTest {
    enqueue(fixture(SCAN_STATUS_FIXTURE))

    client.getScanStatus()

    val url = assertAuthenticatedRequestTo("/rest/getScanStatus")
    // Tempo's getScanStatus calls startScan, re-triggering a full server scan on every poll
    // (spec section 4). This client polls this endpoint; it must never be the thing that makes
    // the server rescan. Asserting the path is `getScanStatus` and that no `fullScan` parameter
    // rides along is what keeps a future "convenience" from reintroducing it.
    assertThat(url.encodedPath).doesNotContain("startScan")
    assertThat(url.queryParameter("fullScan")).isNull()
  }

  @Test
  fun `getScanStatus maps navidrome's lastScan watermark`() = runTest {
    enqueue(fixture(SCAN_STATUS_FIXTURE))

    val status = client.getScanStatus()

    assertThat(status.isScanning).isFalse
    assertThat(status.scannedCount).isEqualTo(4)
    // Asserted at its literal, not merely `isNotNull`/`isNotBlank`. The client treats this as an
    // opaque token and never parses it as a date -- all the sync engine asks is "is this the same
    // string as the one I last committed?" -- but "opaque" is a statement about *interpretation*,
    // not a licence to leave the value unobserved. A watermark checked only for non-blankness is
    // satisfied by any constant, which is exactly the mapper defect this pins.
    assertThat(status.lastScan).isEqualTo(IDLE_WATERMARK)
  }

  @Test
  fun `getScanStatus maps a scan in progress, watermark and all`() = runTest {
    // The second observation of every field `ScanStatus` carries. Unlike the stamp tests, this one
    // cannot reuse the same bytes twice: these three values come *from the body*, so discriminating
    // them needs two different bodies -- hence a second live capture, taken while a real scan was
    // running (`scanning: true`, `count: 0`, and the watermark of the scan *before* it).
    //
    // Without it, hardcoding `isScanning = false`, `scannedCount = 4` and any non-blank `lastScan`
    // left `./gradlew check` at exit 0 with 101 tests green and 56/56 branch coverage. `lastScan`
    // in particular was asserted only `isNotNull`/`isNotBlank` -- never at a value -- which is the
    // same as not asserting it, on the field this method's own KDoc calls the watermark the whole
    // sync design rests on. A mapper returning a constant watermark is a sync that never advances
    // or never detects a change, permanently and silently.
    enqueue(fixture(SCAN_STATUS_SCANNING_FIXTURE))

    val status = client.getScanStatus()

    assertThat(status.isScanning).isTrue
    assertThat(status.scannedCount).isEqualTo(0)
    assertThat(status.lastScan).isEqualTo(SCANNING_WATERMARK)
  }

  @Test
  fun `a successful getScanStatus with no scanStatus payload is malformed`() = runTest {
    enqueue(OK_WITH_NO_PAYLOAD)

    assertThat(thrownBy { client.getScanStatus() })
      .isInstanceOf(SubsonicMalformedResponseException::class.java)
      .hasMessageContaining("scanStatus")
  }

  // --- cover art -----------------------------------------------------------------------------

  @Test
  fun `the cover art url carries full authentication and the art id`() {
    val url = client.coverArtUrl("al-abc_0", sizePx = 256).toHttpUrl()

    // Scheme, host and port first, because this is the one URL in the client that nothing else
    // validates: the command tests are immune to a wrong origin only because a request sent
    // somewhere else would never reach this MockWebServer, whereas `coverArtUrl` is handed
    // straight to an image loader and would be fetched from wherever it points. Pointing every
    // cover-art URL at `http://elsewhere.example:9999` left all 97 tests green.
    //
    // No second observation is needed to make this discriminating, unusually: MockWebServer picks
    // a fresh ephemeral port per run, so there is no constant a mutant could hardcode that would
    // satisfy it twice. `the cover art url is built from the credentials base url ...` below adds
    // the second observation anyway, over the input that actually varies -- the trailing slash.
    assertThat(url.scheme).isEqualTo("http")
    assertThat(url.host).isEqualTo(server.hostName)
    assertThat(url.port).isEqualTo(server.port)
    assertThat(url.encodedPath).isEqualTo("/rest/getCoverArt")
    assertThat(url.queryParameter("id")).isEqualTo("al-abc_0")
    assertThat(url.queryParameter("size")).isEqualTo("256")
    val salt = url.queryParameter("s")
    assertThat(salt).isNotNull.matches("[0-9a-f]{16}")
    assertThat(url.queryParameter("u")).isEqualTo("alice")
    assertThat(url.queryParameter("t")).isEqualTo(md5Hex("sesame" + salt))
    assertThat(url.queryParameter("v")).isEqualTo("1.16.1")
    assertThat(url.queryParameter("c")).isEqualTo("MuPlay")
    assertThat(url.queryParameter("p")).describedAs("plaintext password parameter").isNull()
    assertThat(url.query).describedAs("query string").doesNotContain("sesame")
  }

  @Test
  fun `two cover art urls for the same art differ because the salt is fresh`() {
    // This is not a curiosity: it is the reason `:feature:library` must give Coil an explicit
    // cache key. Coil keys its memory and disk caches on the request URL by default, and a URL
    // that changes on every call can never hit either. Tempo shipped the same defect on Media3's
    // side, where the auth token was part of the cache key.
    assertThat(client.coverArtUrl("al-abc_0", null))
      .isNotEqualTo(client.coverArtUrl("al-abc_0", null))
  }

  @Test
  fun `the cover art url omits size when none is asked for`() {
    assertThat(client.coverArtUrl("al-abc_0", null).toHttpUrl().queryParameter("size")).isNull()
  }

  @Test
  fun `the cover art url is built from the credentials base url, trailing slash or not`() {
    // The second observation of the base URL, over the one part of it that genuinely varies. It
    // also closes a gap `SubsonicClientTest` cannot: that class proves `normalizeBaseUrl` for the
    // Retrofit path (`ping succeeds when baseUrl has no trailing slash`), but `coverArtUrl` is the
    // *other* caller of the same helper, and nothing exercised it. A missing slash here would
    // produce `.../restgetCoverArt` or a dropped path segment, and an image loader would simply
    // show no artwork.
    val withoutSlash = server.url("/").toString().removeSuffix("/")
    assertThat(withoutSlash).doesNotEndWith("/")

    val url = SubsonicClient(SubsonicCredentials(withoutSlash, "alice", "sesame"))
      .coverArtUrl("al-abc_0", sizePx = 256)
      .toHttpUrl()

    assertThat(url.scheme).isEqualTo("http")
    assertThat(url.host).isEqualTo(server.hostName)
    assertThat(url.port).isEqualTo(server.port)
    assertThat(url.encodedPath).isEqualTo("/rest/getCoverArt")
    assertThat(url.queryParameter("id")).isEqualTo("al-abc_0")
  }

  @Test
  fun `the cover art url forwards whichever art id and size it is given`() {
    // Same audit as every request test above, applied to the one URL this client builds rather
    // than sends: `id` and `size` were each observed at exactly one value, so a constant
    // satisfied both. A cover-art URL that always requests the same art, or always the same
    // pixel size, is a defect no response assertion can see -- the bytes come back fine.
    val url = client.coverArtUrl(CAPTURED_COVER_ART_ID, sizePx = 96).toHttpUrl()

    assertThat(url.host).isEqualTo(server.hostName)
    assertThat(url.encodedPath).isEqualTo("/rest/getCoverArt")
    assertThat(url.queryParameter("id")).isEqualTo(CAPTURED_COVER_ART_ID)
    assertThat(url.queryParameter("size")).isEqualTo("96")
  }

  // --- the production factory ----------------------------------------------------------------

  @Test
  fun `the default factory builds a source that really talks to the server it was given`() = runTest {
    // `DefaultSubsonicSourceFactory` is the seam every repository from Task 4 on injects, and it
    // is one line of wiring -- which is exactly the shape of thing that is never asserted and
    // therefore never noticed when it points somewhere else. Proven by use, not by identity:
    // the source it returns is asked for a real command, and the request that reaches this
    // server is asserted to carry this server's credentials.
    enqueue(fixture(SCAN_STATUS_FIXTURE))

    val source: SubsonicSource =
      DefaultSubsonicSourceFactory.create(
        SubsonicCredentials(server.url("/").toString(), "alice", "sesame"),
      )
    val status = source.getScanStatus()

    assertThat(status.scannedCount).isEqualTo(4)
    assertAuthenticatedRequestTo("/rest/getScanStatus")
  }

  // --- shared helpers ------------------------------------------------------------------------

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
    val request = nextRequest()
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

  private var recorded: RecordedRequest? = null

  /** The last request [nextRequest] returned, for assertions on its raw, undecoded query. */
  private fun request(): RecordedRequest = checkNotNull(recorded) { "no request examined yet" }

  /**
   * The next request the client actually sent, or a failed assertion if it sent none.
   * Deliberately not the no-argument `takeRequest` overload: that blocks forever on an empty
   * queue, so the exact regression these assertions exist to catch — a code path that stops
   * issuing a request at all — would hang the build until CI's own timeout killed it.
   */
  private fun nextRequest(): RecordedRequest {
    val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(request)
      .describedAs("a request within %d s -- the client sent none", REQUEST_TIMEOUT_SECONDS)
      .isNotNull()
    recorded = request
    return request!!
  }

  /**
   * The [Throwable] [call] threw, or a failed assertion if it returned normally.
   *
   * AssertJ's own `assertThatThrownBy` takes a `ThrowableAssert.ThrowingCallable` — a plain Java
   * functional interface, which cannot invoke a `suspend` function — so the two malformed-response
   * assertions below cannot use it directly. Running the call here, inside the test's own
   * coroutine, and handing the captured throwable to `assertThat(Throwable)` keeps every
   * downstream assertion (`isInstanceOf`, `hasMessageContaining`) exactly what it would have been.
   *
   * `requireNotNull` rather than a silent null: a command that *stops* throwing on a payload-less
   * success envelope — mapping it to an empty album instead — is precisely the regression these
   * two tests exist to catch, and it must fail loudly rather than pass an `isInstanceOf` on null.
   */
  private suspend fun thrownBy(call: suspend () -> Unit): Throwable =
    requireNotNull(runCatching { call() }.exceptionOrNull()) {
      "expected the call to fail, but it returned normally"
    }

  /** `hex(md5(utf8(input)))`, computed here rather than by calling `SubsonicAuth.token`. */
  private fun md5Hex(input: String): String =
    MessageDigest.getInstance("MD5")
      .digest(input.toByteArray(StandardCharsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte) }

  private companion object {
    const val REQUEST_TIMEOUT_SECONDS = 5L

    /**
     * The ids Navidrome actually minted for the captured album, read out of
     * `get-album-list2-music.json`. Used as the *second* value wherever an id parameter would
     * otherwise be observed at exactly one, so no hardcoded constant can satisfy the suite.
     */
    const val CAPTURED_ALBUM_ID = "7orvCZZyWRqsduCdqXoguY"
    const val CAPTURED_COVER_ART_ID = "al-7orvCZZyWRqsduCdqXoguY_6a8bbb51"

    const val ALBUM_LIST_MUSIC_FIXTURE = "get-album-list2-music.json"
    const val ALBUM_LIST_AUDIOBOOKS_FIXTURE = "get-album-list2-audiobooks.json"
    const val ALBUM_LIST_EMPTY_FIXTURE = "get-album-list2-empty.json"
    const val ALBUM_WITH_SONGS_FIXTURE = "get-album-with-songs.json"
    const val SEARCH3_FIXTURE = "search3-music.json"
    const val RANDOM_SONGS_FIXTURE = "get-random-songs-music.json"
    const val SCAN_STATUS_FIXTURE = "get-scan-status.json"
    const val SCAN_STATUS_SCANNING_FIXTURE = "get-scan-status-scanning.json"

    /**
     * The two watermarks the two captures carry, as literals. Both were read off a real
     * `deluan/navidrome:0.63.2`: the idle capture holds the scan that had completed when it was
     * taken, and the scanning capture -- recorded while a full rescan was genuinely in flight --
     * holds the watermark of the scan *before* that one, which is `lastScan`'s real semantics.
     */
    const val IDLE_WATERMARK = "2026-08-24T03:32:38.477978062Z"
    const val SCANNING_WATERMARK = "2026-08-24T10:07:22.030396558Z"

    /**
     * A spec-valid success envelope with no command payload at all. Synthetic, and deliberately
     * so: no real Navidrome produces it. It exists to exercise the one hazard the flattened
     * `SubsonicResponseBody` introduces — a payload field that decodes to null.
     */
    const val OK_WITH_NO_PAYLOAD =
      """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",""" +
        """"serverVersion":"0.63.2 (be10f89c)","openSubsonic":true}}"""
  }
}
