package app.muplay.model

/**
 * Credentials for a Subsonic-compatible server (e.g. Navidrome).
 *
 * [toString] is hand-written to omit [password]: `data class` generates a `toString()` that
 * includes every constructor property, and logging or crash-reporting a default one would leak
 * the plaintext password.
 */
data class SubsonicCredentials(
  val baseUrl: String,
  val username: String,
  val password: String,
) {
  override fun toString(): String =
    "SubsonicCredentials(baseUrl='$baseUrl', username='$username', password=<redacted>)"
}
