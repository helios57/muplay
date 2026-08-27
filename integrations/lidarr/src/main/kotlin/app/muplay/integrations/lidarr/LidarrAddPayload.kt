package app.muplay.integrations.lidarr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the body for `POST /api/v1/album`.
 *
 * **Decorates the lookup element rather than rebuilding it**, which is exactly what Lidarr's own UI
 * does (`frontend/src/Utilities/Album/getNewAlbum.js`) and what its own integration tests do. There
 * is no published statement of what this endpoint requires: the pinned `3.1.0.4875` serves **no
 * `openapi.json` at all** (measured — `/api/v1/openapi.json` is a 404 with a valid key), and the
 * generated spec later releases do publish is Swashbuckle output that encodes no FluentValidation
 * rule, declares zero required fields and documents a `200` where the code returns `201`. So the
 * only complete statement of what Lidarr wants is what Lidarr sent.
 *
 * The requirements this builder satisfies come from `AlbumController`'s `PostValidator`:
 * `foreignAlbumId` non-empty and known; a non-null `artist` carrying a non-empty `foreignArtistId`,
 * a `qualityProfileId` and a `metadataProfileId` that are both above zero and exist, and — when the
 * artist has no `path` — a `rootFolderPath` that exists. **Those apply whether or not the artist is
 * already in the library**: the validators are conditional on the object being present, not on the
 * artist being new.
 *
 * Every one of them was confirmed against a live `3.1.0.4875-ls40` by breaking it on purpose. A
 * profile id of `0` answers `"'Artist. Metadata Profile Id' must be greater than '0'."`, an id of
 * `999` answers `"Quality Profile does not exist"`, an unconfigured folder answers
 * `"Root folder '/nope' does not exist"`, and an empty body answers `NotEmptyValidator` on
 * `ForeignAlbumId` plus `NotNullValidator` on `Artist` — all four as a **400** carrying a JSON
 * array. The body this object builds from a real lookup element answered **201**.
 *
 * ### Why the write order is load-bearing rather than tidy
 *
 * The passthrough goes first and the decisions go second, and that is not a style choice. A real
 * lookup element's nested artist arrives carrying **`qualityProfileId: 0` and
 * `metadataProfileId: 0`** — measured, and true of every element in
 * `fixtures/lidarr/album-lookup.json`. Written in the other order those two zeros reach the wire
 * and the add is a 400 about profiles, shown to somebody who was choosing an album.
 */
object LidarrAddPayload {

  fun build(
    candidate: LidarrAlbumCandidate,
    targets: LidarrAddTargets,
    searchNow: Boolean,
  ): JsonObject {
    val artist = buildJsonObject {
      // Every field the lookup gave us for this artist, first. Anything this client does not model
      // rides along untouched -- including `artist.id`, which is present exactly when the artist is
      // already in this Lidarr (measured) and is how the server attaches the album to the existing
      // row instead of creating a second artist. Nothing here reads it.
      (candidate.raw["artist"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
      // Then identity, restated from the typed view so a hand-built candidate still produces a
      // usable body, and so `PostValidator.RuleFor(s => s.Artist.ForeignArtistId).NotEmpty()`
      // cannot fail on an element whose nested object was missing.
      put("foreignArtistId", candidate.foreignArtistId)
      put("artistName", candidate.artistName)
      // Then the three the controller validates. `rootFolderPath` is written unconditionally: its
      // rule applies only `When(s.Artist.Path.IsNullOrWhiteSpace())`, so it is harmless on an
      // artist that already has a path and required on one that does not.
      put("qualityProfileId", targets.qualityProfileId)
      put("metadataProfileId", targets.metadataProfileId)
      put("rootFolderPath", targets.rootFolderPath)
      put("monitored", true)
      put("monitorNewItems", targets.newItemMonitorOption)
      put(
        "addOptions",
        buildJsonObject {
          put("monitor", targets.monitorOption)
          put("monitored", true)
          // **Never true.** `AddAlbumService`: if the artist asks for a missing-albums search, the
          // server silently sets `album.addOptions.searchForNewAlbum = false` -- so the album is
          // added, monitored, and never searched for, with nothing reported. Upstream issue
          // Lidarr #5012.
          //
          // Read `LidarrAddPayloadTest`'s test of this field before quoting the sentence above as
          // measured fact: on the container this task drove, the album-add search did not happen
          // for *either* value of this flag, so that instance cannot demonstrate the interaction.
          // The pin rests on Lidarr's source and on the fact that `false` cannot make anything
          // worse.
          put("searchForMissingAlbums", false)
        },
      )
    }

    return buildJsonObject {
      candidate.raw.forEach { (key, value) -> put(key, value) }
      put("foreignAlbumId", candidate.foreignAlbumId)
      // An unmonitored album is never fetched, whatever the search flag says. A lookup element
      // arrives with `monitored: false`, so this is an overwrite rather than a default.
      put("monitored", true)
      put("artist", artist)
      put(
        "addOptions",
        buildJsonObject {
          // `AddAlbumOptions` is `{ AddType, SearchForNewAlbum }` and nothing else. `addType` is
          // overwritten server-side to Manual on every album POST, so it is not sent; a `monitor`
          // here would be bound case-insensitively, ignored, and look like a decision.
          put("searchForNewAlbum", searchNow)
        },
      )
    }
  }
}
