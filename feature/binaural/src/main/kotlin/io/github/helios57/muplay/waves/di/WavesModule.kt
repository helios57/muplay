package io.github.helios57.muplay.waves.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.helios57.muplay.binaural.ProfileLibrary
import io.github.helios57.muplay.waves.BinauralPlayback
import io.github.helios57.muplay.waves.BinauralPlayer
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object WavesModule {

  /**
   * One file, on this phone, never synced and not tied to the Navidrome account: the profiles are
   * the listener's, not the server's, so signing out or into another server keeps them.
   */
  @Provides
  @Singleton
  fun provideProfileLibrary(@ApplicationContext context: Context): ProfileLibrary =
    ProfileLibrary(File(context.filesDir, PROFILE_FILE), Dispatchers.IO)

  const val PROFILE_FILE = "binaural-profiles.json"

  /** Nested for the reason `DataModule.Bindings` records: an `@Binds` compiles to no line. */
  @Module
  @InstallIn(SingletonComponent::class)
  interface Bindings {
    @Binds
    fun bindPlayback(impl: BinauralPlayer): BinauralPlayback
  }
}
