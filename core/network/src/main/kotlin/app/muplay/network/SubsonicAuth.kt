package app.muplay.network

import app.muplay.model.SubsonicCredentials
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Subsonic API token authentication (https://www.subsonic.org/pages/api.jsp#authentication).
 *
 * The salt is a caller-supplied parameter rather than something generated internally, which keeps
 * [token] and [authParams] pure and deterministic for tests. Production wiring must generate a
 * fresh salt per request with `SecureRandom` — never cache or reuse one.
 */
object SubsonicAuth {

  /**
   * The client identifier sent as the `c` parameter. Not cosmetic: Navidrome's
   * `Subsonic.LegacyClients` defaults to `DSub` and `MinimalClients` to `SubMusic`, and a client
   * whose `c` value matches either of those has the entire OpenSubsonic field block stripped from
   * every response.
   */
  const val CLIENT_NAME: String = "MuPlay"

  /** The Subsonic API protocol version this client targets, sent as the `v` parameter. */
  const val PROTOCOL_VERSION: String = "1.16.1"

  /**
   * Computes the Subsonic token: `hex(md5(utf8Bytes(password) + utf8Bytes(salt)))`, lower-cased.
   *
   * Hex-encodes byte by byte rather than via `BigInteger`/`Integer.toHexString` over the whole
   * digest, both of which silently drop a leading zero byte — a bug that only shows up for
   * roughly one salt in sixteen.
   */
  fun token(password: String, salt: String): String {
    val digest = MessageDigest.getInstance("MD5")
      .digest((password + salt).toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  /**
   * Builds the Subsonic authentication query parameters for [credentials] using [salt]. Never
   * includes the plaintext password: only the salted [token] (`t`) and the [salt] itself (`s`)
   * are emitted, per the Subsonic token-authentication scheme.
   */
  fun authParams(credentials: SubsonicCredentials, salt: String): Map<String, String> =
    mapOf(
      "u" to credentials.username,
      "t" to token(credentials.password, salt),
      "s" to salt,
      "v" to PROTOCOL_VERSION,
      "c" to CLIENT_NAME,
      "f" to "json",
    )
}
