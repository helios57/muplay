package app.muplay.integrations.bindery

import app.muplay.integrations.IntegrationCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * A typed Kotlin client over [BinderyApi].
 *
 * The OkHttp stack carries exactly one interceptor, [BinderyAuthInterceptor]. **No logging
 * interceptor is installed and none may be added**: it would print the `X-Api-Key` header on every
 * request, and that key is instance-wide and admin-equivalent. `ConventionTest`'s
 * `nothing in integrations writes to a log` refuses the obvious form of that mistake, and
 * `no okhttp logging interceptor may reach an integration` refuses the one a call-site scan cannot
 * see; the absence of `okhttp-logging-interceptor` from this module's dependencies is the third.
 *
 * The public constructor takes credentials only. The two-argument one is `internal` because
 * [BinderyApi] is: a public signature naming an internal type does not compile, and widening
 * `BinderyApi` to make it fit would put Retrofit's raw `Response<HealthBody>` surface into this
 * module's public API for no caller's benefit.
 */
class BinderyClient internal constructor(
  private val api: BinderyApi,
) : BinderySource {

  /**
   * The production constructor: a real Retrofit stack over [credentials].
   *
   * [credentials] is consumed here and not retained. Nothing this class does afterwards needs the
   * API key again — the interceptor holds it — and a client that kept a copy would be one more
   * object with a secret field for a `toString()` to print.
   */
  constructor(credentials: IntegrationCredentials.Bindery) : this(buildApi(credentials))

  override suspend fun health(): BinderyServer {
    val body = call { api.health() }
    return BinderyServer(
      status = body.status.orEmpty(),
      version = body.version.orEmpty(),
    )
  }

  override suspend fun searchBooks(term: String): List<BinderyBookCandidate> =
    call { api.searchBook(term) }.mapNotNull(::toCandidate)

  /**
   * The add.
   *
   * Nothing is caught here, unlike `LidarrClient.submitAlbum`, and that is the server's doing
   * rather than a difference of taste: Bindery upserts, so a duplicate is a `201` carrying the
   * original id and there is no already-added refusal to tell apart from a real one.
   *
   * **A success with no usable id is a loud failure, deliberately.** Returning a book with
   * `id = 0` would put a row in the request store that every later poll looks up under an id no
   * book has — and `0` is not a hypothetical value here: it is what *every* search result carries,
   * so it is precisely the number a wrong parse would produce. The exception carries the status
   * that actually came back rather than the `201` this endpoint is measured to return, so a proxy
   * that rewrote the status is reported as what it did.
   */
  override suspend fun submitBook(
    candidate: BinderyBookCandidate,
    mediaType: BinderyMediaType,
    searchOnAdd: Boolean,
  ): BinderyBook {
    val response = proven {
      api.addBook(
        AddBookBody(
          foreignBookId = candidate.foreignBookId,
          foreignAuthorId = candidate.foreignAuthorId,
          authorName = candidate.authorName,
          mediaType = mediaType.wireValue,
          searchOnAdd = searchOnAdd,
        ),
      )
    }
    val body = response.body() ?: throw BinderyHttpException(response.code())
    return toBook(body) ?: throw BinderyHttpException(response.code())
  }

  override suspend fun books(status: String?, limit: Int, offset: Int): BinderyBookPage {
    val body = call { api.books(status, limit, offset) }
    return BinderyBookPage(
      // `mapNotNull`, so one unusable row does not lose the page. A row with no id or no
      // `foreignBookId` is one nothing can correlate on, and dropping it keeps the rest usable
      // where failing the whole parse would lose all of them.
      books = body.items.orEmpty().mapNotNull(::toBook),
      total = body.total,
      limit = body.limit,
      offset = body.offset,
    )
  }

  /**
   * A typed view over one book, or `null` when it carries nothing to correlate on.
   *
   * `id == 0` is treated as no id at all: SQLite's `INTEGER PRIMARY KEY AUTOINCREMENT` starts at
   * 1, and `0` is what Bindery sends on a search result that is not in the library — measured,
   * every element of a real search, including one for a book that had just been added.
   */
  private fun toBook(body: BookBody): BinderyBook? {
    val id = body.id?.takeIf { it != 0 } ?: return null
    val foreignBookId = body.foreignBookId?.takeIf { it.isNotBlank() } ?: return null
    return BinderyBook(
      id = id,
      foreignBookId = foreignBookId,
      title = body.title.orEmpty(),
      status = body.status.orEmpty(),
      mediaType = body.mediaType.orEmpty(),
    )
  }

  /**
   * A typed view over one search element, or `null` when the element cannot be asked for.
   *
   * An element with no `foreignBookId` is unusable: `POST /api/v1/author/book` answers
   * `400 {"error":"foreignBookId required"}` without one. Dropping such a row keeps every other
   * result usable, where failing the whole parse would lose all of them.
   *
   * The author fields come from the **nested `author` object**, which is absent on more than half
   * of a real search's elements — 22 of 40, measured. That is the plan's one wrong expectation
   * about this API's shape, and reading them top-level would have yielded `null` on every row
   * silently.
   */
  private fun toCandidate(element: JsonElement): BinderyBookCandidate? {
    val obj = element as? JsonObject ?: return null
    val foreignBookId = obj.string("foreignBookId")?.takeIf { it.isNotBlank() } ?: return null
    val author = obj["author"] as? JsonObject
    return BinderyBookCandidate(
      foreignBookId = foreignBookId,
      title = obj.string("title").orEmpty(),
      authorName = author?.string("authorName")?.takeIf { it.isNotBlank() },
      foreignAuthorId = author?.string("foreignAuthorId")?.takeIf { it.isNotBlank() },
      asin = obj.string("asin")?.takeIf { it.isNotBlank() },
      coverUrl = obj.string("imageUrl")?.takeIf { it.isNotBlank() },
      raw = obj,
    )
  }

  private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

  /**
   * Runs [request] and returns its body only once the response is proven successful.
   *
   * A successful response with no body at all is a [BinderyHttpException] carrying the status
   * rather than a `NullPointerException`.
   */
  internal suspend fun <T : Any> call(request: suspend () -> Response<T>): T {
    val response = proven(request)
    return response.body() ?: throw BinderyHttpException(response.code())
  }

  /**
   * Runs [request] and returns the **response** once its status is proven successful.
   *
   * Separate from [call] because [submitBook] needs the status code as well as the body: a
   * successful add with no id has to fail naming the status that really came back, and hardcoding
   * the `201` this endpoint is measured to return would be a constant standing in for a value —
   * the defect class this whole module is written against.
   *
   * The cascade is two steps rather than Lidarr's four, because Bindery has one failure shape at
   * every status. `401` first, because it is the one failure whose remedy is specific and the one
   * a connection check must name without matching on English; everything else keeps Bindery's own
   * sentence when there is one and degrades to a bare status when there is not.
   */
  private suspend fun <T : Any> proven(request: suspend () -> Response<T>): Response<T> {
    val response = request()
    if (response.isSuccessful) return response
    if (response.code() == 401) throw BinderyUnauthorizedException()
    val message = errorMessage(response.errorBody()?.string().orEmpty())
    throw if (message == null) {
      BinderyHttpException(response.code())
    } else {
      BinderyMessageException(response.code(), message)
    }
  }

  /**
   * Bindery's own sentence out of an `{"error": "…"}` body, or `null` when the body is not one.
   *
   * A body that is neither — a reverse proxy's HTML error page carrying a 502 — yields `null`
   * rather than a parse failure, so the caller still gets a [BinderyException] it can show rather
   * than a `SerializationException` it cannot. A present-but-blank `error` is `null` too: an empty
   * string shown to a user is a dialogue with no text in it.
   */
  private fun errorMessage(raw: String): String? =
    runCatching { json.decodeFromString<ErrorBody>(raw) }
      .getOrNull()
      ?.error
      ?.takeIf { it.isNotBlank() }

  internal companion object {

    val json: Json = Json {
      // Bindery serialises whole Go structs and adds fields between releases; neither may break
      // this client. Measured: one search element carries thirty-one top-level fields and this
      // client reads five of them.
      ignoreUnknownKeys = true
      // Together these two decide what the **request** body looks like, and that is the pairing
      // this module's central trap turns on. `explicitNulls = false` omits a null
      // `foreignAuthorId` or `authorName` rather than sending `"foreignAuthorId": null` --
      // measured, Bindery accepts either field alone. `encodeDefaults = true` means a property
      // that ever gains a default is still written; without it, adding `= "ebook"` to
      // `AddBookBody.mediaType` in a later edit would silently stop sending the field, which is
      // exactly the `201`-and-an-EPUB outcome this module exists to refuse.
      explicitNulls = false
      encodeDefaults = true
    }

    private fun buildApi(credentials: IntegrationCredentials.Bindery): BinderyApi {
      val http = OkHttpClient.Builder()
        .addInterceptor(BinderyAuthInterceptor(credentials.apiKey))
        .build()
      return Retrofit.Builder()
        // `IntegrationBaseUrl.value` always ends in `/`, which Retrofit requires: without it,
        // resolving `api/v1/health` against `https://host/bindery` would drop the `bindery`
        // segment. That guarantee is the type's, not this call site's -- and it is why
        // `IntegrationBaseUrl.parse` keeps the path verbatim instead of stripping it the way it
        // strips the query, the fragment and userinfo.
        .baseUrl(credentials.baseUrl.value)
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BinderyApi::class.java)
    }
  }
}
