package app.muplay.integrations.requests

import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.bindery.BinderyBookCandidate
import app.muplay.integrations.lidarr.LidarrAlbumCandidate

/**
 * One thing a search found, from either service, in the one shape a list renders.
 *
 * **A sealed interface carrying each service's own candidate rather than a flat `data class`**, and
 * that is load-bearing rather than tidy: `LidarrSource.submitAlbum` posts
 * [LidarrAlbumCandidate.raw] — the lookup element *exactly as it came off the wire*, because
 * rebuilding a payload from typed fields drops every field this client does not model — so a
 * candidate that had been flattened into six display strings could not be submitted at all. The
 * common properties below are what a screen reads; the member is what a submit needs.
 *
 * Declared here rather than in `:feature:requests` (where Task 10's plan put it) because
 * [RequestsRepository] both produces and consumes it. That keeps the repository the one entry point
 * to this feature's data, and it keeps `:feature:requests` free of any need to know how either
 * service identifies a work.
 */
sealed interface RequestCandidate {

  val service: IntegrationService

  /** What the *service* identifies the work by — a MusicBrainz id, or Bindery's `foreignBookId`. */
  val externalId: String

  val title: String

  /** The artist or the author. `""` when the service does not know one — see [Book]. */
  val subtitle: String

  val coverUrl: String?

  /**
   * Whether asking for this again would be a duplicate.
   *
   * Two different facts collapse into this one, deliberately, because a user can act on neither
   * differently: the service already has it, or MuPlay already has a request row for it. Note that
   * a `true` here is **not** a reason to disable the button — Lidarr answers a duplicate add with a
   * 400 it can recognise and Bindery upserts — it is a reason to say so on the row.
   */
  val alreadyAdded: Boolean

  /** An album Lidarr's metadata lookup found. */
  data class Album(
    val album: LidarrAlbumCandidate,
    override val alreadyAdded: Boolean,
  ) : RequestCandidate {
    override val service: IntegrationService get() = IntegrationService.LIDARR
    override val externalId: String get() = album.foreignAlbumId
    override val title: String get() = album.title
    override val subtitle: String get() = album.artistName
    override val coverUrl: String? get() = album.remoteCoverUrl
  }

  /**
   * A book Bindery's metadata search found.
   *
   * [subtitle] collapses a null author to `""`, because `BinderyBookCandidate.authorName` is absent
   * on more than half of a real search's results — measured, 18 of 40 — and a screen that had two
   * kinds of nothing to render would show an empty author line for most of them.
   */
  data class Book(
    val book: BinderyBookCandidate,
    override val alreadyAdded: Boolean,
  ) : RequestCandidate {
    override val service: IntegrationService get() = IntegrationService.BINDERY
    override val externalId: String get() = book.foreignBookId
    override val title: String get() = book.title
    override val subtitle: String get() = book.authorName.orEmpty()
    override val coverUrl: String? get() = book.coverUrl
  }
}

/**
 * What one search did.
 *
 * [failed] rather than an exception, for the reason [RefreshReport.failed] exists: one service
 * being down must not throw away the other's results, and "nothing matched" and "we could not ask"
 * are two different sentences. A service that is not configured appears in neither list — it was
 * never asked, and there is nothing for a screen to say about it.
 */
data class SearchReport(
  val candidates: List<RequestCandidate>,
  val failed: Set<IntegrationService>,
)

/**
 * What happened when a candidate was asked for.
 *
 * Two members and not three, because the caller can do exactly two things: show the new row, or
 * show a sentence. [Refused] therefore carries the *sentence* rather than a cause — a rejected
 * validation, a service that threw, and a corrupt credential are all "here is why not".
 */
sealed interface SubmitResult {

  /** The service accepted it and [request] is the row that was written. */
  data class Recorded(val request: MediaRequest) : SubmitResult

  /**
   * It was not asked for, and [reason] is what to tell the user.
   *
   * **Server-supplied text reaches here only through a named field**, never through
   * `Throwable.message`: `LidarrValidationException.lidarrMessage` and
   * `BinderyMessageException.binderyMessage` are what those two classes exist to keep *out* of the
   * one string a crash reporter uploads, and showing them is a deliberate act at a surface. See
   * either exception's own documentation.
   */
  data class Refused(val reason: String) : SubmitResult
}
