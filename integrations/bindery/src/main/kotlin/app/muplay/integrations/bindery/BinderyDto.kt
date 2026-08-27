package app.muplay.integrations.bindery

import kotlinx.serialization.Serializable

/**
 * The wire shapes this client reads and writes.
 *
 * **Bindery publishes no OpenAPI or Swagger document at all**, so unlike `:core:network`'s
 * Subsonic types there is no oracle here to validate a fixture against. The substitute is that
 * every shape below was captured from a real `ghcr.io/vavallee/bindery:v1.32.1` and committed
 * under `src/test/resources/fixtures/bindery/`; nothing in this file was written from
 * documentation, and two of the plan's expected field names turned out to be wrong (see
 * [BookBody]).
 *
 * Every field this client reads is nullable with a default. Bindery's Go handlers serialise a
 * struct rather than a map, so most fields are always present — but "always present on the build
 * we measured" is not a guarantee, and a non-nullable Kotlin field with no default turns an added
 * or removed key into a parse failure for the whole response.
 */

/**
 * `GET /api/v1/health`.
 *
 * **Unauthenticated — measured**: this endpoint answers `200 {"status":"ok","version":"v1.32.1"}`
 * with a wrong `X-Api-Key`, and with no `X-Api-Key` at all. That is why
 * [BinderySource.health] cannot be a credential check, and why anything offering the user a
 * "test connection" button has to call something else as well. See [BinderySource.health].
 */
@Serializable
internal data class HealthBody(val status: String? = null, val version: String? = null)

/**
 * The envelope `GET /api/v1/book` returns: `{items, total, limit, offset}`.
 *
 * Note that `GET /api/v1/search/book` returns a **bare array** instead. Two shapes on one service,
 * and reading either with the other's reader yields an empty list rather than an error — which is
 * why `BinderySearchTest` asserts the search's element count from a real fixture rather than
 * asserting that it merely parsed.
 *
 * [total] is the count *before* [limit] is applied — measured: with four books stored,
 * `?limit=2&offset=1` answers `{"total":4,"limit":2,"offset":1}` and two items. So a caller that
 * ignored [total] would silently see one page and believe it had seen everything, which is why
 * [BinderyBookPage] carries all three numbers out to its caller instead of returning a bare list.
 */
@Serializable
internal data class BookPageBody(
  val items: List<BookBody>? = null,
  val total: Int = 0,
  val limit: Int = 0,
  val offset: Int = 0,
)

/**
 * One book, as `GET /api/v1/book` and `POST /api/v1/author/book` both return it.
 *
 * **`id` is an integer, not a string.** The plan expected a string; measured, a created book comes
 * back as `{"id":1,…}` and a search element as `{"id":0,…}` — SQLite `INTEGER PRIMARY KEY
 * AUTOINCREMENT`, so `0` means "not persisted" and is the value every search result carries.
 * [BinderyClient] treats `0` as no id at all for that reason.
 *
 * **`foreignBookId` is namespaced**: `gb:` for Google Books, `hc:` for Hardcover, `dnb:` for the
 * Deutsche Nationalbibliothek, and an unprefixed value means Open Library. Measured on one search:
 * twenty `dnb:`-prefixed results and twenty unprefixed ones in the same array. This client treats
 * it as an opaque string and never parses the prefix — but it is what a request row stores as its
 * `externalId`, so two books from different providers cannot collide.
 *
 * **There is no ISBN here.** `Book.ProviderISBNs` is `json:"-"` in Bindery's own model; ISBNs live
 * on editions, reachable through `GET /api/v1/book/{id}`. This client does not need one and does
 * not fetch editions.
 *
 * `mediaType` is read back deliberately. It is the field the whole module's central trap is about
 * — it defaults to `ebook` server-side — and reading it back off a stored book is the only way a
 * caller can ever see that what was acquired is not what was asked for.
 */
@Serializable
internal data class BookBody(
  val id: Int? = null,
  val foreignBookId: String? = null,
  val title: String? = null,
  val status: String? = null,
  val mediaType: String? = null,
)

/**
 * The body of `POST /api/v1/author/book`. **Undocumented**, and captured by running it.
 *
 * **[mediaType] and [searchOnAdd] have no defaults, and that is load-bearing rather than tidy.**
 * kotlinx.serialization omits a property that equals its default; a `mediaType` with one could
 * therefore be dropped from the wire by a later edit that never touched a call site, and an omitted
 * `mediaType` is a `201` that quietly acquires an EPUB. Measured against a real instance: a POST
 * omitting the field answers `201` with `"mediaType":"ebook"` in the created book.
 *
 * [foreignAuthorId] and [authorName] are nullable and are omitted when null
 * (`explicitNulls = false`). Measured, Bindery accepts **either one alone** — a POST with only
 * `foreignAuthorId` and a POST with only `authorName` both answered `201` — and refuses the pair
 * being absent with a `422` whose body names the fix (`fixtures/bindery/error-author-unavailable
 * .json`). Sending both when both are known is what this client does; sending neither is a
 * legible failure rather than a wrong add.
 */
@Serializable
internal data class AddBookBody(
  val foreignBookId: String,
  val foreignAuthorId: String? = null,
  val authorName: String? = null,
  val mediaType: String,
  val searchOnAdd: Boolean,
)

/**
 * Every failure body Bindery produces: a single-key object `{"error": "…"}`.
 *
 * Measured at 400 (`term parameter required`, `foreignBookId required`,
 * `mediaType must be 'ebook', 'audiobook', or 'both'`), 401 (`unauthorized`), 404
 * (`book not found after author sync — try again shortly`), 422 (`Author metadata unavailable for
 * this result…`) and 502 (`look up book metadata: … not found`) — one shape at seven statuses,
 * which is why [BinderyMessageException] carries the status beside the text rather than trying to
 * classify from the text.
 */
@Serializable
internal data class ErrorBody(val error: String? = null)
