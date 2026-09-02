package app.muplay.cast.net

/**
 * **Whether a URL carries the user's server credentials** -- the half of the local-network rule
 * that is about what leaves, rather than about where it goes.
 *
 * [LocalNetworkOnly] answers *"may this host be spoken to in the clear"*. This answers *"may this
 * string be handed to a peer at all"*, and the two are independent: a renderer on the LAN is a
 * perfectly legitimate destination for plain HTTP and a completely illegitimate destination for a
 * password.
 *
 * ### What is being looked for, and why it is written down here
 *
 * Subsonic token authentication puts three parameters on every authenticated URL: `u` (the
 * username), `t` (`md5(password + salt)`) and `s` (that salt). The pair `t`+`s` is a
 * **non-expiring password equivalent** -- it is not a session, it cannot be revoked, and it grants
 * the whole API as the user -- so a URL carrying them is a credential in every sense that matters.
 *
 * `:core:cast` otherwise knows nothing about Navidrome, deliberately: it takes a URL and relays it,
 * which is what keeps it a protocol module. This object is the one exception, and it is a
 * deliberate one, because **a guard that cannot name what it is looking for is not a guard**.
 * `CredentialQueryTest` holds the list below against `:core:network`'s `SubsonicAuth.authParams`
 * -- the single authority that mints them -- so the two cannot drift apart in silence.
 *
 * `v`, `c` and `f` ride on the same URLs and are deliberately absent from the list: they are the
 * protocol version, the client name and the response format. Listing them would make this object
 * report "credentials" for a URL that carries none, and an over-reporting guard is one somebody
 * later switches off.
 *
 * ### Why the scan is over text and not over a parsed URL
 *
 * The thing being protected is *what the renderer receives*, and that is a SOAP envelope whose
 * `CurrentURIMetaData` argument is an XML-escaped DIDL-Lite document whose `<res>` element is
 * itself an XML-escaped URL. By the time a stream URL reaches the wire its `&` separators have
 * been escaped once or twice, so a scan that insisted on parsing a URL would find nothing in the
 * exact artifact that matters. [parametersIn] unescapes first and then scans, which is what lets
 * the same predicate answer both *"is this URL credential-bearing"* (one URL) and *"did a
 * credential reach this renderer"* (a whole document, whatever is later added to it).
 */
object CredentialQuery {

  /**
   * The Subsonic query parameters that are, or reveal, a credential.
   *
   * Pinned to `SubsonicAuth.authParams`'s own output by `CredentialQueryTest`, which builds a real
   * authenticated URL through `SubsonicClient` and requires this object to detect it. Adding a
   * parameter there without adding it here fails that test rather than quietly widening what may
   * reach a speaker.
   */
  val AUTH_PARAMETERS: List<String> = listOf("u", "t", "s")

  /**
   * Whether [url] would hand a peer a credential. `null` and a URL with no query answer `false`.
   *
   * The proxy URLs this module mints -- `http://<phone>:<port>/media/<token>.mp3` -- have no query
   * component at all, so they answer `false` without a special case.
   */
  fun carries(url: String?): Boolean = url != null && parametersIn(url).isNotEmpty()

  /**
   * Every credential parameter named anywhere in [text], in [AUTH_PARAMETERS] order, without
   * repeats -- and **never their values**, which is the whole point of returning names.
   *
   * A finding from this function goes into an assertion message, and an assertion message is the
   * one string in this project that reliably reaches a bug report. Returning `t=<the token>` would
   * make the leak detector into a second leak.
   *
   * A parameter counts when it is preceded by a `?` or an `&`, i.e. when it is in a query position.
   * A title containing `s=1` is not a credential and must not be reported as one; a guard that
   * cries wolf is a guard that gets deleted.
   */
  fun parametersIn(text: String): List<String> {
    val plain = unescapeAmpersands(text)
    return AUTH_PARAMETERS.filter { name ->
      QUERY_SEPARATORS.any { separator -> plain.contains("$separator$name=") }
    }
  }

  /**
   * `&amp;` (and the numeric forms of the same character) collapsed back to `&`, repeatedly.
   *
   * Repeatedly, because the artifact this scan is aimed at is escaped **twice**: `DidlLite.render`
   * escapes the resource URL into the document, and `SoapEnvelope.render` escapes that document
   * into an argument, so an ampersand that started life as a query separator arrives on the wire as
   * `&amp;amp;`. One pass would leave `&amp;u=` and find nothing.
   *
   * Bounded at [MAX_UNESCAPE_PASSES] rather than looped until stable: the input is a document a
   * **peer** may have influenced, and an unbounded rewrite over hostile input is a denial of
   * service in a guard. Two passes is what the real artifact needs; the bound is generous.
   */
  private fun unescapeAmpersands(text: String): String {
    var current = text
    repeat(MAX_UNESCAPE_PASSES) {
      val next = AMPERSAND_ENTITIES.fold(current) { accumulator, entity ->
        accumulator.replace(entity, "&", ignoreCase = true)
      }
      if (next == current) return current
      current = next
    }
    return current
  }

  private const val MAX_UNESCAPE_PASSES = 4

  private val AMPERSAND_ENTITIES = listOf("&amp;", "&#38;", "&#x26;")

  /** Where a query parameter may begin. */
  private val QUERY_SEPARATORS = listOf('?', '&')
}
