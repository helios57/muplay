package app.muplay.integrations.lidarr

import app.muplay.integrations.RequestStatus
import kotlin.math.roundToInt

/**
 * Turns what Lidarr says about a download into what this app shows about a request.
 *
 * A pure object with no HTTP in it, so every branch is Tier-1 enforceable -- the same argument
 * [LidarrAddTargets] and Plan 3's `StreamRetryPolicy` make.
 *
 * **Branches on `trackedDownloadState` and never on `status`.** A queue record carries both.
 * `status` is `model.Status.FirstCharToLower()` -- a download-*client* status whose complete value
 * set is not enumerated anywhere, and branching on a set you cannot enumerate means an `else` arm
 * that is a guess presented as a fact. `trackedDownloadState` is declared as a nine-member enum in
 * Lidarr's own OpenAPI document; all nine are named in [KNOWN_STATES] and every one is mapped by
 * [map]. `trackedDownloadStatus` (`ok`/`warning`/`error`) is carried on [LidarrQueueItem] and is
 * never decided on: a `warning` on an item that is still downloading is not a failure.
 *
 * **There is no `in IN_PROGRESS ->` arm in [map], and its absence is a measurement rather than an
 * oversight.** An in-progress state and an unrecognised one must produce the *same*
 * `Downloading(pct)` -- that is the fail-closed rule this plan's severability contract states for
 * this task -- so an arm for the first would be behaviourally identical to the fall-through that
 * already handles the second. Written that way first and then probed: deleting the arm outright
 * left **all 117 tests in this module green**, which makes it code no assertion in this repository
 * can hold to anything. It is gone; [IN_PROGRESS] survives as data, and
 * `LidarrStatusMapperTest.the client recognises exactly lidarrs nine states` is what holds it to
 * Lidarr's enum.
 */
object LidarrStatusMapper {

  /** `imported` -- Lidarr has the files. Never [RequestStatus.Arrived]; see [map]. */
  private const val IMPORTED = "imported"

  /** `ignored` -- a human or a rule told Lidarr to stop. Terminal, and its own kind of failure. */
  private const val IGNORED = "ignored"

  /** The download client gave up. `Pending` is a retry window, and it is still a failed download. */
  private val DOWNLOAD_FAILED = setOf("downloadFailed", "downloadFailedPending")

  /** The bytes arrived and Lidarr would not file them. A different problem, and a different fix. */
  private val IMPORT_FAILED = setOf("importFailed", "importBlocked")

  /**
   * Something is happening and nothing has gone wrong yet.
   *
   * Deliberately **not** branched on -- see this object's own comment. It is here so that
   * [KNOWN_STATES] can state the client's coverage of Lidarr's enum, which is the only claim about
   * these three that any test can actually check.
   */
  private val IN_PROGRESS = setOf("downloading", "importPending", "importing")

  /**
   * Every `trackedDownloadState` this client recognises: Lidarr's own nine.
   *
   * Derived from the sets above rather than written out a second time, so it cannot drift from what
   * this object is built on.
   */
  val KNOWN_STATES: Set<String> = DOWNLOAD_FAILED + IMPORT_FAILED + IN_PROGRESS + IMPORTED + IGNORED

  /** Shown when Lidarr sends no `errorMessage` of its own. Three kinds, never merged into one. */
  internal const val DOWNLOAD_FAILED_REASON = "the download failed"
  internal const val IMPORT_FAILED_REASON = "Lidarr could not import the files"
  internal const val IGNORED_REASON = "Lidarr was told to ignore this download"

  /**
   * What to show for one request, given what the queue says and what is on disk.
   *
   * **[progress] outranks [queueItem].** Files on disk is a stronger fact than a download client's
   * opinion, and it is the only one that survives the queue item vanishing -- which happens the
   * moment an import completes, so a poller that read only the queue would watch an item disappear
   * and have no idea whether it had succeeded.
   *
   * Returns [RequestStatus.Imported], never [RequestStatus.Arrived]: Lidarr having the files is not
   * Navidrome having scanned them, and collapsing the two would put a "play it" button on a row
   * that navigates nowhere. Task 9 owns that transition and this object has no album id to make one
   * with.
   */
  fun map(queueItem: LidarrQueueItem?, progress: LidarrAlbumProgress?): RequestStatus {
    if (progress?.isComplete == true) return RequestStatus.Imported
    if (queueItem == null) return RequestStatus.Requested
    // The state decides first and the message only supplies wording. Lidarr sets `errorMessage` on
    // records it goes on to import anyway, so a mapper that read the message first would turn a
    // finished download into a failure the user cannot dismiss.
    val detail = queueItem.errorMessage?.takeIf { it.isNotBlank() }
    return when (queueItem.trackedDownloadState) {
      IMPORTED -> RequestStatus.Imported
      IGNORED -> RequestStatus.Failed(detail ?: IGNORED_REASON)
      in DOWNLOAD_FAILED -> RequestStatus.Failed(detail ?: DOWNLOAD_FAILED_REASON)
      in IMPORT_FAILED -> RequestStatus.Failed(detail ?: IMPORT_FAILED_REASON)
      // Everything else: the three [IN_PROGRESS] states, and any state a Lidarr newer than this
      // client invents. The item is in the queue, so *something* is happening -- which is the only
      // claim its mere presence supports. Reporting a failure here would be a guess that reads to
      // the user as a verdict, and reporting `Imported` would be one that reads as an invitation.
      else -> RequestStatus.Downloading(percentComplete(queueItem))
    }
  }

  /**
   * `1 - sizeleft/size`, as a whole percentage, or `null` when the size is unknown.
   *
   * Lidarr sends no percentage on a queue record; its own queue sort computes this the same way and
   * with the same zero guard (`q.Size == 0 ? 0 : 100 - (q.Sizeleft / q.Size * 100)`). `null` rather
   * than that `0`, because "we do not know" and "none of it has arrived" are different things to
   * show someone.
   *
   * Clamped because a download client can briefly report `sizeleft` above `size` or below zero, and
   * a progress bar at -14% is a bug the user sees.
   */
  fun percentComplete(item: LidarrQueueItem): Int? {
    if (item.sizeBytes <= 0.0) return null
    val done = (item.sizeBytes - item.sizeLeftBytes) / item.sizeBytes
    return (done * 100).roundToInt().coerceIn(0, 100)
  }
}
