package app.muplay.watchlink.di

import app.muplay.watchlink.DataLayerWatchLink
import app.muplay.watchlink.WatchLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the only shipping [WatchLink].
 *
 * `@Binds` and not `@Provides`: `DataLayerWatchLink` is `@Inject`-constructible, so there is nothing
 * for a factory method to do, and a `@Provides` body would be a second place the transport could
 * grow a decision.
 *
 * `WatchSyncEngine` itself needs no entry here -- it is a `@Singleton` with an `@Inject` constructor
 * over this binding, `CredentialStore` and `MediaProgressDao`, all three of which the graph already
 * has.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WatchLinkModule {
  @Binds
  @Singleton
  abstract fun bindWatchLink(impl: DataLayerWatchLink): WatchLink
}
