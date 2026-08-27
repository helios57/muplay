package app.muplay.integrations.lidarr

import app.muplay.integrations.IntegrationCredentialStore
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [LidarrSource] from whatever is currently configured — or reports that nothing is.
 *
 * **[current] returns `null` rather than throwing, and that is the single most important design
 * decision in this module.** `:core:database`'s `SubsonicSourceProvider` throws
 * `NotConfiguredException` because a MuPlay with no Navidrome is a broken app. A MuPlay with no
 * Lidarr is a *normal* app — it is the state most users are in, permanently — so "not configured"
 * is a value every caller must handle, not an exception a caller may forget to catch.
 *
 * The plan's severability contract names the opposite mistake explicitly: a not-configured path
 * that every test configures around is a path no test exercises. `LidarrSourceProviderTest` is
 * instrumented rather than JVM for that reason and no other — [IntegrationCredentialStore] is
 * backed by DataStore and the Android Keystore, so the only way to observe this class against the
 * real store is on a device, and observing it against a fake store would prove nothing about the
 * one collaborator it has.
 *
 * The [IntegrationService.BINDERY] entry is never consulted here. That is not an omission: a
 * Lidarr provider that asked for Bindery's credentials would be the cross-service reachability the
 * severability contract's third clause forbids.
 */
@Singleton
class LidarrSourceProvider @Inject constructor(
  private val credentialStore: IntegrationCredentialStore,
  private val factory: LidarrSourceFactory,
) {

  /**
   * The configured Lidarr, or `null` when there is none.
   *
   * `as?` rather than a cast: [IntegrationCredentialStore.load] is typed to the sealed supertype,
   * and a Bindery credential arriving under `LIDARR` is a corrupt store, not a crash.
   */
  suspend fun current(): LidarrSource? =
    (credentialStore.load(IntegrationService.LIDARR) as? IntegrationCredentials.Lidarr)
      ?.let(factory::create)
}
