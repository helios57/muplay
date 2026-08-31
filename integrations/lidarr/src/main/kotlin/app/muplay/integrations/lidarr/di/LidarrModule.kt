package app.muplay.integrations.lidarr.di

import app.muplay.integrations.lidarr.DefaultLidarrSourceFactory
import app.muplay.integrations.lidarr.LidarrSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the one Lidarr collaborator Hilt cannot construct on its own.
 *
 * **Who asks for it:** `RequestsRepository` (`:integrations:requests`) and
 * `RequestsFeatureModule.provideConnectionProbe` (`:feature:requests`). This KDoc used to name
 * `LidarrSourceProvider` as the reason this module exists, which was the plan's design and never
 * the built one -- the provider was injected by nothing and Plan 8 deleted it. A binding whose
 * stated justification names a class that is not in the graph is how a module outlives its reason,
 * so the consumers are named here instead of the intent.
 *
 * `@Provides` returning the `object` rather than `@Binds` on a class: [DefaultLidarrSourceFactory]
 * is a stateless `object` implementing a `fun interface`, and there is no instance for Hilt to
 * create.
 */
@Module
@InstallIn(SingletonComponent::class)
object LidarrModule {

  @Provides
  @Singleton
  fun provideLidarrSourceFactory(): LidarrSourceFactory = DefaultLidarrSourceFactory
}
