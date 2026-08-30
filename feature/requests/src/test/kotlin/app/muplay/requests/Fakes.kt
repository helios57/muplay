package app.muplay.requests

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
import app.muplay.integrations.requests.ConfiguredServices
import app.muplay.model.SearchResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Hand-written fakes. **No mock framework may enter this build** (`ConventionTest`'s
 * `no mock framework is declared in any build file or convention plugin`).
 *
 * These overlap with `:integrations:requests`' own `Fakes.kt` and that duplication is deliberate, so
 * it is worth stating rather than leaving to be discovered: a test source set is not published, and
 * the alternative -- putting them in `:core:testing` -- would give the lowest test module in the
 * tree an edge into `integrations/`, which `ConventionTest`'s severability rule forbids outright.
 * Two small copies are cheaper than making Plan 7 un-droppable.
 *
 * Every method a test does not drive throws rather than returning a plausible empty value: an
 * accidental call is then a loud failure naming the method, not a silently-correct-looking run.
 */
private fun unused(method: String): Nothing =
  throw UnsupportedOperationException("$method is not part of this suite and must not be called")

/** The credential store's read side, as a flow a test can move. */
class FakeConfiguredServices : ConfiguredServices {
  private val state = MutableStateFlow(emptyMap<IntegrationService, IntegrationCredentials>())

  override fun configured(): Flow<Map<IntegrationService, IntegrationCredentials>> = state

  fun save(credentials: IntegrationCredentials) {
    state.value = state.value + (credentials.service to credentials)
  }

  fun clear(service: IntegrationService) {
    state.value = state.value - service
  }
}

/** The credential store's write side. Records what it was handed, not merely that it was called. */
class RecordingCredentialWriter : IntegrationCredentialWriter {
  val saved: MutableList<IntegrationCredentials> = mutableListOf()

  override suspend fun save(credentials: IntegrationCredentials) {
    saved += credentials
  }
}

class RecordingCredentialEraser : IntegrationCredentialEraser {
  val forgotten: MutableList<IntegrationService> = mutableListOf()

  override suspend fun forget(service: IntegrationService) {
    forgotten += service
  }
}

/**
 * A probe a test can point at any observation, and which records what it was asked about.
 *
 * [gate] is what lets a test observe the *in-flight* state: a real probe is a network round trip,
 * and the one thing that can go wrong while it is in flight -- a second tap starting a second probe
 * -- is invisible to a fake that answers instantly.
 */
class FakeConnectionProbe(var observation: ConnectionObservation = ConnectionObservation(true, "Lidarr", null)) :
  ConnectionProbe {
  val asked: MutableList<IntegrationCredentials> = mutableListOf()

  /** When set, `observe` suspends on it until a test completes it. */
  var gate: CompletableDeferred<Unit>? = null

  override suspend fun observe(credentials: IntegrationCredentials): ConnectionObservation {
    asked += credentials
    gate?.await()
    return observation
  }
}

/**
 * An in-memory `MediaRequestDao`, so a view model test drives the **real** `MediaRequestRepository`
 * and the **real** `RequestsRepository` rather than a fake of either.
 *
 * `observeAll`'s ordering repeats the DAO's own `ORDER BY requestedAtEpochMs DESC, id ASC`, because
 * a fake that returned insertion order would hide the defect that clause exists to prevent.
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
      id to existing.copy(status = status, statusDetail = statusDetail, updatedAtEpochMs = updatedAtEpochMs)
      )
  }

  override suspend fun delete(id: String) {
    rows.value = rows.value - id
  }
}

/** A Lidarr answering the calls a search, a submit and a connection check make. */
class FakeLidarrSource : LidarrSource {
  var pingAnswer: Boolean = true
  var server: LidarrServer = lidarrServer("Lidarr")
  var statusFailWith: Exception? = null
  var lookupResults: List<LidarrAlbumCandidate> = emptyList()
  var lookupFailWith: Exception? = null
  var addOutcome: LidarrAddOutcome = LidarrAddOutcome.Added(albumId = 7)

  val lookupTerms: MutableList<String> = mutableListOf()
  var pingCalls: Int = 0
    private set
  var statusCalls: Int = 0
    private set

  override suspend fun ping(): Boolean {
    pingCalls++
    return pingAnswer
  }

  override suspend fun status(): LidarrServer {
    statusCalls++
    statusFailWith?.let { throw it }
    return server
  }

  override suspend fun lookupAlbums(term: String): List<LidarrAlbumCandidate> {
    lookupTerms += term
    lookupFailWith?.let { throw it }
    return lookupResults
  }

  override suspend fun rootFolders(): List<LidarrRootFolder> = listOf(
    LidarrRootFolder(
      id = 1, name = "Music", path = "/music", accessible = true, freeSpaceBytes = null,
      defaultQualityProfileId = 3, defaultMetadataProfileId = 4,
      defaultMonitorOption = "all", defaultNewItemMonitorOption = "all",
    ),
  )

  override suspend fun qualityProfiles(): List<LidarrProfile> = listOf(LidarrProfile(3, "Any"))

  override suspend fun metadataProfiles(): List<LidarrProfile> = listOf(LidarrProfile(4, "Standard"))

  override suspend fun submitAlbum(
    candidate: LidarrAlbumCandidate,
    targets: LidarrAddTargets,
    searchNow: Boolean,
  ): LidarrAddOutcome = addOutcome

  override suspend fun findAddedAlbumId(foreignAlbumId: String): Int? = null

  override suspend fun queue(): List<LidarrQueueItem> = emptyList()

  override suspend fun albumProgress(albumId: Int): LidarrAlbumProgress? = unused("albumProgress")
}

/** A Bindery answering the calls a search, a submit and a connection check make. */
class FakeBinderySource : BinderySource {
  var server: BinderyServer = BinderyServer(status = "ok", version = "v1.32.1")
  var healthFailWith: Exception? = null
  var booksFailWith: Exception? = null
  var searchResults: List<BinderyBookCandidate> = emptyList()
  var searchFailWith: Exception? = null
  var addResult: BinderyBook =
    BinderyBook(id = 9, foreignBookId = "ol-1", title = "Dune", status = "wanted", mediaType = "audiobook")

  val searchTerms: MutableList<String> = mutableListOf()
  var healthCalls: Int = 0
    private set

  /** Every `(status, limit, offset)` `books` was asked for -- the authenticated half of the check. */
  val pagesAsked: MutableList<Triple<String?, Int, Int>> = mutableListOf()

  override suspend fun health(): BinderyServer {
    healthCalls++
    healthFailWith?.let { throw it }
    return server
  }

  override suspend fun searchBooks(term: String): List<BinderyBookCandidate> {
    searchTerms += term
    searchFailWith?.let { throw it }
    return searchResults
  }

  override suspend fun submitBook(
    candidate: BinderyBookCandidate,
    mediaType: BinderyMediaType,
    searchOnAdd: Boolean,
  ): BinderyBook = addResult

  override suspend fun books(status: String?, limit: Int, offset: Int): BinderyBookPage {
    pagesAsked += Triple(status, limit, offset)
    booksFailWith?.let { throw it }
    return BinderyBookPage(books = emptyList(), total = 0, limit = limit, offset = offset)
  }
}

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

/** `RequestArrivalDetector`'s three ports. Nothing in this module's suite drives an arrival. */
fun quietMirror() = app.muplay.integrations.requests.MirrorSync { SyncState.UpToDate }

fun quietSearch() = app.muplay.integrations.requests.AlbumSearch { _, _, _ ->
  SearchResults(artists = emptyList(), albums = emptyList(), songs = emptyList())
}

fun quietRoles() = app.muplay.integrations.requests.LibraryRoles { emptyList() }

fun lidarrServer(appName: String) = LidarrServer(
  appName = appName,
  instanceName = appName,
  version = "3.1.0",
  urlBase = "",
  authentication = "forms",
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
  raw = kotlinx.serialization.json.JsonObject(emptyMap()),
)

fun binderyCandidate(foreignBookId: String, title: String, authorName: String?) = BinderyBookCandidate(
  foreignBookId = foreignBookId,
  title = title,
  authorName = authorName,
  foreignAuthorId = null,
  asin = null,
  coverUrl = null,
  raw = kotlinx.serialization.json.JsonObject(emptyMap()),
)

/**
 * Credentials for a test, built through `IntegrationBaseUrl.parse` because there is no other
 * constructor. `https` and `Forbidden`, the strictest policy this build has, so nothing here leans
 * on the debug variant's carve-out. The key is a literal placeholder: nothing in this repository,
 * fixture or test, carries a real API key.
 */
fun lidarrCredentials(apiKey: String = "test-key-lidarr") =
  IntegrationCredentials.Lidarr(baseUrl("https://lidarr.test:8686"), apiKey)

fun binderyCredentials(apiKey: String = "test-key-bindery") =
  IntegrationCredentials.Bindery(baseUrl("https://bindery.test:8787"), apiKey)

private fun baseUrl(raw: String): IntegrationBaseUrl =
  when (val result = IntegrationBaseUrl.parse(raw, CleartextPolicy.Forbidden)) {
    is BaseUrlResult.Valid -> result.url
    else -> error("test base URL $raw did not parse: $result")
  }
