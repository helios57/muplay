package app.muplay.network;

import javax.annotation.Nonnull;

/**
 * A Subsonic-level error: the server understood the request and produced a full {@code
 * subsonic-response} body, but that body's {@code error} element signals failure. Note these
 * arrive with HTTP 200 — the status lives in the response body, not the status line. Contrast
 * with {@link SubsonicHttpException}, which is a non-2xx HTTP status with no Subsonic body at
 * all.
 */
public final class SubsonicErrorException extends SubsonicResponseException {

  // Not intended for Java serialization; declared only to satisfy -Xlint:serial (this build
  // treats warnings as errors) since IOException implements Serializable.
  private static final long serialVersionUID = 1L;

  /**
   * A generic error, per the Subsonic API spec's own error code table. Used by {@link
   * SubsonicClient} when a response's {@code status} field is {@code "failed"} but it carries no
   * {@code error} object to take a real code from — a non-compliant server or a mangling proxy,
   * not a documented failure mode, so there is no more specific code to report.
   */
  public static final int CODE_GENERIC = 0;

  /** Wrong username or password. */
  public static final int CODE_WRONG_CREDENTIALS = 40;

  /** The requested data was not found. */
  public static final int CODE_NOT_FOUND = 70;

  private final int code;

  public SubsonicErrorException(int code, @Nonnull String message) {
    super("Subsonic error " + code + ": " + message);
    this.code = code;
  }

  public int code() {
    return code;
  }
}
