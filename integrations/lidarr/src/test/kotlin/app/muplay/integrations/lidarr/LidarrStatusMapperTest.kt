package app.muplay.integrations.lidarr

import app.muplay.integrations.RequestStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Lidarr's nine tracked-download states, mapped onto the five statuses this app shows.
 *
 * **The exact-list assertion below is necessary and is not sufficient, and this comment is the
 * record of why.** A test that enumerates the same nine strings the mapper enumerates and checks
 * the nine answers it was written from proves the map equals itself: it is a change detector. It
 * catches a mutated arm -- which is worth having, and `ci/mutation-probes.sh` depends on it -- but
 * it cannot tell you the mapping *discriminates*, because nine copies of one status would satisfy
 * an `allMatch { it is RequestStatus }` and, less obviously, a mapping that conflated the download
 * failures with the import failures would still be "nine values in a list".
 *
 * So the discriminating evidence is asserted separately and on purpose:
 *
 *  - **what an unknown tenth value does** (`an unrecognised state reports progress rather than a
 *    verdict`), and what that costs -- see the measured limitation on that test;
 *  - **what each mapped value produces that the others do not** (`the five outcome classes are
 *    mutually distinguishable`), which fails if any two kinds of failure are merged;
 *  - **what no state produces at all** (`no download state ever maps to Requested or Arrived`),
 *    which is the vacuous-mapper defect and the `Imported`/`Arrived` collapse, stated as
 *    properties over the whole enum rather than as nine more literals.
 */
class LidarrStatusMapperTest {

  private fun item(
    state: String,
    size: Double = 100.0,
    sizeLeft: Double = 25.0,
    status: String = "ok",
    error: String? = null,
  ) = LidarrQueueItem(
    albumId = 1,
    artistId = 2,
    sizeBytes = size,
    sizeLeftBytes = sizeLeft,
    trackedDownloadState = state,
    trackedDownloadStatus = status,
    errorMessage = error,
  )

  /**
   * The full `trackedDownloadState` enum, in the order Lidarr's OpenAPI document declares it.
   *
   * Written out here as a literal **on purpose**, rather than read from
   * [LidarrStatusMapper.KNOWN_STATES]: this list is the external contract, and a test that sourced
   * it from the object under test would go green on a mapper that had quietly forgotten a state.
   */
  private val allStates = listOf(
    "downloading", "downloadFailed", "downloadFailedPending", "importBlocked",
    "importPending", "importing", "importFailed", "imported", "ignored",
  )

  @Test
  fun `every tracked download state maps to a status, and no two kinds of failure are conflated`() {
    val mapped = allStates.map { LidarrStatusMapper.map(item(it), progress = null) }

    assertThat(mapped).containsExactly(
      RequestStatus.Downloading(percentComplete = 75),
      RequestStatus.Failed("the download failed"),
      RequestStatus.Failed("the download failed"),
      RequestStatus.Failed("Lidarr could not import the files"),
      RequestStatus.Downloading(percentComplete = 75),
      RequestStatus.Downloading(percentComplete = 75),
      RequestStatus.Failed("Lidarr could not import the files"),
      RequestStatus.Imported,
      RequestStatus.Failed("Lidarr was told to ignore this download"),
    )
  }

  /**
   * The client's own enumeration is exactly Lidarr's, with nothing missing and nothing invented.
   *
   * This is what makes [LidarrStatusMapper.IN_PROGRESS] falsifiable at all. Read the class comment
   * on the mapper: an in-progress state and an unrecognised one produce the **same**
   * `Downloading(pct)`, by design and correctly, so no assertion on `map`'s output can distinguish
   * "recognised as downloading" from "fell through". Measured rather than reasoned: with a
   * redundant `in IN_PROGRESS ->` arm present in the `when`, deleting that arm outright left every
   * test in this module green. The arm is gone for that reason and the set survives as data --
   * which this test, and only this test, holds to the nine.
   */
  @Test
  fun `the client recognises exactly lidarrs nine states`() {
    assertThat(LidarrStatusMapper.KNOWN_STATES).containsExactlyInAnyOrderElementsOf(allStates)
  }

  @Test
  fun `a failure message from lidarr replaces the generic one`() {
    // Two observations, so the message is not a constant -- and the whole value of surfacing it is
    // that "no audio files found" tells the user something the generic text cannot.
    assertThat(LidarrStatusMapper.map(item("importFailed", error = "no audio files found"), null))
      .isEqualTo(RequestStatus.Failed("no audio files found"))
    assertThat(LidarrStatusMapper.map(item("downloadFailed", error = "tracker rejected"), null))
      .isEqualTo(RequestStatus.Failed("tracker rejected"))
    // ...and it reaches the third failure kind too, which has its own generic wording to displace.
    assertThat(LidarrStatusMapper.map(item("ignored", error = "removed by the user"), null))
      .isEqualTo(RequestStatus.Failed("removed by the user"))
    // A blank message is not a message.
    assertThat(LidarrStatusMapper.map(item("downloadFailed", error = "  "), null))
      .isEqualTo(RequestStatus.Failed("the download failed"))
    // An error message on a *successful* state is not a failure. Lidarr sets `errorMessage` on
    // records it later imports anyway, so a mapper that read the message before the state would
    // turn a finished download into a failure the user cannot dismiss.
    assertThat(LidarrStatusMapper.map(item("imported", error = "a warning about the release"), null))
      .isEqualTo(RequestStatus.Imported)
    assertThat(LidarrStatusMapper.map(item("downloading", error = "a warning about the release"), null))
      .isEqualTo(RequestStatus.Downloading(percentComplete = 75))
  }

  /**
   * An unrecognised state means a Lidarr newer than this client. The item is in the queue, so
   * *something* is happening; `Downloading` is the only claim its mere presence supports, and
   * reporting `Failed` would be a guess that reads as a verdict. This is the fail-closed clause
   * the plan's severability contract names for this task.
   *
   * **What this test cannot see, stated rather than left implied.** Its expected value is
   * identical to what `downloading`, `importPending` and `importing` produce, so it does not
   * discriminate "unknown" from "known and in progress" -- nothing can, because the two answers
   * are deliberately the same. It discriminates unknown from every *other* outcome, which is the
   * property that matters: an unknown state must not become `Failed`, `Imported` or `Requested`.
   */
  @Test
  fun `an unrecognised state reports progress rather than a verdict`() {
    val unknown = LidarrStatusMapper.map(item("somethingNewInLidarr4"), null)

    assertThat(unknown).isEqualTo(RequestStatus.Downloading(percentComplete = 75))
    // The three it must not be, named individually so a regression says which line it broke.
    assertThat(unknown).isNotInstanceOf(RequestStatus.Failed::class.java)
    assertThat(unknown).isNotEqualTo(RequestStatus.Imported)
    assertThat(unknown).isNotEqualTo(RequestStatus.Requested)
    // An empty state -- `trackedDownloadState` absent from the record entirely -- is unrecognised
    // in exactly the same way, and is the shape a truncated or proxied response produces.
    assertThat(LidarrStatusMapper.map(item(""), null))
      .isEqualTo(RequestStatus.Downloading(percentComplete = 75))
    // Case matters: these are .NET enum members through a camelCase policy, and a mapper matching
    // case-insensitively would be claiming a rendering rule it has not got.
    assertThat(LidarrStatusMapper.map(item("IMPORTED"), null))
      .isEqualTo(RequestStatus.Downloading(percentComplete = 75))
  }

  /**
   * **What each mapped value produces that the others do not.**
   *
   * The nine states collapse onto exactly five observable outcomes, and this asserts that number
   * and that grouping directly. A mapper that merged the download failures with the import
   * failures -- the single most plausible way to get this wrong, since both are `Failed` -- passes
   * a nine-element `containsExactly` written from *its* behaviour and fails here.
   */
  @Test
  fun `the five outcome classes are mutually distinguishable`() {
    val byOutcome = allStates.groupBy { LidarrStatusMapper.map(item(it), null) }

    assertThat(byOutcome.keys).hasSize(5)
    assertThat(byOutcome[RequestStatus.Downloading(75)])
      .containsExactlyInAnyOrder("downloading", "importPending", "importing")
    assertThat(byOutcome[RequestStatus.Imported]).containsExactly("imported")
    assertThat(byOutcome[RequestStatus.Failed("the download failed")])
      .containsExactlyInAnyOrder("downloadFailed", "downloadFailedPending")
    assertThat(byOutcome[RequestStatus.Failed("Lidarr could not import the files")])
      .containsExactlyInAnyOrder("importBlocked", "importFailed")
    assertThat(byOutcome[RequestStatus.Failed("Lidarr was told to ignore this download")])
      .containsExactly("ignored")
  }

  /**
   * Two statuses no download state may ever produce, as a property over the whole enum.
   *
   * `Requested` is the vacuous-mapper defect: a mapper that returned it for everything would be
   * "nine values" and would tell every user their download had not started. `Arrived` is the
   * collapse this plan names explicitly -- Lidarr having the files on disk is **not** Navidrome
   * having scanned them, and an `Arrived` here would put a "play it" button on a row that
   * navigates nowhere. Task 9 owns that transition; this mapper cannot reach it, and cannot
   * because it has no Navidrome album id to put in one.
   */
  @Test
  fun `no download state ever maps to Requested or Arrived`() {
    val mapped = (allStates + "somethingNewInLidarr4").map { LidarrStatusMapper.map(item(it), null) }

    // Positive control first: ten states really were mapped. Without it every negative below is
    // vacuously true over an empty list.
    assertThat(mapped).hasSize(10)
    assertThat(mapped).allSatisfy { assertThat(it).isNotEqualTo(RequestStatus.Requested) }
    assertThat(mapped).allSatisfy { assertThat(it).isNotInstanceOf(RequestStatus.Arrived::class.java) }
  }

  @Test
  fun `the percentage is computed from size and sizeleft, at more than one value`() {
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 100.0, sizeLeft = 25.0)))
      .isEqualTo(75)
    // The same ratio at a different scale: a client that returned `size - sizeLeft` rather than a
    // ratio gives 75 for the first and 150 for this one.
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 200.0, sizeLeft = 50.0)))
      .isEqualTo(75)
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 100.0, sizeLeft = 90.0)))
      .isEqualTo(10)
    // Not inverted: `sizeleft` is what REMAINS, so a nearly-finished download is a high percentage.
    // Swapping the operands gives 90 here and 10 above, so this pair pins the direction.
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 100.0, sizeLeft = 10.0)))
      .isEqualTo(90)
  }

  /** Rounded, not truncated: 2/3 of the way through is 67%, and `toInt()` would say 66. */
  @Test
  fun `the percentage is rounded rather than truncated`() {
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 3.0, sizeLeft = 1.0)))
      .isEqualTo(67)
  }

  @Test
  fun `a zero-size item has an unknown percentage rather than a divide by zero`() {
    // Lidarr's own queue sort guards this identically: `q.Size == 0 ? 0 : ...`. `null` rather than
    // that `0`, because "we do not know" and "none of it has arrived" are different things to show.
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 0.0, sizeLeft = 0.0)))
      .isNull()
    // A negative size is not a size either, and `<= 0.0` rather than `== 0.0` is what covers it.
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = -1.0, sizeLeft = 0.0)))
      .isNull()
    // ...and it reaches `map`, which must not report a percentage it has not got.
    assertThat(LidarrStatusMapper.map(item("downloading", size = 0.0, sizeLeft = 0.0), null))
      .isEqualTo(RequestStatus.Downloading(percentComplete = null))
  }

  @Test
  fun `a percentage outside zero to one hundred is clamped rather than shown`() {
    // `sizeleft` can exceed `size` briefly while a download client re-reports. A progress bar at
    // -14% is a bug the user sees.
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 100.0, sizeLeft = 114.0)))
      .isEqualTo(0)
    assertThat(LidarrStatusMapper.percentComplete(item("downloading", size = 100.0, sizeLeft = -5.0)))
      .isEqualTo(100)
  }

  /**
   * Files on disk beat the queue. This is what makes a poll correct after the queue item has
   * vanished, which it does the moment an import finishes.
   */
  @Test
  fun `complete statistics report Imported even while a queue item still exists`() {
    val progress = LidarrAlbumProgress(trackFileCount = 10, totalTrackCount = 10)

    assertThat(LidarrStatusMapper.map(item("downloading"), progress)).isEqualTo(RequestStatus.Imported)
    assertThat(LidarrStatusMapper.map(queueItem = null, progress = progress))
      .isEqualTo(RequestStatus.Imported)
    // Even a failing queue item loses to files that are actually there. Lidarr leaves a failed
    // record in the queue after a *second* release succeeded, so this is the ordering that stops a
    // finished album being reported as a failure.
    assertThat(LidarrStatusMapper.map(item("importFailed", error = "no audio files"), progress))
      .isEqualTo(RequestStatus.Imported)
    // More files than tracks is still complete -- `>=`, not `==`. A multi-disc release can file
    // more tracks than Lidarr's own count, and `==` would leave such an album downloading forever.
    assertThat(LidarrStatusMapper.map(null, LidarrAlbumProgress(11, 10))).isEqualTo(RequestStatus.Imported)
  }

  @Test
  fun `incomplete statistics do not report Imported`() {
    // Two observations of the same comparison, either side of the boundary.
    val partial = LidarrAlbumProgress(trackFileCount = 9, totalTrackCount = 10)

    assertThat(LidarrStatusMapper.map(null, partial)).isEqualTo(RequestStatus.Requested)
    assertThat(LidarrStatusMapper.map(item("downloading"), partial))
      .isEqualTo(RequestStatus.Downloading(percentComplete = 75))
  }

  /**
   * `totalTrackCount == 0` means Lidarr has not fetched the track list. `0 >= 0` would read as
   * "fully downloaded" and put a play button on an empty album.
   *
   * **This is a measured state, not a hypothetical one.** On the live `3.1.0.4875-ls40` this task
   * ran against, an album seconds after a successful `POST /api/v1/album` had `"releases": []`,
   * zero rows from `GET /api/v1/track?albumId=`, and no `statistics` object at all. The moment a
   * request is made is exactly the moment this arm is on.
   */
  @Test
  fun `an album with no tracks yet is not complete, however many files it has`() {
    assertThat(LidarrStatusMapper.map(null, LidarrAlbumProgress(0, 0)))
      .isEqualTo(RequestStatus.Requested)
    // ...and not even when the file count is somehow non-zero, which is the arm `totalTrackCount
    // > 0` actually guards. Without it this is `Imported`.
    assertThat(LidarrStatusMapper.map(null, LidarrAlbumProgress(3, 0)))
      .isEqualTo(RequestStatus.Requested)
    assertThat(LidarrAlbumProgress(3, 0).isComplete).isFalse()
    assertThat(LidarrAlbumProgress(3, 3).isComplete).isTrue()
  }

  @Test
  fun `nothing in the queue and nothing on disk is still Requested`() {
    // The state a monitored album sits in between being added and a release being found. It is not
    // a failure and it is not progress -- and it is the state `albumProgress` reports for a
    // freshly added album, which returns `null` because Lidarr sends no statistics object at all.
    assertThat(LidarrStatusMapper.map(queueItem = null, progress = null))
      .isEqualTo(RequestStatus.Requested)
  }
}
