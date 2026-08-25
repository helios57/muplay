package app.muplay.integrations

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.muplay.integrations.db.IntegrationRequestsDatabase
import app.muplay.integrations.db.MediaRequestEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The request store, against real Room and real SQL.
 *
 * The two things being proven here are the ones a fake DAO could not prove: that the **order** the
 * repository promises is produced by the query rather than by insertion luck, and that
 * `requests(service)` **passes its argument through** instead of returning everything or returning
 * one hardcoded service's rows.
 *
 * Per this plan's four-combination rule, the service-shaped state is exercised at every
 * combination this class can reach: no rows at all (`nothing is stored before anything is
 * recorded`), Lidarr rows only, Bindery rows only (`a service with no rows of its own reads as
 * empty even when the table is not`), and both (`requests filtered by service returns that
 * service's rows and only those`). The *credential* combinations the rule also names are not
 * reachable from this class: the request store holds no credential and never reads one.
 *
 * **camelCase method names, not this project's JVM-tier backticks.** A name with spaces in it is a
 * DEX SimpleName with spaces in it, and D8 refuses those below DEX version 040 — `minSdk 26`
 * compiles 035. Measured both ways here rather than assumed: a plain backticked method fails with
 * *"Space characters in SimpleName 'the provided clock reads the wall clock in utc' … (method name
 * … on class …)"*, and a backticked `runTest` fails on the **lambda class** Kotlin names after its
 * enclosing method (*"SimpleName 'MediaRequestRepositoryTest${'$'}setStatus round-trips a status
 * that carries data${'$'}1'"*) — which names a class nobody wrote and is the more confusing of the
 * two. Every instrumented test class in this repository is camelCase for this reason; the plan's
 * Step 3 listing was written in backticks and is wrong on that one point.
 */
class MediaRequestRepositoryTest {

  private lateinit var database: IntegrationRequestsDatabase
  private lateinit var repository: MediaRequestRepository

  /** A clock the test moves by hand, so `requestedAt` is a value and never a race. */
  private class SteppingClock(private var millis: Long) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId) = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
    fun advanceTo(newMillis: Long) { millis = newMillis }
  }

  private val clock = SteppingClock(1_000L)

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, IntegrationRequestsDatabase::class.java).build()
    repository = MediaRequestRepository(database.requestDao(), clock)
  }

  @After
  fun tearDown() = database.close()

  @Test
  fun nothingIsStoredBeforeAnythingIsRecorded() = runTest {
    // The empty state, observed. It is also what the UI renders when a service is configured but
    // has never been asked for anything, and it must not be reachable only by accident.
    assertThat(repository.requests().first()).isEmpty()
    assertThat(repository.requests(IntegrationService.LIDARR).first()).isEmpty()
    assertThat(repository.requests(IntegrationService.BINDERY).first()).isEmpty()
  }

  @Test
  fun recordWritesEveryFieldAndReturnsWhatItWrote() = runTest {
    clock.advanceTo(1_700_000_000_000L)

    val returned = repository.record(
      service = IntegrationService.LIDARR,
      externalId = "mbid-abc",
      title = "Kind of Blue",
      subtitle = "Miles Davis",
      remoteId = "42",
    )

    val stored = repository.requests().first().single()

    // Field by field on both the return value and the stored row, so neither can be a constant.
    assertThat(returned.id).isEqualTo("LIDARR:mbid-abc")
    assertThat(returned.service).isEqualTo(IntegrationService.LIDARR)
    assertThat(returned.externalId).isEqualTo("mbid-abc")
    assertThat(returned.title).isEqualTo("Kind of Blue")
    assertThat(returned.subtitle).isEqualTo("Miles Davis")
    assertThat(returned.remoteId).isEqualTo("42")
    assertThat(returned.status).isEqualTo(RequestStatus.Requested)
    assertThat(returned.requestedAtEpochMs).isEqualTo(1_700_000_000_000L)
    assertThat(returned.updatedAtEpochMs).isEqualTo(1_700_000_000_000L)
    assertThat(stored).isEqualTo(returned)
  }

  @Test
  fun recordWritesTheValuesItIsGivenNotAFixedRow() = runTest {
    // The second observation of every argument. Without this, a `record` that hardcoded its
    // title, subtitle and external id passes the test above.
    clock.advanceTo(2_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "Artist A", remoteId = "1")
    clock.advanceTo(3_000L)
    repository.record(IntegrationService.LIDARR, "mbid-2", "B", "Artist B", remoteId = null)

    val rows = repository.requests().first()

    assertThat(rows.map { it.externalId }).containsExactly("mbid-2", "mbid-1")
    assertThat(rows.map { it.title }).containsExactly("B", "A")
    assertThat(rows.map { it.subtitle }).containsExactly("Artist B", "Artist A")
    assertThat(rows.map { it.remoteId }).containsExactly(null, "1")
    assertThat(rows.map { it.requestedAtEpochMs }).containsExactly(3_000L, 2_000L)
  }

  @Test
  fun recordStampsTheServiceItIsGivenNotAFixedOne() = runTest {
    // The `service` column is the one field of `record` that the two tests above cannot see: both
    // write Lidarr rows, so a `service = IntegrationService.LIDARR.name` hardcode passes them.
    // Two services, one assertion, exact list.
    clock.advanceTo(1_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)
    clock.advanceTo(2_000L)
    repository.record(IntegrationService.BINDERY, "book-1", "B", "y", null)

    assertThat(repository.requests().first().map { it.service })
      .containsExactly(IntegrationService.BINDERY, IntegrationService.LIDARR)
  }

  /**
   * **Order is a property.** Newest first, and proven by inserting in an order that the query has
   * to undo: without an `ORDER BY`, SQLite returns rows in rowid order, which for these three
   * inserts is exactly the *opposite* of what is asserted.
   */
  @Test
  fun requestsComeBackNewestFirstRegardlessOfInsertionOrder() = runTest {
    clock.advanceTo(1_000L); repository.record(IntegrationService.LIDARR, "a", "A", "x", null)
    clock.advanceTo(3_000L); repository.record(IntegrationService.LIDARR, "b", "B", "x", null)
    clock.advanceTo(2_000L); repository.record(IntegrationService.LIDARR, "c", "C", "x", null)

    assertThat(repository.requests().first().map { it.externalId }).containsExactly("b", "c", "a")
  }

  /**
   * The same property on the *filtered* query, which is a separate `@Query` string and so a
   * separate place the `ORDER BY` can be missing. Same insertion order, same undo.
   */
  @Test
  fun requestsForOneServiceComeBackNewestFirstToo() = runTest {
    clock.advanceTo(1_000L); repository.record(IntegrationService.LIDARR, "a", "A", "x", null)
    clock.advanceTo(3_000L); repository.record(IntegrationService.LIDARR, "b", "B", "x", null)
    clock.advanceTo(2_000L); repository.record(IntegrationService.LIDARR, "c", "C", "x", null)

    assertThat(repository.requests(IntegrationService.LIDARR).first().map { it.externalId })
      .containsExactly("b", "c", "a")
  }

  /**
   * The argument-passthrough proof. Two services, two queries, each returning exactly the other's
   * complement — so a `requests(service)` that ignored its argument fails whichever way it was
   * hardcoded, and one that returned everything fails both.
   */
  @Test
  fun requestsFilteredByServiceReturnsThatServicesRowsAndOnlyThose() = runTest {
    clock.advanceTo(1_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "An album", "An artist", null)
    clock.advanceTo(2_000L)
    repository.record(IntegrationService.BINDERY, "book-1", "A book", "An author", null)

    assertThat(repository.requests(IntegrationService.LIDARR).first().map { it.externalId })
      .containsExactly("mbid-1")
    assertThat(repository.requests(IntegrationService.BINDERY).first().map { it.externalId })
      .containsExactly("book-1")
    assertThat(repository.requests().first().map { it.externalId })
      .containsExactly("book-1", "mbid-1")
  }

  @Test
  fun aServiceWithNoRowsOfItsOwnReadsAsEmptyEvenWhenTheTableIsNot() = runTest {
    // The one-service-configured user's permanent state, and the combination every other test in
    // this class configures around: a non-empty table queried for the service that has nothing in
    // it. An empty list here is a real observation, not the absence of one, because the *other*
    // query on the same table returns a row.
    repository.record(IntegrationService.BINDERY, "book-1", "A book", "An author", null)

    assertThat(repository.requests(IntegrationService.LIDARR).first()).isEmpty()
    assertThat(repository.requests(IntegrationService.BINDERY).first()).hasSize(1)
  }

  @Test
  fun theSameExternalIdUnderTwoServicesIsTwoRowsNotOne() = runTest {
    // The subtler half of the composite key. Two services can legitimately use the same
    // identifier space, and a key of `externalId` alone would silently overwrite.
    repository.record(IntegrationService.LIDARR, "shared", "Album", "Artist", null)
    repository.record(IntegrationService.BINDERY, "shared", "Book", "Author", null)

    assertThat(repository.requests().first().map { it.id })
      .containsExactlyInAnyOrder("LIDARR:shared", "BINDERY:shared")
  }

  @Test
  fun reRequestingTheSameThingUpdatesTheRowRatherThanDuplicatingIt() = runTest {
    clock.advanceTo(1_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "Old title", "Artist", remoteId = null)
    clock.advanceTo(5_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "New title", "Artist", remoteId = "42")

    val rows = repository.requests().first()

    assertThat(rows).hasSize(1)
    assertThat(rows.single().title).isEqualTo("New title")
    assertThat(rows.single().remoteId).isEqualTo("42")
    // The original request time survives; only `updatedAt` moves. A user's "requested on" date
    // must not jump every time a poll refreshes the row.
    assertThat(rows.single().requestedAtEpochMs).isEqualTo(1_000L)
    assertThat(rows.single().updatedAtEpochMs).isEqualTo(5_000L)
  }

  @Test
  fun reRecordingKeepsTheStatusTheRowAlreadyReached() = runTest {
    // `record` is called again by every poll that refreshes a row's title or remote id. If it
    // wrote `Requested` unconditionally, a finished download would revert to "queued" on the next
    // refresh -- and the test above cannot see it, because its row never left `Requested`.
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)
    repository.setStatus("LIDARR:mbid-1", RequestStatus.Downloading(percentComplete = 40))

    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", remoteId = "42")

    assertThat(repository.requests().first().single().status)
      .isEqualTo(RequestStatus.Downloading(percentComplete = 40))
  }

  @Test
  fun setStatusChangesOnlyTheRowItNamesAndStampsUpdatedAt() = runTest {
    clock.advanceTo(1_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)
    repository.record(IntegrationService.LIDARR, "mbid-2", "B", "x", null)

    clock.advanceTo(9_000L)
    repository.setStatus("LIDARR:mbid-1", RequestStatus.Downloading(percentComplete = 40))

    val byId = repository.requests().first().associateBy { it.id }

    // Two rows, one changed, one not: a `setStatus` that ignored its id and updated everything
    // passes a single-row test.
    assertThat(byId.getValue("LIDARR:mbid-1").status)
      .isEqualTo(RequestStatus.Downloading(percentComplete = 40))
    assertThat(byId.getValue("LIDARR:mbid-1").updatedAtEpochMs).isEqualTo(9_000L)
    assertThat(byId.getValue("LIDARR:mbid-2").status).isEqualTo(RequestStatus.Requested)
    assertThat(byId.getValue("LIDARR:mbid-2").updatedAtEpochMs).isEqualTo(1_000L)
  }

  @Test
  fun setStatusWritesTheStatusItIsGivenNotAFixedOne() = runTest {
    // The second observation of `setStatus`'s other argument. The test above observes exactly one
    // status value, so a `setStatus` that wrote `Downloading(40)` whatever it was handed passes
    // it. Two rows, two different statuses, one exact list.
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)
    repository.record(IntegrationService.LIDARR, "mbid-2", "B", "x", null)

    repository.setStatus("LIDARR:mbid-1", RequestStatus.Downloading(percentComplete = 40))
    repository.setStatus("LIDARR:mbid-2", RequestStatus.Arrived(albumId = "al-7"))

    val byId = repository.requests().first().associateBy { it.id }
    assertThat(byId.getValue("LIDARR:mbid-1").status)
      .isEqualTo(RequestStatus.Downloading(percentComplete = 40))
    assertThat(byId.getValue("LIDARR:mbid-2").status).isEqualTo(RequestStatus.Arrived("al-7"))
  }

  @Test
  fun setStatusRoundTripsAStatusThatCarriesData() = runTest {
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)

    repository.setStatus("LIDARR:mbid-1", RequestStatus.Arrived(albumId = "al-99"))

    assertThat(repository.requests().first().single().status)
      .isEqualTo(RequestStatus.Arrived(albumId = "al-99"))
  }

  @Test
  fun setStatusOnAnIdThatDoesNotExistChangesNothingAndDoesNotThrow() = runTest {
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)

    repository.setStatus("LIDARR:nope", RequestStatus.Imported)

    assertThat(repository.requests().first().single().status).isEqualTo(RequestStatus.Requested)
  }

  @Test
  fun forgetRemovesOnlyTheRowItNames() = runTest {
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)
    repository.record(IntegrationService.LIDARR, "mbid-2", "B", "x", null)

    repository.forget("LIDARR:mbid-1")

    assertThat(repository.requests().first().map { it.id }).containsExactly("LIDARR:mbid-2")
  }

  @Test
  fun findReturnsTheRowItNamesAndNullForOneThatIsNotThere() = runTest {
    // `find` is DAO surface the repository does not expose, and Task 9's arrival matching reads
    // it. Two observations: the row it names, and the absence of one it does not.
    clock.advanceTo(4_000L)
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", "42")

    val found = database.requestDao().find("LIDARR:mbid-1")

    assertThat(found?.externalId).isEqualTo("mbid-1")
    assertThat(found?.remoteId).isEqualTo("42")
    assertThat(database.requestDao().find("LIDARR:nope")).isNull()
  }

  @Test
  fun aRowNamingAServiceThisBuildDoesNotKnowIsSkippedNotFatal() = runTest {
    database.requestDao().upsert(
      MediaRequestEntity(
        id = "SOMETHING:1", service = "SOMETHING", externalId = "1", title = "t",
        subtitle = "s", remoteId = null, status = "REQUESTED", statusDetail = null,
        requestedAtEpochMs = 1L, updatedAtEpochMs = 1L,
      ),
    )
    repository.record(IntegrationService.LIDARR, "mbid-1", "A", "x", null)

    // The known row still renders; the unknown one is simply not there.
    assertThat(repository.requests().first().map { it.id }).containsExactly("LIDARR:mbid-1")
  }

  @Test
  fun aStoredStatusThisBuildCannotReadRendersAsAFailureRatherThanCrashing() =
    runTest {
      // The corruption path, planted on disk rather than hoped for. It is the only way to reach
      // `fromStored`'s `else` arm through the repository, and the row has to survive the read.
      database.requestDao().upsert(
        MediaRequestEntity(
          id = "LIDARR:mbid-1", service = "LIDARR", externalId = "mbid-1", title = "t",
          subtitle = "s", remoteId = null, status = "TELEPORTED", statusDetail = null,
          requestedAtEpochMs = 1L, updatedAtEpochMs = 1L,
        ),
      )

      val status = repository.requests().first().single().status

      assertThat(status).isInstanceOf(RequestStatus.Failed::class.java)
      assertThat((status as RequestStatus.Failed).reason).contains("TELEPORTED")
    }
}
