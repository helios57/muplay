package app.muplay.network

/**
 * Sealed hierarchy for everything that can go wrong once a Subsonic server produced *some*
 * response on purpose — as opposed to the transport simply failing to get an answer at all.
 *
 * A sealed *interface*, not a sealed class: Kotlin cannot make an interface extend `Throwable` (a
 * class, and Kotlin has no multiple class inheritance), so each concrete member below both extends
 * [Exception] — to be throwable in the first place — and implements this interface, which is what
 * this project's "sealed interfaces for state and results" rule actually buys here: a
 * `when (exception)` over [SubsonicException] is exhaustive, even though nothing can `catch
 * (e: SubsonicException)` directly (the JVM requires a catch type to itself be a `Throwable`
 * subclass, which an interface, sealed or not, can never be).
 *
 * Deliberately excludes "we could not ask" — a genuine transport failure (no connection, a
 * timeout) or an unparseable body is not a member of this hierarchy at all and propagates as
 * whatever the transport or the JSON parser actually threw ([java.io.IOException],
 * [kotlinx.serialization.SerializationException], ...). Only a real, on-purpose response — a
 * Subsonic-level failure or an unsuccessful HTTP status — belongs here. See
 * [SubsonicClient]'s private `call` for exactly where that line is drawn; Task 5's capability
 * negotiation depends on this distinction staying intact.
 */
sealed interface SubsonicException

/**
 * The server answered — `HTTP 200`, in Subsonic's own case — but the *command* itself failed.
 * [code] is the Subsonic numeric error code from the response body's `error.code` (0, 10, 20, 30,
 * 40, 41, 42, 43, 44, 50, 60, or 70 per the OpenSubsonic `SubsonicError` schema), never an HTTP
 * status — see [SubsonicHttpException] for that.
 */
class SubsonicErrorException(val code: Int, message: String? = null) :
  Exception(message ?: "Subsonic error $code"), SubsonicException

/**
 * The HTTP transport itself reported an unsuccessful response — [status] is the HTTP status code
 * (a 404, a 500, an auth proxy rejecting the request outright) — before any Subsonic-level body
 * could even be considered.
 */
class SubsonicHttpException(val status: Int) :
  Exception("Subsonic HTTP error $status"), SubsonicException
