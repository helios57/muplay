package app.muplay.integrations.bindery

import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The raw Retrofit surface for Bindery's v1 API.
 *
 * Every method returns `Response<T>` rather than `T`, because this client has to read the **status
 * code and the raw error body** on failure: Bindery answers with one shape, `{"error": "…"}`, at
 * seven different statuses, and the status is the only thing that separates "you asked for a book
 * that does not exist" (502) from "this book has no author metadata, add the author first" (422)
 * from "your key is wrong" (401). Retrofit's default `HttpException` gives neither the code with
 * the body nor a second chance to read it.
 *
 * No `@Headers` here, and that is the point of [BinderyAuthInterceptor]: `X-Api-Key` and `Accept`
 * are attached to every request by the OkHttp stack, so an endpoint a later task adds cannot ship
 * without them by forgetting an annotation.
 */
internal interface BinderyApi {

  /**
   * `GET /api/v1/health`. **Unauthenticated** — see [HealthBody].
   *
   * Under `api/v1` like everything else here, unlike Lidarr's `/ping`, which sits at the
   * application root outside its versioned controllers.
   */
  @GET("api/v1/health")
  suspend fun health(): Response<HealthBody>

  /**
   * `GET /api/v1/search/book`. **The parameter is `term`.**
   *
   * Bindery's own documentation says `q`, and it is wrong: the handler reads `term`. Measured
   * against `v1.32.1` — `?q=project%20hail%20mary` answers **400**
   * `{"error":"term parameter required"}`, and so does a request with no parameter at all, so a
   * client written from the docs cannot search. Both bodies are byte-identical, which is why this
   * is worth pinning at the request rather than trusting a distinguishable error.
   *
   * Returns raw `JsonElement`s, not a typed resource, because [BinderyBookCandidate] keeps the
   * whole element: a real result carries thirty-one top-level fields (description, genres,
   * releaseDate, metadataProvider, narrator, durationSeconds …) and a surface asking a user to
   * pick between forty near-identical titles needs more of them than this client models.
   *
   * **Not served from the user's own library.** It proxies to Open Library and the DNB, so it is
   * slow, is rate-limited upstream, and can fail while the user's own server is perfectly healthy.
   */
  @GET("api/v1/search/book")
  suspend fun searchBook(@Query("term") term: String): Response<List<JsonElement>>

  /**
   * `POST /api/v1/author/book` — **undocumented, and the only way to ask Bindery for anything.**
   *
   * Measured against `v1.32.1`: **201 Created** with the persisted book as the body, including its
   * real database `id`. A **duplicate is also a 201**, carrying the same id as the original —
   * Bindery upserts, so unlike Lidarr's add there is no "already added" outcome to model and no
   * 400 to tell apart from a real refusal.
   *
   * The path says `author` and the thing created is a book. That is Bindery's spelling, not a
   * typo here: the handler adds the book *and* its author, which is why a body with neither
   * `foreignAuthorId` nor `authorName` is refused with a 422 rather than filed under an unknown
   * author.
   */
  @POST("api/v1/author/book")
  suspend fun addBook(@Body body: AddBookBody): Response<BookBody>

  /**
   * `GET /api/v1/book`, optionally filtered by [status].
   *
   * [status] is nullable and Retrofit omits a null `@Query` entirely, which is exactly the
   * unfiltered call — measured, `GET /api/v1/book` with no parameter returns every book.
   *
   * **A status Bindery does not know is not an error.** Measured: `?status=bogus` answers `200`
   * with `{"items":[],"total":0,…}`, indistinguishable from an honest "nothing is in that state".
   * A caller passing a misspelled status therefore gets silence, not a failure, which is why
   * [BinderySource.books] documents the four values rather than leaving them to be guessed.
   *
   * [limit] and [offset] are always sent, never left to the server's default. Measured, that
   * default is 100 and `total` is the count before it is applied, so a client relying on the
   * default would silently see one page of a larger library and a later Bindery release could move
   * the number underneath it.
   */
  @GET("api/v1/book")
  suspend fun books(
    @Query("status") status: String?,
    @Query("limit") limit: Int,
    @Query("offset") offset: Int,
  ): Response<BookPageBody>
}
