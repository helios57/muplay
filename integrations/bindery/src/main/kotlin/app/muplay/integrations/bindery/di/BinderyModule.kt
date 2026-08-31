package app.muplay.integrations.bindery.di

import app.muplay.integrations.bindery.BinderySourceFactory
import app.muplay.integrations.bindery.DefaultBinderySourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the one Bindery collaborator Hilt cannot construct on its own.
 *
 * **Who asks for it:** `RequestsRepository` (`:integrations:requests`) and
 * `RequestsFeatureModule.provideConnectionProbe` (`:feature:requests`) -- see `LidarrModule` for
 * why this names its consumers rather than the `BinderySourceProvider` that used to stand here and
 * that nothing ever injected.
 *
 * `@Provides` returning the `object` rather than `@Binds` on a class: [DefaultBinderySourceFactory]
 * is a stateless `object` implementing a `fun interface`, and there is no instance for Hilt to
 * create.
 */
@Module
@InstallIn(SingletonComponent::class)
object BinderyModule {

  @Provides
  @Singleton
  fun provideBinderySourceFactory(): BinderySourceFactory = DefaultBinderySourceFactory
}
