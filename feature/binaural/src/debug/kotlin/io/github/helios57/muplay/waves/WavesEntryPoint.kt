package io.github.helios57.muplay.waves

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.helios57.muplay.binaural.ProfileLibrary

/**
 * The application's own generator and profile file, for `:app`'s instrumented journeys. Debug only,
 * like `PlaybackEntryPoint`: a test that built a second `BinauralPlayer` would be testing a copy the
 * screens never talk to, and a second `ProfileLibrary` over the same file would race the first.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WavesEntryPoint {
  fun binauralPlayback(): BinauralPlayback

  fun profileLibrary(): ProfileLibrary
}
