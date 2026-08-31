package app.muplay.integrations.bindery

import app.muplay.integrations.IntegrationCredentials
import kotlinx.serialization.json.JsonObject

/**
 * Everything this app asks of a Bindery server, as one interface.
 *
 * ### Which Bindery
 *
 * **`github.com/vavallee/bindery`** — MIT, actively developed, `ghcr.io/vavallee/bindery`, default
 * port **8787**, and `v1.32.1` is the version every measurement in this module was taken against.
 * **Three unrelated projects are called Bindery and the wrong ones are easier to find**:
 * `evanbrooks/bindery` is a browser book-layout library archived in 2023, and
 * `jarynclouatre/bindery` is an e-book format converter — and confusingly the only "Bindery" in
 * awesome-selfhosted. Neither is this.
 *
 * ### Asking *is* acquiring
 *
 * Spec §8 lists Bindery beside Lidarr as a service to *"request audiobooks"* from, with the
 * framing that both are **request** services. That framing is wrong, and it was established by
 * reading Bindery's source rather than its README and then confirmed against a running instance:
 * **Bindery is a Readarr replacement, an acquisition automation tool.** There is no
 * request/approval concept in it — no request or approve routes in its router (the only `approve`
 * is an *import review* queue), and none on its roadmap. Adding a book *is* acquiring it, because
 * the person adding it owns the server and there is nobody to approve anything.
 *
 * This interface therefore models acquisition. There is no `Pending approval` state, because
 * Bindery has none; inventing one would be a state machine the server does not have, displayed to
 * a user as if it did.
 *
 * ### A port, for one reason
 *
 * Exactly like `LidarrSource` next door and `SubsonicSource` in `:core:network`: a test needs to
 * make a *specific call* fail at a *specific point* — Task 9's status poller must not advance a
 * request's state when one of several calls fails — and no real Bindery can be asked to do that on
 * demand. A hand-written fake implementing this interface can, with no mock framework anywhere
 * near the build.
 */
interface BinderySource {

  /**
   * Bindery's own report of what it is and what version it runs.
   *
   * **This is not a credential check, and nothing may treat it as one.** `GET /api/v1/health` is
   * unauthenticated — measured, it answers `200 {"status":"ok","version":"v1.32.1"}` with a wrong
   * `X-Api-Key` and with none at all. A connection check that called only this would tell a user
   * with a mistyped key that everything was fine; [books] is the cheapest authenticated call and
   * is what proves the key.
   *
   * Throws a [BinderyException] on failure rather than returning a nullable, because the two
   * questions it could otherwise conflate — "nothing is listening" and "something is listening and
   * it is not Bindery" — are answered by [BinderyServer.isBindery] and by the transport
   * respectively, and collapsing them into `null` would lose both.
   */
  suspend fun health(): BinderyServer

  /**
   * Books matching [term], from Bindery's metadata search.
   *
   * **Not served from the user's own library.** It proxies to Open Library and the DNB, so it is
   * slow, is rate-limited upstream, and can fail while the user's own server is perfectly healthy.
   * Callers debounce and do not retry automatically.
   *
   * [term] is sent as the user typed it, as the `term` query parameter. Bindery's documentation
   * says `q`; the handler reads `term`, and `q` is a 400. See [BinderyApi.searchBook].
   *
   * **A result carries no indication of whether it is already in the library.** Measured: after
   * adding a book, the same search still returns it with `"id": 0`, exactly as before — so unlike
   * `LidarrAlbumCandidate.alreadyAdded` there is nothing here to model, and a surface that wants to
   * know must cross-reference [books]. Asking twice is harmless in any case: a duplicate add is a
   * `201` carrying the original id.
   */
  suspend fun searchBooks(term: String): List<BinderyBookCandidate>

  /**
   * Asks Bindery for [candidate], as [mediaType].
   *
   * **[mediaType] is not optional and has no default here**, because it has one on the server:
   * omitting it yields a `201` and an EPUB. MuPlay plays audiobooks. See [BinderyMediaType].
   *
   * [searchOnAdd] becomes `searchOnAdd` in the body: whether Bindery should go to its indexers
   * immediately or merely record the book as wanted.
   *
   * Returns the created book — Bindery's `201` carries the persisted resource including its real
   * database id, measured — so a caller has something durable to correlate later polls on.
   * **A `201` with no usable id is a [BinderyHttpException], not a book with `id = 0`.**
   *
   * Throws rather than returning an outcome type, which is where this differs from
   * `LidarrSource.submitAlbum` and the difference is the server's, not a design preference:
   * Bindery upserts, so a duplicate add is a `201` with the original id rather than a 400 that has
   * to be told apart from a real refusal. The failures that remain are all things the caller shows
   * and cannot route around — a rejected key, a book whose author cannot be resolved (422), a
   * `foreignBookId` the metadata provider does not have (502).
   */
  suspend fun submitBook(
    candidate: BinderyBookCandidate,
    mediaType: BinderyMediaType,
    searchOnAdd: Boolean,
  ): BinderyBook

  /**
   * The books in the user's Bindery, optionally filtered to one [status].
   *
   * [status] is `null` for "every book", and otherwise one of `wanted`, `downloading`,
   * `downloaded` or `imported` — the complete set, from Bindery's own source and confirmed by
   * serving all four. **A value outside that set is not an error**: measured, `?status=bogus`
   * answers `200` with an empty page, indistinguishable from an honest "nothing is in that state".
   *
   * [limit] and [offset] are required rather than defaulted, so that a caller is made to decide.
   * Measured, the server's own default limit is 100 and [BinderyBookPage.total] is the count
   * *before* it applies, so a client that let the default stand would silently read one page of a
   * larger library.
   */
  suspend fun books(status: String?, limit: Int, offset: Int): BinderyBookPage
}

/**
 * What `GET /api/v1/health` says. Two fields, which is all it sends.
 *
 * [status] is Bindery's own word for its health and is `"ok"` on a healthy instance; [version] is
 * the release, `"v1.32.1"` on the instance every fixture here came from. Both are `""` rather than
 * null where the field was absent, for the same reason `LidarrServer`'s five are: a surface that
 * had to distinguish "absent" from "empty" would be distinguishing something no caller can act on.
 */
data class BinderyServer(val status: String, val version: String) {

  /**
   * Whether this really is a healthy Bindery rather than something else answering at that path.
   *
   * The nearest thing to Lidarr's `appName` check that Bindery offers — its health body carries no
   * application name at all — so this is deliberately the **weaker** claim: it says the two fields
   * Bindery sends were both there and `status` was `ok`, not that the server is Bindery. A URL
   * pointing at some other service that happens to serve `{"status":"ok"}` at `/api/v1/health`
   * would satisfy it, and the authenticated call a connection check makes next is what settles
   * that.
   *
   * Case-insensitive on [status] because the value is a server-side constant this client does not
   * control, and a check that broke on a capitalisation change would fail in the wrong direction —
   * telling a user with a working Bindery that they have not got one. [version] is required to be
   * non-blank rather than matched: this client supports no particular version and pinning one
   * would be a claim it has not measured.
   */
  val isBindery: Boolean get() = status.equals("ok", ignoreCase = true) && version.isNotBlank()
}

/**
 * One book Bindery's metadata search found, ready to be asked for.
 *
 * ### The two fields the plan expected in the wrong place
 *
 * **`authorName` and `foreignAuthorId` are not top-level on a search element.** They are nested
 * under an `author` object, and that object is **absent on more than half of them** — measured,
 * 18 of 40 results for one query carried it. Both are therefore nullable here, and a candidate
 * that has neither is still offered to the user rather than dropped: measured, Bindery accepts a
 * submit carrying **either one alone**, and refuses one carrying neither with a `422` whose message
 * tells the user exactly what to do about it. Hiding 55% of a search's results to avoid a legible,
 * actionable failure would be the worse trade.
 *
 * [foreignBookId] is the one field with no fallback: `POST /api/v1/author/book` answers
 * `400 {"error":"foreignBookId required"}` without it, so [BinderyClient] drops an element that
 * has none rather than offering something that cannot be asked for.
 *
 * ### `raw`
 *
 * The element **exactly as it came off the wire**, thirty-one top-level fields of it. Unlike
 * `LidarrAlbumCandidate.raw` this is *not* posted back — Bindery's add takes a small typed body,
 * not the decorated lookup element — and it is here for the surface rather than for the wire: one
 * query returned forty results whose titles differ only in punctuation, and `description`,
 * `releaseDate`, `genres` and `metadataProvider` are what let a user tell them apart. Keeping the
 * element means a screen can show a field this client never modelled without a new release of it.
 */
data class BinderyBookCandidate(
  val foreignBookId: String,
  val title: String,
  /** From the nested `author` object, or `null` — see the class doc. Blank collapses to null. */
  val authorName: String?,
  /** From the nested `author` object, or `null` — see the class doc. Blank collapses to null. */
  val foreignAuthorId: String?,
  /**
   * Top-level on a Bindery book, per its own model. **Not used for identity** — [foreignBookId] is.
   *
   * Blank collapses to null: measured, this arrives as `""` on every element of a real search
   * rather than being omitted, and a surface that had two ways to say nothing would render an
   * empty ASIN row for all forty results.
   */
  val asin: String?,
  /**
   * From the element's `imageUrl` field. Named for what it is to a caller rather than for the wire
   * key, the same way `LidarrAlbumCandidate.remoteCoverUrl` is named for `remoteCover`.
   *
   * Measured, an Open Library result carries a real `https://covers.openlibrary.org/…` URL and a
   * DNB result carries `""`; blank collapses to null so a surface has one kind of nothing.
   */
  val coverUrl: String?,
  val raw: JsonObject,
)

/**
 * One book in the user's Bindery.
 *
 * [id] is Bindery's own database id and is an **`Int`** — the plan expected a string. It is what a
 * later poll correlates on, alongside [foreignBookId], which is what the user actually asked for.
 *
 * [status] is Bindery's word, unmapped: `wanted`, `downloading`, `downloaded` or `imported`.
 * [BinderyStatusMapper] is what turns it into a `RequestStatus`, and keeping the raw word here
 * means a Bindery release that adds a fifth is visible to a caller rather than silently collapsed.
 *
 * [mediaType] is read back for one reason: it is the field this module's central trap is about. A
 * book asked for as an audiobook that comes back `ebook` is the silent wrong answer, and this is
 * the only place a caller can ever see it.
 */
data class BinderyBook(
  val id: Int,
  val foreignBookId: String,
  val title: String,
  val status: String,
  val mediaType: String,
)

/**
 * One page of [BinderySource.books].
 *
 * A page rather than a bare list, because Bindery pages and says so: [total] is the count before
 * [limit] applies. A `books()` returning `List<BinderyBook>` would let a caller with more than one
 * page of books read the first one and believe it had read them all — the silent-wrong-answer
 * class, one layer up from a dropped field.
 */
data class BinderyBookPage(
  val books: List<BinderyBook>,
  val total: Int,
  val limit: Int,
  val offset: Int,
)

/**
 * What Bindery should go and get.
 *
 * **The wire value is carried here rather than derived from the enum's name**, because the two are
 * not the same shape — `EBOOK` is `ebook` but a `when` that lowercased a name would be one rename
 * away from sending `both_formats` — and because a constant in [BinderyClient] is the exact defect
 * `ci/mutation-probes.sh`'s `integrations/bindery-mediaType` exists to catch.
 *
 * Measured, Bindery validates this: `{"mediaType":"vinyl"}` answers
 * `400 {"error":"mediaType must be 'ebook', 'audiobook', or 'both'"}`, so these three are the
 * complete set rather than this client's guess at one.
 *
 * **There is no default and there must not be one.** `mediaType` defaults to `ebook` *server-side*
 * — measured: a POST omitting it answers `201` with `"mediaType":"ebook"` on the created book —
 * and MuPlay is an audiobook player. A default here would be a second place for that trap to hide.
 */
enum class BinderyMediaType(val wireValue: String) {
  EBOOK("ebook"),
  AUDIOBOOK("audiobook"),
  BOTH("both"),
}

/**
 * How a [BinderySource] is made from credentials.
 *
 * A `fun interface` so that a test can hand `RequestsRepository` a factory returning a
 * hand-written fake, without either of them knowing that the real one builds an OkHttp stack.
 */
fun interface BinderySourceFactory {
  fun create(credentials: IntegrationCredentials.Bindery): BinderySource
}

/** The production factory: a real [BinderyClient] with its real Retrofit stack. */
object DefaultBinderySourceFactory : BinderySourceFactory {
  override fun create(credentials: IntegrationCredentials.Bindery): BinderySource =
    BinderyClient(credentials)
}
