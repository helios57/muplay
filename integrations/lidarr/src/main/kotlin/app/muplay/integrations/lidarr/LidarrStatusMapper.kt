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
 * Lidarr's own OpenAPI document; all nine are named in [KNOWN_STATES] and every one of them is
 * mapped below.
 */
object LidarrStatusMapper {

  /** `imported` -- Lidarr has the files. Not [RequestStatus.Arrived]; see [map]. */
  private const val IMPORTED = "imported"

  /** `ignored` -- a human or a rule told Lidarr to stop. Terminal, and not the same as a failure. */
  private const val IGNORED = "ignored"

  /** The download client gave up. Both members are terminal from this app's point of view. */
  private val DOWNLOAD_FAILED = setOf("downloadFailed", "downloadFailedPending")

  /** The bytes arrived and Lidarr would not file them. A different problem, and a different fix. */
  private val IMPORT_FAILED = setOf("importFailed", "importBlocked")

  /** Something is happening and nothing has gone wrong yet. */
  private val IN_PROGRESS = setOf("downloading", "importPending", "importing")

  /**
   * Every `trackedDownloadState` this client recognises: Lidarr's own nine.
   *
   * Derived from the sets above rather than written out a second time, so it cannot drift from what
   * [map] actually branches on.
   */
  val KNOWN_STATES: Set<String> = DOWNLOAD_FAILED + IMPORT_FAILED + IN_PROGRESS + IMPORTED + IGNORED

  internal const val DOWNLOAD_FAILED_REASON = "the download failed"
  internal const val IMPORT_FAILED_REASON = "Lidarr could not import the files"
  internal const val IGNORED_REASON = "Lidarr was told to ignore this download"

  fun map(queueItem: LidarrQueueItem?, progress: LidarrAlbumProgress?): RequestStatus {
    if (progress?.isComplete == true) return RequestStatus.Imported
    if (queueItem == null) return RequestStatus.Requested
    val detail = queueItem.errorMessage?.takeIf { it.isNotBlank() }
    return when (queueItem.trackedDownloadState) {
      IMPORTED -> RequestStatus.Imported
      IGNORED -> RequestStatus.Failed(detail ?: IGNORED_REASON)
      in DOWNLOAD_FAILED -> RequestStatus.Failed(detail ?: DOWNLOAD_FAILED_REASON)
      in IMPORT_FAILED -> RequestStatus.Failed(detail ?: IMPORT_FAILED_REASON)
      in IN_PROGRESS -> RequestStatus.Downloading(percentComplete(queueItem))
      else -> RequestStatus.Downloading(percentComplete(queueItem))
    }
  }

  fun percentComplete(item: LidarrQueueItem): Int? {
    if (item.sizeBytes <= 0.0) return null
    val done = (item.sizeBytes - item.sizeLeftBytes) / item.sizeBytes
    return (done * 100).roundToInt().coerceIn(0, 100)
  }
}
