package app.muplay.model;

import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Negotiated OpenSubsonic capabilities.
 *
 * <p>Versions are stored as a list rather than a boolean because extensions are versioned
 * independently and differ materially between versions — {@code songLyrics} v2 adds word-level
 * timing that v1 has no representation for.
 */
public record ServerCapabilities(
    boolean isOpenSubsonic, @Nonnull Map<String, List<Integer>> extensions) {

  public static final ServerCapabilities NONE = new ServerCapabilities(false, Map.of());

  public ServerCapabilities {
    extensions = Map.copyOf(extensions);
  }

  public boolean supports(@Nonnull String extension) {
    return extensions.containsKey(extension);
  }

  public boolean supports(@Nonnull String extension, int version) {
    return extensions.getOrDefault(extension, List.of()).contains(version);
  }
}
