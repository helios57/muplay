package app.muplay.requests

import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.RequestStatus
import app.muplay.integrations.bindery.BinderyBookCandidate
import app.muplay.integrations.lidarr.LidarrAlbumCandidate
import app.muplay.integrations.requests.RequestCandidate
import kotlinx.serialization.json.JsonObject

/**
 * Shared fixtures for this module's three instrumented suites.
 *
 * **Pairwise different strings throughout, and that is the point rather than tidiness.** A row that
 * rendered `subtitle` where `title` belongs satisfies every "is it displayed" assertion if the two
 * share a value; `:feature:player`'s `PlayerFixtures` records the same rule for the same reason.
 *
 * These fixtures are a second copy of the shapes in this module's own JVM `Fakes.kt`, and the
 * duplication is deliberate: `src/test` and `src/androidTest` are separate compilations, a test
 * source set is not published, and the alternative -- a shared module -- would give a testing
 * module an edge into `integrations/`, which `ConventionTest`'s severability rule forbids.
 */

// ---- what a search found ---------------------------------------------------------------------

internal const val ALBUM_ID = "mb-album-1"
internal const val ALBUM_TITLE = "The Album Lidarr Found"
internal const val ALBUM_ARTIST = "An Artist"

internal const val BOOK_ID = "ol-book-1"
internal const val BOOK_TITLE = "The Book Bindery Found"
internal const val BOOK_AUTHOR = "An Author"

internal fun albumCandidate(
  externalId: String = ALBUM_ID,
  title: String = ALBUM_TITLE,
  artistName: String = ALBUM_ARTIST,
  alreadyAdded: Boolean = false,
): RequestCandidate = RequestCandidate.Album(
  album = LidarrAlbumCandidate(
    foreignAlbumId = externalId,
    title = title,
    disambiguation = null,
    albumType = null,
    releaseDate = null,
    remoteCoverUrl = null,
    artistName = artistName,
    foreignArtistId = "artist-$externalId",
    alreadyAdded = alreadyAdded,
    raw = JsonObject(emptyMap()),
  ),
  alreadyAdded = alreadyAdded,
)

internal fun bookCandidate(
  externalId: String = BOOK_ID,
  title: String = BOOK_TITLE,
  authorName: String? = BOOK_AUTHOR,
  alreadyAdded: Boolean = false,
): RequestCandidate = RequestCandidate.Book(
  book = BinderyBookCandidate(
    foreignBookId = externalId,
    title = title,
    authorName = authorName,
    foreignAuthorId = null,
    asin = null,
    coverUrl = null,
    raw = JsonObject(emptyMap()),
  ),
  alreadyAdded = alreadyAdded,
)

// ---- what has already been asked for ----------------------------------------------------------

internal const val LIDARR_REQUEST_TITLE = "An Album Already Asked For"
internal const val SECOND_LIDARR_REQUEST_TITLE = "A Second Album Already Asked For"
internal const val BINDERY_REQUEST_TITLE = "A Book Already Asked For"

/** The album id an `Arrived` row navigates to. Nothing else in these suites uses this string. */
internal const val ARRIVED_ALBUM_ID = "navidrome-album-77"

internal fun mediaRequest(
  service: IntegrationService,
  externalId: String,
  title: String,
  status: RequestStatus,
  subtitle: String = "$title subtitle",
): MediaRequest = MediaRequest(
  id = MediaRequest.idFor(service, externalId),
  service = service,
  externalId = externalId,
  title = title,
  subtitle = subtitle,
  remoteId = null,
  status = status,
  requestedAtEpochMs = 1_700_000_000_000L,
  updatedAtEpochMs = 1_700_000_100_000L,
)

// ---- the state itself --------------------------------------------------------------------------

/**
 * A `Ready` state with everything defaulted to "one configured service and nothing going on", so
 * each test overrides only the one thing it is about.
 *
 * The service set goes through `requestsUiState`'s own ordering rule nowhere here: these suites
 * hand the screen a `Ready` directly, because what is under test is the screen, and
 * `RequestsUiStateTest` already gates the mapping.
 */
internal fun ready(
  services: Set<IntegrationService> = setOf(IntegrationService.LIDARR),
  requests: List<MediaRequest> = emptyList(),
  query: String = "",
  searching: Boolean = false,
  results: List<RequestCandidate> = emptyList(),
  error: String? = null,
): RequestsUiState.Ready = RequestsUiState.Ready(
  services = services,
  requests = requests,
  query = query,
  searching = searching,
  results = results,
  error = error,
)
