package app.muplay.network;

/**
 * The server responded, but with an HTTP status outside the 2xx range.
 *
 * <p>Note this arrives instead of a Subsonic-level error only when the server never got as far
 * as producing a {@code subsonic-response} body at all — e.g. a 404 from an old server that
 * doesn't implement the endpoint, a reverse proxy returning 502, or a 401 from HTTP-layer auth in
 * front of Subsonic. A Subsonic server that understood the request but rejected it responds with
 * HTTP 200 and an error in the body instead; see {@link SubsonicErrorException} for that case.
 */
public final class SubsonicHttpException extends SubsonicResponseException {

  // Not intended for Java serialization; declared only to satisfy -Xlint:serial (this build
  // treats warnings as errors) since IOException implements Serializable.
  private static final long serialVersionUID = 1L;

  private final int httpStatusCode;

  public SubsonicHttpException(int httpStatusCode) {
    super("Subsonic server responded with HTTP " + httpStatusCode);
    this.httpStatusCode = httpStatusCode;
  }

  public int httpStatusCode() {
    return httpStatusCode;
  }
}
