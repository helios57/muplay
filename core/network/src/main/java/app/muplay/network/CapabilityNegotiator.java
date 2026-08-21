package app.muplay.network;

import app.muplay.model.ServerCapabilities;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Three-tier capability negotiation: {@code ping} establishes reachability and whether the
 * server speaks OpenSubsonic at all; only then is it worth asking for the extension list.
 *
 * <p>The extension fetch itself is allowed to fail without failing negotiation: a server that
 * advertised {@code openSubsonic=true} on {@code ping} but errors on {@code
 * getOpenSubsonicExtensions} (an older OpenSubsonic-flagged server that predates the endpoint, a
 * transient 404, or a Subsonic-level error in the body) still degrades to a capabilities object
 * with no extensions rather than failing every caller of {@link #negotiate()}. {@link
 * SubsonicClient} maps both an unsuccessful HTTP response and a Subsonic-level error onto an
 * {@link IOException} (the latter via {@link SubsonicErrorException}), so catching {@link
 * IOException} here covers both without masking an unrelated programming error.
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
                      IOException.class,
                      e -> new ServerCapabilities(true, Map.of()),
                      MoreExecutors.directExecutor());
            },
            MoreExecutors.directExecutor());
  }
}
