package app.muplay.network;

import app.muplay.model.ServerCapabilities;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Three-tier capability negotiation: {@code ping} establishes reachability and whether the
 * server speaks OpenSubsonic at all; only then is it worth asking for the extension list.
 *
 * <p>The extension fetch itself is allowed to fail without failing negotiation, but only for a
 * failure that is itself a real answer: a server that advertised {@code openSubsonic=true} on
 * {@code ping} but responds to {@code getOpenSubsonicExtensions} with a non-2xx HTTP status (an
 * older OpenSubsonic-flagged server 404ing an endpoint it doesn't implement — {@link
 * SubsonicHttpException}) or a Subsonic-level error in the body ({@link SubsonicErrorException})
 * still degrades to a capabilities object with no extensions rather than failing every caller of
 * {@link #negotiate()}. Catching the sealed {@link SubsonicResponseException} — the common
 * supertype of exactly those two — covers both of those "we asked, and the answer is no" cases.
 * A transport-level failure (DNS, connection refused, timeout, TLS error — a bare {@link
 * java.io.IOException} from OkHttp, or the "malformed/unparseable response" {@link
 * java.io.IOException} raised by {@link SubsonicClient} when a 2xx response has no usable body)
 * means "we don't know what the server would have said" and is deliberately left to propagate
 * out of {@link #negotiate()} instead of being folded into the same degraded result.
 */
public final class CapabilityNegotiator {

  private final SubsonicClient client;

  public CapabilityNegotiator(@Nonnull SubsonicClient client) {
    this.client = client;
  }

  @Nonnull
  public ListenableFuture<ServerCapabilities> negotiate() {
    return FluentFuture.from(client.ping())
        .transformAsync(
            info -> {
              if (!info.openSubsonic()) {
                return Futures.immediateFuture(ServerCapabilities.NONE);
              }
              return FluentFuture.from(client.getOpenSubsonicExtensions())
                  .transform(
                      exts -> new ServerCapabilities(true, exts), MoreExecutors.directExecutor())
                  .catching(
                      SubsonicResponseException.class,
                      e -> new ServerCapabilities(true, Map.of()),
                      MoreExecutors.directExecutor());
            },
            MoreExecutors.directExecutor());
  }
}
