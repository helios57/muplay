package app.muplay.integrations.requests

import app.muplay.database.SyncState
import app.muplay.integrations.BaseUrlResult
import app.muplay.integrations.CleartextPolicy
import app.muplay.integrations.IntegrationBaseUrl
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.bindery.BinderyBook
import app.muplay.integrations.bindery.BinderyBookCandidate
import app.muplay.integrations.bindery.BinderyBookPage
import app.muplay.integrations.bindery.BinderyMediaType
import app.muplay.integrations.bindery.BinderyServer
import app.muplay.integrations.bindery.BinderySource
import app.muplay.integrations.bindery.BinderySourceFactory
import app.muplay.integrations.db.MediaRequestDao
import app.muplay.integrations.db.MediaRequestEntity
import app.muplay.integrations.lidarr.LidarrAddOutcome
import app.muplay.integrations.lidarr.LidarrAddTargets
import app.muplay.integrations.lidarr.LidarrAlbumCandidate
import app.muplay.integrations.lidarr.LidarrAlbumProgress
import app.muplay.integrations.lidarr.LidarrProfile
import app.muplay.integrations.lidarr.LidarrQueueItem
import app.muplay.integrations.lidarr.LidarrRootFolder
import app.muplay.integrations.lidarr.LidarrServer
import app.muplay.integrations.lidarr.LidarrSource
import app.muplay.integrations.lidarr.LidarrSourceFactory
import app.muplay.model.Album
import app.muplay.model.LibraryRole
import app.muplay.model.SearchResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

/**
 * Hand-written fakes. **No mock framework may enter this build** (`ConventionTest`'s
 * `no mock framework is declared in any build file or convention plugin`), and these exist for the
 * one reason this project's test hierarchy allows a fake at all: making a specific call fail at a
 * specific point, which no real `SyncEngine`, Lidarr or Bindery can be asked to do.
 *
 * Each records what it was asked, so a test can assert **argument passthrough** rather than
 * "it was called" — which is the defect class round six of this project's reviews found.
 *
 * Every method a test does not drive throws rather than returning a plausible empty value: an
 * accidental call is then a loud failure naming the method, not a silently-correct-looking run.
 */
class FakeMirrorSync(private var next: SyncState = SyncState.UpToDate) : MirrorSync {
  var calls: Int = 0
    private set

  fun willReturn(state: SyncState) {
    next = state
  }

  override suspend fun syncIfStale(): SyncState {
    calls++
    return next
  }
}

class FakeAlbumSearch(private val byLibrary: Map<Int, List<Album>> = emptyMap()) : AlbumSearch {
  /** Every `(libraryId, query, limit)` this was called with, in order. */
  val queries: MutableList<Triple<Int, String, Int>> = mutableListOf()

  override suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults {
    queries += Triple(libraryId, query, limit)
    return SearchResults(
      artists = emptyList(),
      albums = byLibrary[libraryId].orEmpty(),
      songs = emptyList(),
    )
  }
}

class FakeLibraryRoles(private val byRole: Map<LibraryRole, List<Int>>) : LibraryRoles {
  val asked: MutableList<LibraryRole> = mutableListOf()

  override suspend fun idsWithRole(role: LibraryRole): List<Int> {
    asked += role
    return byRole[role].orEmpty()
  }
}

/**
 * The credential store, as a flow a test can move.
 *
 * [save] and [clear] are named after `IntegrationCredentialStore`'s own methods so that a test
 * reads the same whether it is driving this or the device-only real thing.
 */
class FakeConfiguredServices : ConfiguredServices {
  private val state = MutableStateFlow(emptyMap<IntegrationService, IntegrationCredentials>())

  override fun configured(): Flow<Map<IntegrationService, IntegrationCredentials>> = state

  fun save(credentials: IntegrationCredentials) {
    state.value = state.value + (credentials.service to credentials)
  }

  fun clear(service: IntegrationService) {
    state.value = state.value - service
  }

  /**
   * Files [credentials] under a service key that is **not** its own.
   *
   * The shipped `IntegrationCredentialStore.read` cannot produce this — it builds each credential
   * from the key it is reading — but [ConfiguredServices] is an interface and its type permits it,
   * and `RequestsRepository` has to be right for any implementation of the port rather than for
   * the one adapter that happens to be wired today. Named `saveUnder` rather than `save` so that no
   * ordinary test reaches it by accident.
   */
  fun saveUnder(service: IntegrationService, credentials: IntegrationCredentials) {
    state.value = state.value + (service to credentials)
  }
}

/**
 * An in-memory `MediaRequestDao`, so that `RequestsRepositoryTest` drives the **real**
 * `MediaRequestRepository` rather than a fake of it.
 *
 * That matters: `record`'s "keep the original `requestedAtEpochMs` and the status the row already
 * reached" rule and `requests()`'s "drop a row naming an unknown service" rule are Task 3's, and a
 * faked repository would let this task's composition be green against a repository that does not
 * behave like the shipped one. The DAO is an `interface`, so this is the lowest seam that is
 * available without Room and a device.
 *
 * `observeAll`'s ordering repeats the DAO's own `ORDER BY requestedAtEpochMs DESC, id ASC` because
 * a fake that returned insertion order would hide exactly the defect that clause exists to prevent.
 */
class FakeMediaRequestDao : MediaRequestDao {
  private val rows = MutableStateFlow(emptyMap<String, MediaRequestEntity>())

  private fun sorted(all: Collection<MediaRequestEntity>) =
    all.sortedWith(compareByDescending<MediaRequestEntity> { it.requestedAtEpochMs }.thenBy { it.id })

  override fun observeAll(): Flow<List<MediaRequestEntity>> = rows.map { sorted(it.values) }

  override fun observeByService(service: String): Flow<List<MediaRequestEntity>> =
    rows.map { all -> sorted(all.values.filter { it.service == service }) }

  override suspend fun find(id: String): MediaRequestEntity? = rows.value[id]

  override suspend fun upsert(entity: MediaRequestEntity) {
    rows.value = rows.value + (entity.id to entity)
  }

  override suspend fun updateStatus(
    id: String,
    status: String,
    statusDetail: String?,
    updatedAtEpochMs: Long,
  ) {
    val existing = rows.value[id] ?: return
    rows.value = rows.value + (
      id to existing.copy(
        status = status,
        statusDetail = statusDetail,
        updatedAtEpochMs = updatedAtEpochMs,
      )
      )
  }

  override suspend fun delete(id: String) {
    rows.value = rows.value - id
  }
}

/**
 * A Lidarr that answers the calls a status refresh, a lookup and an add make.
 *
 * `ping` and `status` still throw: a connection check builds its own source, and an accidental call
 * to either from here would be a loud failure naming the method rather than a green run.
 */
class FakeLidarrSource : LidarrSource {
  var queue: List<LidarrQueueItem> = emptyList()

  /** Keyed by album id, so a test can give two requests two different answers. */
  var progress: Map<Int, LidarrAlbumProgress> = emptyMap()

  /** When set, `queue()` throws it — one dead service, with the other still expected to refresh. */
  var failWith: Exception? = null

  var queueCalls: Int = 0
    private set

  /** Every album id `albumProgress` was asked about, in order. */
  val progressAsked: MutableList<Int> = mutableListOf()

  override suspend fun queue(): List<LidarrQueueItem> {
    queueCalls++
    failWith?.let { throw it }
    return queue
  }

  override suspend fun albumProgress(albumId: Int): LidarrAlbumProgress? {
    progressAsked += albumId
    return progress[albumId]
  }

  // ---- the lookup and add calls a search and a submit make -----------------------------------

  var lookupResults: List<LidarrAlbumCandidate> = emptyList()

  /** When set, `lookupAlbums` throws it. Separate from [failWith] so one call can fail alone. */
  var lookupFailWith: Exception? = null

  /** Every term `lookupAlbums` was asked, verbatim -- argument passthrough, not "it was called". */
  val lookupTerms: MutableList<String> = mutableListOf()

  /** One accessible folder whose defaults resolve, so a test opts *out* of a working add. */
  var rootFolders: List<LidarrRootFolder> = listOf(rootFolder())
  var qualityProfiles: List<LidarrProfile> = listOf(LidarrProfile(id = 3, name = "Any"))
  var metadataProfiles: List<LidarrProfile> = listOf(LidarrProfile(id = 4, name = "Standard"))

  var addOutcome: LidarrAddOutcome = LidarrAddOutcome.Added(albumId = 101)
  var addFailWith: Exception? = null

  /** Every `(candidate, targets, searchNow)` an add was made with, in order. */
  val submitted: MutableList<Triple<LidarrAlbumCandidate, LidarrAddTargets, Boolean>> = mutableListOf()

  /** What `findAddedAlbumId` answers -- `null` is a real answer and not "not configured". */
  var addedAlbumId: Int? = null
  val addedAlbumIdsAsked: MutableList<String> = mutableListOf()

  override suspend fun lookupAlbums(term: String): List<LidarrAlbumCandidate> {
    lookupTerms += term
    lookupFailWith?.let { throw it }
    return lookupResults
  }

  override suspend fun rootFolders(): List<LidarrRootFolder> = rootFolders

  override suspend fun qualityProfiles(): List<LidarrProfile> = qualityProfiles

  override suspend fun metadataProfiles(): List<LidarrProfile> = metadataProfiles

  override suspend fun submitAlbum(
    candidate: LidarrAlbumCandidate,
    targets: LidarrAddTargets,
    searchNow: Boolean,
  ): LidarrAddOutcome {
    submitted += Triple(candidate, targets, searchNow)
    addFailWith?.let { throw it }
    return addOutcome
  }

  override suspend fun findAddedAlbumId(foreignAlbumId: String): Int? {
    addedAlbumIdsAsked += foreignAlbumId
    return addedAlbumId
  }

  override suspend fun ping(): Boolean = unused("ping")
  override suspend fun status(): LidarrServer = unused("status")
}

/**
 * A Bindery that answers the calls a status refresh, a search and an add make.
 *
 * `health` still throws, for the reason `FakeLidarrSource.ping` does.
 */
class FakeBinderySource : BinderySource {
  /** Every book this Bindery holds. [books] pages over it, honouring `limit`/`offset`. */
  var library: List<BinderyBook> = emptyList()

  /** Overrides the `total` this reports, so a client that trusted it blindly can be caught. */
  var reportedTotal: Int? = null

  var failWith: Exception? = null

  /** Every `(status, limit, offset)` this was called with, in order. */
  val pagesAsked: MutableList<Triple<String?, Int, Int>> = mutableListOf()

  val bookCalls: Int get() = pagesAsked.size

  override suspend fun books(status: String?, limit: Int, offset: Int): BinderyBookPage {
    pagesAsked += Triple(status, limit, offset)
    failWith?.let { throw it }
    val page = library.drop(offset).take(limit)
    return BinderyBookPage(
      books = page,
      total = reportedTotal ?: library.size,
      limit = limit,
      offset = offset,
    )
  }

  // ---- the search and add calls a submit makes ------------------------------------------------

  var searchResults: List<BinderyBookCandidate> = emptyList()
  var searchFailWith: Exception? = null
  val searchTerms: MutableList<String> = mutableListOf()

  var addResult: BinderyBook = binderyBook(id = 55, foreignBookId = "book", title = "A", status = "wanted")
  var addFailWith: Exception? = null

  /** Every `(candidate, mediaType, searchOnAdd)` an add was made with. `mediaType` is the trap. */
  val submitted: MutableList<Triple<BinderyBookCandidate, BinderyMediaType, Boolean>> = mutableListOf()

  override suspend fun searchBooks(term: String): List<BinderyBookCandidate> {
    searchTerms += term
    searchFailWith?.let { throw it }
    return searchResults
  }

  override suspend fun submitBook(
    candidate: BinderyBookCandidate,
    mediaType: BinderyMediaType,
    searchOnAdd: Boolean,
  ): BinderyBook {
    submitted += Triple(candidate, mediaType, searchOnAdd)
    addFailWith?.let { throw it }
    return addResult
  }

  override suspend fun health(): BinderyServer = unused("health")
}

/**
 * A factory that counts how often it was asked to build a client.
 *
 * This is where "zero Bindery traffic" is observed from for a service with no rows to poll at all:
 * a `create` that never happens is stronger than a `books()` that never happens, because there was
 * never anything to call it on.
 */
class FakeLidarrSourceFactory(private val source: LidarrSource) : LidarrSourceFactory {
  val credentialsSeen: MutableList<IntegrationCredentials.Lidarr> = mutableListOf()

  override fun create(credentials: IntegrationCredentials.Lidarr): LidarrSource {
    credentialsSeen += credentials
    return source
  }
}

class FakeBinderySourceFactory(private val source: BinderySource) : BinderySourceFactory {
  val credentialsSeen: MutableList<IntegrationCredentials.Bindery> = mutableListOf()

  override fun create(credentials: IntegrationCredentials.Bindery): BinderySource {
    credentialsSeen += credentials
    return source
  }
}

private fun unused(method: String): Nothing =
  throw UnsupportedOperationException("$method is not part of a status refresh and must not be called")

fun album(id: String, libraryId: Int, name: String, artist: String?) = Album(
  id = id,
  libraryId = libraryId,
  name = name,
  artistId = null,
  artistName = artist,
  coverArtId = null,
  songCount = 1,
  durationSeconds = 1,
)

fun queueItem(
  albumId: Int?,
  state: String,
  size: Double = 0.0,
  left: Double = 0.0,
  errorMessage: String? = null,
) = LidarrQueueItem(
  albumId = albumId,
  artistId = null,
  sizeBytes = size,
  sizeLeftBytes = left,
  trackedDownloadState = state,
  trackedDownloadStatus = "ok",
  errorMessage = errorMessage,
)

fun binderyBook(id: Int, foreignBookId: String, title: String, status: String) = BinderyBook(
  id = id,
  foreignBookId = foreignBookId,
  title = title,
  status = status,
  mediaType = "audiobook",
)

fun lidarrCandidate(foreignAlbumId: String, title: String, artistName: String) = LidarrAlbumCandidate(
  foreignAlbumId = foreignAlbumId,
  title = title,
  disambiguation = null,
  albumType = null,
  releaseDate = null,
  remoteCoverUrl = null,
  artistName = artistName,
  foreignArtistId = "artist-$foreignAlbumId",
  alreadyAdded = false,
  raw = JsonObject(emptyMap()),
)

fun binderyCandidate(foreignBookId: String, title: String, authorName: String?) = BinderyBookCandidate(
  foreignBookId = foreignBookId,
  title = title,
  authorName = authorName,
  foreignAuthorId = null,
  asin = null,
  coverUrl = null,
  raw = JsonObject(emptyMap()),
)

/**
 * Credentials for a test.
 *
 * Built through `IntegrationBaseUrl.parse` because there is no other constructor — which is the
 * property that keeps a secret out of every URL this plan builds. The key is a literal placeholder
 * and not a real one: nothing in this repository, fixture or test, carries a real API key.
 */
fun lidarrCredentials(apiKey: String = "test-key-lidarr") = IntegrationCredentials.Lidarr(
  baseUrl = baseUrl("https://lidarr.test:8686"),
  apiKey = apiKey,
)

fun binderyCredentials(apiKey: String = "test-key-bindery") = IntegrationCredentials.Bindery(
  baseUrl = baseUrl("https://bindery.test:8787"),
  apiKey = apiKey,
)

/**
 * `https` and [CleartextPolicy.Forbidden], deliberately: the strictest policy this build has, so
 * nothing here depends on the debug variant's carve-out.
 */
private fun baseUrl(raw: String): IntegrationBaseUrl =
  when (val result = IntegrationBaseUrl.parse(raw, CleartextPolicy.Forbidden)) {
    is BaseUrlResult.Valid -> result.url
    else -> error("test base URL $raw did not parse: $result")
  }

/**
 * A root folder Lidarr would accept an add into: accessible, with both profile defaults set.
 *
 * Defaults chosen so a test that cares about something else gets a working add, and a test about
 * an unusable folder says so explicitly.
 */
fun rootFolder(
  id: Int = 1,
  name: String = "Music",
  path: String = "/music",
  accessible: Boolean = true,
  defaultQualityProfileId: Int = 3,
  defaultMetadataProfileId: Int = 4,
) = LidarrRootFolder(
  id = id,
  name = name,
  path = path,
  accessible = accessible,
  freeSpaceBytes = null,
  defaultQualityProfileId = defaultQualityProfileId,
  defaultMetadataProfileId = defaultMetadataProfileId,
  defaultMonitorOption = "all",
  defaultNewItemMonitorOption = "all",
)
