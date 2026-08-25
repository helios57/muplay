package app.muplay.media

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.muplay.database.CredentialStore
import app.muplay.database.SubsonicSourceProvider
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSource
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * A real [SubsonicSourceProvider] whose factory always yields [source].
 *
 * Real `CredentialStore`, real DataStore file, real credentials — the only thing substituted is
 * the `SubsonicSourceFactory`, which is already a `fun interface` in production code and therefore
 * needs no seam invented for it. This is the same construction Plan 2's `ShuffleRepositoryTest`
 * uses; copying it is better than adding an interface to `SubsonicSourceProvider` for a test.
 *
 * Returns the store's backing [File] alongside the provider so the caller can delete it in
 * `@After`. DataStore refuses a second instance for one path, so the path must be unique per test.
 */
fun fixedSubsonicSourceProvider(
  context: Context,
  source: SubsonicSource,
  baseUrl: String = "http://localhost:4533",
): Pair<SubsonicSourceProvider, File> {
  val file = File(context.filesDir, "media-test-${System.nanoTime()}.preferences_pb")
  val credentialStore = CredentialStore(PreferenceDataStoreFactory.create { file })
  runBlocking { credentialStore.save(SubsonicCredentials(baseUrl, "admin", "testpass")) }
  return SubsonicSourceProvider(credentialStore, SubsonicSourceFactory { source }) to file
}
