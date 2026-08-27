package app.muplay.integrations.requests

import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequestRepository
import app.muplay.integrations.RequestStatus
import app.muplay.integrations.lidarr.LidarrAlbumProgress
import app.muplay.model.LibraryRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The composition: stored rows, each service's own vocabulary, and the bridge to Navidrome's scan.
 *
 * **Every test class that touches configuration state must exercise all four combinations** —
 * neither, Lidarr only, Bindery only, both — because a "service not configured" path that every
 * test configures around is a path no test exercises, and it is the path a real user with one
 * service is on permanently. All four are here, and the assertion for each is that the *other*
 * service's client was never even built.
 *
 * Everything runs on the JVM. That is what [ConfiguredServices] buys: the real
 * `IntegrationCredentialStore` reaches the Android Keystore, so behind that port this is a fast-tier
 * test rather than an emulator one.
 */
class RequestsRepositoryTest {

  /** A clock a test can move, so that "was this row rewritten" is observable through `updatedAt`. */
  private class MutableClock(var now: Long = 1_000L) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
  }

  private val clock = MutableClock()
  private val dao = FakeMediaRequestDao()

  /** The **real** repository over an in-memory DAO — see `FakeMediaRequestDao`'s own note. */
  private val requests = MediaRequestRepository(dao, clock)

  private val services = FakeConfiguredServices()
  private val lidarrSource = FakeLidarrSource()
  private val binderySource = FakeBinderySource()
  private val lidarrFactory = FakeLidarrSourceFactory(lidarrSource)
  private val binderyFactory = FakeBinderySourceFactory(binderySource)

  private var albums: Map<Int, List<app.muplay.model.Album>> = emptyMap()
  private val sync = FakeMirrorSync()
  private val roles = FakeLibraryRoles(
    mapOf(LibraryRole.MUSIC to listOf(1), LibraryRole.AUDIOBOOKS to listOf(2)),
  )

  /** Built per call so a test can seed `albums` after construction and before the refresh. */
  private fun repository() = RequestsRepository(
    requests = requests,
    services = services,
    lidarrFactory = lidarrFactory,
    binderyFactory = binderyFactory,
    arrival = RequestArrivalDetector(sync, FakeAlbumSearch(albums), roles),
  )

  // ---- the four configuration combinations ---------------------------------------------------

  @Test
  fun `refresh with nothing configured touches nothing and reports both as skipped`() = runTest {
    // The path a real user is permanently on until they configure something. It must not throw,
    // must issue no HTTP, and must not rewrite any row.
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")

    val report = repository().refresh()

    assertThat(lidarrFactory.credentialsSeen).isEmpty()
    assertThat(binderyFactory.credentialsSeen).isEmpty()
    assertThat(lidarrSource.queueCalls).isZero()
    assertThat(binderySource.bookCalls).isZero()
    assertThat(report.polled).isZero()
    assertThat(report.updated).isZero()
    assertThat(report.failed).isEmpty()
    assertThat(report.skippedUnconfigured)
      .containsExactlyInAnyOrder(IntegrationService.LIDARR, IntegrationService.BINDERY)
  }

  /**
   * **The severability contract, at the data layer.** A user with only Lidarr configured must
   * cause **zero** Bindery traffic — not "the Bindery UI was not shown", but that no call was made
   * at all, and indeed that no Bindery client was ever built to make one with.
   */
  @Test
  fun `refresh polls only the services that are configured`() = runTest {
    services.save(lidarrCredentials()) // Bindery deliberately not configured
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")
    requests.record(IntegrationService.BINDERY, "book", "B", "y", remoteId = "9")

    val report = repository().refresh()

    assertThat(lidarrSource.queueCalls).isEqualTo(1)
    assertThat(binderyFactory.credentialsSeen).isEmpty()
    assertThat(binderySource.bookCalls).isZero()
    assertThat(report.skippedUnconfigured).containsExactly(IntegrationService.BINDERY)
    // The Bindery row is left exactly as it was rather than being marked failed: "we did not
    // ask" is not "it went wrong".
    assertThat(requests.requests(IntegrationService.BINDERY).first().single().status)
      .isEqualTo(RequestStatus.Requested)
  }

  /** The mirror image, so neither service's skip path is the only one anybody ever exercises. */
  @Test
  fun `refresh with only bindery configured builds no lidarr client`() = runTest {
    services.save(binderyCredentials())
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")
    requests.record(IntegrationService.BINDERY, "book", "B", "y", remoteId = "9")
    binderySource.library = listOf(binderyBook(9, "book", "B", "downloading"))

    val report = repository().refresh()

    assertThat(lidarrFactory.credentialsSeen).isEmpty()
    assertThat(lidarrSource.queueCalls).isZero()
    assertThat(binderySource.bookCalls).isEqualTo(1)
    assertThat(report.skippedUnconfigured).containsExactly(IntegrationService.LIDARR)
    assertThat(requests.requests(IntegrationService.BINDERY).first().single().status)
      .isEqualTo(RequestStatus.Downloading(percentComplete = null))
    assertThat(requests.requests(IntegrationService.LIDARR).first().single().status)
      .isEqualTo(RequestStatus.Requested)
  }

  @Test
  fun `refresh with both configured polls both and skips neither`() = runTest {
    services.save(lidarrCredentials())
    services.save(binderyCredentials())
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")
    requests.record(IntegrationService.BINDERY, "book", "B", "y", remoteId = "9")
    lidarrSource.queue = listOf(queueItem(albumId = 7, state = "downloading", size = 100.0, left = 40.0))
    binderySource.library = listOf(binderyBook(9, "book", "B", "imported"))

    val report = repository().refresh()

    assertThat(report.skippedUnconfigured).isEmpty()
    assertThat(report.failed).isEmpty()
    assertThat(report.polled).isEqualTo(2)
    assertThat(report.updated).isEqualTo(2)
    val byId = requests.requests().first().associateBy { it.externalId }
    assertThat(byId.getValue("mbid").status).isEqualTo(RequestStatus.Downloading(60))
    assertThat(byId.getValue("book").status).isEqualTo(RequestStatus.Imported)
  }

  // ---- which row gets which answer -------------------------------------------------------------

  @Test
  fun `refresh updates the row whose remote id it polled, and not another`() = runTest {
    // The delegating-argument rule again, one level up: a refresh that wrote its result to the
    // first row rather than the matching one passes a single-row test.
    services.save(lidarrCredentials())
    requests.record(IntegrationService.LIDARR, "mbid-1", "A", "x", remoteId = "7")
    requests.record(IntegrationService.LIDARR, "mbid-2", "B", "y", remoteId = "8")
    lidarrSource.queue = listOf(queueItem(albumId = 8, state = "downloading", size = 100.0, left = 25.0))

    val report = repository().refresh()

    val byId = requests.requests().first().associateBy { it.externalId }
    assertThat(byId.getValue("mbid-2").status).isEqualTo(RequestStatus.Downloading(75))
    assertThat(byId.getValue("mbid-1").status).isEqualTo(RequestStatus.Requested)
    // Two rows were asked about even though only one moved -- `polled` counts requests, not
    // services, and a `polled` that counted services reads identically on every single-row test.
    assertThat(report.polled).isEqualTo(2)
    assertThat(report.updated).isEqualTo(1)
  }

  @Test
  fun `each lidarr row's progress is asked for under its own album id`() = runTest {
    // Two disjoint observations of the same passthrough: the ids asked about, and two different
    // answers landing on the two different rows.
    services.save(lidarrCredentials())
    requests.record(IntegrationService.LIDARR, "mbid-1", "A", "x", remoteId = "7")
    requests.record(IntegrationService.LIDARR, "mbid-2", "B", "y", remoteId = "8")
    lidarrSource.progress = mapOf(8 to LidarrAlbumProgress(trackFileCount = 5, totalTrackCount = 5))

    repository().refresh()

    assertThat(lidarrSource.progressAsked).containsExactlyInAnyOrder(7, 8)
    val byId = requests.requests().first().associateBy { it.externalId }
    assertThat(byId.getValue("mbid-2").status).isEqualTo(RequestStatus.Imported)
    assertThat(byId.getValue("mbid-1").status).isEqualTo(RequestStatus.Requested)
  }

  @Test
  fun `a bindery row is matched by the remote id bindery gave it`() = runTest {
    services.save(binderyCredentials())
    requests.record(IntegrationService.BINDERY, "book-1", "A", "x", remoteId = "11")
    requests.record(IntegrationService.BINDERY, "book-2", "B", "y", remoteId = "22")
    binderySource.library = listOf(
      binderyBook(11, "book-1", "A", "wanted"),
      binderyBook(22, "book-2", "B", "imported"),
    )

    repository().refresh()

    val byId = requests.requests().first().associateBy { it.externalId }
    assertThat(byId.getValue("book-2").status).isEqualTo(RequestStatus.Imported)
    assertThat(byId.getValue("book-1").status).isEqualTo(RequestStatus.Requested)
  }

  // ---- arrival ---------------------------------------------------------------------------------

  @Test
  fun `an imported request that the detector locates becomes Arrived with that album id`() = runTest {
    services.save(lidarrCredentials())
    requests.record(IntegrationService.LIDARR, "mbid-1", "Kind of Blue", "Miles Davis", remoteId = "7")
    lidarrSource.progress = mapOf(7 to LidarrAlbumProgress(trackFileCount = 5, totalTrackCount = 5))
    albums = mapOf(1 to listOf(album("al-1", 1, "Kind of Blue", "Miles Davis")))

    val report = repository().refresh()

    assertThat(requests.requests().first().single().status)
      .isEqualTo(RequestStatus.Arrived(albumId = "al-1"))
    // One write, not two: the status a refresh settles on is written once, so `updatedAt` moves
    // once and a screen's "last updated" means something.
    assertThat(report.updated).isEqualTo(1)
  }

  @Test
  fun `an imported request the detector cannot locate stays Imported, not Failed`() = runTest {
    // The user can still find it in their library; telling them it failed would be false.
    services.save(lidarrCredentials())
    requests.record(IntegrationService.LIDARR, "mbid-1", "Kind of Blue", "Miles Davis", remoteId = "7")
    lidarrSource.progress = mapOf(7 to LidarrAlbumProgress(5, 5))

    repository().refresh()

    assertThat(requests.requests().first().single().status).isEqualTo(RequestStatus.Imported)
  }

  /**
   * Ambiguity, all the way out to the stored row. Two albums the library cannot tell apart leave
   * the request at `Imported` rather than pointing it at one of them.
   */
  @Test
  fun `an ambiguous arrival leaves the row at Imported rather than picking one`() = runTest {
    services.save(lidarrCredentials())
    requests.record(IntegrationService.LIDARR, "mbid-1", "Kind of Blue", "Miles Davis", remoteId = "7")
    lidarrSource.progress = mapOf(7 to LidarrAlbumProgress(5, 5))
    albums = mapOf(
      1 to listOf(
        album("al-1", 1, "Kind of Blue", "Miles Davis"),
        album("al-2", 1, "Kind of Blue", "Miles Davis"),
      ),
    )

    repository().refresh()

    assertThat(requests.requests().first().single().status).isEqualTo(RequestStatus.Imported)
  }

  @Test
  fun `an arrived request is never polled again`() = runTest {
    // Terminal. Re-polling it could only ever take a working "play it" button away -- and if it is
    // the only row, there is nothing worth opening a connection for at all.
    services.save(lidarrCredentials())
    val stored = requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")
    requests.setStatus(stored.id, RequestStatus.Arrived("al-1"))

    val report = repository().refresh()

    assertThat(lidarrFactory.credentialsSeen).isEmpty()
    assertThat(lidarrSource.queueCalls).isZero()
    assertThat(report.polled).isZero()
    assertThat(requests.requests().first().single().status).isEqualTo(RequestStatus.Arrived("al-1"))
  }

  // ---- what a refresh must not do --------------------------------------------------------------

  @Test
  fun `a status that has not changed is not written back`() = runTest {
    // An unconditional write would move `updatedAt` on every refresh and make "last updated"
    // useless. Observed on the row itself, not on a call count.
    services.save(lidarrCredentials())
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")
    val before = requests.requests().first().single().updatedAtEpochMs
    clock.now += 60_000

    val report = repository().refresh()

    assertThat(report.polled).isEqualTo(1)
    assertThat(report.updated).isZero()
    assertThat(requests.requests().first().single().updatedAtEpochMs).isEqualTo(before)
  }

  @Test
  fun `a request with no remote id is not polled and is left alone`() = runTest {
    // A submit that came back without an id leaves a row nothing can be correlated on. Asking
    // anyway would map an absent answer onto it and rewrite it from nothing.
    services.save(lidarrCredentials())
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = null)

    val report = repository().refresh()

    assertThat(lidarrSource.queueCalls).isZero()
    assertThat(report.polled).isZero()
    assertThat(report.skippedUnconfigured).containsExactly(IntegrationService.BINDERY)
    assertThat(requests.requests().first().single().status).isEqualTo(RequestStatus.Requested)
  }

  @Test
  fun `a configured service with nothing to ask about is not contacted and is not reported skipped`() =
    runTest {
      services.save(lidarrCredentials())
      services.save(binderyCredentials())
      requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")

      val report = repository().refresh()

      assertThat(binderyFactory.credentialsSeen).isEmpty()
      assertThat(binderySource.bookCalls).isZero()
      // Configured with nothing to do is not "not configured", and a screen told otherwise would
      // offer the user a setup flow they have already completed.
      assertThat(report.skippedUnconfigured).isEmpty()
    }

  @Test
  fun `a bindery book the server no longer lists leaves its request untouched`() = runTest {
    services.save(binderyCredentials())
    requests.record(IntegrationService.BINDERY, "book", "B", "y", remoteId = "9")
    val stored = requests.requests().first().single()
    requests.setStatus(stored.id, RequestStatus.Downloading(percentComplete = null))
    val before = requests.requests().first().single().updatedAtEpochMs
    clock.now += 60_000
    binderySource.library = emptyList()

    repository().refresh()

    assertThat(requests.requests().first().single().status)
      .isEqualTo(RequestStatus.Downloading(percentComplete = null))
    assertThat(requests.requests().first().single().updatedAtEpochMs).isEqualTo(before)
  }

  @Test
  fun `a request whose service throws leaves every other request updated`() = runTest {
    // One dead service must not stop the other from refreshing. This is the "fail closed, never
    // block core playback" rule of spec section 8, applied within the feature itself.
    services.save(lidarrCredentials())
    services.save(binderyCredentials())
    lidarrSource.failWith = IllegalStateException("500")
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")
    requests.record(IntegrationService.BINDERY, "book", "B", "y", remoteId = "9")
    binderySource.library = listOf(binderyBook(9, "book", "B", "imported"))

    val report = repository().refresh()

    assertThat(requests.requests(IntegrationService.BINDERY).first().single().status)
      .isEqualTo(RequestStatus.Imported)
    // The Lidarr row is untouched rather than marked failed: this client failing to ask says
    // nothing about the request.
    assertThat(requests.requests(IntegrationService.LIDARR).first().single().status)
      .isEqualTo(RequestStatus.Requested)
    assertThat(report.polled).isEqualTo(1)
    // ...and the failure is *reported*, so a refresh where everything went wrong is not
    // indistinguishable from one with nothing to do.
    assertThat(report.failed).containsExactly(IntegrationService.LIDARR)
    assertThat(report.skippedUnconfigured).isEmpty()
  }

  // ---- paging ----------------------------------------------------------------------------------

  @Test
  fun `every bindery page is read, so a book past the first page still updates its request`() =
    runTest {
      // `BinderyBookPage.total` is the count BEFORE `limit` applies, and a client that read one
      // page and believed it had read them all is the silent-wrong-answer class one layer up from
      // a dropped field. 250 books, derived from the fixture rather than asserted as a constant.
      services.save(binderyCredentials())
      requests.record(IntegrationService.BINDERY, "book-249", "B", "y", remoteId = "249")
      binderySource.library = (0 until 250).map { binderyBook(it, "book-$it", "T$it", "wanted") }
      binderySource.library = binderySource.library.map {
        if (it.id == 249) it.copy(status = "imported") else it
      }

      repository().refresh()

      assertThat(binderySource.pagesAsked.map { it.third })
        .describedAs("offsets, derived from what each page returned")
        .containsExactly(0, 100, 200)
      assertThat(binderySource.pagesAsked.map { it.first }).allMatch { it == null }
      assertThat(requests.requests().first().single().status).isEqualTo(RequestStatus.Imported)
    }

  @Test
  fun `a server that keeps claiming more books than it sends does not loop forever`() = runTest {
    // A `total` nobody can reach. The page comes back empty and the loop ends on that, not on the
    // count -- so a dishonest total costs one wasted request, not a hung refresh.
    services.save(binderyCredentials())
    requests.record(IntegrationService.BINDERY, "book", "B", "y", remoteId = "1")
    binderySource.library = listOf(binderyBook(1, "book", "B", "imported"))
    binderySource.reportedTotal = 999_999

    repository().refresh()

    assertThat(binderySource.pagesAsked.map { it.third }).containsExactly(0, 1)
    assertThat(requests.requests().first().single().status).isEqualTo(RequestStatus.Imported)
  }

  // ---- the flows, and the recording methods ----------------------------------------------------

  @Test
  fun `configuredServices reports exactly what is configured, and changes when it changes`() =
    runTest {
      // All four combinations through one flow, in order. This is what Task 10's UI decides
      // whether to render anything at all from.
      val repository = repository()
      assertThat(repository.configuredServices.first()).isEmpty()
      services.save(lidarrCredentials())
      assertThat(repository.configuredServices.first()).containsExactly(IntegrationService.LIDARR)
      services.save(binderyCredentials())
      assertThat(repository.configuredServices.first())
        .containsExactlyInAnyOrder(IntegrationService.LIDARR, IntegrationService.BINDERY)
      services.clear(IntegrationService.LIDARR)
      assertThat(repository.configuredServices.first()).containsExactly(IntegrationService.BINDERY)
    }

  @Test
  fun `all exposes every stored request, newest first`() = runTest {
    clock.now = 1_000
    requests.record(IntegrationService.LIDARR, "older", "A", "x", remoteId = null)
    clock.now = 2_000
    requests.record(IntegrationService.BINDERY, "newer", "B", "y", remoteId = null)

    assertThat(repository().all.first().map { it.externalId }).containsExactly("newer", "older")
  }

  @Test
  fun `recordLidarrAdd stores the candidate's own fields and the album id it was given`() = runTest {
    val repository = repository()
    val first = repository.recordLidarrAdd(lidarrCandidate("mbid-1", "Kind of Blue", "Miles Davis"), 7)
    // Two disjoint observations, because a method that hardcoded any one of these passes a
    // single-call test.
    val second = repository.recordLidarrAdd(lidarrCandidate("mbid-2", "Bitches Brew", "Davis Miles"), 8)

    assertThat(first.externalId).isEqualTo("mbid-1")
    assertThat(first.title).isEqualTo("Kind of Blue")
    assertThat(first.subtitle).isEqualTo("Miles Davis")
    assertThat(first.remoteId).isEqualTo("7")
    assertThat(first.service).isEqualTo(IntegrationService.LIDARR)
    assertThat(second.externalId).isEqualTo("mbid-2")
    assertThat(second.title).isEqualTo("Bitches Brew")
    assertThat(second.subtitle).isEqualTo("Davis Miles")
    assertThat(second.remoteId).isEqualTo("8")
    assertThat(requests.requests().first()).hasSize(2)
  }

  @Test
  fun `recordLidarrAdd with no album id stores a row that cannot yet be polled`() = runTest {
    val stored = repository().recordLidarrAdd(lidarrCandidate("mbid", "A", "x"), albumId = null)

    assertThat(stored.remoteId).isNull()
  }

  @Test
  fun `recordBinderyAdd takes its ids from the book and its author from the candidate`() = runTest {
    val repository = repository()
    val first = repository.recordBinderyAdd(
      binderyCandidate("gb:1", "Ignored Title", "Frank Herbert"),
      binderyBook(id = 11, foreignBookId = "gb:1", title = "Dune", status = "wanted"),
    )
    val second = repository.recordBinderyAdd(
      binderyCandidate("gb:2", "Also Ignored", null),
      binderyBook(id = 22, foreignBookId = "gb:2", title = "Dune Messiah", status = "wanted"),
    )

    // The server's title, not the candidate's: it is the server's word the arrival match compares
    // against Navidrome's, and these two fixtures disagree on purpose so that reading the wrong
    // one is visible.
    assertThat(first.title).isEqualTo("Dune")
    assertThat(first.subtitle).isEqualTo("Frank Herbert")
    assertThat(first.externalId).isEqualTo("gb:1")
    assertThat(first.remoteId).isEqualTo("11")
    assertThat(first.service).isEqualTo(IntegrationService.BINDERY)
    assertThat(second.title).isEqualTo("Dune Messiah")
    // A book with no author is recorded with an empty subtitle, which is what makes the arrival
    // match fall back to the title alone rather than never matching.
    assertThat(second.subtitle).isEmpty()
    assertThat(second.remoteId).isEqualTo("22")
  }

  @Test
  fun `forget deletes the row it names and not another`() = runTest {
    val repository = repository()
    val doomed = requests.record(IntegrationService.LIDARR, "mbid-1", "A", "x", remoteId = null)
    requests.record(IntegrationService.LIDARR, "mbid-2", "B", "y", remoteId = null)

    repository.forget(doomed.id)

    assertThat(requests.requests().first().map { it.externalId }).containsExactly("mbid-2")
  }

  @Test
  fun `the credentials each client is built from are the ones the store holds`() = runTest {
    // A repository that built its clients from a constant would pass every status test above.
    services.save(lidarrCredentials(apiKey = "lidarr-key-under-test"))
    services.save(binderyCredentials(apiKey = "bindery-key-under-test"))
    requests.record(IntegrationService.LIDARR, "mbid", "A", "x", remoteId = "7")
    requests.record(IntegrationService.BINDERY, "book", "B", "y", remoteId = "9")

    repository().refresh()

    assertThat(lidarrFactory.credentialsSeen.map { it.apiKey }).containsExactly("lidarr-key-under-test")
    assertThat(binderyFactory.credentialsSeen.map { it.apiKey }).containsExactly("bindery-key-under-test")
    // The trailing slash is `IntegrationBaseUrl.parse`'s own normalisation, asserted as the exact
    // string it produces rather than as a `contains`.
    assertThat(lidarrFactory.credentialsSeen.map { it.baseUrl.value })
      .containsExactly("https://lidarr.test:8686/")
    assertThat(binderyFactory.credentialsSeen.map { it.baseUrl.value })
      .containsExactly("https://bindery.test:8787/")
  }
}
