package app.muplay.integrations.requests

import app.muplay.database.SyncState
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.RequestStatus
import app.muplay.model.LibraryRole
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Whether a thing the service finished fetching is visible in Navidrome yet, and which album it is.
 *
 * The design constraint that shapes every test here: **a wrong answer is far worse than no
 * answer.** A request stuck at `Imported` is a harmless annoyance; a request that flips to the
 * wrong album puts a "play it" button on something else.
 *
 * The fixture that carries most of that weight is [nearlyIdenticalAlbums]: four albums all titled
 * `Kind of Blue`, which is what a real Lidarr lookup for that title returns (measured in Task 6 —
 * they are distinguished only by `foreignAlbumId`, and Navidrome has no such field to join on). A
 * test that seeds one album and finds it proves nothing about that library.
 */
class RequestArrivalDetectorTest {

  private fun request(
    service: IntegrationService = IntegrationService.LIDARR,
    title: String = "Kind of Blue",
    subtitle: String = "Miles Davis",
    status: RequestStatus = RequestStatus.Imported,
  ) = MediaRequest(
    id = "x", service = service, externalId = "e", title = title, subtitle = subtitle,
    remoteId = null, status = status, requestedAtEpochMs = 0, updatedAtEpochMs = 0,
  )

  private val roles = FakeLibraryRoles(
    mapOf(LibraryRole.MUSIC to listOf(1), LibraryRole.AUDIOBOOKS to listOf(2)),
  )

  /**
   * The shape of the real problem: one title, four albums, three of them by somebody else.
   *
   * `al-live` is a near miss on the title and `al-tribute` is a near miss on the artist, so a
   * matcher that relaxed either half returns two candidates and — because the rule is exactly one
   * — then returns nothing at all. `al-orig` is the only answer.
   */
  private val nearlyIdenticalAlbums = listOf(
    album("al-orig", 1, "Kind of Blue", "Miles Davis"),
    album("al-live", 1, "Kind of Blue (Live)", "Miles Davis"),
    album("al-tribute", 1, "Kind of Blue", "The Bill Evans Trio"),
    album("al-cover", 1, "Kind of Blue", "Roberto Fonseca"),
  )

  @Test
  fun `a matching album in the right library is found and its id returned`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request())).isEqualTo("al-1")
  }

  @Test
  fun `the id returned is the matching album's, not a constant`() = runTest {
    // The second observation. A `locate` returning a fixed id passes the test above.
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-99", 1, "Bitches Brew", "Miles Davis"))))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request(title = "Bitches Brew"))).isEqualTo("al-99")
  }

  @Test
  fun `the search is issued with the request's own title, in the right libraries`() = runTest {
    val search = FakeAlbumSearch()
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    detector.locate(request(title = "Bitches Brew"))

    // Argument passthrough, proven as an exact list rather than "the search was called". The query
    // is the raw title, not the normalised one: the mirror's LIKE search is what runs against it,
    // and normalisation is a matching rule rather than a query-building one.
    assertThat(search.queries.map { it.first }).containsExactly(1)
    assertThat(search.queries.map { it.second }).containsExactly("Bitches Brew")
    assertThat(search.queries.map { it.third }).containsExactly(50)
  }

  /**
   * Spec section 4: library id is the only mechanism scoping has. A Bindery request must be looked
   * for in the **audiobook** libraries, and a Lidarr one in the **music** libraries — searching
   * both would let a book with an album's title satisfy a music request, which is the exact
   * cross-library contamination this application exists to prevent.
   */
  @Test
  fun `a bindery request is looked for in the audiobook libraries and a lidarr one in music`() = runTest {
    val search = FakeAlbumSearch()
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    detector.locate(request(service = IntegrationService.LIDARR))
    detector.locate(request(service = IntegrationService.BINDERY))

    // Two observations of the role, and of the library ids that follow from it.
    assertThat(roles.asked).containsExactly(LibraryRole.MUSIC, LibraryRole.AUDIOBOOKS)
    assertThat(search.queries.map { it.first }).containsExactly(1, 2)
  }

  /**
   * The same rule from the side that can actually hurt somebody: the album a music request wants
   * is sitting in the audiobook library under the same title, and must not be found.
   */
  @Test
  fun `an album that only exists in the other role's library is never matched`() = runTest {
    val search = FakeAlbumSearch(mapOf(2 to listOf(album("al-book", 2, "Kind of Blue", "Miles Davis"))))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request(service = IntegrationService.LIDARR))).isNull()
    // ...and the audiobook library was never even searched, so this is scoping rather than luck.
    assertThat(search.queries.map { it.first }).containsExactly(1)
  }

  @Test
  fun `a request that is not imported is not looked for at all`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))
    val sync = FakeMirrorSync()
    val detector = RequestArrivalDetector(sync, search, roles)

    // Every status that is not `Imported`, so none of them can slip through individually.
    val notReady = listOf(
      RequestStatus.Requested,
      RequestStatus.Downloading(percentComplete = 50),
      RequestStatus.Arrived(albumId = "already"),
      RequestStatus.Failed("nope"),
    )

    assertThat(notReady.map { detector.locate(request(status = it)) })
      .containsExactly(null, null, null, null)
    // ...and no sync was triggered by any of them. A detector that synced first and filtered
    // afterwards would poll Navidrome on every refresh for every dead request.
    assertThat(sync.calls).isZero()
    assertThat(search.queries).isEmpty()
  }

  @Test
  fun `a scan in progress defers rather than answering`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))
    val sync = FakeMirrorSync(SyncState.ScanInProgress)
    val detector = RequestArrivalDetector(sync, search, roles)

    // The album is right there in the fake mirror, and the answer is still null: mid-scan the
    // mirror is not a fact yet. Stopping before the search is what makes "try again next refresh"
    // correct rather than lucky.
    assertThat(detector.locate(request())).isNull()
    assertThat(sync.calls).isEqualTo(1)
    assertThat(search.queries).isEmpty()
  }

  @Test
  fun `a failed sync defers rather than answering`() = runTest {
    val sync = FakeMirrorSync(SyncState.Failed(IllegalStateException("no route")))
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))

    assertThat(RequestArrivalDetector(sync, search, roles).locate(request())).isNull()
    assertThat(sync.calls).isEqualTo(1)
    assertThat(search.queries).isEmpty()
  }

  @Test
  fun `an up-to-date mirror is searched, and so is one that just synced`() = runTest {
    // Both success states of `SyncState`, so a detector that only accepted one of them fails.
    for (state in listOf(SyncState.UpToDate, SyncState.Synced(emptyMap()))) {
      val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))
      val detector = RequestArrivalDetector(FakeMirrorSync(state), search, roles)
      assertThat(detector.locate(request())).describedAs("%s", state).isEqualTo("al-1")
    }
  }

  /**
   * **The trade, as a test.** A near-miss is not a match. "Kind of Blue (Remastered)" is a
   * different album, and guessing costs the user a button that opens the wrong thing.
   */
  @Test
  fun `a near miss does not match`() = runTest {
    val search = FakeAlbumSearch(
      mapOf(1 to listOf(album("al-1", 1, "Kind of Blue (Remastered)", "Miles Davis"))),
    )

    assertThat(RequestArrivalDetector(FakeMirrorSync(), search, roles).locate(request())).isNull()
  }

  @Test
  fun `a title match with the wrong artist does not match`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Someone Else"))))

    assertThat(RequestArrivalDetector(FakeMirrorSync(), search, roles).locate(request())).isNull()
  }

  @Test
  fun `a request with no subtitle matches on the title alone`() = runTest {
    // Bindery may not give an author for every book. Requiring an artist match would make those
    // requests never arrive; requiring the title alone is the weakest rule that still discriminates.
    val search = FakeAlbumSearch(mapOf(2 to listOf(album("al-2", 2, "Dune", "Some Narrator"))))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request(IntegrationService.BINDERY, "Dune", subtitle = "")))
      .isEqualTo("al-2")
  }

  @Test
  fun `two equally good matches is no answer, not the first one`() = runTest {
    val search = FakeAlbumSearch(
      mapOf(
        1 to listOf(
          album("al-1", 1, "Kind of Blue", "Miles Davis"),
          album("al-2", 1, "kind of blue", "miles davis"),
        ),
      ),
    )

    // Ambiguity is a fact about the library, not a tie to break. Picking `al-1` would be right
    // half the time and silently wrong the other half.
    assertThat(RequestArrivalDetector(FakeMirrorSync(), search, roles).locate(request())).isNull()
  }

  /**
   * The whole point of the task, on the corpus that makes it discriminating.
   *
   * Four albums share the title; one shares the artist. Exactly one candidate exists and it is
   * found — and every one of the other three is a specific way a looser rule would have produced a
   * second candidate and, under the exactly-one rule, no answer at all.
   */
  @Test
  fun `exactly one of four albums with the same title matches, and it is the right one`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to nearlyIdenticalAlbums))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request())).isEqualTo("al-orig")
  }

  /**
   * The same corpus with the one discriminator removed: a second Miles Davis `Kind of Blue`, which
   * is what a library holding a re-issue actually looks like. Two candidates, so no answer.
   *
   * This is the fixture that makes the ambiguous case **able to fail**. The four-album test above
   * would stay green against a `firstOrNull`; this one cannot.
   */
  @Test
  fun `a second album by the same artist with the same title makes the answer nothing`() = runTest {
    val search = FakeAlbumSearch(
      mapOf(1 to nearlyIdenticalAlbums + album("al-reissue", 1, "Kind of Blue", "Miles Davis")),
    )
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request())).isNull()
  }

  /**
   * The third fixture the ambiguity question needs: a library that has the *near* misses and not
   * the album itself. Nothing matches, and nothing is what comes back — a matcher that fell back
   * to "closest" would answer `al-live` here.
   */
  @Test
  fun `a library holding only near misses matches nothing`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to nearlyIdenticalAlbums.filterNot { it.id == "al-orig" }))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request())).isNull()
  }

  /**
   * Two candidates across **two** libraries, which is a different code path from two in one: the
   * flatten has to see both before the count is taken, so a detector that returned at the first
   * library with a hit would answer `al-a`.
   */
  @Test
  fun `two matches in two different libraries is also no answer`() = runTest {
    val search = FakeAlbumSearch(
      mapOf(
        1 to listOf(album("al-a", 1, "Kind of Blue", "Miles Davis")),
        3 to listOf(album("al-b", 3, "Kind of Blue", "Miles Davis")),
      ),
    )
    val detector = RequestArrivalDetector(
      FakeMirrorSync(), search, FakeLibraryRoles(mapOf(LibraryRole.MUSIC to listOf(1, 3))),
    )

    assertThat(detector.locate(request())).isNull()
    assertThat(search.queries.map { it.first }).containsExactly(1, 3)
  }

  @Test
  fun `no library with the right role means no answer and no search`() = runTest {
    // The user tagged no library `Audiobooks`. Searching everything would be the scope leak spec
    // section 4 spends a page on.
    val search = FakeAlbumSearch()
    val detector = RequestArrivalDetector(
      FakeMirrorSync(), search, FakeLibraryRoles(mapOf(LibraryRole.MUSIC to listOf(1))),
    )

    assertThat(detector.locate(request(service = IntegrationService.BINDERY))).isNull()
    assertThat(search.queries).isEmpty()
  }

  @Test
  fun `a match in either of two libraries with the same role is found`() = runTest {
    val search = FakeAlbumSearch(mapOf(3 to listOf(album("al-3", 3, "Kind of Blue", "Miles Davis"))))
    val detector = RequestArrivalDetector(
      FakeMirrorSync(), search, FakeLibraryRoles(mapOf(LibraryRole.MUSIC to listOf(1, 3))),
    )

    assertThat(detector.locate(request())).isEqualTo("al-3")
    // Both were searched, in order -- so a detector that stopped at the first empty library fails.
    assertThat(search.queries.map { it.first }).containsExactly(1, 3)
  }

  /**
   * Diacritics, end to end. The request came from a service that spells it one way and the mirror
   * holds the other; both are the same album and it is found.
   */
  @Test
  fun `a request and an album that differ only in accents and punctuation still match`() = runTest {
    val search = FakeAlbumSearch(mapOf(2 to listOf(album("al-de", 2, "Hörbücher: Band 1", "Kafka"))))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request(IntegrationService.BINDERY, "Horbucher - Band 1", "Kafka")))
      .isEqualTo("al-de")
  }

  /**
   * A title with nothing alphanumeric in it normalises to the empty string, and so does every
   * other such title. Without the guard, this request matches an album called `???` — a confident
   * wrong answer, which is the one outcome this class exists to make impossible.
   */
  @Test
  fun `a title that normalises to nothing matches nothing, and syncs nothing`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-punct", 1, "???", "Miles Davis"))))
    val sync = FakeMirrorSync()
    val detector = RequestArrivalDetector(sync, search, roles)

    assertThat(detector.locate(request(title = "!!!"))).isNull()
    assertThat(sync.calls).isZero()
    assertThat(search.queries).isEmpty()
  }

  /**
   * The same hole one field over: a *subtitle* that normalises away must not become an artist
   * requirement that only an album with no artist can satisfy.
   */
  @Test
  fun `a subtitle that normalises to nothing falls back to the title alone`() = runTest {
    val search = FakeAlbumSearch(mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis"))))
    val detector = RequestArrivalDetector(FakeMirrorSync(), search, roles)

    assertThat(detector.locate(request(subtitle = "---"))).isEqualTo("al-1")
  }
}
