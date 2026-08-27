package app.muplay.integrations.bindery

import app.muplay.integrations.RequestStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Bindery's four book statuses, mapped onto the five this app shows.
 *
 * All four as one exact list. The set is `wanted | downloading | downloaded | imported`, and it is
 * **measured rather than read**: `fixtures/bindery/books-all.json` is one real response from a real
 * instance carrying seven books across all four states, and the first test below maps that
 * fixture's own `status` column rather than a list of strings this file typed out.
 *
 * The set **has no failure member** — so this client never synthesises one. A book Bindery cannot
 * find simply stays `wanted`, and telling the user it failed would be a claim the server never
 * made.
 */
class BinderyStatusMapperTest {

  /**
   * The whole mapping, driven by a real payload.
   *
   * A list of four strings typed into this file would be a test of a `when` against itself: it
   * would still pass if Bindery's real vocabulary were something else entirely. Reading the
   * statuses out of the fixture makes the fixture the oracle, which is the closest thing available
   * for a service that publishes no schema.
   */
  @Test
  fun `every status a real bindery sent maps to exactly one request status`() {
    val statuses = Json.parseToJsonElement(readFixture("bindery/books-all.json"))
      .let { it as JsonObject }["items"]
      .let { it as JsonArray }
      .map { (it as JsonObject).getValue("status").jsonPrimitive.content }

    // Positive control first, and it is not decoration: `containsExactly` over an empty list is
    // vacuously true, and a fixture that failed to parse into anything would leave every assertion
    // below satisfied.
    assertThat(statuses).hasSize(7)
    assertThat(statuses.toSet())
      .containsExactlyInAnyOrder("wanted", "downloading", "downloaded", "imported")

    assertThat(statuses.map(BinderyStatusMapper::map)).containsExactly(
      RequestStatus.Imported,
      RequestStatus.Downloading(percentComplete = null),
      RequestStatus.Requested,
      RequestStatus.Requested,
      RequestStatus.Downloading(percentComplete = null),
      RequestStatus.Requested,
      RequestStatus.Requested,
    )
  }

  /** The same four, spelled out, so a fixture regenerated with different rows still pins each arm. */
  @Test
  fun `each of the four statuses maps to its own member`() {
    val statuses = listOf("wanted", "downloading", "downloaded", "imported")

    assertThat(statuses.map(BinderyStatusMapper::map)).containsExactly(
      RequestStatus.Requested,
      RequestStatus.Downloading(percentComplete = null),
      RequestStatus.Downloading(percentComplete = null),
      RequestStatus.Imported,
    )
  }

  /**
   * `downloaded` is deliberately **not** [RequestStatus.Imported]. The file has been fetched but
   * has not been moved into the library folder, so Navidrome cannot possibly have scanned it —
   * and `Imported` is what Task 9 treats as "start looking for it in the mirror". Collapsing the
   * two would start a search that can never succeed and would look, to a user, like the arrival
   * detection was broken.
   */
  @Test
  fun `downloaded is progress, not arrival`() {
    assertThat(BinderyStatusMapper.map("downloaded"))
      .isNotEqualTo(RequestStatus.Imported)
      .isEqualTo(RequestStatus.Downloading(percentComplete = null))
  }

  @Test
  fun `the percentage is null, because bindery does not report one`() {
    // Not zero. "We do not know how far along this is" and "it has not started" are different
    // things to show a user, and inventing 0 would show a progress bar that never moves.
    assertThat((BinderyStatusMapper.map("downloading") as RequestStatus.Downloading).percentComplete)
      .isNull()
  }

  @Test
  fun `a status this client does not know makes the least possible claim`() {
    // A newer Bindery with a fifth status. `Requested` says only "we have asked and it is not
    // here yet", which is true of every state short of success. `Failed` would be a verdict.
    assertThat(BinderyStatusMapper.map("somethingNew")).isEqualTo(RequestStatus.Requested)
    assertThat(BinderyStatusMapper.map("")).isEqualTo(RequestStatus.Requested)
  }

  @Test
  fun `the status match is case-insensitive`() {
    // Two arms at two casings, not one: a `lowercase()` dropped from the subject would leave a
    // single-value assertion green if that value happened to be the one already lowercase.
    assertThat(BinderyStatusMapper.map("IMPORTED")).isEqualTo(RequestStatus.Imported)
    assertThat(BinderyStatusMapper.map("Downloading"))
      .isEqualTo(RequestStatus.Downloading(percentComplete = null))
  }

  /**
   * Nothing this mapper produces is a [RequestStatus.Failed], for any input.
   *
   * The property the class doc claims, asserted rather than described. Bindery has no failure
   * status, so a client that produced one would be inventing a verdict — and the natural way for
   * that to happen is a later `else -> Failed(status)` arm that looks more informative than
   * `Requested`.
   */
  @Test
  fun `no bindery status this client can be handed becomes a failure`() {
    val inputs = listOf("wanted", "downloading", "downloaded", "imported", "", "failed", "error")

    assertThat(inputs).hasSize(7)
    assertThat(inputs.map(BinderyStatusMapper::map))
      .noneMatch { it is RequestStatus.Failed }
    // Including the two that *say* failure. A mapper that matched on the word rather than on
    // Bindery's actual vocabulary would fail here and pass everything above.
    assertThat(BinderyStatusMapper.map("failed")).isEqualTo(RequestStatus.Requested)
  }
}
