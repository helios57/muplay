package io.github.helios57.muplay.media.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.helios57.muplay.database.AccountScopedData
import io.github.helios57.muplay.media.OfflineDownloads
import io.github.helios57.muplay.media.PlaybackConnection
import io.github.helios57.muplay.media.QueueStore
import java.io.File
import javax.inject.Singleton

/**
 * What the media layer keeps on behalf of one account: the saved play queue and the audio of
 * playlists kept for offline listening, and how sign-out forgets both.
 *
 * Its own module rather than more of [MediaModule], for a coverage reason: every line here is
 * executed only by Hilt's graph on a device, and `MediaModule`'s CLASS floor is evaluated on the
 * JVM tier, where it holds the timeout policy and the resume-policy decorator. Added to that object
 * these providers took it from 0.90 to 0.77 and made the floor gate them instead of what it is
 * for. They are exercised by `:core:media`'s `QueueKeeperTest` and `OfflineDownloadsTest` and by
 * `:app`'s sign-out journeys.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaAccountModule {

  /** One writer over one file, so `@Singleton` is correctness rather than economy. */
  @Provides
  @Singleton
  fun provideQueueStore(@ApplicationContext context: Context): QueueStore =
    QueueStore(File(context.filesDir, QueueStore.FILE_NAME))

  /** Saved playlists' audio goes with the account that saved it. */
  @Provides
  @IntoSet
  fun provideOfflineAudioSignOut(downloads: OfflineDownloads): AccountScopedData =
    AccountScopedData { downloads.clearAudio() }

  /**
   * Sign-out stops playback and forgets the queue. The file is cleared on both sides of clearing
   * the player because the service's `QueueKeeper` may write the old queue in between; after the
   * player is empty it has nothing left to write.
   */
  @Provides
  @IntoSet
  fun provideQueueSignOut(store: QueueStore, connection: PlaybackConnection): AccountScopedData =
    AccountScopedData {
      store.clear()
      try {
        connection.onController { controller ->
          controller.stop()
          controller.clearMediaItems()
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (_: Exception) {
        // No service to reach is a player with nothing in it, which is what this wanted.
      }
      store.clear()
    }
}
