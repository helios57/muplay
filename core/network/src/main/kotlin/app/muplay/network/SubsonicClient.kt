package app.muplay.network

import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.model.AlbumWithSongs
import app.muplay.model.Artist
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ReplayGain
import app.muplay.model.ScanStatus
import app.muplay.model.SearchResults
import app.muplay.model.ServerCapabilities
import app.muplay.model.ServerInfo
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.model.AlbumBody
import app.muplay.network.model.ArtistBody
import app.muplay.network.model.ChildBody
import app.muplay.network.model.ReplayGainBody
import app.muplay.network.model.SubsonicEnvelope
import app.muplay.network.model.SubsonicResponseBody
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * A typed Kotlin client over [SubsonicApi]: builds authenticated requests and maps every response
 * to either a domain type or [SubsonicException] — never mistaking a raw transport or parsing
 * exception for one. See [call] for exactly where that line is drawn.
 *
 * [api] defaults to a real Retrofit instance pointed at [credentials]'s `baseUrl`, wired with the
 * kotlinx.serialization converter (`Json(ignoreUnknownKeys = true)` — servers add fields over
 * time, and an unknown one must never break this client) this whole class depends on. The
 * secondary constructor parameter exists so a test can point a client at an [SubsonicApi] built
 * against a `MockWebServer`'s own `Retrofit` instance if it ever needs to (this project has no
 * mock framework to fake one with a spy); every test in `SubsonicClientTest` instead exercises the
 * default, so the real Retrofit + kotlinx.serialization stack is what is actually under test.
 */
class SubsonicClient(
  private val credentials: SubsonicCredentials,
  private val api: SubsonicApi = buildApi(credentials.baseUrl),
) : SubsonicSource {

  /** Calls `ping` and returns the server's identity. Throws [SubsonicException] on failure. */
  override suspend fun ping(): ServerInfo {
    val body = call { api.ping(authParams()) }
    return ServerInfo(
      type = body.type.orEmpty(),
      serverVersion = body.serverVersion.orEmpty(),
      apiVersion = body.version.orEmpty(),
      isOpenSubsonic = body.openSubsonic ?: false,
    )
  }

  /**
   * Calls `getMusicFolders` and maps every returned folder to a [MusicLibrary]. The Subsonic
   * response carries nothing that identifies what a folder is *for*, so every result here has
   * [LibraryRole.UNASSIGNED] — see [LibraryRole]'s own documentation for why that is correct, not
   * a placeholder. A folder missing its (optional, per the spec) `name` gets the stable fallback
   * `"Library <id>"` rather than a blank or null one.
   */
  override suspend fun getMusicFolders(): List<MusicLibrary> {
    val body = call { api.getMusicFolders(authParams()) }
    val folders = body.musicFolders?.musicFolder.orEmpty()
    return folders.map { folder ->
      MusicLibrary(
        id = folder.id,
        name = folder.name ?: "Library ${folder.id}",
        role = LibraryRole.UNASSIGNED,
      )
    }
  }

  /**
   * Calls `getOpenSubsonicExtensions` and returns each advertised extension name mapped to its
   * list of supported versions, straight from the response's `versions` arrays — no filtering or
   * interpretation here. [CapabilityNegotiator] is what decides what an empty or absent entry
   * means; this method only reports what the server said.
   *
   * Throws [SubsonicException] on failure exactly like [ping] and [getMusicFolders] — including
   * when the server rejects the call outright (a Subsonic-level error, or a non-2xx HTTP status,
   * e.g. a server old enough not to implement this command at all). [CapabilityNegotiator] is
   * where that failure gets interpreted as "no extensions" rather than propagated; this method
   * itself draws no such distinction; a caller with no need to degrade should let it propagate.
   */
  suspend fun getOpenSubsonicExtensions(): Map<String, List<Int>> {
    val body = call { api.getOpenSubsonicExtensions(authParams()) }
    return body.openSubsonicExtensions.orEmpty().associate { it.name to it.versions }
  }

  /**
   * Calls `getScanStatus` and reports the server's scan state, including Navidrome's own
   * `lastScan` extension — the watermark this project's whole sync design rests on.
   *
   * Reads only. It never calls `startScan`, and the request test asserts no `fullScan` parameter
   * rides along: Tempo re-triggers a full server scan on every poll of this endpoint, and a
   * client that polls must not be the thing that makes the server work.
   *
   * A success envelope with no `scanStatus` payload is a [SubsonicMalformedResponseException],
   * not a default-valued [ScanStatus]: "not scanning, no watermark" is a *specific claim* a caller
   * would act on (it is indistinguishable from a server that has genuinely never scanned), and
   * inventing it from a missing field would silently reset the sync watermark.
   */
  override suspend fun getScanStatus(): ScanStatus {
    val body = call { api.getScanStatus(authParams()) }
    val status = body.scanStatus ?: throw SubsonicMalformedResponseException("scanStatus")
    return ScanStatus(
      isScanning = status.scanning,
      scannedCount = status.count,
      lastScan = status.lastScan,
    )
  }

  /**
   * One page of albums from one library, every result stamped with [musicFolderId] — see
   * [SubsonicSource.getAlbumList2] and [Album.libraryId] for why the stamp comes from the request
   * rather than from the response.
   */
  override suspend fun getAlbumList2(
    musicFolderId: Int,
    type: AlbumListType,
    size: Int,
    offset: Int,
  ): List<Album> {
    val body = call {
      api.getAlbumList2(
        authParams() + mapOf(
          "type" to type.wireValue,
          "size" to size.coerceIn(1, MAX_ALBUM_LIST_PAGE).toString(),
          "offset" to offset.coerceAtLeast(0).toString(),
          "musicFolderId" to musicFolderId.toString(),
        ),
      )
    }
    // Absent container -> no albums. Legal, and exactly what a past-the-end offset returns.
    return body.albumList2?.album.orEmpty().map { it.toAlbum(musicFolderId) }
  }

  /**
   * One album and its tracks. [musicFolderId] is a *stamp*, never a request parameter — the spec
   * gives `getAlbum` exactly one parameter, `id`.
   *
   * A success envelope with no `album` payload is a [SubsonicMalformedResponseException] rather
   * than an empty album, because "the server said ok and told us nothing" is not a state a caller
   * can act on: a full reconcile that read it as "this album has no songs" would delete a real
   * album's tracks.
   */
  override suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs {
    // No `musicFolderId` on the wire: `getAlbum` takes only `id` per the spec. The argument is a
    // stamp, not a parameter.
    val body = call { api.getAlbum(authParams() + mapOf("id" to albumId)) }
    val album = body.album ?: throw SubsonicMalformedResponseException("album")
    return AlbumWithSongs(
      album = album.toAlbum(musicFolderId),
      songs = album.song.map { it.toSong(musicFolderId) },
    )
  }

  /**
   * Searches one library, stamping every artist, album and song with [musicFolderId].
   *
   * An absent `searchResult3` container, or any absent array within it, maps to an empty list
   * rather than to a failure: unlike [getAlbum], a search that matched nothing is a real answer a
   * caller can act on, and a server that omits the container for "no matches" is saying exactly
   * that.
   */
  override suspend fun search3(
    query: String,
    musicFolderId: Int,
    artistCount: Int,
    albumCount: Int,
    songCount: Int,
  ): SearchResults {
    val body = call {
      api.search3(
        authParams() + mapOf(
          "query" to query,
          "musicFolderId" to musicFolderId.toString(),
          "artistCount" to artistCount.coerceAtLeast(0).toString(),
          "albumCount" to albumCount.coerceAtLeast(0).toString(),
          "songCount" to songCount.coerceAtLeast(0).toString(),
        ),
      )
    }
    val result = body.searchResult3
    return SearchResults(
      artists = result?.artist.orEmpty().map { it.toArtist(musicFolderId) },
      albums = result?.album.orEmpty().map { it.toAlbum(musicFolderId) },
      songs = result?.song.orEmpty().map { it.toSong(musicFolderId) },
    )
  }

  /** Random songs from one library — the server side of library-scoped shuffle. */
  override suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song> {
    val body = call {
      api.getRandomSongs(
        authParams() + mapOf(
          // Navidrome caps this at 500 and silently truncates; clamping here keeps the number on
          // the wire and the number the caller reasons about the same one.
          "size" to size.coerceIn(1, MAX_RANDOM_SONGS).toString(),
          "musicFolderId" to musicFolderId.toString(),
        ),
      )
    }
    return body.randomSongs?.song.orEmpty().map { it.toSong(musicFolderId) }
  }

  /**
   * An authenticated `getCoverArt` URL. Built here rather than issued, because the caller is an
   * image loader, not this client.
   *
   * `authParams()` supplies a **fresh salt** on every call, so two URLs for the same art are
   * different strings — see [SubsonicSource.coverArtUrl] for why that matters to Coil.
   */
  override fun coverArtUrl(coverArtId: String, sizePx: Int?): String {
    val builder = normalizeBaseUrl(credentials.baseUrl).toHttpUrl().newBuilder()
      .addPathSegments("rest/getCoverArt")
      .addQueryParameter("id", coverArtId)
    authParams().forEach { (name, value) -> builder.addQueryParameter(name, value) }
    if (sizePx != null) builder.addQueryParameter("size", sizePx.toString())
    return builder.build().toString()
  }

  /**
   * An authenticated `/rest/stream` URL — see [SubsonicSource.streamUrl].
   *
   * **Two** parameters are conspicuously absent, and each absence is a decision. There were three
   * until Task 12; see below for what happened to the third.
   *
   * - **`estimateContentLength`.** It makes a transcoded response carry a *guessed*
   *   `Content-Length`. ExoPlayer trusts that header for seeking, so a guess produces seeks that
   *   land in the wrong place with nothing reported anywhere.
   * - **`maxBitRate` on a raw request.** `format=raw` disables transcoding, so a bitrate cap
   *   beside it is a parameter the server ignores and a reader misreads.
   *
   * The third used to be `timeOffset`, and it is now **conditional** rather than absent -- which is
   * the interesting part, because the condition is the whole feature. `timeOffset` means something
   * only to the transcoder, so it rides along exactly when the caller asked for one *and* the
   * request is a transcode, and never on a `format=raw` request, where the server ignores it and a
   * reader misreads it. Whether the caller may ask at all is the `transcodeOffset` extension's
   * question, asked through [capabilities] and answered by `:core:media`'s `TranscodeOffsetSupport`;
   * this method does not second-guess it.
   */
  override fun streamUrl(songId: String, format: StreamFormat, timeOffsetSeconds: Int?): String {
    val builder = normalizeBaseUrl(credentials.baseUrl).toHttpUrl().newBuilder()
      .addPathSegments("rest/stream")
      .addQueryParameter("id", songId)
      .addQueryParameter("format", format.wireValue)
    if (format is StreamFormat.Mp3) {
      builder.addQueryParameter("maxBitRate", format.maxBitRateKbps.toString())
    }
    if (format is StreamFormat.Mp3 && timeOffsetSeconds != null) {
      // Clamped rather than rejected: `Player.seekTo(-1)` is a legal call on a Media3 player, and
      // the second the negative reaches the wire it is a server-side surprise instead of a seek to
      // the top. `TranscodeSeek` clamps too; both, because neither is the other's guard.
      builder.addQueryParameter("timeOffset", timeOffsetSeconds.coerceAtLeast(0).toString())
    }
    authParams().forEach { (name, value) -> builder.addQueryParameter(name, value) }
    return builder.build().toString()
  }

  /**
   * Delegates to [CapabilityNegotiator] rather than re-deriving anything -- see
   * [SubsonicSource.capabilities].
   *
   * A one-line override on purpose: the three-tier rule (ping, then extensions, and what each
   * failure means) is one decision and it lives in one class. A second copy of it here would be a
   * second answer to "does this server speak OpenSubsonic", free to drift from the first.
   */
  override suspend fun capabilities(): ServerCapabilities = CapabilityNegotiator(this).negotiate()

  private fun AlbumBody.toAlbum(musicFolderId: Int) = Album(
    id = id,
    libraryId = musicFolderId,
    name = name,
    artistId = artistId,
    artistName = artist,
    coverArtId = coverArt,
    songCount = songCount,
    durationSeconds = duration,
  )

  private fun ArtistBody.toArtist(musicFolderId: Int) = Artist(
    id = id,
    libraryId = musicFolderId,
    name = name,
    coverArtId = coverArt,
    albumCount = albumCount,
  )

  private fun ChildBody.toSong(musicFolderId: Int) = Song(
    id = id,
    libraryId = musicFolderId,
    title = title,
    albumId = albumId,
    albumName = album,
    artistId = artistId,
    artistName = artist,
    trackNumber = track,
    discNumber = discNumber,
    durationSeconds = duration,
    suffix = suffix,
    coverArtId = coverArt,
    replayGain = replayGain.toDomain(),
  )

  /**
   * `null` rather than an all-null [ReplayGain]: "this file carries no gain tags" and "this file
   * carries tags whose every value happens to be absent" are the same fact, and the player's one
   * question is "is there a decision to apply". Navidrome sends `"replayGain": {}` on every
   * untagged file, so the empty-object case is the common one rather than a curiosity.
   *
   * The peak falls back from the track's to the album's, because a peak is only ever consumed as a
   * ceiling on a positive gain and an album peak is a safe -- if conservative -- one for a track
   * inside that album.
   *
   * `baseGain` and `fallbackGain` are parsed by [ReplayGainBody] so the oracle keeps validating
   * the whole object, and are deliberately dropped here: they configure a *server-side* normaliser
   * this client does not use.
   */
  private fun ReplayGainBody?.toDomain(): ReplayGain? {
    val trackGainDb = this?.trackGain
    val albumGainDb = this?.albumGain
    val peak = this?.trackPeak ?: this?.albumPeak
    if (trackGainDb == null && albumGainDb == null) return null
    return ReplayGain(trackGainDb = trackGainDb, albumGainDb = albumGainDb, peakAmplitude = peak)
  }

  /**
   * Runs [request] and returns the decoded [SubsonicResponseBody] only once it is proven to
   * represent success. Two, and only two, things become a [SubsonicException] here:
   *
   * - [request] throwing [HttpException] (an unsuccessful HTTP status) becomes
   *   [SubsonicHttpException].
   * - A response that *did* come back cleanly but whose body reports failure becomes
   *   [SubsonicErrorException]. Detected on `error != null` **or** `status == "failed"` — not
   *   their conjunction: the OpenSubsonic schema requires both together on a compliant failure
   *   response, but a non-compliant server, or a proxy that mangles one of the two, must not read
   *   as success either. If `status == "failed"` arrives with no `error` object at all (itself
   *   non-compliant), the code falls back to `0` — "a generic error" in the `SubsonicError`
   *   schema's own enumeration — rather than inventing a more specific one nothing in the response
   *   actually supports.
   *
   * Anything else [request] throws — [kotlinx.serialization.SerializationException] from an
   * unparseable body, [java.io.IOException] from a dead socket — is not caught here and propagates
   * unchanged. Those are "we could not ask", not "the server said no", and only the latter belongs
   * in [SubsonicException] (see that type's own documentation, and Task 5's capability negotiation,
   * which depends on this distinction).
   */
  private suspend fun call(request: suspend () -> SubsonicEnvelope): SubsonicResponseBody {
    val envelope =
      try {
        request()
      } catch (e: HttpException) {
        throw SubsonicHttpException(e.code())
      }
    val body = envelope.subsonicResponse
    if (body.error != null || body.status == "failed") {
      throw SubsonicErrorException(body.error?.code ?: GENERIC_ERROR_CODE, body.error?.message)
    }
    return body
  }

  private fun authParams(): Map<String, String> = SubsonicAuth.authParams(credentials, generateSalt())

  /**
   * A fresh salt for every call, per [SubsonicAuth]'s own requirement — never cached or reused —
   * generated from [SecureRandom], not [kotlin.random.Random], since this feeds directly into an
   * authentication token.
   */
  private fun generateSalt(): String {
    val bytes = ByteArray(SALT_BYTES)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  companion object {
    private const val SALT_BYTES = 8

    // The OpenSubsonic `SubsonicError.code` enum's own "0: A generic error" — the fallback used
    // when a response claims `status == "failed"` but, non-compliantly, carries no `error` object
    // to read a real code from.
    private const val GENERIC_ERROR_CODE = 0

    // One SecureRandom, reused across every call and every SubsonicClient instance, rather than
    // one per request: SecureRandom seeding can block gathering entropy, and freshness per call
    // comes from nextBytes() on the shared instance, not from constructing a new one each time.
    private val secureRandom = SecureRandom()

    /** Subsonic's documented cap on `getRandomSongs.size`; Navidrome truncates silently at it. */
    const val MAX_RANDOM_SONGS = 500

    /** Subsonic's documented cap on `getAlbumList2.size`. */
    const val MAX_ALBUM_LIST_PAGE = 500

    /**
     * Extracted from [buildApi], which had it inline, so the cover-art URL builder and the
     * Retrofit base URL cannot disagree about whether a user-entered URL needs a trailing slash.
     * `SubsonicClientTest`'s `ping succeeds when baseUrl has no trailing slash` already covers
     * the branch; nothing about its behaviour changes here, only where it lives.
     */
    private fun normalizeBaseUrl(baseUrl: String): String =
      if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private fun buildApi(baseUrl: String): SubsonicApi {
      val json = Json { ignoreUnknownKeys = true }
      val contentType = "application/json".toMediaType()
      val retrofit =
        Retrofit.Builder()
          .baseUrl(normalizeBaseUrl(baseUrl))
          .addConverterFactory(json.asConverterFactory(contentType))
          .build()
      return retrofit.create(SubsonicApi::class.java)
    }
  }
}
