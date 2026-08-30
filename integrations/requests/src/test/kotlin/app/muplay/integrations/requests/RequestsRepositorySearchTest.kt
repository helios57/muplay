package app.muplay.integrations.requests

import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.MediaRequestRepository
import app.muplay.integrations.bindery.BinderyMediaType
import app.muplay.integrations.bindery.BinderyMessageException
import app.muplay.integrations.lidarr.LidarrAddOutcome
import app.muplay.integrations.lidarr.LidarrHttpException
import app.muplay.integrations.lidarr.LidarrValidationException
import app.muplay.integrations.lidarr.LidarrValidationFailure
import app.muplay.model.LibraryRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Finding something to ask for, and asking for it.
 *
 * These two methods are Task 10's, and they are here rather than in `:feature:requests` for the
 * reason [RequestsRepository.search]'s own doc gives: the severability contract is **structural**
 * in this class — a service that is not in the credential map has nothing to build a client with —
 * and a view model holding the two `…SourceFactory` interfaces would have had to re-establish it by
 * remembering to check.
 *
 * So the same rule as `RequestsRepositoryTest`: **every test class that touches configuration state
 * exercises all four combinations**, and the assertion for a service that is not configured is that
 * its client was never even built.
 */
class RequestsRepositorySearchTest {

  private class MutableClock(var now: Long = 1_000L) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
  }

  private val clock = MutableClock()
  private val dao = FakeMediaRequestDao()
  private val requests = MediaRequestRepository(dao, clock)
  private val services = FakeConfiguredServices()
  private val lidarrSource = FakeLidarrSource()
  private val binderySource = FakeBinderySource()
  private val lidarrFactory = FakeLidarrSourceFactory(lidarrSource)
  private val binderyFactory = FakeBinderySourceFactory(binderySource)

  private val repository = RequestsRepository(
    requests = requests,
    services = services,
    lidarrFactory = lidarrFactory,
    binderyFactory = binderyFactory,
    arrival = RequestArrivalDetector(
      FakeMirrorSync(),
      FakeAlbumSearch(),
      FakeLibraryRoles(mapOf(LibraryRole.MUSIC to listOf(1))),
    ),
  )

  // ---- search: the four configuration combinations --------------------------------------------

  @Test
  fun `a search with nothing configured asks nobody and builds no client`() = runTest {
    val report = repository.search("blue")

    assertThat(report.candidates).isEmpty()
    assertThat(report.failed).isEmpty()
    assertThat(lidarrFactory.credentialsSeen).isEmpty()
    assertThat(binderyFactory.credentialsSeen).isEmpty()
  }

  @Test
  fun `a search with only lidarr configured builds no bindery client`() = runTest {
    services.save(lidarrCredentials())
    lidarrSource.lookupResults = listOf(lidarrCandidate("mbid-1", "Kind of Blue", "Miles Davis"))

    val report = repository.search("blue")

    assertThat(report.candidates.map { it.service }).containsExactly(IntegrationService.LIDARR)
    assertThat(binderyFactory.credentialsSeen).isEmpty()
    assertThat(binderySource.searchTerms).isEmpty()
    assertThat(report.failed).isEmpty()
  }

  /** The mirror image, so neither service's skip path is the only one anybody ever exercises. */
  @Test
  fun `a search with only bindery configured builds no lidarr client`() = runTest {
    services.save(binderyCredentials())
    binderySource.searchResults = listOf(binderyCandidate("ol-1", "Dune", "Frank Herbert"))

    val report = repository.search("dune")

    assertThat(report.candidates.map { it.service }).containsExactly(IntegrationService.BINDERY)
    assertThat(lidarrFactory.credentialsSeen).isEmpty()
    assertThat(lidarrSource.lookupTerms).isEmpty()
  }

  @Test
  fun `a search with both configured returns both, in declaration order`() = runTest {
    services.save(lidarrCredentials())
    services.save(binderyCredentials())
    lidarrSource.lookupResults = listOf(lidarrCandidate("mbid-1", "Blue", "Miles"))
    binderySource.searchResults = listOf(binderyCandidate("ol-1", "Blue", "Someone"))

    val report = repository.search("blue")

    // `IntegrationService.entries` order, which is the order every list in this feature renders in
    // without any screen sorting -- see that enum's own note.
    assertThat(report.candidates.map { it.service })
      .containsExactly(IntegrationService.LIDARR, IntegrationService.BINDERY)
  }

  // ---- search: what is asked, and what comes back ---------------------------------------------

  @Test
  fun `the term reaches both services trimmed and otherwise verbatim`() = runTest {
    // Argument passthrough, not "it was called". `LidarrSource.lookupAlbums` sends the term as the
    // user typed it -- this client sends no `mbid:`/`lidarr:` prefixes -- so a repository that
    // lowercased or truncated it would silently change what a user searched for.
    services.save(lidarrCredentials())
    services.save(binderyCredentials())

    repository.search("  Kind of Blue  ")

    assertThat(lidarrSource.lookupTerms).containsExactly("Kind of Blue")
    assertThat(binderySource.searchTerms).containsExactly("Kind of Blue")
  }

  @Test
  fun `a blank term asks nobody at all`() = runTest {
    // Both lookups proxy to a rate-limited third party, so the empty search a text field produces
    // on its way to the first character must not become a request.
    services.save(lidarrCredentials())
    services.save(binderyCredentials())

    val report = repository.search("   ")

    assertThat(report.candidates).isEmpty()
    assertThat(report.failed).isEmpty()
    assertThat(lidarrFactory.credentialsSeen).isEmpty()
    assertThat(binderyFactory.credentialsSeen).isEmpty()
  }

  @Test
  fun `a candidate carries the fields a row renders, from each service's own shape`() = runTest {
    services.save(lidarrCredentials())
    services.save(binderyCredentials())
    lidarrSource.lookupResults = listOf(lidarrCandidate("mbid-1", "Kind of Blue", "Miles Davis"))
    binderySource.searchResults = listOf(binderyCandidate("ol-1", "Dune", "Frank Herbert"))

    val (album, book) = repository.search("x").candidates

    assertThat(album.externalId).isEqualTo("mbid-1")
    assertThat(album.title).isEqualTo("Kind of Blue")
    assertThat(album.subtitle).isEqualTo("Miles Davis")
    assertThat(book.externalId).isEqualTo("ol-1")
    assertThat(book.title).isEqualTo("Dune")
    assertThat(book.subtitle).isEqualTo("Frank Herbert")
  }

  @Test
  fun `a candidate carries each service's own cover url under one name`() = runTest {
    // Two different wire keys -- Lidarr's `remoteCover`, Bindery's `imageUrl` -- and one property a
    // row reads. Asserted for both, because a mapping that read the wrong field on one service
    // shows a list of placeholder squares and nothing else goes wrong.
    services.save(lidarrCredentials())
    services.save(binderyCredentials())
    lidarrSource.lookupResults =
      listOf(lidarrCandidate("mbid-1", "A", "x").copy(remoteCoverUrl = "https://lidarr.test/a.jpg"))
    binderySource.searchResults =
      listOf(binderyCandidate("ol-1", "D", "H").copy(coverUrl = "https://covers.test/b.jpg"))

    val (album, book) = repository.search("x").candidates

    assertThat(album.coverUrl).isEqualTo("https://lidarr.test/a.jpg")
    assertThat(book.coverUrl).isEqualTo("https://covers.test/b.jpg")
  }

  @Test
  fun `a bindery candidate with no author renders an empty subtitle rather than the word null`() =
    runTest {
      // Measured in `:integrations:bindery`: the nested `author` object is absent on more than half
      // of a real search's results, so this is the ordinary case and not an edge one.
      services.save(binderyCredentials())
      binderySource.searchResults = listOf(binderyCandidate("ol-1", "Dune", authorName = null))

      assertThat(repository.search("dune").candidates.single().subtitle).isEmpty()
    }

  // ---- search: alreadyAdded, from two different facts ------------------------------------------

  @Test
  fun `lidarr's own alreadyAdded flag is carried through`() = runTest {
    services.save(lidarrCredentials())
    lidarrSource.lookupResults =
      listOf(lidarrCandidate("mbid-1", "Blue", "Miles").copy(alreadyAdded = true))

    assertThat(repository.search("blue").candidates.single().alreadyAdded).isTrue()
  }

  @Test
  fun `an album muplay already has a row for is marked added even when lidarr says otherwise`() =
    runTest {
      // The second of the two facts `alreadyAdded` collapses. A user who asked yesterday, on a
      // Lidarr that has since been rebuilt, still gets told they already asked.
      services.save(lidarrCredentials())
      requests.record(IntegrationService.LIDARR, "mbid-1", "Blue", "Miles", remoteId = "7")
      lidarrSource.lookupResults = listOf(lidarrCandidate("mbid-1", "Blue", "Miles"))

      assertThat(repository.search("blue").candidates.single().alreadyAdded).isTrue()
    }

  @Test
  fun `a bindery book is marked added only from muplay's own row, because bindery has no flag`() =
    runTest {
      // Measured in `:integrations:bindery`: after an add, the same search still returns the book
      // with `"id": 0`, so there is nothing on the wire to read and MuPlay's row is the only answer.
      services.save(binderyCredentials())
      binderySource.searchResults =
        listOf(binderyCandidate("ol-1", "Dune", "Herbert"), binderyCandidate("ol-2", "Emma", "Austen"))
      requests.record(IntegrationService.BINDERY, "ol-2", "Emma", "Austen", remoteId = "9")

      val added = repository.search("x").candidates.associate { it.externalId to it.alreadyAdded }

      assertThat(added).containsExactly(
        java.util.Map.entry("ol-1", false),
        java.util.Map.entry("ol-2", true),
      )
    }

  @Test
  fun `a stored row for one service does not mark the other service's identical id as added`() =
    runTest {
      // `MediaRequest.idFor` namespaces by service precisely so two services that happen to share
      // an identifier space cannot collide, and this is the observation that holds it.
      services.save(lidarrCredentials())
      services.save(binderyCredentials())
      requests.record(IntegrationService.BINDERY, "same-id", "B", "y", remoteId = null)
      lidarrSource.lookupResults = listOf(lidarrCandidate("same-id", "A", "x"))
      binderySource.searchResults = listOf(binderyCandidate("same-id", "B", "y"))

      val added = repository.search("x").candidates.associate { it.service to it.alreadyAdded }

      assertThat(added).containsExactly(
        java.util.Map.entry(IntegrationService.LIDARR, false),
        java.util.Map.entry(IntegrationService.BINDERY, true),
      )
    }

  // ---- search: failure ------------------------------------------------------------------------

  @Test
  fun `one service failing leaves the other's results and names itself`() = runTest {
    services.save(lidarrCredentials())
    services.save(binderyCredentials())
    lidarrSource.lookupFailWith = LidarrHttpException(status = 502)
    binderySource.searchResults = listOf(binderyCandidate("ol-1", "Dune", "Herbert"))

    val report = repository.search("dune")

    assertThat(report.candidates.map { it.externalId }).containsExactly("ol-1")
    assertThat(report.failed).containsExactly(IntegrationService.LIDARR)
  }

  @Test
  fun `a credential filed under the wrong service is a failure and no client is built for it`() =
    runTest {
      // The corrupt-store case. The one thing this class must never do is send Bindery's key to
      // Lidarr, and "we did not ask" would offer the user a setup flow they have already completed.
      services.saveUnder(IntegrationService.LIDARR, binderyCredentials())

      val report = repository.search("blue")

      assertThat(report.failed).containsExactly(IntegrationService.LIDARR)
      assertThat(lidarrFactory.credentialsSeen).isEmpty()
      assertThat(binderyFactory.credentialsSeen).isEmpty()
    }

  @Test
  fun `cancelling the caller is not reported as a service failure`() = runTest {
    // `SyncEngine.syncIfStale`'s own rule, and `refresh`'s: a cancelled scope is the caller leaving,
    // not Lidarr being down, and swallowing it would mark a healthy service failed.
    services.save(lidarrCredentials())
    val cancellation = CancellationException("the screen went away")
    lidarrSource.lookupFailWith = cancellation

    // `runCatching` and not a nested `runTest`, which refuses a second call in one test.
    val thrown = runCatching { repository.search("blue") }.exceptionOrNull()

    assertThat(thrown).isSameAs(cancellation)
  }

  // ---- submit: lidarr -------------------------------------------------------------------------

  @Test
  fun `an accepted album is recorded with lidarr's own album id`() = runTest {
    services.save(lidarrCredentials())
    val candidate = RequestCandidate.Album(lidarrCandidate("mbid-1", "Kind of Blue", "Miles"), false)
    lidarrSource.addOutcome = LidarrAddOutcome.Added(albumId = 4242)

    val result = repository.submit(candidate)

    val request = (result as SubmitResult.Recorded).request
    // The identifier, not "a request was made": a submit that recorded the wrong remote id polls
    // somebody else's album forever.
    assertThat(request.externalId).isEqualTo("mbid-1")
    assertThat(request.remoteId).isEqualTo("4242")
    assertThat(request.service).isEqualTo(IntegrationService.LIDARR)
    assertThat(requests.requests().first().map { it.id })
      .containsExactly(MediaRequest.idFor(IntegrationService.LIDARR, "mbid-1"))
  }

  @Test
  fun `an add is filed under the root folder's own defaults and asks lidarr to search now`() =
    runTest {
      // Not a fixed profile id and not a remembered one: `LidarrAddTargets.resolve` reads the
      // folder's defaults, which change on the server without telling anybody.
      services.save(lidarrCredentials())
      lidarrSource.rootFolders = listOf(
        rootFolder(name = "Archive", path = "/archive", defaultQualityProfileId = 11, defaultMetadataProfileId = 12),
      )

      repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

      val (_, targets, searchNow) = lidarrSource.submitted.single()
      assertThat(targets.rootFolderPath).isEqualTo("/archive")
      assertThat(targets.qualityProfileId).isEqualTo(11)
      assertThat(targets.metadataProfileId).isEqualTo(12)
      // A user who asked for an album meant "go and get it".
      assertThat(searchNow).isTrue()
    }

  @Test
  fun `the first inaccessible root folder is skipped rather than used`() = runTest {
    // Measured in `:integrations:lidarr`: an inaccessible folder fails the add with a message about
    // UNIX ownership, shown to somebody who was choosing an album.
    services.save(lidarrCredentials())
    lidarrSource.rootFolders = listOf(
      rootFolder(id = 1, name = "Broken", path = "/broken", accessible = false),
      rootFolder(id = 2, name = "Music", path = "/music"),
    )

    repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

    assertThat(lidarrSource.submitted.single().second.rootFolderPath).isEqualTo("/music")
  }

  @Test
  fun `an album lidarr already has is still recorded, with the id lidarr already holds`() = runTest {
    // A normal outcome, not an error: the user asked twice, or a housemate added it. Recording it
    // is what puts the thing they pressed a button for into their own list.
    services.save(lidarrCredentials())
    lidarrSource.addOutcome = LidarrAddOutcome.AlreadyAdded
    lidarrSource.addedAlbumId = 77

    val result = repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

    assertThat((result as SubmitResult.Recorded).request.remoteId).isEqualTo("77")
    assertThat(lidarrSource.addedAlbumIdsAsked).containsExactly("mbid-1")
  }

  @Test
  fun `an already-added album lidarr cannot find an id for is recorded with no remote id`() =
    runTest {
      // `null` is a real answer -- the album is not in the library, or the row carried no usable id
      // -- and a row with no remote id is simply one that cannot be polled yet. Inventing an id
      // would send every later status check at somebody else's album.
      services.save(lidarrCredentials())
      lidarrSource.addOutcome = LidarrAddOutcome.AlreadyAdded
      lidarrSource.addedAlbumId = null

      val result = repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

      assertThat((result as SubmitResult.Recorded).request.remoteId).isNull()
    }

  @Test
  fun `a rejected add shows lidarr's own sentences and records nothing`() = runTest {
    services.save(lidarrCredentials())
    lidarrSource.addOutcome = LidarrAddOutcome.Rejected(
      listOf(
        LidarrValidationFailure("Artist.QualityProfileId", "Quality profile does not exist", "X"),
        LidarrValidationFailure("Path", "Folder '/music' is not writable by user 'abc'", null),
      ),
    )

    val result = repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

    assertThat((result as SubmitResult.Refused).reason)
      .isEqualTo("Quality profile does not exist Folder '/music' is not writable by user 'abc'")
    assertThat(requests.requests().first()).isEmpty()
  }

  @Test
  fun `a rejection that says nothing still produces a sentence`() = runTest {
    // `Refused("")` renders as a blank line under the button, which reads as a UI bug.
    services.save(lidarrCredentials())
    lidarrSource.addOutcome =
      LidarrAddOutcome.Rejected(listOf(LidarrValidationFailure("Path", "  ", null)))

    val result = repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

    assertThat((result as SubmitResult.Refused).reason).isNotBlank()
  }

  @Test
  fun `a rejection whose failures carry no message at all still produces a sentence`() = runTest {
    // `errorMessage` is nullable on `LidarrValidationFailure` because nothing obliges a proxy or a
    // future release to send it, and a null is a different value from a blank one.
    services.save(lidarrCredentials())
    lidarrSource.addOutcome =
      LidarrAddOutcome.Rejected(listOf(LidarrValidationFailure("Path", null, "RootFolderExists")))

    val result = repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

    assertThat((result as SubmitResult.Refused).reason).isNotBlank()
  }

  @Test
  fun `a lidarr with no writable folder is refused before anything is posted`() = runTest {
    services.save(lidarrCredentials())
    lidarrSource.rootFolders = listOf(rootFolder(accessible = false))

    val result = repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

    assertThat(result).isInstanceOf(SubmitResult.Refused::class.java)
    // Nothing was posted: being told before pressing the button is strictly better than a
    // validation message about UNIX ownership afterwards.
    assertThat(lidarrSource.submitted).isEmpty()
  }

  @Test
  fun `a folder with no usable profile is refused and the message names the folder`() = runTest {
    services.save(lidarrCredentials())
    lidarrSource.rootFolders = listOf(rootFolder(name = "Archive", defaultQualityProfileId = 0))
    lidarrSource.qualityProfiles = emptyList()

    val result = repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

    // "This cannot be filed" is much less useful than naming the folder it cannot be filed in.
    assertThat((result as SubmitResult.Refused).reason).contains("Archive")
    assertThat(lidarrSource.submitted).isEmpty()
  }

  @Test
  fun `a validation exception's message reaches the user through lidarr's named field`() = runTest {
    // `LidarrValidationException.message` is a **constant**, deliberately, so that server-supplied
    // text never reaches the one string a crash reporter uploads. Showing it is a deliberate act at
    // a surface, and this is that surface -- so a repository that used `e.message` would show the
    // constant and lose the only actionable sentence there was.
    services.save(lidarrCredentials())
    lidarrSource.addFailWith = LidarrValidationException(
      listOf(LidarrValidationFailure("ForeignAlbumId", "This album has already been added.", null)),
    )

    val result = repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

    assertThat((result as SubmitResult.Refused).reason).contains("This album has already been added.")
  }

  // ---- submit: bindery ------------------------------------------------------------------------

  @Test
  fun `a book is asked for as an audiobook, never as bindery's ebook default`() = runTest {
    // This module's central trap: `mediaType` defaults to `ebook` **server-side**, so a request
    // that omitted it would be answered 201 with an e-book MuPlay cannot play.
    services.save(binderyCredentials())
    binderySource.addResult = binderyBook(id = 55, foreignBookId = "ol-1", title = "Dune", status = "wanted")

    val result = repository.submit(RequestCandidate.Book(binderyCandidate("ol-1", "Dune", "Herbert"), false))

    val (_, mediaType, searchOnAdd) = binderySource.submitted.single()
    assertThat(mediaType).isEqualTo(BinderyMediaType.AUDIOBOOK)
    assertThat(searchOnAdd).isTrue()
    val request = (result as SubmitResult.Recorded).request
    assertThat(request.remoteId).isEqualTo("55")
    assertThat(request.service).isEqualTo(IntegrationService.BINDERY)
  }

  @Test
  fun `the stored title is bindery's own word for the book and the author is the candidate's`() =
    runTest {
      // `BinderyBook` has no author field at all, and the arrival match compares the **server's**
      // title against Navidrome's -- so neither half can come from the other.
      services.save(binderyCredentials())
      binderySource.addResult =
        binderyBook(id = 55, foreignBookId = "ol-1", title = "Dune (Unabridged)", status = "wanted")

      val result = repository.submit(RequestCandidate.Book(binderyCandidate("ol-1", "Dune", "Frank Herbert"), false))

      val request = (result as SubmitResult.Recorded).request
      assertThat(request.title).isEqualTo("Dune (Unabridged)")
      assertThat(request.subtitle).isEqualTo("Frank Herbert")
    }

  @Test
  fun `bindery's own refusal sentence reaches the user through its named field`() = runTest {
    // The same containment boundary as Lidarr's above, and the reason it matters here is that
    // Bindery's sentence is the actionable one: "Add the author manually first".
    services.save(binderyCredentials())
    binderySource.addFailWith = BinderyMessageException(
      status = 422,
      binderyMessage = "Author metadata unavailable. Add the author manually first.",
    )

    val result = repository.submit(RequestCandidate.Book(binderyCandidate("ol-1", "Dune", "Herbert"), false))

    assertThat((result as SubmitResult.Refused).reason)
      .isEqualTo("Author metadata unavailable. Add the author manually first.")
  }

  // ---- submit: everything that is not a service answering --------------------------------------

  @Test
  fun `submitting to a service that is not configured builds no client and says so`() = runTest {
    val result = repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

    assertThat((result as SubmitResult.Refused).reason).contains("Lidarr")
    assertThat(lidarrFactory.credentialsSeen).isEmpty()
  }

  @Test
  fun `submitting against a credential of the wrong type sends nothing anywhere`() = runTest {
    // A Bindery key filed under LIDARR. The candidate is an album; neither arm matches, and the one
    // thing that must not happen is a Lidarr client built from a Bindery key.
    services.saveUnder(IntegrationService.LIDARR, binderyCredentials())

    val result = repository.submit(RequestCandidate.Album(lidarrCandidate("mbid-1", "A", "x"), false))

    assertThat(result).isInstanceOf(SubmitResult.Refused::class.java)
    assertThat(lidarrFactory.credentialsSeen).isEmpty()
    assertThat(binderyFactory.credentialsSeen).isEmpty()
  }

  @Test
  fun `a book submitted against a lidarr credential sends nothing anywhere either`() = runTest {
    // The mirror of the case above, and not decoration: the `when` has one arm per service and each
    // arm is a conjunction, so a rule that only ever saw one of them mismatched would leave the
    // other arm's second half unexercised -- the "a branch observed once is a branch untested"
    // shape this plan keeps finding.
    services.saveUnder(IntegrationService.BINDERY, lidarrCredentials())

    val result = repository.submit(RequestCandidate.Book(binderyCandidate("ol-1", "D", "H"), false))

    assertThat(result).isInstanceOf(SubmitResult.Refused::class.java)
    assertThat(lidarrFactory.credentialsSeen).isEmpty()
    assertThat(binderyFactory.credentialsSeen).isEmpty()
  }

  @Test
  fun `a failure with no message still produces a sentence`() = runTest {
    services.save(binderyCredentials())
    binderySource.addFailWith = IllegalStateException()

    val result = repository.submit(RequestCandidate.Book(binderyCandidate("ol-1", "D", "H"), false))

    assertThat((result as SubmitResult.Refused).reason).isNotBlank()
  }

  @Test
  fun `a failure whose message is blank still produces a sentence`() = runTest {
    // Two observations, because `message == null` and `message == ""` are different values and a
    // `?:` covers only the first.
    services.save(binderyCredentials())
    binderySource.addFailWith = IllegalStateException("   ")

    val result = repository.submit(RequestCandidate.Book(binderyCandidate("ol-1", "D", "H"), false))

    assertThat((result as SubmitResult.Refused).reason).isNotBlank()
  }

  @Test
  fun `an ordinary transport failure is shown as the exception's own constant message`() = runTest {
    services.save(binderyCredentials())
    binderySource.addFailWith = java.io.IOException("no route to host")

    val result = repository.submit(RequestCandidate.Book(binderyCandidate("ol-1", "D", "H"), false))

    assertThat((result as SubmitResult.Refused).reason).isEqualTo("no route to host")
  }

  @Test
  fun `cancelling the caller during a submit is not turned into a refusal`() = runTest {
    services.save(binderyCredentials())
    val cancellation = CancellationException("the screen went away")
    binderySource.addFailWith = cancellation

    val thrown =
      runCatching { repository.submit(RequestCandidate.Book(binderyCandidate("ol-1", "D", "H"), false)) }
        .exceptionOrNull()

    assertThat(thrown).isSameAs(cancellation)
  }
}
