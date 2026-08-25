package app.muplay.integrations

/**
 * The optional services MuPlay can request media from.
 *
 * An `enum`, not a sealed interface: this project reserves sealed interfaces for *state and
 * results* (roadmap global constraints), and this is a closed identity set with no per-member
 * data — the same shape as `LibraryRole` in `:core:model`.
 *
 * The order of the entries is load-bearing in exactly one place: every configuration screen and
 * every list renders `IntegrationService.entries` in declaration order, so both services appear
 * in the same order everywhere without any screen sorting them itself.
 */
enum class IntegrationService(val displayName: String) {
  LIDARR("Lidarr"),
  BINDERY("Bindery"),
}
