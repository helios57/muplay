package app.muplay.integrations.lidarr.di

import app.muplay.integrations.lidarr.DefaultLidarrSourceFactory
import app.muplay.integrations.lidarr.LidarrSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the one collaborator [app.muplay.integrations.lidarr.LidarrSourceProvider] needs that Hilt
 * cannot construct on its own.
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
