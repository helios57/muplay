package io.github.helios57.muplay.setup.di

import io.github.helios57.muplay.database.CredentialStore
import io.github.helios57.muplay.settings.SettingsSection
import io.github.helios57.muplay.setup.ServerAccount
import io.github.helios57.muplay.setup.ServerIdentity
import io.github.helios57.muplay.setup.ServerSection
import io.github.helios57.muplay.setup.SetupScope
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Setup's contribution to the settings screen, and the credential store behind it.
 *
 * **This file is the whole of the coupling between setup and settings.** Delete `feature/setup/`
 * and the `@IntoSet` below goes with it; `:feature:settings`, which names neither this module nor
 * `:core:database`, keeps compiling and simply renders one section fewer.
 */
@Module
@InstallIn(SingletonComponent::class)
object SetupFeatureModule {

  /**
   * The real credential store, narrowed to the two fields that are not secrets.
   *
   * `map` rather than passing `SubsonicCredentials` through: the password is dropped here, at the
   * module boundary, so no type reachable from a composable ever carries one. See
   * [ServerIdentity]'s own note.
   */
  @Provides
  @Singleton
  fun provideServerAccount(credentialStore: CredentialStore): ServerAccount =
    object : ServerAccount {
      override val identity: Flow<ServerIdentity?> =
        credentialStore.credentials.map { stored ->
          stored?.let { ServerIdentity(baseUrl = it.baseUrl, username = it.username) }
        }

      override suspend fun signOut() = credentialStore.clear()
    }

  /**
   * `SupervisorJob() + Dispatchers.Default`, matching `@CastPickerScope`. Never cancelled: it
   * outlives the composable that launches into it on purpose, because that composable navigates
   * away in the same gesture that writes.
   */
  @Provides
  @Singleton
  @SetupScope
  fun provideSetupScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Module
  @InstallIn(SingletonComponent::class)
  interface Bindings {
    @Binds
    @IntoSet
    fun bindServerSection(section: ServerSection): SettingsSection
  }
}
