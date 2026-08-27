package app.muplay.integrations.requests

import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.MediaRequestRepository
import app.muplay.integrations.RequestStatus
import app.muplay.integrations.bindery.BinderyBook
import app.muplay.integrations.bindery.BinderyBookCandidate
import app.muplay.integrations.bindery.BinderySource
import app.muplay.integrations.bindery.BinderySourceFactory
import app.muplay.integrations.bindery.BinderyStatusMapper
import app.muplay.integrations.lidarr.LidarrAlbumCandidate
import app.muplay.integrations.lidarr.LidarrSource
import app.muplay.integrations.lidarr.LidarrSourceFactory
import app.muplay.integrations.lidarr.LidarrStatusMapper
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What one refresh did.
 *
 * [skippedUnconfigured] and [failed] are two different facts and must not be merged: "we did not
 * ask" is not "it went wrong". A user with only Lidarr configured is on the first one permanently
 * and nothing about it is an error; the second is the one a screen may want to say something
 * about.
 *
 * [failed] is a **fourth** field the plan's Step 5 did not name, defaulted so that the plan's
 * three-argument construction still compiles. Without it a refresh in which every configured
 * service threw is indistinguishable from one that had nothing to do — both report
 * `polled = 0, updated = 0` and an empty skip set — which is the silent-failure class this plan
 * spends its severability contract avoiding everywhere else.
 *
 * @property polled how many stored requests were asked about — rows of a configured service, not
 * yet [RequestStatus.Arrived], carrying a [MediaRequest.remoteId] to correlate on, whose service
 * answered.
 * @property updated how many rows this refresh actually rewrote. A status that did not change is
 * not written back, so `updated = 0` with `polled > 0` means "nothing has moved", not "nothing
 * happened".
 */
data class RefreshReport(
  val polled: Int,
  val updated: Int,
  val skippedUnconfigured: Set<IntegrationService>,
  val failed: Set<IntegrationService> = emptySet(),
)

/**
 * The one entry point to this feature's data, per this project's repository rule.
 *
 * Composes four things and owns no protocol of its own: the stored rows ([MediaRequestRepository]),
 * which services the user has configured ([ConfiguredServices]), each service's own status
 * vocabulary (`LidarrStatusMapper` / `BinderyStatusMapper`), and the bridge to Navidrome's scan
 * ([RequestArrivalDetector]).
 *
 * **The severability contract, at the data layer.** A user with only Lidarr configured causes
 * **zero** Bindery traffic — not "the Bindery UI was not shown", but that no call is made at all.
 * That is structural here rather than careful: a service's client is built from the credentials in
 * [ConfiguredServices]' own map, so a service that is not in the map has nothing to call with.
 *
 * **This takes the two `…SourceFactory` interfaces rather than the two `…SourceProvider` classes
 * the plan's Interfaces block named, and that is what keeps `RequestsRepositoryTest` in Tier 1.**
 * Both providers are concrete classes whose one collaborator is `IntegrationCredentialStore`,
 * which is DataStore over the Android Keystore — inject either and this whole class becomes
 * instrumented-only, including the four-configuration-combination test the severability contract
 * demands. Both factories are already `fun interface`s, declared as such by Tasks 4 and 8 for
 * exactly this ("so that Task 9's tests can hand [the provider] a factory returning a hand-written
 * fake"). The second gain is that "configured" and "the client we poll with" then come from one
 * read of the store and cannot disagree.
 */
@Singleton
class RequestsRepository @Inject constructor(
  private val requests: MediaRequestRepository,
  private val services: ConfiguredServices,
  private val lidarrFactory: LidarrSourceFactory,
  private val binderyFactory: BinderySourceFactory,
  private val arrival: RequestArrivalDetector,
) {

  /**
   * Which services the user has configured, as it changes.
   *
   * This is what Task 10's navigation decides whether to render anything at all from: empty means
   * the Requests destination does not exist, not that it is empty.
   */
  val configuredServices: Flow<Set<IntegrationService>> =
    services.configured().map { credentials -> credentials.keys }

  /** Every stored request, newest first — `MediaRequestRepository`'s own order. */
  val all: Flow<List<MediaRequest>> = requests.requests()

  /**
   * Asks every configured service what happened to its requests, and writes back what changed.
   *
   * One service failing leaves the other's rows updated and its own rows **untouched** rather than
   * marked failed — spec section 8's "fail closed" rule applied within the feature itself. A row
   * whose status did not change is not written back at all, because an unconditional write would
   * move `updatedAtEpochMs` on every refresh and make "last updated" useless.
   */
  suspend fun refresh(): RefreshReport {
    val credentials = services.configured().first()
    val stored = requests.requests().first()
    var polled = 0
    var updated = 0
    val skipped = mutableSetOf<IntegrationService>()
    val failed = mutableSetOf<IntegrationService>()

    for (service in IntegrationService.entries) {
      val credential = credentials[service]
      if (credential == null) {
        skipped += service
        continue
      }
      // A credential of the wrong type under this service's key. The shipped
      // `IntegrationCredentialStore.read` cannot produce one -- it builds the credential *from*
      // the key it is reading, and `aStoredBinderyEntryReadsAsBinderysNotAsLidarrs` is what holds
      // it to that -- but `ConfiguredServices` is an interface and its type permits it, and the
      // one thing this class must never do with a corrupt store is poll Lidarr with Bindery's key.
      //
      // Reported as **failed**, not skipped: the user did configure this service, and telling a
      // screen it was never set up would offer them a setup flow they have already completed.
      // Checked once here rather than with an `as?` inside each of the two status functions, so
      // that `nextStatuses` can be exhaustive over the sealed type with no cast at all and
      // `polled` never counts a row nothing was ever asked about.
      if (credential.service != service) {
        failed += service
        continue
      }
      val pollable = stored.filter { it.service == service && isPollable(it) }
      // Nothing to ask about is not a reason to open a connection. Note this is NOT a skip: the
      // service is configured, and reporting it as skipped would tell a screen the user had not
      // set it up.
      if (pollable.isEmpty()) continue

      val next = try {
        nextStatuses(credential, pollable)
      } catch (e: CancellationException) {
        // Cancelling the caller's scope is not a service failure and must not be reported as one --
        // `SyncEngine.syncIfStale`'s own rule, for the same reason.
        throw e
      } catch (e: Exception) {
        failed += service
        continue
      }

      polled += pollable.size
      for (request in pollable) {
        // No entry means "this service does not know about this row" -- a Bindery book the user
        // deleted, a remote id that is not a number. Leave it exactly as it is rather than
        // rewriting it from an absence.
        val mapped = next[request.id] ?: continue
        // Asked for every polled row, with no `if (mapped == Imported)` guard in front of it, and
        // that absence is a measurement: `RequestArrivalDetector.locate` already returns `null` for
        // every status but `Imported`, and it does so **before** it syncs, so a guard here is
        // behaviourally identical to the call. Written with the guard first and then probed --
        // removing it left all 54 tests in this module green, which makes it a second place the
        // "only an imported request is looked for" rule would have to be kept in step. One rule,
        // one site; the detector's own `a request that is not imported is not looked for at all`
        // is what holds it.
        val located = arrival.locate(request.copy(status = mapped))
        val status = located?.let(RequestStatus::Arrived) ?: mapped
        if (status != request.status) {
          requests.setStatus(request.id, status)
          updated++
        }
      }
    }
    return RefreshReport(polled, updated, skipped, failed)
  }

  /**
   * Records a Lidarr add that Lidarr has **already accepted**, and returns the stored row.
   *
   * [albumId] is Lidarr's own album id — `LidarrAddOutcome.Added.albumId`, or what
   * `LidarrSource.findAddedAlbumId` answers for an `AlreadyAdded`. It is nullable because neither
   * is guaranteed, and a request with no remote id is simply one that cannot be polled yet.
   * Deciding which of those two an outcome is belongs to the caller; this method does not read a
   * `LidarrAddOutcome` so that it cannot record a `Rejected` one.
   */
  suspend fun recordLidarrAdd(candidate: LidarrAlbumCandidate, albumId: Int?): MediaRequest =
    requests.record(
      service = IntegrationService.LIDARR,
      externalId = candidate.foreignAlbumId,
      title = candidate.title,
      subtitle = candidate.artistName,
      remoteId = albumId?.toString(),
    )

  /**
   * Records a Bindery add that Bindery has **already accepted**, and returns the stored row.
   *
   * Takes both halves because neither carries everything: [book] is what Bindery stored (its own
   * durable id, and the title and `foreignBookId` as the server has them), while [candidate] is
   * the only place an author name exists at all — `BinderyBook` has no author field. The title
   * comes from [book] rather than [candidate] deliberately: it is the server's own word for the
   * thing, and it is the server's word that the arrival match compares against Navidrome's.
   */
  suspend fun recordBinderyAdd(candidate: BinderyBookCandidate, book: BinderyBook): MediaRequest =
    requests.record(
      service = IntegrationService.BINDERY,
      externalId = book.foreignBookId,
      title = book.title,
      subtitle = candidate.authorName.orEmpty(),
      remoteId = book.id.toString(),
    )

  /** Forgets one request. Deletes MuPlay's row and nothing on any server — see spec section 8. */
  suspend fun forget(id: String) = requests.forget(id)

  /**
   * Whether a stored row is worth asking a service about.
   *
   * [RequestStatus.Arrived] is terminal: the album is in the user's library and there is nothing
   * left to learn, so re-polling it could only ever take a working "play it" button away. A row
   * with no [MediaRequest.remoteId] has nothing to correlate a service's answer on.
   */
  private fun isPollable(request: MediaRequest): Boolean =
    request.status !is RequestStatus.Arrived && request.remoteId != null

  /**
   * Dispatched on the **credential's own type**, not on the service key it was filed under.
   *
   * `refresh` has already refused a credential whose [IntegrationCredentials.service] disagrees
   * with its key, so the two are the same fact here and this `when` needs no cast — which is what
   * keeps both status functions free of an `as?` whose null arm nothing could ever reach.
   */
  private suspend fun nextStatuses(
    credential: IntegrationCredentials,
    pollable: List<MediaRequest>,
  ): Map<String, RequestStatus> = when (credential) {
    is IntegrationCredentials.Lidarr -> lidarrStatuses(credential, pollable)
    is IntegrationCredentials.Bindery -> binderyStatuses(credential, pollable)
  }

  /**
   * One `queue()` for the whole service, then one `albumProgress` per row.
   *
   * Both are needed and `LidarrStatusMapper` says why: the queue record vanishes the moment an
   * import completes, so files-on-disk is the only fact that survives it, and it is the one the
   * mapper ranks first.
   *
   * Takes `IntegrationCredentials.Lidarr` rather than the sealed supertype, so that no cast lives
   * here at all: a Bindery credential filed under `LIDARR` is a corrupt store rather than a crash,
   * and [refresh] is the one place that says so.
   */
  private suspend fun lidarrStatuses(
    credential: IntegrationCredentials.Lidarr,
    pollable: List<MediaRequest>,
  ): Map<String, RequestStatus> {
    val source: LidarrSource = lidarrFactory.create(credential)
    val queue = source.queue()
    return pollable.mapNotNull { request ->
      // Lidarr's album id is an Int and this column is TEXT. A row that will not parse is one this
      // build cannot correlate; leaving it alone is better than rewriting it from an empty answer.
      val albumId = request.remoteId?.toIntOrNull() ?: return@mapNotNull null
      val item = queue.firstOrNull { it.albumId == albumId }
      request.id to LidarrStatusMapper.map(item, source.albumProgress(albumId))
    }.toMap()
  }

  /**
   * Every book Bindery has, then one lookup per row.
   *
   * Bindery has no by-id book endpoint, so the whole list is what there is; [allBooks] pages it
   * rather than reading the first page and believing it, which is what `BinderyBookPage`'s own
   * documentation warns is one layer up from a dropped field.
   */
  private suspend fun binderyStatuses(
    credential: IntegrationCredentials.Bindery,
    pollable: List<MediaRequest>,
  ): Map<String, RequestStatus> {
    val source: BinderySource = binderyFactory.create(credential)
    val books = allBooks(source).associateBy { it.id.toString() }
    return pollable.mapNotNull { request ->
      val book = books[request.remoteId] ?: return@mapNotNull null
      request.id to BinderyStatusMapper.map(book.status)
    }.toMap()
  }

  /**
   * Every book in the user's Bindery, across as many pages as it takes.
   *
   * The loop's bound is the server's own `total` — the count *before* `limit` applies — so the
   * corpus size is derived from the answer rather than assumed by this client. [MAX_BOOKS] is a
   * second bound for the case where it is not: a `total` larger than the library, or a page that
   * keeps returning rows, must not loop forever inside a refresh.
   */
  private suspend fun allBooks(source: BinderySource): List<BinderyBook> {
    val books = mutableListOf<BinderyBook>()
    do {
      val page = source.books(status = null, limit = BOOK_PAGE_SIZE, offset = books.size)
      books += page.books
    } while (page.books.isNotEmpty() && books.size < page.total && books.size < MAX_BOOKS)
    return books
  }

  private companion object {
    /** Bindery's own default limit, measured in Task 8. One page covers an ordinary library. */
    const val BOOK_PAGE_SIZE = 100

    /** The point at which a paging loop has stopped being a paging loop. */
    const val MAX_BOOKS = 10_000
  }
}
