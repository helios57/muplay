package app.muplay.model;

import javax.annotation.Nonnull;

/** Immutable Subsonic server credentials. */
public record SubsonicCredentials(
    @Nonnull String baseUrl, @Nonnull String username, @Nonnull String password) {

  public SubsonicCredentials {
    if (baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    if (username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    // Trailing slashes break path concatenation against /rest/*.
    baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @Nonnull
  public static SubsonicCredentials create(
      @Nonnull String baseUrl, @Nonnull String username, @Nonnull String password) {
    return new SubsonicCredentials(baseUrl, username, password);
  }

  /** Never log credentials. */
  @Override
  @Nonnull
  public String toString() {
    return "SubsonicCredentials{" + username + "@" + baseUrl + "}";
  }
}
