package app.muplay.model

/**
 * The server identity reported by a successful Subsonic `ping` command — the
 * `SubsonicBaseResponse` fields every OpenSubsonic response carries ([type], [serverVersion],
 * [apiVersion]) plus the [isOpenSubsonic] flag that later tasks' capability negotiation keys off
 * of.
 *
 * Domain-level and deliberately not the raw network DTO: [app.muplay.network.SubsonicClient.ping]
 * only ever returns this once the response has already been proven *not* to be a Subsonic-level
 * failure (see [app.muplay.network.SubsonicException]), so every field here reflects a server that
 * actually answered "ok".
 *
 * @property type the server's self-reported implementation name, e.g. `"navidrome"`.
 * @property serverVersion the server's own version string, e.g. `"0.63.2 (be10f89c)"` — opaque to
 *   this client, never parsed as a semantic version.
 * @property apiVersion the Subsonic REST API protocol version the server supports, e.g.
 *   `"1.16.1"`. Named `apiVersion`, not `version`, so it cannot be confused with [serverVersion] at
 *   a call site — the wire field is literally `version`, but that name on its own does not say
 *   *which* version.
 * @property isOpenSubsonic whether the server advertises OpenSubsonic API support at all. `false`
 *   (or a missing field, degraded to `false`) means a legacy Subsonic server: capability
 *   negotiation must not even attempt `getOpenSubsonicExtensions` against one.
 */
data class ServerInfo(
  val type: String,
  val serverVersion: String,
  val apiVersion: String,
  val isOpenSubsonic: Boolean,
)
