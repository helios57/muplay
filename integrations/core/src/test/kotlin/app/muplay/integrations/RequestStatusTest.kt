package app.muplay.integrations

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The persisted form of [RequestStatus], round-tripped.
 *
 * Every member is exercised, and the members that carry data are exercised at **two** values.
 * A `fromStored` that returned a constant `Requested` would satisfy any test that only checked
 * one member, and a status column that silently collapsed to one value is the failure mode where
 * a user's finished download shows as still queued forever.
 */
class RequestStatusTest {

  private fun roundTrip(status: RequestStatus): RequestStatus =
    RequestStatus.fromStored(status.storedName, status.storedDetail)

  @Test
  fun `every member round-trips to an equal value`() {
    val all = listOf(
      RequestStatus.Requested,
      RequestStatus.Downloading(percentComplete = null),
      RequestStatus.Downloading(percentComplete = 0),
      RequestStatus.Downloading(percentComplete = 73),
      RequestStatus.Imported,
      RequestStatus.Arrived(albumId = "al-1"),
      RequestStatus.Arrived(albumId = "al-2"),
      RequestStatus.Failed(reason = "no results"),
      RequestStatus.Failed(reason = "download client rejected the release"),
    )

    // The exact mapped list, not `allMatch { roundTrip(it) == it }`. `allMatch` over an empty
    // list is vacuously true, and this project has been bitten by that shape specifically.
    assertThat(all.map(::roundTrip)).containsExactlyElementsOf(all)
  }

  @Test
  fun `the stored names are the exact strings the database holds`() {
    // Pinned as literals, because these strings are on disk. Renaming a member must not silently
    // orphan every row a user already has.
    assertThat(
      listOf(
        RequestStatus.Requested,
        RequestStatus.Downloading(null),
        RequestStatus.Imported,
        RequestStatus.Arrived("al-1"),
        RequestStatus.Failed("x"),
      ).map { it.storedName },
    ).containsExactly("REQUESTED", "DOWNLOADING", "IMPORTED", "ARRIVED", "FAILED")
  }

  @Test
  fun `the detail column carries the member's data and nothing else`() {
    // Two observations per data-carrying member: a `storedDetail` hardcoded to any single string
    // fails at least one of these.
    assertThat(RequestStatus.Requested.storedDetail).isNull()
    assertThat(RequestStatus.Imported.storedDetail).isNull()
    assertThat(RequestStatus.Downloading(null).storedDetail).isNull()
    assertThat(RequestStatus.Downloading(7).storedDetail).isEqualTo("7")
    assertThat(RequestStatus.Downloading(99).storedDetail).isEqualTo("99")
    assertThat(RequestStatus.Arrived("al-1").storedDetail).isEqualTo("al-1")
    assertThat(RequestStatus.Arrived("al-2").storedDetail).isEqualTo("al-2")
    assertThat(RequestStatus.Failed("a").storedDetail).isEqualTo("a")
    assertThat(RequestStatus.Failed("b").storedDetail).isEqualTo("b")
  }

  @Test
  fun `a downloading percentage that is not a number reads as unknown rather than crashing`() {
    // Defensive, and reachable: the column is a TEXT column and a future writer could put
    // anything in it. `toIntOrNull` is the branch; this is the assertion that makes it real.
    assertThat(RequestStatus.fromStored("DOWNLOADING", "not-a-number"))
      .isEqualTo(RequestStatus.Downloading(percentComplete = null))
  }

  @Test
  fun `an unrecognised stored status reads as a failure that names itself`() {
    // This row can only exist through database corruption or a downgrade. Reading it as
    // `Requested` would tell the user their request is still in progress forever; reading it as a
    // named failure tells them, and tells whoever reads the bug report, exactly what happened.
    val status = RequestStatus.fromStored("SOMETHING_ELSE", null)

    assertThat(status).isInstanceOf(RequestStatus.Failed::class.java)
    assertThat((status as RequestStatus.Failed).reason).contains("SOMETHING_ELSE")
  }

  @Test
  fun `an ARRIVED row with no album id is a failure, not an Arrived with an empty id`() {
    // `Arrived("")` would render a "Play it" button that navigates nowhere. A missing detail on a
    // status that requires one is corruption, and it says so.
    assertThat(RequestStatus.fromStored("ARRIVED", null))
      .isInstanceOf(RequestStatus.Failed::class.java)
  }

  @Test
  fun `an ARRIVED row with an empty album id is a failure too`() {
    // The second arm of `isNullOrEmpty`. Room stores a TEXT column, and "" is a value a writer can
    // produce where `null` is not -- so the empty case is separately reachable and separately
    // asserted rather than assumed to follow from the null one.
    assertThat(RequestStatus.fromStored("ARRIVED", ""))
      .isInstanceOf(RequestStatus.Failed::class.java)
  }

  @Test
  fun `a FAILED row with no reason still says something`() {
    // The `?:` arm. A `Failed(null)` is unrepresentable, so the stored form has to supply a
    // fallback, and a user staring at an empty error message learns nothing.
    assertThat(RequestStatus.fromStored("FAILED", null))
      .isEqualTo(RequestStatus.Failed("the request failed"))
  }

  @Test
  fun `the request id is derived from the service and the external id`() {
    // Two observations on each half of the key. A hardcoded id would make every request in the
    // database collide onto one row -- and a hardcoded *service* half would make a Lidarr and a
    // Bindery request for the same external id collide, which is the subtler one.
    assertThat(MediaRequest.idFor(IntegrationService.LIDARR, "mbid-1")).isEqualTo("LIDARR:mbid-1")
    assertThat(MediaRequest.idFor(IntegrationService.LIDARR, "mbid-2")).isEqualTo("LIDARR:mbid-2")
    assertThat(MediaRequest.idFor(IntegrationService.BINDERY, "mbid-1")).isEqualTo("BINDERY:mbid-1")
  }
}
