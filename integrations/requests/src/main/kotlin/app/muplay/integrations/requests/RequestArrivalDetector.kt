package app.muplay.integrations.requests

import app.muplay.database.SyncState
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.RequestStatus
import app.muplay.model.Album
import app.muplay.model.LibraryRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers one question: the service says it has the files — is it in Navidrome yet, and which
 * album is it?
 *
 * Returns `null` for every kind of "we do not know", and there are five of them: the request is
 * not ready, its title carries nothing to match on, the mirror is mid-scan or its sync failed, no
 * library carries the right role, and the match is ambiguous. **None of them is a failure.** Each
 * simply means "ask again next refresh", which is correct because the answer genuinely changes
 * over time.
 *
 * The single design rule, stated once: **a wrong answer is worse than no answer.** A request stuck
 * at [RequestStatus.Imported] is a harmless annoyance the user can route around by opening their
 * library; a request that flips to the wrong album puts a "play it" button on something else.
 *
 * That is why the last step is [Iterable.singleOrNull] and not `firstOrNull`. Matching a Lidarr
 * album to a Navidrome one is a join across two systems with **no shared identifier**, and a real
 * Lidarr lookup for `Kind of Blue` returns four albums of that exact title distinguished only by
 * their `foreignAlbumId` (measured, Plan 7 Task 6). Two equally good candidates is a fact about the
 * library, not a tie to break.
 */
@Singleton
class RequestArrivalDetector @Inject constructor(
  private val sync: MirrorSync,
  private val search: AlbumSearch,
  private val roles: LibraryRoles,
) {

  /**
   * The Navidrome album id this request has arrived as, or `null`.
   *
   * Never throws: every collaborator here already turns its own failures into a value
   * (`SyncEngine.syncIfStale` catches everything but cancellation), and a detector that threw
   * would take a whole refresh down over one unmatchable row.
   */
  suspend fun locate(request: MediaRequest): String? {
    // Filter *before* syncing. A detector that synced first would poll Navidrome on every refresh
    // for every request that has nothing to find.
    if (request.status != RequestStatus.Imported) return null

    // Nothing to match on. A title that normalises away entirely -- "!!!", or whitespace -- would
    // otherwise be equal to every other such title, which is the one way this matcher could
    // produce a confident wrong answer rather than no answer. Checked before the sync for the same
    // reason the status is.
    val title = TitleMatching.normalise(request.title)
    if (title.isEmpty()) return null

    // Mid-scan the mirror is not yet a fact, and a failed sync did not advance the watermark, so
    // in both cases the honest answer is "not yet". Two arms rather than one combined arm because
    // each is separately falsifiable and separately probed.
    when (sync.syncIfStale()) {
      SyncState.ScanInProgress -> return null
      is SyncState.Failed -> return null
      SyncState.UpToDate, is SyncState.Synced -> Unit
    }

    // Spec section 4: library id is the only scoping mechanism there is. A book must not be found
    // by a music request, and vice versa.
    //
    // No `if (libraryIds.isEmpty()) return null` guard, and its absence is a measurement rather
    // than an oversight: over an empty list this whole expression already issues no search and
    // yields `null`, so such a guard is behaviourally identical to the fall-through -- exactly the
    // shape Task 7 deleted from `LidarrStatusMapper` after probing it. Measured rather than
    // reasoned: re-introducing the guard leaves all **54** tests in this module green, so no
    // assertion here or anywhere else can tell the two versions apart.
    //
    // `.distinct()` is absent for the same reason, plus a second one. `AlbumEntity`'s primary key
    // is the album id alone -- its own comment says "an albumId cannot itself span two libraries"
    // -- so two library-scoped searches cannot return one id twice, and no assertion over a real
    // mirror could ever hold that call to anything -- measured the same way, re-adding it leaves
    // all 54 green. And if the impossible did happen, collapsing the duplicates would answer
    // confidently where declining is the fail-closed direction.
    return roles.idsWithRole(roleFor(request.service))
      .flatMap { libraryId -> search.search(libraryId, request.title, SEARCH_LIMIT).albums }
      .filter { album -> matches(album, request, title) }
      .map(Album::id)
      .singleOrNull()
  }

  /**
   * Whether [album] is what [request] asked for. [normalisedTitle] is [MediaRequest.title] already
   * through [TitleMatching], passed in so the request's side is normalised once per `locate` rather
   * than once per candidate.
   *
   * The rule is equality after normalisation and nothing else, so `"Kind of Blue"` and
   * `"Kind of Blue (Remastered)"` are different albums to this code, on purpose.
   */
  private fun matches(album: Album, request: MediaRequest, normalisedTitle: String): Boolean {
    if (TitleMatching.normalise(album.name) != normalisedTitle) return false
    // An artist to check against is decided on the **normalised** subtitle, not the raw one. A
    // service that did not tell us an author or artist -- common for Bindery -- leaves this empty
    // and the title alone has to do, because requiring an artist match would make those requests
    // never arrive. Normalising first is what stops a subtitle of "!!!" from being "non-blank" and
    // then matching every album whose artist is absent.
    val artist = TitleMatching.normalise(request.subtitle)
    if (artist.isEmpty()) return true
    return TitleMatching.normalise(album.artistName.orEmpty()) == artist
  }

  private fun roleFor(service: IntegrationService): LibraryRole = when (service) {
    IntegrationService.LIDARR -> LibraryRole.MUSIC
    IntegrationService.BINDERY -> LibraryRole.AUDIOBOOKS
  }

  private companion object {
    /**
     * Enough rows to contain every album whose title matches, small enough that a two-word title
     * does not drag a page of the mirror into memory. The match is exact after normalisation, so
     * a bigger page would not find anything a smaller one misses unless the library holds more
     * than this many *substring* matches for one title.
     */
    const val SEARCH_LIMIT = 50
  }
}
