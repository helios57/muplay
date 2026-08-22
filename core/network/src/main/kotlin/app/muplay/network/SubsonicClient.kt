package app.muplay.network

import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ServerInfo
import app.muplay.model.SubsonicCredentials
import app.muplay.network.model.SubsonicEnvelope
import app.muplay.network.model.SubsonicResponseBody
import java.security.SecureRandom
import kotlinx.serialization.json.Json
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
) {

  /** Calls `ping` and returns the server's identity. Throws [SubsonicException] on failure. */
  suspend fun ping(): ServerInfo {
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
  suspend fun getMusicFolders(): List<MusicLibrary> {
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

    private fun buildApi(baseUrl: String): SubsonicApi {
      val json = Json { ignoreUnknownKeys = true }
      val contentType = "application/json".toMediaType()
      val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
      val retrofit =
        Retrofit.Builder()
          .baseUrl(normalizedBaseUrl)
          .addConverterFactory(json.asConverterFactory(contentType))
          .build()
      return retrofit.create(SubsonicApi::class.java)
    }
  }
}
