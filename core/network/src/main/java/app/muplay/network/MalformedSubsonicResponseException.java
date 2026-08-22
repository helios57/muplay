package app.muplay.network;

import java.io.IOException;
import javax.annotation.Nonnull;

/**
 * The server answered with a successful HTTP status, but the body was not a usable Subsonic
 * response at all: empty/unparseable JSON, or valid JSON with no {@code subsonic-response}
 * envelope.
 *
 * <p>Deliberately <strong>not</strong> a {@link SubsonicResponseException} — not one of that
 * sealed type's {@code permits}. {@link SubsonicResponseException} means "the server gave a real,
 * on-protocol answer, and the answer is no," which is a safe basis for {@link
 * CapabilityNegotiator} to degrade gracefully (see its class doc). This is a different case: the
 * server did not give a real answer at all, so it must propagate out of {@link
 * CapabilityNegotiator#negotiate()} like any other transport-level failure, not be caught and
 * folded into a degraded result a caller can't tell apart from a confirmed "no."
 *
 * <p>Named — rather than a bare {@link IOException}, which {@link SubsonicClient} used to throw
 * here — so Plan 2's error UI can distinguish "server unreachable" (a bare transport {@link
 * IOException} from OkHttp: DNS failure, connection refused, timeout, TLS error) from "server
 * replied with garbage" (this) by type, without parsing English out of {@link
 * Throwable#getMessage()}.
 */
public final class MalformedSubsonicResponseException extends IOException {

  // Not intended for Java serialization; declared only to satisfy -Xlint:serial (this build
  // treats warnings as errors) since IOException implements Serializable.
  private static final long serialVersionUID = 1L;

  public MalformedSubsonicResponseException(@Nonnull String message) {
    super(message);
  }
}
