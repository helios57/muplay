package app.muplay.integrations.bindery

/**
 * Everything that can go wrong once Bindery produced a response on purpose.
 *
 * A sealed *interface* whose members each also extend `Exception`, for the same reason
 * `SubsonicException` in `:core:network` and `LidarrException` next door are built this way:
 * Kotlin cannot make an interface extend `Throwable`, so this buys an exhaustive `when` at the
 * cost of not being directly catchable. A genuine transport failure — no route, a timeout, a TLS
 * handshake that failed — is deliberately **not** a member and propagates as whatever the
 * transport threw. *"We could not ask"* is not *"Bindery said no"*, and a type that collapsed them
 * would leave the configuration screen unable to tell a wrong address from a rejected key.
 *
 * **No member carries the API key or the base URL.** [BinderyMessageException] carries Bindery's
 * own sentence and nothing this client sent beyond the book identifier the server chose to echo;
 * `BinderyAuthTest`'s `no failure this client raises names the api key` is what keeps that true as
 * members are added.
 */
sealed interface BinderyException

/**
 * Bindery answered 401.
 *
 * **It is not knowable from this response whether the key is wrong or missing.** Measured against
 * `v1.32.1`, a wrong `X-Api-Key`, no `X-Api-Key` at all, and a key supplied as `?apikey=` on a
 * mutation all produce the same `401 {"error":"unauthorized"}` — see
 * `fixtures/bindery/error-unauthorized.json`. The message therefore says only that the key was
 * *rejected*; claiming to know which would be a guess presented to the user as a fact, and would
 * send someone to regenerate a key that was never the problem.
 *
 * Its own member rather than a [BinderyMessageException] carrying `"unauthorized"`, because it is
 * the one failure whose remedy is specific ("your key is wrong") and the one a connection check
 * has to be able to name without matching on English.
 */
class BinderyUnauthorizedException :
  Exception("Bindery rejected this API key"), BinderyException

/**
 * Bindery refused and said why, in its own words.
 *
 * Bindery has **one** failure shape — `{"error": "…"}` — and uses it at every status. Measured on
 * `v1.32.1`, in the same afternoon: `400 {"error":"term parameter required"}`,
 * `400 {"error":"foreignBookId required"}`,
 * `400 {"error":"mediaType must be 'ebook', 'audiobook', or 'both'"}`,
 * `404 {"error":"book not found after author sync — try again shortly"}`,
 * `422 {"error":"Author metadata unavailable for this result. Add the author manually first
 * (Authors → Add Author by name), then try again."}` and
 * `502 {"error":"look up book metadata: get book …: not found"}`.
 *
 * **[status] is carried beside the text rather than deduced from it**, and that is the whole
 * reason this member exists instead of a family of typed ones. The text is a human sentence a
 * Bindery release may reword; the status is the machine-readable part. It lets a caller retry on
 * `404` — Bindery's own message says to try again shortly, because the author sync is asynchronous
 * and a POST issued too soon after one really does 404 and then succeed — without matching on
 * English.
 *
 * ### Why the server's sentence is NOT this exception's `message`
 *
 * [binderyMessage] is a named field and [Throwable.message] is a constant naming the status. That
 * looks like an inconvenience and it is a deliberate containment boundary, **found by running a
 * test rather than by reasoning**: `BinderyAuthTest`'s
 * `no failure this client raises names the api key` enqueues a refusal whose body quotes the key
 * back, and the first version of this class — `Exception(binderyMessage)`, which is the obvious
 * way to write it and is what `:integrations:lidarr`'s `LidarrValidationException` does with
 * Lidarr's validation text — put that key straight into the exception's `toString()`.
 *
 * A crash reporter serialises `Throwable.toString()` and the stack trace and nothing else, so a
 * server-supplied string reaches a third party through `message` and through no other field. This
 * client cannot know what a server will say: not only its own API key, but a path, a hostname or
 * anything else an operator's reverse proxy decides to echo. Making the caller ask for
 * [binderyMessage] by name turns showing it into a deliberate act at a surface, which is where the
 * decision belongs — and it is still shown, because "Add the author manually first (Authors → Add
 * Author by name)" is more actionable than anything this client could write.
 *
 * The same shape exists next door and is not covered by any assertion there; see this module's
 * task report.
 */
class BinderyMessageException(val status: Int, val binderyMessage: String) :
  Exception("Bindery refused this request (HTTP $status)"), BinderyException

/**
 * Any other unsuccessful HTTP status, or one whose body was not Bindery's `{"error": …}` shape.
 *
 * [status] is the HTTP code, never a Bindery-level one. A body this client could not read is
 * reported as a plain status rather than as a message, because a proxy's HTML error page carrying
 * a 502 is not Bindery speaking and quoting it at a user would be worse than saying nothing.
 *
 * **Also raised for a `201` that carried no usable book id**, deliberately, and carrying the
 * status that really came back rather than a hardcoded `201`: returning a book with `id = 0` would
 * put a row in the request store that every later poll looks up under an id no book has — the
 * silent-wrong-answer class — and a proxy that rewrote the status is then reported as what it did.
 */
class BinderyHttpException(val status: Int) :
  Exception("Bindery HTTP error $status"), BinderyException
