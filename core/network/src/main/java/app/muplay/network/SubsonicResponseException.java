package app.muplay.network;

import java.io.IOException;
import javax.annotation.Nonnull;

/**
 * Base type for "the server responded, and the response itself signals failure" — as opposed to
 * a transport-level failure, where the server never got a chance to say anything.
 *
 * <p>This distinction matters to callers such as {@link CapabilityNegotiator}: a {@link
 * SubsonicHttpException} (non-2xx HTTP status, e.g. an old server 404ing an endpoint it doesn't
 * implement) or a {@link SubsonicErrorException} (a Subsonic-level error in an HTTP 200 body)
 * both mean "we asked, and the answer is no" — a legitimate basis for degrading gracefully.
 * A plain {@link IOException} from OkHttp (DNS failure, connection refused, timeout, TLS error)
 * means "we don't know what the server would have said," which is not a safe basis for the same
 * degradation and should propagate instead. Sealing this type to exactly those two subclasses
 * lets a caller catch {@code SubsonicResponseException} to mean precisely "a real answer, but a
 * negative one" without also swallowing the "don't know" case.
 */
public abstract sealed class SubsonicResponseException extends IOException
    permits SubsonicHttpException, SubsonicErrorException {

  // Not intended for Java serialization; declared only to satisfy -Xlint:serial (this build
  // treats warnings as errors) since IOException implements Serializable.
  private static final long serialVersionUID = 1L;

  protected SubsonicResponseException(@Nonnull String message) {
    super(message);
  }
}
