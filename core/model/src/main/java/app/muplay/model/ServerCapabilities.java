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

  // The OpenSubsonic spec sets no minItems on an extension's versions array, so a server may
  // legally advertise an extension with an empty version list. Treating mere key presence as
  // "supported" would then disagree with supports(extension, version), which correctly returns
  // false for every version in that case — a caller using the unversioned form as a pre-flight
  // gate before a version-specific call would get a contradiction. "Supported" therefore means
  // "advertised with at least one usable version," which is exactly what a caller doing that
  // pre-flight check needs it to mean.
  public boolean supports(@Nonnull String extension) {
    return !extensions.getOrDefault(extension, List.of()).isEmpty();
  }

  public boolean supports(@Nonnull String extension, int version) {
    return extensions.getOrDefault(extension, List.of()).contains(version);
  }
}
