package app.muplay.network

import app.muplay.model.ServerCapabilities

/**
 * Three-tier OpenSubsonic capability negotiation over a [SubsonicClient]: `ping` first, and only
 * if that reports [app.muplay.model.ServerInfo.isOpenSubsonic] does this attempt
 * `getOpenSubsonicExtensions` at all.
 *
 * The two degraded paths this negotiates around are deliberately different outcomes, not the same
 * one reached two ways:
 *
 * - `ping` reporting `isOpenSubsonic == false` (a plain, legacy Subsonic server) negotiates
 *   straight to [ServerCapabilities] with no extensions, without even attempting
 *   `getOpenSubsonicExtensions` — see [negotiate].
 * - An OpenSubsonic server (`ping` reported `isOpenSubsonic == true`) whose
 *   `getOpenSubsonicExtensions` call itself fails — a Subsonic-level error, or a non-2xx HTTP
 *   status such as a 404 from a server old enough not to implement the command — still negotiates
 *   to `isOpenSubsonic == true`, just with no extensions. Collapsing this into the same result as
 *   the first case would throw away information `ping` already established independently: this
 *   server *is* OpenSubsonic, it just would not (or could not) answer this one question.
 *
 * A genuine transport failure — no connection, a timeout, an unparseable body — is neither of
 * those. It is not a member of [SubsonicException] at all (see that type's own documentation for
 * why that boundary is structural, not a convention this class has to remember to respect), so it
 * is not caught by either `catch` clause below and simply propagates out of [negotiate] unchanged,
 * from *either* the `ping` call or the `getOpenSubsonicExtensions` call. "The server said no" and
 * "we could not ask" are different failures, and only the first justifies degrading to a
 * [ServerCapabilities] value instead of throwing.
 */
class CapabilityNegotiator(private val client: SubsonicClient) {

  /**
   * Runs the three-tier negotiation described in the class documentation and returns the result.
   *
   * Never catches anything around the `ping` call itself: a [SubsonicException] there (e.g. wrong
   * credentials) or a transport failure both propagate unchanged, exactly as calling
   * [SubsonicClient.ping] directly would. Negotiation only begins interpreting failures as "no
   * extensions" once `ping` has already succeeded and reported OpenSubsonic support.
   */
  suspend fun negotiate(): ServerCapabilities {
    val info = client.ping()
    if (!info.isOpenSubsonic) {
      return ServerCapabilities(isOpenSubsonic = false, extensions = emptyMap())
    }

    // Deliberately two catch clauses, not one `catch (e: SubsonicException)`: a sealed
    // *interface* is not itself a Throwable subtype, so the JVM will not allow catching it
    // directly (see SubsonicException's own documentation). SubsonicErrorException and
    // SubsonicHttpException are the sealed hierarchy's only two permitted members, so this pair is
    // the exhaustive equivalent of `catch (e: SubsonicException)` for a JVM that cannot express
    // that directly. Anything else — java.io.IOException, kotlinx.serialization.SerializationException
    // — is structurally incapable of matching either clause and propagates unchanged.
    val extensions =
      try {
        client.getOpenSubsonicExtensions()
      } catch (e: SubsonicErrorException) {
        emptyMap()
      } catch (e: SubsonicHttpException) {
        emptyMap()
      }

    return ServerCapabilities(isOpenSubsonic = true, extensions = extensions)
  }
}
