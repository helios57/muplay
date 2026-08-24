package app.muplay.database

import app.muplay.network.SubsonicSource
import app.muplay.network.SubsonicSourceFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [SubsonicSource] from whatever credentials are stored right now.
 *
 * Every repository that talks to the server injects this rather than a `SubsonicSource` directly,
 * because there is no source to inject until the user has signed in, and the answer changes when
 * they sign into a different server. Reading the credentials per call, rather than caching a
 * client, is what makes "sign out, sign into another server" work with no invalidation logic.
 */
@Singleton
class SubsonicSourceProvider @Inject constructor(
  private val credentialStore: CredentialStore,
  private val factory: SubsonicSourceFactory,
) {
  suspend fun current(): SubsonicSource =
    factory.create(credentialStore.load() ?: throw NotConfiguredException())
}
