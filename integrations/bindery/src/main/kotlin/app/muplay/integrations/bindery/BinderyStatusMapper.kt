package app.muplay.integrations.bindery

import app.muplay.integrations.RequestStatus

/**
 * Bindery's four book statuses, mapped onto the five this app shows.
 *
 * The set is `wanted | downloading | downloaded | imported`. **Measured**, not read: all four were
 * served by a real `ghcr.io/vavallee/bindery:v1.32.1` and are committed as
 * `fixtures/bindery/books-*.json`; `books-all.json` carries every one of them in a single
 * response, and `BinderyStatusMapperTest` maps that fixture's whole `status` column as one exact
 * list.
 *
 * **The set has no failure member**, so this client never synthesises one: a book Bindery cannot
 * find stays `wanted`, and reporting that as a failure would be a claim the server never made.
 */
object BinderyStatusMapper {

  /**
   * [status] as Bindery sent it, mapped onto [RequestStatus].
   *
   * Case-insensitive because the value is a server-side constant this client does not control, and
   * a mapping that broke on a capitalisation change would fail in the *wrong* direction — reporting
   * an imported book as still merely requested.
   */
  fun map(status: String): RequestStatus = when (status.lowercase()) {
    "wanted" -> RequestStatus.Requested
    // Bindery reports no byte counts on a book — measured, a `wanted`/`downloading` element carries
    // no size, sizeleft or percentage field of any kind — so there is no percentage to compute.
    // `null`, not `0`: "we do not know how far along this is" and "it has not started" are
    // different things to show a user, and a progress bar pinned at 0 looks broken.
    "downloading" -> RequestStatus.Downloading(percentComplete = null)
    // **Not [RequestStatus.Imported].** The file has been fetched but has not been moved into the
    // library folder, so Navidrome cannot possibly have scanned it — and `Imported` is what Task 9
    // treats as "start looking for it in the mirror". Collapsing the two would start a search that
    // can never succeed and would look, to a user, like the arrival detection was broken.
    "downloaded" -> RequestStatus.Downloading(percentComplete = null)
    "imported" -> RequestStatus.Imported
    // A newer Bindery with a fifth status. `Requested` claims only "we have asked and it is not
    // here yet", which is true of every state short of success; `Failed` would be a verdict.
    else -> RequestStatus.Requested
  }
}
