package app.muplay.network;

import app.muplay.model.SubsonicCredentials;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Builds Subsonic authentication query parameters.
 *
 * <p>Navidrome 0.63.2 supports only {@code p=} and {@code t}+{@code s}; the
 * OpenSubsonic {@code apiKeyAuthentication} extension is not implemented. We use
 * token auth and never put the password on the wire.
 */
public final class SubsonicAuth {

  /** Identifies this client. Must not be "DSub" or "SubMusic" — Navidrome
   * strips the entire OpenSubsonic field block for those. */
  public static final String CLIENT_NAME = "MuPlay";

  public static final String PROTOCOL_VERSION = "1.16.1";

  private static final String SALT_ALPHABET =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final int SALT_LENGTH = 12;

  private static final SecureRandom RANDOM = new SecureRandom();

  @Nonnull
  public static Supplier<String> randomSaltSupplier() {
    return () -> {
      StringBuilder sb = new StringBuilder(SALT_LENGTH);
      for (int i = 0; i < SALT_LENGTH; i++) {
        sb.append(SALT_ALPHABET.charAt(RANDOM.nextInt(SALT_ALPHABET.length())));
      }
      return sb.toString();
    };
  }

  /** {@code t = md5(password + salt)}, lowercase hex. */
  @Nonnull
  public static String token(@Nonnull String password, @Nonnull String salt) {
    try {
      MessageDigest md5 = MessageDigest.getInstance("MD5");
      byte[] digest = md5.digest((password + salt).getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(32);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 is required by the Java platform", e);
    }
  }

  @Nonnull
  public Map<String, String> authParams(
      @Nonnull SubsonicCredentials credentials, @Nonnull Supplier<String> saltSupplier) {
    String salt = saltSupplier.get();
    Map<String, String> params = new LinkedHashMap<>();
    params.put("u", credentials.username());
    params.put("t", token(credentials.password(), salt));
    params.put("s", salt);
    params.put("v", PROTOCOL_VERSION);
    params.put("c", CLIENT_NAME);
    params.put("f", "json");
    return Map.copyOf(params);
  }
}
