package app.muplay.integrations.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.muplay.integrations.IntegrationPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The integrations' own object graph.
 *
 * A **separate DataStore file** from `:core:database`'s `credentials.preferences_pb`, and a
 * qualified binding so the two `DataStore<Preferences>` instances cannot be confused. Two reasons,
 * both concrete: DataStore throws `IllegalStateException: There are multiple DataStores active for
 * the same file` if two instances share a path, and severability means deleting this plan should
 * delete this file rather than leave orphan keys inside the one holding the Navidrome password.
 *
 * **In `src/main`, not `src/debug`, and that is a decision rather than a default.** The Hilt
 * `@EntryPoint`s in `:core:database` are moving to `src/debug/` because they exist only so an
 * instrumented test can reach into the running application's graph — they are public API of a
 * module in the release graph, serving nothing that ships. This is the opposite case: it is the
 * only binding for `@IntegrationPreferences DataStore<Preferences>`, and every screen and client
 * Tasks 3-11 add injects `IntegrationCredentialStore` in a release build. Putting it in `src/debug`
 * would make the release variant fail to build its graph the moment the first real consumer lands.
 *
 * This module adds **no `@EntryPoint` at all**, for the same reason: `IntegrationCredentialStore`
 * takes its DataStore through an ordinary constructor, so `IntegrationCredentialStoreTest` builds
 * one directly over a file of its own and needs nothing from the application's graph.
 * `IntegrationsDataModuleTest` exercises this provider by calling it, which needs no Hilt
 * machinery either — it is an `object` whose provider takes a plain `Context`.
 */
@Module
@InstallIn(SingletonComponent::class)
object IntegrationsDataModule {

  /**
   * `@Singleton` is a correctness requirement, not a performance choice: DataStore refuses a
   * second instance over the same file, and throws when the store is first *used* rather than when
   * it is constructed — so a second instance looks fine until an assertion runs.
   */
  @Provides
  @Singleton
  @IntegrationPreferences
  fun provideIntegrationDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create {
      File(context.filesDir, "integrations.preferences_pb")
    }
}
